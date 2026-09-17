package com.mani.orbit.wear

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelProvider
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ColorScheme
import com.mani.orbit.sync.WatchMeasurement
import com.mani.orbit.sync.MeasurementProfile
import com.mani.health.integration.samsungsensor.OnDemandException
import com.mani.health.core.model.measurement.MeasurementTracker
import android.content.Context
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDate

/** Real unsupported-service route plus display-only fixtures. No synthetic reading enters a health journal. */
class WatchMeasurementUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun profileAcquisitionAndFailureStatesRemainActionableAtBothSizes() {
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertTrue("Local measurement review requires the existing BODY_SENSORS test grant", WatchPermissions.granted(context, WatchPermissions.heart))
        val profile = measurementFixture().profile!!
        data class Case(val name: String, val tracker: MeasurementTracker?, val action: String?, val profile: MeasurementProfile? = null,
            val running: Boolean = false, val loading: Boolean = false, val saving: Boolean = false,
            val error: String? = null, val profileError: String? = null, val historyError: String? = null)
        val cases = listOf(
            Case("menu", null, "Body composition"),
            Case("profile-loading", MeasurementTracker.BIA, "Refresh", loading = true),
            Case("profile-missing", MeasurementTracker.BIA, "Refresh"),
            Case("profile-failed", MeasurementTracker.BIA, "Refresh", profileError = "Profile could not refresh. Check your phone connection."),
            Case("profile-ready", MeasurementTracker.BIA, "Measure", profile),
            Case("profile-cached", MeasurementTracker.BIA, "Measure", profile, profileError = "Profile could not refresh."),
            Case("profile-under-20", MeasurementTracker.BIA, "Measure", profile.copy(birth = LocalDate.now().minusYears(17))),
            Case("oxygen-ready", MeasurementTracker.SPO2, "Measure"),
            Case("temperature-ready", MeasurementTracker.SKIN_TEMPERATURE, "Measure"),
            Case("collecting-bia", MeasurementTracker.BIA, "Stop", profile, running = true),
            Case("collecting-oxygen", MeasurementTracker.SPO2, "Stop", running = true),
            Case("saving", MeasurementTracker.SPO2, null, saving = true),
            Case("policy-error", MeasurementTracker.SPO2, "Try again", error = samsungMeasurementError(OnDemandException("SDK_POLICY_REJECTED"))),
            Case("timeout", MeasurementTracker.BIA, "Try again", profile, error = samsungMeasurementError(OnDemandException("TIMED_OUT"))),
            Case("history-failed", null, "Refresh", historyError = "Saved measurements could not refresh."))
        ActivityScenario.launch(WatchMeasurementActivity::class.java).use { scenario ->
            lateinit var model: WatchMeasurementModel
            scenario.onActivity { model = ViewModelProvider(it)[WatchMeasurementModel::class.java] }
            compose.waitUntil(30_000) { !model.loading }
            var width by mutableIntStateOf(192)
            var scale by mutableFloatStateOf(1f)
            var frame by mutableStateOf("")
            scenario.onActivity { activity ->
                val pixels = activity.windowManager.currentWindowMetrics.bounds.width()
                activity.setContent {
                    CompositionLocalProvider(LocalDensity provides Density(pixels.toFloat() / width, scale)) {
                        MaterialTheme(colorScheme = ColorScheme(primary = Color(0xFFBBA1ED), background = Color(0xFF0B0A0F), surfaceContainer = Color(0xFF201D27))) { key(frame) { activity.Content() } }
                    }
                }
            }
            for (size in listOf(192, 228)) for (font in listOf(1f, 1.5f)) for (case in cases) {
                compose.runOnIdle {
                    width = size; scale = font; frame = "${case.name}-$size-$font"; model.tracker = case.tracker; model.result = null; model.confirmed = false; model.error = case.error
                    state(model, "profile", case.profile); state(model, "profileError", case.profileError); state(model, "historyError", case.historyError)
                    state(model, "running", case.running); state(model, "loading", case.loading); state(model, "saving", case.saving)
                    state<WatchMeasurement?>(model, "latest", null); setPending(model, null)
                    state(model, "guidance", if (case.tracker == MeasurementTracker.BIA) "Keep touching both keys" else "Connecting…")
                }
                capture("measurement-state-${case.name}-$size-$font")
                if (case.action != null && case.name !in setOf("profile-ready", "profile-cached", "profile-under-20")) {
                    val button = compose.onNodeWithText(case.action).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                    val viewport = compose.onNodeWithTag("watch-safe-viewport").fetchSemanticsNode().boundsInRoot
                    val pixels = context.resources.displayMetrics.widthPixels
                    assertTrue("Action under fade at $size/$font/${case.name}: $button in $viewport",
                        button.bottom <= viewport.bottom - 12f * pixels / size + 1f)
                }
                case.action?.let { compose.onNodeWithText(it).performScrollTo().assertIsDisplayed() }
                if (case.tracker == MeasurementTracker.BIA && case.error == null && !case.running) {
                    compose.onNodeWithTag("start-sensor").performScrollTo().assertIsNotEnabled()
                    if (case.profile != null) {
                        compose.onNodeWithText("These do not apply").performScrollTo().performClick()
                        compose.onNodeWithText("Confirmed").assertIsDisplayed()
                        compose.onNodeWithTag("start-sensor").performScrollTo().assertIsEnabled()
                        if (case.name == "profile-under-20") compose.onNodeWithText("Samsung warns that results under age 20 may be inaccurate.").performScrollTo().assertIsDisplayed()
                    }
                }
                if (case.name == "profile-cached") compose.onNodeWithText("Using the last saved profile").performScrollTo().assertIsDisplayed()
            }
            compose.runOnIdle { state(model, "running", false); state(model, "saving", false) }
        }
    }
    @Test fun realMenuProfileGateAndUnsupportedServiceStayActionable() {
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        ActivityScenario.launch(WatchMeasurementActivity::class.java).use { activity ->
            compose.onNodeWithTag("measure-BIA").performScrollTo().performClick()
            compose.waitUntil(30_000) { compose.onAllNodesWithText("Refresh").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("start-sensor").assertIsNotEnabled()
            capture("measurement-profile-needed")
            activity.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithTag("measure-SPO2").performScrollTo().performClick()
            compose.onNodeWithText("Rest your arm. Keep the Watch snug and still.").assertExists()
            capture("measurement-oxygen-ready")
            // Full verification grants existing BODY_SENSORS for the emulator. This never grants access itself.
            val context = ApplicationProvider.getApplicationContext<Context>()
            if (WatchPermissions.granted(context, WatchPermissions.heart)) {
                compose.onNodeWithTag("start-sensor").performScrollTo().performClick()
                compose.waitUntil(35_000) { compose.onAllNodesWithText("Samsung Health Sensor Service is not installed on this Watch.").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Try again").assertIsEnabled()
                capture("measurement-service-unavailable")
            } else compose.onNodeWithText("Allow access").assertExists()
        }
    }

    @Test fun pendingRecoveryAndPinnedResultsSurviveRecreationAndFitBothWatchSizes() {
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        ActivityScenario.launch(WatchMeasurementActivity::class.java).use { scenario ->
            compose.onNodeWithTag("measure-BIA").assertExists()
            lateinit var model: WatchMeasurementModel
            val pending = measurementFixture()
            scenario.onActivity {
                model = ViewModelProvider(it)[WatchMeasurementModel::class.java]
                setPending(model, pending); model.tracker = MeasurementTracker.BIA; model.error = "Could not save. Your result is kept here."
                it.onBackPressedDispatcher.onBackPressed()
                assertFalse(it.isFinishing)
            }
            scenario.recreate()
            scenario.onActivity { assertSame(model, ViewModelProvider(it)[WatchMeasurementModel::class.java]); assertSame(pending, model.pending) }
            compose.onNodeWithText("Retry save").assertIsEnabled().assertIsDisplayed()
            var width by mutableIntStateOf(192)
            var scale by mutableFloatStateOf(1f)
            scenario.onActivity { activity ->
                val pixels = activity.windowManager.currentWindowMetrics.bounds.width()
                activity.setContent {
                    CompositionLocalProvider(LocalDensity provides Density(pixels.toFloat() / width, scale)) {
                        MaterialTheme(colorScheme = ColorScheme(primary = Color(0xFFBBA1ED), background = Color(0xFF0B0A0F), surfaceContainer = Color(0xFF201D27))) { activity.Content() }
                    }
                }
            }
            for (dpWidth in listOf(192, 228)) for (font in listOf(1f, 1.5f)) {
                compose.runOnIdle { width = dpWidth; scale = font; setPending(model, pending); model.result = null; model.error = "Retry save before leaving to keep this result." }
                compose.onNodeWithText("Retry save").assertIsEnabled().performScrollTo().assertIsDisplayed()
                capture("measurement-pending-$dpWidth-$font")
                for (kind in listOf("BIA", "SPO2", "SKIN_TEMPERATURE")) {
                    val result = measurementFixture(kind)
                    compose.runOnIdle { setPending(model, null); model.error = null; model.tracker = null; model.details = false; model.result = result }
                    compose.onNodeWithText(String.format(java.util.Locale.UK, "%.1f", result.primary.value)).assertIsDisplayed()
                    capture("measurement-result-$kind-$dpWidth-$font")
                    if (result.values.size > 1) {
                        compose.onNodeWithText("Details").performScrollTo().assertIsDisplayed()
                        val before = compose.onNodeWithText("Details").fetchSemanticsNode().boundsInRoot
                        compose.onNodeWithText("Details").performClick()
                        val after = compose.onNodeWithText("Less detail").fetchSemanticsNode().boundsInRoot
                        assertEquals("Disclosure control must not jump", before.top, after.top, 1f)
                        result.values.filter { it.metric != result.primary.metric }.forEach { item ->
                            compose.onNodeWithText("${measurementLabel(item.metric)}\n${String.format(java.util.Locale.UK, "%.1f", item.value)} ${item.unit}")
                                .performScrollTo().assertIsDisplayed()
                        }
                        capture("measurement-details-$kind-$dpWidth-$font")
                        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                        compose.onNodeWithText("Details").assertExists()
                    } else compose.onNodeWithText("Details").assertDoesNotExist()
                    scenario.onActivity { model.refresh() }
                    compose.waitUntil(30_000) { !model.loading }
                    assertSame("A history refresh must not replace the open result", result, model.result)
                    compose.onNodeWithText("Done").performScrollTo().performClick()
                    compose.runOnIdle { assertNull(model.result) }
                }
            }
        }
    }

    /** Display-only failure fixture. Never writes a synthetic measurement to the production journal. */
    @Suppress("UNCHECKED_CAST") private fun <T> state(model: WatchMeasurementModel, name: String, value: T) {
        val state = model.javaClass.getDeclaredField("${name}\$delegate").apply { isAccessible = true }.get(model) as MutableState<T>
        state.value = value
    }
    @Suppress("UNCHECKED_CAST") private fun setPending(model: WatchMeasurementModel, value: WatchMeasurement?) {
        val state = model.javaClass.getDeclaredField("pending\$delegate").apply { isAccessible = true }.get(model) as MutableState<WatchMeasurement?>
        state.value = value
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val context = ApplicationProvider.getApplicationContext<Context>()
        File(context.cacheDir, "$name.png").outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
