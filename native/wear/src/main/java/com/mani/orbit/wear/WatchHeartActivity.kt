package com.mani.orbit.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.*
import com.mani.health.integration.samsungsensor.*
import java.util.Locale

internal fun recordingTitle(probe: SensorRawProbe?) = when (probe) {
    null -> "Live heart rate"
    SensorRawProbe.PPG_CONTINUOUS -> "Optical signal"
    SensorRawProbe.ACCELEROMETER_CONTINUOUS -> "Movement"
    SensorRawProbe.SKIN_TEMPERATURE_CONTINUOUS -> "Skin temperature"
}

internal fun recordingPermissions(probe: SensorRawProbe?): Array<String> = when {
    probe == null -> arrayOf(WatchPermissions.heart)
    // Samsung's additional permission alone does not satisfy Android's health foreground-service gate.
    probe == SensorRawProbe.PPG_CONTINUOUS && android.os.Build.VERSION.SDK_INT >= 36 ->
        arrayOf(requiredRawProbePermission(probe), android.Manifest.permission.ACTIVITY_RECOGNITION)
    else -> arrayOf(requiredRawProbePermission(probe))
}

private fun recordingHint(probe: SensorRawProbe?) = when (probe) {
    null -> "Records heart rate and beat intervals until you stop."
    SensorRawProbe.PPG_CONTINUOUS -> "Records three optical channels. Keep your Watch snug."
    SensorRawProbe.ACCELEROMETER_CONTINUOUS -> "Records movement on three axes until you stop."
    SensorRawProbe.SKIN_TEMPERATURE_CONTINUOUS -> "Records skin and surrounding temperature. This is not core body temperature."
}

/** Navigation leaves the explicit foreground session running. Only Stop ends collection. */
class WatchHeartActivity : ComponentActivity() {
    private var permissionError by mutableStateOf<String?>(null)
    private var permissionRevision by mutableIntStateOf(0)
    private val permission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { allowed ->
        permissionError = if (allowed.isNotEmpty() && allowed.values.all { it }) null else "Sensor access is off. Allow it in Orbit’s permissions."
        permissionRevision++
    }
    override fun onResume() { super.onResume(); permissionRevision++ }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by WatchHeartService.state.collectAsState()
            var selected by rememberSaveable { mutableStateOf(intent.getStringExtra("probe")) }
            val picker = intent.getBooleanExtra("selectRaw", false)
            val probe = if (state.active) state.probe else SensorRawProbe.entries.firstOrNull { it.name == selected }
            val granted = remember(permissionRevision, probe) { recordingPermissions(probe).all { WatchPermissions.granted(this, it) } }
            val haptic = LocalHapticFeedback.current
            BackHandler {
                if (picker && selected != null && !WatchHeartService.state.value.active) selected = null else finish()
            }
            WatchEnvironment { display ->
                MaterialTheme(colorScheme = ColorScheme(primary = Color(0xFFBBA1ED), background = Color(0xFF0B0A0F), surfaceContainer = Color(0xFF201D27))) {
                    val reading = state.reading
                    val fresh = state.phase == "running" && reading != null && reading.quality == "valid" && !reading.timeUncertain &&
                        display.elapsed - reading.elapsedMs in 0..10_000
                    val choose = picker && selected == null && !state.active
                    val title = if (choose) "Sensor recordings" else recordingTitle(probe)
                    if (display.ambient != null) WatchAmbientScreen(title, if (state.active) "Recording · raise wrist" else "Stopped", display)
                    else key(choose, probe) {
                        WorkoutPage {
                            Text(title, fontSize = 12.sp, lineHeight = 14.sp)
                            if (choose) {
                                SensorRawProbe.entries.forEach { kind ->
                                    FilledTonalButton(onClick = { selected = kind.name; permissionError = null; haptic.performHapticFeedback(HapticFeedbackType.Confirm) },
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("raw-${kind.name}"), contentPadding = PaddingValues(8.dp)) {
                                        Text(recordingTitle(kind), fontSize = 12.sp, lineHeight = 14.sp, textAlign = TextAlign.Center)
                                    }
                                }
                            } else {
                            if (state.active && probe == null) WatchNumber(if (fresh) reading!!.value!!.toInt().toString() else "—", Modifier.testTag("live-heart-value"))
                            if (state.active && probe == SensorRawProbe.SKIN_TEMPERATURE_CONTINUOUS) {
                                WatchNumber(if (fresh) String.format(Locale.UK, "%.1f°", reading!!.value) else "—")
                            }
                            FilledTonalButton(onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                                permissionError = null
                                try {
                                    if (state.active) WatchHeartService.stop(this@WatchHeartActivity)
                                    else if (granted) WatchHeartService.start(this@WatchHeartActivity, probe)
                                    else permission.launch(recordingPermissions(probe))
                                } catch (_: Exception) { permissionError = "Could not start. Check sensor access and retry." }
                            }, enabled = state.phase != "stopping", contentPadding = PaddingValues(8.dp),
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("live-heart-action")) {
                                Text(if (state.phase == "stopping") "Stopping…" else if (state.active) "Stop" else if (granted) "Start" else "Allow access",
                                    modifier = Modifier.fillMaxWidth(), fontSize = 12.sp, textAlign = TextAlign.Center)
                            }
                            Text(permissionError ?: state.message?.takeIf { state.probe == probe } ?: when {
                                state.phase == "starting" -> "Connecting to Samsung sensor…"
                                fresh -> if (probe == null) "bpm · live" else "°C · skin"
                                state.active && state.callbacks > 0 && probe != null -> "Recording · saved on Watch"
                                state.active -> "Waiting for sensor readings"
                                else -> getSharedPreferences("samsung-heart", MODE_PRIVATE).let { prefs ->
                                    prefs.getString("outcome", null).takeIf { prefs.getString("probe", null) == probe?.name }
                                } ?: recordingHint(probe)
                            }, fontSize = 11.sp, lineHeight = 13.sp, textAlign = TextAlign.Center)
                            if (state.probe == probe && state.callbacks > 0) Text(
                                if (probe == null) "${state.callbacks} batches · ${state.intervals} intervals saved" else "${state.samples} samples saved",
                                fontSize = 10.sp, textAlign = TextAlign.Center)
                            Text(if (probe == null) "Ordinary pulse checks and background pulse remain available in Today."
                                else "Open Watch readings on your phone to explore the recording.", fontSize = 10.sp, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
            }
        }
    }
}
