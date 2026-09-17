package com.mani.orbit

import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mani.orbit.sync.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WatchReadingsUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    @Test fun committedWatchReadingsAppearAndUpdateInTheNativePhone() {
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val now = System.currentTimeMillis()
        val installation = UUID.nameUUIDFromBytes("Orbit emulator watch".toByteArray()).toString()
        val sample = WatchReading(UUID.randomUUID().toString(), 1, now, now, 0, "heart", 83.0, "bpm", "valid", "instant",
            UUID.randomUUID().toString(), 1000)
        val path = rule.activity.getDatabasePath("watch-readings.db")
        ReadingJournal(path).use { it.receive(ReadingWire.encode(ReadingBatch(UUID.randomUUID().toString(), installation, listOf(sample))), now) }
        rule.onNodeWithContentDescription("Settings").performClick()
        val watchButton = rule.onNodeWithText("Watch readings").performScrollTo()
        check(watchButton.fetchSemanticsNode().boundsInRoot.bottom <= rule.onNodeWithTag("explore-island").fetchSemanticsNode().boundsInRoot.top)
        java.io.File(rule.activity.cacheDir, "settings-native-route.png").outputStream().use {
            rule.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        watchButton.performClick()
        rule.waitUntil(6000) { rule.onAllNodesWithText("83").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("83").assertIsDisplayed()
        java.io.File(rule.activity.cacheDir, "settings-watch-sources.png").outputStream().use {
            rule.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        ReadingJournal(path).use { it.receive(ReadingWire.encode(ReadingBatch(UUID.randomUUID().toString(), installation,
            listOf(sample.copy(revision = 2, value = 86.0)))), now + 1) }
        rule.waitUntil(6000) { rule.onAllNodesWithText("86").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("86").assertIsDisplayed()
        rule.activityRule.scenario.recreate()
        rule.waitUntil(6000) { rule.onAllNodesWithText("86").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("86").assertIsDisplayed()
    }
}
