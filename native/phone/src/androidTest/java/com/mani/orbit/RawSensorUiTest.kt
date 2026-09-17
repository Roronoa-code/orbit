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

class RawSensorUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun channelsPagingAndSavedNavigationRemainUsableAtLargeText() {
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        fun id() = UUID.randomUUID().toString()
        val chunk = mapSamsungRawProbeChunk(SensorRawProbe.PPG_CONTINUOUS, 0, Instant.ofEpochMilli(1789502400000),
            List(750) { i -> SamsungPpgPointRead(SamsungFieldRead.Value(1789502360000L + i * 40),
                SamsungFieldRead.Value(i % 50), SamsungFieldRead.Value(0), SamsungFieldRead.Value(null), SamsungFieldRead.Value(-1),
                SamsungFieldRead.Value(i % 30), SamsungFieldRead.Value(0)) })
        val page = RawSensorPage(RawSensorFrame(id(), id(), id(), 8, 100_000, 0, false, chunk), id(), null)
        var chosen: String? = null
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFBFA6EF), surface = Color(0xFF201D27)), typography = OrbitTypography) {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                Surface(color = Color(0xFF0B0A0F)) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                        RawSensorCard(page, false, false) { chosen = it }
                    }
                }
            }
        } }
        compose.onNodeWithText("Optical signal").assertIsDisplayed()
        capture("raw-phone-optical-large")
        compose.onNodeWithText("Infrared").performClick()
        compose.onNodeWithText("No qualified readings in this section").assertIsDisplayed()
        compose.onNodeWithText("Red").performClick()
        compose.onNodeWithContentDescription("Later samples").performScrollTo().performClick()
        compose.onNodeWithText("Samples 501–750 of 750").assertIsDisplayed()
        compose.onNodeWithContentDescription("Later samples").assertIsNotEnabled()
        compose.onNodeWithText("Older").performScrollTo().performClick()
        assertEquals(page.older, chosen)
        compose.onNodeWithText("Newer").assertIsNotEnabled()
        compose.onNodeWithText("Recording details").performScrollTo().performClick()
        compose.onNodeWithText("750 samples · 0 capture flags").performScrollTo().assertIsDisplayed()
        capture("raw-phone-details-large")
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        File(compose.activity.cacheDir, "$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
