package com.mani.orbit.wear

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.*
import com.mani.orbit.sync.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.util.UUID

class WatchSweatUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun roundWatchEstimateAndFailureKeepTheirReturnActionReachable() {
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        fun id() = UUID.randomUUID().toString()
        var value by mutableStateOf(SweatEstimate(id(), id(), id(), 1000, 3, "complete", 1_700_000_000_000, 700_000,
            MeasurementProfile(LocalDate.of(1995, 2, 3), "male", 180.0, 80.0), 0, 240.0, sensorAt = 1_700_000_000_000))
        compose.setContent { WatchEnvironment { MaterialTheme {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.3f)) {
                Box(Modifier.fillMaxSize().background(Color(0xFF0B0A0F))) { WorkoutPage {
                    WatchSweatEstimate(value)
                    FilledTonalButton(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text("Back to history") }
                } }
            }
        } } }
        compose.onNodeWithText("240 ml").assertIsDisplayed()
        compose.onNodeWithText("Back to history").performScrollTo().assertIsDisplayed()
        capture("sweat-watch-result-large")
        compose.runOnIdle { value = value.copy(phase = "unavailable", status = 3, rawMl = 0.0, reason = "SDK_STATUS") }
        compose.onNodeWithText("0 ml").assertDoesNotExist()
        compose.onNodeWithText("run under 5 min · distance or energy too low").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Back to history").performScrollTo().assertIsDisplayed()
        capture("sweat-watch-unavailable-large")
    }
    private fun capture(name: String) {
        File(compose.activity.cacheDir, "$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
