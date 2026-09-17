package com.mani.orbit

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import com.mani.orbit.sync.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.util.UUID

class WatchSensorHistoryUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun historyPagesAndExpandsWithoutLeakingThePreviousSelection() {
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        fun id() = UUID.randomUUID().toString()
        val installation = id(); val boot = id()
        fun reading(metric: String, value: Double) = WatchReading(id(), 1, 1789426800000, 1789426800000, 0,
            metric, value, WatchReading.UNITS.getValue(metric), "valid", "instant", boot, 500, source = "samsung_sensor")
        val body = WatchMeasurement(ReadingBatch(id(), installation, listOf(reading("fat", 20.0), reading("muscle", 32.0), reading("lean", 64.0))), "BIA",
            listOf(MeasurementValue("BODY_FAT", 20.0, "%"), MeasurementValue("BODY_FAT_MASS", 16.0, "kg"), MeasurementValue("BODY_WATER", 44.0, "L"),
                MeasurementValue("SKELETAL_MUSCLE_MASS", 32.0, "kg"), MeasurementValue("FAT_FREE_MASS", 64.0, "kg"), MeasurementValue("BASAL_METABOLIC_RATE", 1500.0, "kcal")).reversed(),
            MeasurementProfile(LocalDate.of(1995, 5, 2), "female", 175.0, 80.0))
        val oxygen = WatchMeasurement(ReadingBatch(id(), installation, listOf(reading("oxygen", 98.0))), "SPO2", listOf(MeasurementValue("SPO2", 98.0, "%")))
        val page = mutableStateOf(MeasurementPage(body, oxygen.batch.id, null))
        val fontScale = mutableStateOf(1f)
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFBFA6EF), surface = Color(0xFF201D27)), typography = OrbitTypography) {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale.value)) {
            Surface(color = Color(0xFF0B0A0F), contentColor = MaterialTheme.colorScheme.onSurface) {
            Column(Modifier.fillMaxSize().background(Color(0xFF0B0A0F)).verticalScroll(rememberScrollState()).padding(20.dp)) {
                Text("Watch measurements", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(24.dp))
                WatchSensorResult(page.value) { selected -> page.value = if (selected == oxygen.batch.id)
                    MeasurementPage(oxygen, null, body.batch.id) else MeasurementPage(body, oxygen.batch.id, null) }
            }
        } } } }
        compose.onNodeWithText("20.0 %").assertIsDisplayed()
        compose.onNodeWithContentDescription("Newer measurement").assertIsNotEnabled()
        capture("sensor-history-compact")
        compose.onNodeWithText("Full result").performClick()
        compose.onNodeWithText("1500.0 kcal").performScrollTo().assertIsDisplayed()
        capture("sensor-history-details")
        compose.onNodeWithContentDescription("Older measurement").performScrollTo().performClick()
        compose.onNodeWithText("98.0 %").assertIsDisplayed()
        compose.onNodeWithText("Full result").assertDoesNotExist()
        compose.onNodeWithContentDescription("Older measurement").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Newer measurement").performClick()
        compose.onNodeWithText("Full result").assertIsDisplayed()
        compose.onNodeWithText("1500.0 kcal").assertDoesNotExist()
        compose.runOnIdle { fontScale.value = 1.5f }
        compose.onNodeWithText("20.0 %").performScrollTo().assertIsDisplayed()
        capture("sensor-history-large-compact")
        compose.onNodeWithText("Full result").performScrollTo().performClick()
        compose.onNodeWithText("1500.0 kcal").performScrollTo().assertIsDisplayed()
        capture("sensor-history-large-details")
        compose.runOnIdle { page.value = page.value.copy(orderingUncertain = true) }
        compose.onNodeWithContentDescription("Previous measurement").performScrollTo().assertIsEnabled()
        compose.onNodeWithContentDescription("Next measurement").assertIsNotEnabled()
        compose.onNodeWithText("Reading order uncertain").assertIsDisplayed()
        capture("sensor-history-order-uncertain")
        compose.onNodeWithContentDescription("Previous measurement").performClick()
        compose.onNodeWithText("Reading order uncertain").assertDoesNotExist()
        compose.onNodeWithText("98.0 %").assertIsDisplayed()
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        File(compose.activity.cacheDir, "$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
