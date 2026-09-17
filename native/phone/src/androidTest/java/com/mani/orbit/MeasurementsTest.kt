package com.mani.orbit

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class MeasurementsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val date = LocalDate.of(2026, 9, 14)
    private fun at(day: String) = LocalDate.parse(day).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val samples = MeasurementHistory(
        weights = listOf(Reading(at("2026-08-23"), 76.04), Reading(at("2026-08-25"), 76.01)),
        fatPercent = listOf(Reading(at("2026-08-25") + 30_000, 18.0)),
        lean = listOf(Reading(at("2026-08-25"), 62.0)),
    )

    @Before fun emulatorOnly() {
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish") && android.os.Build.MODEL.contains("sdk", ignoreCase = true))
        compose.activityRule.scenario.onActivity {
            it.enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
                navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
            it.window.isNavigationBarContrastEnforced = false
        }
    }

    @Test fun originalDatesPairedCompositionAndSparseRanges() {
        fun row(type: String, stamp: Long, value: Double) = JSONObject().put("type", type).put("id", "$type-$stamp")
            .put("source", "com.sec.android.app.shealth").put("start", stamp).put("end", stamp).put("value", value)
        val stamp = at("2026-08-25")
        val raw = JSONObject().put("schema", 1).put("rows", JSONArray()
            .put(row("weight", stamp, 76.01)).put(row("fat", stamp + 60_000, 18.0))
            .put(row("fat", stamp + 120_000, 20.0)).put(row("lean", stamp, 62.0)))
        val history = NativeHealthProjection.read(raw, date).measurements
        assertEquals(2, history.fatPercent.size)
        assertEquals(1, history.readings(Measurement.Fat).size)
        assertEquals(13.6818, history.readings(Measurement.Fat).single().value, .00001)
        assertTrue(history.readings(Measurement.Muscle).isEmpty())
        assertEquals(62.0 / 76.01, history.share(Measurement.Lean, history.lean.single())!!.toDouble(), .00001)
        val trend = MeasurementTrend.from(samples.weights, MeasurementPeriod.Month, date)
        assertTrue(trend.hasChart)
        assertFalse(trend.hasStatistics)
        assertEquals(trend.y(0), trend.y(1), 0f)
        assertEquals(0, trend.nearest(-1f)); assertEquals(1, trend.nearest(2f))
        val uneven = MeasurementTrend.from(listOf(Reading(stamp, 76.0), Reading(stamp + 86_400_000, 75.0), Reading(stamp + 864_000_000, 77.0)), MeasurementPeriod.Month, date)
        assertEquals(.1f, uneven.x(1), .0001f)
        assertEquals(LocalDate.of(2023, 3, 2), MeasurementPeriod.Year.start(LocalDate.of(2024, 3, 1)))
        assertFalse(MeasurementTrend.from(samples.weights, MeasurementPeriod.Week, date).hasChart)
        assertFalse(MeasurementTrend.from(samples.weights.take(1), MeasurementPeriod.Year, date).hasChart)
        assertNull(history.share(Measurement.Lean, Reading(stamp + 61_000, 60.0)))
    }

    @Test fun nativeChartSelectionHoldReversalAndEmptyStates() {
        val state = mutableStateOf(HealthScreenState(day = HealthDay(date = date, measurements = samples), loading = false))
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme(background = Color(0xFF0B0A0F)), typography = OrbitTypography) {
            Box(Modifier.fillMaxSize().background(Color(0xFF0B0A0F)).safeDrawingPadding()) { MeasurementsScreen(state.value) }
        } }
        compose.onNodeWithTag("measurement-trend").performScrollTo()
        compose.onNodeWithTag("measurement-chart").assertExists()
        compose.onNodeWithTag("measurement-statistics").assertDoesNotExist()
        capture("two-readings")
        compose.onNodeWithTag("measurement-chart").performTouchInput { click(Offset(5f, centerY)) }
        compose.onNodeWithTag("measurement-chart").assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "76.0 kilograms, 23 Aug 2026"))
        compose.onNodeWithTag("measurement-chart").performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(1f)) }
        compose.onNodeWithTag("measurement-chart").assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "76.0 kilograms, 25 Aug 2026"))
        compose.onNodeWithText("7D").performClick()
        compose.onNodeWithTag("measurement-chart").assertDoesNotExist()
        compose.onNodeWithText("No readings in this period.", substring = true).assertExists()
        compose.onNodeWithText("1Y").performClick()
        compose.onNodeWithTag("measurement-chart").assertExists()
        compose.onNodeWithText("15 Sept 2025 – 14 Sept 2026").assertExists()
        compose.onNodeWithTag("measurement-metric").performScrollTo()
        compose.onNodeWithTag("measurement-metric").performTouchInput {
            down(Offset(width * .12f, centerY)); advanceEventTime(600)
            moveTo(Offset(width * .87f, centerY), 120); moveTo(Offset(width * .38f, centerY), 70)
            moveTo(Offset(width * .87f, centerY), 80); up()
        }
        compose.onNode(hasText("Lean mass") and hasClickAction()).assertIsSelected()
        compose.onNodeWithTag("measurement-metric").performTouchInput {
            down(Offset(width * .87f, centerY)); moveTo(Offset(width * .12f, centerY), 90); up()
        }
        compose.onNode(hasText("Weight") and hasClickAction()).assertIsSelected()
        compose.onNodeWithTag("measurement-metric").performTouchInput {
            down(Offset(width * .12f, centerY)); moveTo(Offset(width * .75f, centerY), 120)
        }
        capture("held-selector")
        compose.onNodeWithTag("measurement-metric").performTouchInput { cancel() }
        compose.onNode(hasText("Weight") and hasClickAction()).assertIsSelected()
        compose.onNodeWithTag("composition-ring").performScrollTo().performTouchInput { swipeLeft(durationMillis = 160) }
        compose.onNode(hasText("Fat") and hasClickAction()).assertIsSelected()
        compose.onNodeWithTag("composition-ring").performTouchInput { swipeLeft(durationMillis = 160) }
        compose.onNode(hasText("Muscle") and hasClickAction()).assertIsSelected()
        compose.onNodeWithText("No muscle readings shared yet.").assertExists()
        compose.onNode(hasText("Weight") and hasClickAction()).performScrollTo().performClick()
        compose.onNodeWithTag("measurement-trend").performScrollTo()
        compose.runOnIdle { state.value = state.value.copy(day = HealthDay(date = date, measurements = samples.copy(weights = samples.weights.take(1)))) }
        compose.onNodeWithTag("measurement-chart").assertDoesNotExist()
        capture("single-reading")
        compose.runOnIdle { state.value = state.value.copy(day = HealthDay(date = date)) }
        compose.onNodeWithText("No weight readings shared yet.").assertExists()
        compose.onNodeWithTag("measurement-chart").assertDoesNotExist()
    }

    @Test fun largeTextKeepsSelectorsAndReadingReachable() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                MaterialTheme(colorScheme = darkColorScheme(), typography = OrbitTypography) {
                    Box(Modifier.fillMaxSize().background(Color(0xFF0B0A0F)).safeDrawingPadding()) {
                        MeasurementsScreen(HealthScreenState(day = HealthDay(date = date, measurements = samples), loading = false))
                    }
                }
            }
        }
        compose.onNodeWithTag("measurement-trend").performScrollTo()
        compose.onNodeWithTag("measurement-period").performScrollTo()
        compose.onNodeWithText("3M").assertIsDisplayed().performClick().assertIsSelected()
        compose.onNodeWithTag("measurement-chart").assertExists()
        capture("large-text")
    }

    private fun capture(name: String) {
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            File(compose.activity.cacheDir, "measurements-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
