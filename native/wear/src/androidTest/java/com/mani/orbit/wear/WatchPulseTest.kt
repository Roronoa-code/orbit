package com.mani.orbit.wear

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.wear.compose.material3.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WatchPulseTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun emulatorOnly() { check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish")) }
    @Test fun newMeasurementRejectsCachedOtherBootUncertainAndDroppedOutReadings() {
        val now = 1789450200000L
        val started = PulseRequest("current-boot", 100000)
        var request by mutableStateOf<PulseRequest?>(started)
        var elapsed by mutableLongStateOf(102000)
        var error by mutableStateOf<String?>(null)
        var status by mutableStateOf("Measurement stopped")
        var font by mutableFloatStateOf(1f)
        fun reading(at: Long = 101000, boot: String = started.boot, uncertain: Boolean = false, quality: String = "valid") = JSONObject()
            .put("metric", "heart").put("value", 78).put("quality", quality).put("boot", boot)
            .put("end", now - 1000).put("elapsedMs", at).put("timeUncertain", uncertain)
        var row by mutableStateOf<JSONObject?>(null)
        compose.setContent {
            val device = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(device.density, font)) {
                MaterialTheme(colorScheme = ColorScheme(primary = Color(0xFFBBA1ED), background = Color(0xFF0B0A0F), surfaceContainer = Color(0xFF201D27))) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { WorkoutPage(compact = true) {
                    Text("Heart rate", style = MaterialTheme.typography.titleSmall)
                    WatchPulseReading(row, now, elapsed, request, status, error)
                    FilledTonalButton(onClick = {}) { Text(if (request == null) "Measure" else "Stop") }
                } } }
            }
        }
        fun assertReading(value: String, guidance: String, capture: String? = null) {
            compose.onNodeWithTag("pulse-value").performScrollTo().assertTextEquals(value)
            compose.onNodeWithTag("pulse-guidance").performScrollTo().assertTextEquals(guidance)
            if (capture != null) {
                val guidanceBounds = compose.onNodeWithTag("pulse-guidance").fetchSemanticsNode().boundsInRoot
                val viewport = compose.onNodeWithTag("watch-safe-viewport").fetchSemanticsNode().boundsInRoot
                compose.onNode(hasScrollAction()).performSemanticsAction(SemanticsActions.ScrollBy) {
                    it(0f, guidanceBounds.center.y - viewport.center.y)
                }
                compose.waitForIdle()
                val node = compose.onNodeWithTag("pulse-guidance").fetchSemanticsNode()
                assertEquals("Complete guidance remains reachable", node.layoutInfo.height.toFloat(), node.boundsInRoot.height, 1f)
                val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
                compose.activity.cacheDir.resolve("pulse-$capture-$font.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        for (scale in listOf(1f, 1.5f)) {
            compose.runOnIdle { font = scale; row = null; elapsed = 102000; request = started; error = null }
            assertReading("—", "Finding your pulse…", "missing")
            compose.runOnIdle { row = reading(at = started.elapsed - 1) }
            assertReading("—", "Finding your pulse…")
            compose.runOnIdle { row = reading(boot = "previous-boot") }
            assertReading("—", "Finding your pulse…")
            compose.runOnIdle { row = reading(uncertain = true) }
            assertReading("—", "Reading time uncertain")
            compose.runOnIdle { row = reading(quality = "unreliable") }
            assertReading("—", "Waiting for a reliable reading", "unreliable")
            compose.runOnIdle { row = reading() }
            assertReading("78", "Measuring", "valid")
            compose.runOnIdle { elapsed = 112000 }
            assertReading("—", "Finding your pulse…", "dropout")
            compose.runOnIdle { status = "Adjust your watch and keep still" }
            assertReading("—", status, "contact")
            compose.runOnIdle { error = "Measurement could not start"; request = null }
            assertReading("—", error!!, "failed")
            compose.runOnIdle { error = null; status = "Measurement stopped"; row = reading().put("end", now - 3600000) }
            assertReading("78", "Recorded 1h ago", "historical")
            compose.runOnIdle { row = reading(quality = "no_contact") }
            assertReading("—", "Check watch contact")
        }
    }
}
