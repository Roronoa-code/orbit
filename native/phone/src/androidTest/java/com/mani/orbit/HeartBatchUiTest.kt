package com.mani.orbit

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.mani.health.integration.samsungsensor.*
import com.mani.orbit.sync.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.UUID

class HeartBatchUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun intervalsStayQualifiedAndDetailsRemainUsableAtLargeText() {
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        fun id() = UUID.randomUUID().toString()
        val batch = mapSamsungHeartRateBatch(0, Instant.ofEpochMilli(1789502400000), listOf(SamsungHeartRatePointRead(
            SamsungFieldRead.Value(1789502399000L), SamsungFieldRead.Value(80), SamsungFieldRead.Value(1),
            SamsungFieldRead.Value(listOf(750, 780, 20)), SamsungFieldRead.Value(listOf(0, 0, -1)))))
        val page = HeartPage(HeartFrame(id(), id(), id(), 8, 100_000, 0, false, batch), id(), null)
        var expanded by mutableStateOf(false); var font by mutableStateOf(1f); var chosen: String? = null
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFBFA6EF), surface = Color(0xFF201D27)), typography = OrbitTypography) {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, font)) {
                Surface(color = Color(0xFF0B0A0F)) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                        HeartBatchCard(page, false, expanded, false, { expanded = !expanded }, { chosen = it })
                    }
                }
            }
        } }
        compose.onNodeWithText("750–780 ms").assertIsDisplayed()
        compose.onNodeWithText("2 of 3 intervals marked normal").assertIsDisplayed()
        capture("heart-phone-summary")
        compose.onNodeWithText("Recording details").performClick()
        compose.onNodeWithText("Newer").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Older").performClick(); assertEquals(page.older, chosen)
        compose.runOnIdle { font = 1.5f }
        compose.onNodeWithText("Older").performScrollTo().assertIsDisplayed()
        capture("heart-phone-details-large")
        compose.onNodeWithText("Less").performScrollTo().performClick()
        compose.onNodeWithText("Recording details").assertIsDisplayed()
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        File(compose.activity.cacheDir, "$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
