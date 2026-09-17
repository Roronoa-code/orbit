package com.mani.orbit.wear

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.assertSame
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import com.mani.orbit.sync.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class WatchEcgUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun actualEcgRouteRequiresAnExplicitStartAndShowsTheUnsupportedService() {
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        ActivityScenario.launch(WatchEcgActivity::class.java).use { scenario ->
            compose.onNodeWithText("ECG · 30 seconds").assertIsDisplayed()
            compose.onNodeWithTag("start-ecg").performScrollTo().assertIsDisplayed()
            capture("ecg-watch-ready")
            val context = ApplicationProvider.getApplicationContext<Context>()
            if (WatchPermissions.granted(context, WatchPermissions.heart)) {
                compose.onNodeWithText("Record").performClick()
                compose.waitUntil(35_000) { compose.onAllNodesWithText("Samsung Health Sensor Service is not installed on this Watch.").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Try again").assertIsEnabled()
                capture("ecg-watch-unavailable")
                val store = WatchStore(context)
                val saved = store.journal().use { EcgJournal(it).latest(store.installation) }!!
                assertNotNull(saved.startedElapsedMs)
                assertTrue(saved.startedElapsedMs!! <= android.os.SystemClock.elapsedRealtime())
                assertNotNull(saved.bootCount)
                lateinit var before: WatchEcgModel
                scenario.onActivity { before = ViewModelProvider(it)[WatchEcgModel::class.java] }
                scenario.recreate()
                scenario.onActivity { assertSame(before, ViewModelProvider(it)[WatchEcgModel::class.java]) }
                compose.onNodeWithText("Try again").assertIsEnabled().performClick()
                compose.onNodeWithText("ECG · 30 seconds").assertIsDisplayed()
            } else compose.onNodeWithText("Allow access").assertExists()
        }
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val context = ApplicationProvider.getApplicationContext<Context>()
        File(context.cacheDir, "$name.png").outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
