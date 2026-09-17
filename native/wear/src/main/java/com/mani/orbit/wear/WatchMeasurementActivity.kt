package com.mani.orbit.wear

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.wear.compose.material3.*
import com.mani.health.core.model.measurement.MeasurementTracker
import com.mani.health.core.model.measurement.measurementPermission
import com.mani.orbit.sync.*
import java.time.LocalDate
import java.time.Period
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class WatchMeasurementActivity : ComponentActivity() {
    private val model: WatchMeasurementModel by viewModels()
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        model.permissionRevision++
        if (!granted) model.error = "Sensor access is off. You can enable it in Settings."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (model.tracker == null) model.tracker = savedInstanceState?.getString("tracker")?.let { name -> MeasurementTracker.entries.firstOrNull { it.name == name } }
        if (savedInstanceState?.getBoolean("running") == true && !model.running && !model.saving && model.pending == null && model.result == null)
            model.error = "Measurement stopped. Try again."
        setContent { WatchEnvironment { display ->
            MaterialTheme(colorScheme = ColorScheme(primary = Color(0xFFBBA1ED), background = Color(0xFF0B0A0F), surfaceContainer = Color(0xFF201D27))) {
                if (display.ambient != null) WatchAmbientScreen("Measure", "Raise wrist to continue", display)
                else Content()
            }
        } }
    }

    override fun onResume() { super.onResume(); model.permissionRevision++; model.refresh() }
    override fun onPause() { model.stop(); super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("tracker", model.tracker?.name)
        outState.putBoolean("running", model.running || model.saving || model.pending != null)
        super.onSaveInstanceState(outState)
    }

    @Composable internal fun Content() = with(model) {
        val haptic = LocalHapticFeedback.current
        BackHandler {
            when {
                running -> stop()
                saving || pending != null -> error = "Retry save before leaving to keep this result."
                result != null && details -> details = false
                result != null -> result = null
                tracker != null -> { tracker = null; confirmed = false; error = null }
                else -> finish()
            }
        }
        LaunchedEffect(savedFeedback) {
            if (savedFeedback) {
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) window.decorView.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                savedFeedback = false
            }
        }
        val selected = tracker
        val granted = remember(selected, permissionRevision) { selected == null || WatchPermissions.granted(this@WatchMeasurementActivity, measurementPermission(selected, Build.VERSION.SDK_INT)) }
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            key(selected, result?.batch?.id, pending != null, running, saving, error != null) { WorkoutPage(compact = true) {
                Text(when {
                    pending != null && !saving -> "Not saved"
                    result != null -> measurementLabel(result!!.primary.metric)
                    else -> selected?.label() ?: "Measure"
                }, fontSize = 12.sp, lineHeight = 14.sp,
                    textAlign = TextAlign.Center)
                when {
                    saving -> Text("Saving result…", fontSize = 14.sp, textAlign = TextAlign.Center)
                    pending != null && !running -> {
                        Button(onClick = { retrySave() }, modifier = Modifier.fillMaxWidth()) { Text("Retry save", fontSize = 12.sp) }
                        Text(error ?: "Retry to keep this result.", fontSize = 11.sp, lineHeight = 13.sp, textAlign = TextAlign.Center)
                    }
                    running -> {
                        FilledTonalButton(onClick = { stop() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Stop", fontSize = 12.sp) }
                        Text(guidance, fontSize = 11.sp, lineHeight = 13.sp, textAlign = TextAlign.Center)
                        Text(if (selected == MeasurementTracker.BIA) "Middle finger: Home\nRing finger: Back\nKeep hands apart and arms raised." else "Watch snug. Rest your arm and keep still.",
                            fontSize = 11.sp, lineHeight = 13.sp, textAlign = TextAlign.Center)
                    }
                    result != null -> {
                        val result = result!!
                        val primary = result.primary
                        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.Bottom) {
                            WatchNumber(String.format(Locale.UK, "%.1f", primary.value), Modifier.alignByBaseline())
                            Text(" ${primary.unit}", Modifier.alignByBaseline(), fontSize = 12.sp, lineHeight = 14.sp)
                        }
                        if (result.values.size > 1) {
                            FilledTonalButton(onClick = { details = !details; haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
                                modifier = Modifier.fillMaxWidth().semantics { stateDescription = if (details) "Expanded" else "Collapsed" }) {
                                Text(if (details) "Less detail" else "Details", fontSize = 12.sp)
                            }
                            Column(Modifier.fillMaxWidth().animateContentSize(), horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                if (details) result.values.filter { it.metric != primary.metric }.forEach { item ->
                                    Text("${measurementLabel(item.metric)}\n${String.format(Locale.UK, "%.1f", item.value)} ${item.unit}",
                                        style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                                }
                            }
                        }
                        FilledTonalButton(onClick = { model.result = null; details = false; tracker = null }, modifier = Modifier.fillMaxWidth()) { Text("Done", fontSize = 12.sp) }
                        Text(Instant.ofEpochMilli(result.batch.readings.first().start).atZone(ZoneId.systemDefault())
                            .format(DateTimeFormatter.ofPattern("d MMM · HH:mm", Locale.UK)), fontSize = 11.sp, lineHeight = 13.sp)
                        if (resultOrderUncertain) Text("Reading order uncertain", fontSize = 11.sp, lineHeight = 13.sp, textAlign = TextAlign.Center)
                        Text("Samsung Watch sensor", fontSize = 11.sp, lineHeight = 13.sp, textAlign = TextAlign.Center)
                        syncWarning?.let { Text(it, fontSize = 11.sp, lineHeight = 13.sp, textAlign = TextAlign.Center) }
                    }
                    selected != null && error != null -> {
                        FilledTonalButton(onClick = { error = null; confirmed = false }, contentPadding = PaddingValues(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Text("Try again", fontSize = 12.sp, lineHeight = 14.sp)
                        }
                        Text("Not completed", fontSize = 12.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.primary)
                        Text(error!!, fontSize = 11.sp, lineHeight = 13.sp, textAlign = TextAlign.Center)
                        if (!granted) FilledTonalButton(onClick = {
                            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                        }, modifier = Modifier.fillMaxWidth()) { Text("Settings", fontSize = 12.sp, lineHeight = 14.sp) }
                    }
                    selected == null -> {
                        historyError?.let {
                            FilledTonalButton(onClick = { refresh() }, enabled = !loading, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Refresh", fontSize = 12.sp) }
                            Text("History unavailable", fontSize = 11.sp, lineHeight = 13.sp, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
                            Text(it, fontSize = 11.sp, lineHeight = 13.sp, textAlign = TextAlign.Center)
                        }
                        listOf(MeasurementTracker.BIA, MeasurementTracker.SPO2, MeasurementTracker.SKIN_TEMPERATURE).forEach { kind ->
                            FilledTonalButton(onClick = { tracker = kind; error = null; confirmed = false }, contentPadding = PaddingValues(8.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("measure-${kind.name}")) {
                                Text(kind.label(), fontSize = 12.sp, lineHeight = 14.sp, textAlign = TextAlign.Center)
                            }
                        }
                        FilledTonalButton(onClick = { startActivity(Intent(this@WatchMeasurementActivity, WatchEcgActivity::class.java)) },
                            contentPadding = PaddingValues(8.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("measure-ECG")) { Text("ECG recording", fontSize = 12.sp, lineHeight = 14.sp) }
                        latest?.let { FilledTonalButton(onClick = ::openLatest, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Last result", fontSize = 12.sp) } }
                        FilledTonalButton(onClick = { startActivity(Intent(this@WatchMeasurementActivity, WatchHeartActivity::class.java)) },
                            contentPadding = PaddingValues(8.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("measure-HEART")) { Text("Live heart rate", fontSize = 12.sp, lineHeight = 14.sp) }
                        FilledTonalButton(onClick = { startActivity(Intent(this@WatchMeasurementActivity, WatchHeartActivity::class.java).putExtra("selectRaw", true)) },
                            contentPadding = PaddingValues(8.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("measure-RAW")) { Text("Sensor recordings", fontSize = 12.sp, lineHeight = 14.sp) }
                    }
                    else -> {
                        if (selected == MeasurementTracker.BIA) {
                            val ready = profile?.complete(LocalDate.now()) == true
                            if (!ready) {
                                FilledTonalButton(onClick = { refresh(true) }, enabled = !loading, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Refresh", fontSize = 12.sp, lineHeight = 14.sp) }
                                Text(if (loading) "Loading profile…" else profileError ?: "Add date of birth, sex, height and weight in Orbit’s phone profile.",
                                    fontSize = 11.sp, lineHeight = 13.sp, textAlign = TextAlign.Center)
                            } else {
                                Text("${profile!!.heightCm} cm · ${profile!!.weightKg} kg", fontSize = 11.sp, lineHeight = 13.sp, textAlign = TextAlign.Center)
                                Text("${Period.between(profile!!.birth, LocalDate.now()).years} years · ${profile!!.sex}", fontSize = 11.sp, lineHeight = 13.sp)
                                Text("From your phone profile", fontSize = 10.sp, lineHeight = 12.sp)
                                profileError?.let { Text("Using the last saved profile", fontSize = 11.sp, lineHeight = 13.sp) }
                                Text("Do not measure if pregnant or using an implanted electronic medical device.", fontSize = 11.sp, lineHeight = 13.sp, textAlign = TextAlign.Center)
                                if (Period.between(profile!!.birth, LocalDate.now()).years < 20) Text("Samsung warns that results under age 20 may be inaccurate.", fontSize = 11.sp, lineHeight = 13.sp, textAlign = TextAlign.Center)
                                FilledTonalButton(onClick = { confirmed = !confirmed; haptic.performHapticFeedback(if (confirmed) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff) }, modifier = Modifier.fillMaxWidth().semantics {
                                    role = Role.Checkbox; toggleableState = ToggleableState(confirmed)
                                }) {
                                    Text(if (confirmed) "Confirmed" else "These do not apply", style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
                                }
                            }
                        }
                        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center) }
                        if (!granted) FilledTonalButton(onClick = {
                            permission.launch(measurementPermission(selected, Build.VERSION.SDK_INT))
                        }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Allow access", fontSize = 12.sp) }
                        else Button(onClick = { start() }, enabled = selected != MeasurementTracker.BIA || confirmed && profile?.complete(LocalDate.now()) == true,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("start-sensor")) { Text("Measure", fontSize = 12.sp) }
                        if (selected != MeasurementTracker.BIA) Text(if (selected == MeasurementTracker.SPO2) "Rest your arm. Keep the Watch snug and still." else "A skin surface reading, not core body temperature.",
                            fontSize = 11.sp, lineHeight = 13.sp, textAlign = TextAlign.Center)
                        if (!granted && error != null) FilledTonalButton(onClick = {
                            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                        }, modifier = Modifier.fillMaxWidth()) { Text("Settings") }
                    }
                }
            } }
        }
    }
}

internal fun MeasurementTracker.label() = when (this) {
    MeasurementTracker.BIA -> "Body composition"
    MeasurementTracker.SPO2 -> "Blood oxygen"
    MeasurementTracker.SKIN_TEMPERATURE -> "Skin temperature"
    else -> name
}

internal fun measurementLabel(metric: String) = when (metric) {
    "BODY_FAT" -> "Body fat"; "BODY_FAT_MASS" -> "Fat mass"; "BODY_WATER" -> "Body water"
    "SKELETAL_MUSCLE_MASS" -> "Muscle"; "FAT_FREE_MASS" -> "Lean mass"; "BASAL_METABOLIC_RATE" -> "Resting energy"
    "SPO2" -> "Blood oxygen"; "SKIN_TEMPERATURE" -> "Skin temperature"; "AMBIENT_TEMPERATURE" -> "Surroundings"
    else -> metric
}
