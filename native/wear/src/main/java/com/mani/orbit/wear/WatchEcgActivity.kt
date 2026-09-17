package com.mani.orbit.wear

import android.content.Intent
import android.app.Application
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
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewModelScope
import androidx.wear.compose.material3.*
import com.mani.health.core.model.measurement.MeasurementTracker
import com.mani.health.core.model.measurement.measurementPermission
import com.mani.orbit.sync.EcgRecord
import kotlinx.coroutines.*

/** Keep unsaved samples through Activity recreation; sensor collection still stops when leaving. */
internal class WatchEcgModel(application: Application) : AndroidViewModel(application) {
    var running by mutableStateOf(false); private set
    var saving by mutableStateOf(false); private set
    var seconds by mutableIntStateOf(0); private set
    var contact by mutableStateOf("Connecting…"); private set
    var error by mutableStateOf<String?>(null)
    var result by mutableStateOf<EcgRecord?>(null); private set
    var revision by mutableIntStateOf(0)
    var savedFeedback by mutableStateOf(false)
    var needsReview by mutableStateOf(false); private set
    var syncWarning by mutableStateOf<String?>(null); private set
    private var capture: WatchEcgCapture? = null
    private var operation: Job? = null
    val needsSave get() = capture?.needsSave == true

    fun stop() { if (running) operation?.cancel() }
    fun start() {
        if (running || saving || needsSave) return
        error = null; result = null; syncWarning = null; seconds = 0; contact = "Connecting…"; running = true
        val recorder = WatchEcgCapture(getApplication()); capture = recorder
        operation = viewModelScope.launch {
            try {
                result = recorder.run { time, signal -> seconds = time; contact = when {
                    signal.noContactCallbackCount > 0 -> "Check finger contact"
                    signal.saturationSampleCount > 0 -> "Signal needs review"
                    signal.unknownContactCallbackCount > 0 -> "Contact unavailable"
                    else -> "Keep still"
                } }
                needsReview = recorder.quality.completionIssue() != null
                savedFeedback = true
            } catch (cancelled: CancellationException) { error = "Recording stopped. Saved portions remain in history." }
            catch (failure: Exception) { error = if (recorder.needsSave) "Some samples need saving. Free space, then retry." else samsungMeasurementError(failure) }
            finally { syncWarning = recorder.syncWarning; running = false; revision++ }
        }
    }
    fun retrySave() {
        if (running || saving || !needsSave) return
        saving = true
        operation = viewModelScope.launch {
            try {
                result = withContext(NonCancellable + Dispatchers.IO) { capture!!.save() }
                needsReview = capture!!.quality.completionIssue() != null
                error = null; savedFeedback = true
            } catch (_: Exception) { error = "Could not save. Keep this screen open and free some space." }
            finally { syncWarning = capture?.syncWarning; saving = false; revision++ }
        }
    }
}

class WatchEcgActivity : ComponentActivity() {
    private val model: WatchEcgModel by viewModels()
    private val permissionName get() = measurementPermission(MeasurementTracker.ECG, Build.VERSION.SDK_INT)
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        model.revision++; if (!it) model.error = "Sensor access is off. Enable it in Settings."
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState?.getBoolean("running") == true && !model.running && !model.saving && !model.needsSave && model.result == null)
            model.error = "Recording stopped. Saved portions remain in history."
        setContent { WatchEnvironment { display ->
            MaterialTheme(colorScheme = ColorScheme(primary = Color(0xFFBBA1ED), background = Color(0xFF0B0A0F), surfaceContainer = Color(0xFF201D27))) {
                if (display.ambient != null) WatchAmbientScreen("ECG", "Raise wrist to continue", display) else Content()
            }
        } }
    }
    override fun onResume() { super.onResume(); model.revision++ }
    override fun onPause() { model.stop(); super.onPause() }
    override fun onSaveInstanceState(out: Bundle) { out.putBoolean("running", model.running || model.saving || model.needsSave); super.onSaveInstanceState(out) }

    @Composable private fun Content() = with(model) {
        BackHandler {
            when {
                running -> stop()
                saving || needsSave -> error = "Some samples need saving. Retry save before leaving."
                else -> finish()
            }
        }
        LaunchedEffect(savedFeedback) {
            if (savedFeedback) {
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                    window.decorView.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                savedFeedback = false
            }
        }
        val granted = remember(revision) { WatchPermissions.granted(this@WatchEcgActivity, permissionName) }
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            key(running, saving, needsSave, result?.id, error != null) { WorkoutPage(compact = true) {
                Text(if (!running && !saving && !needsSave && result == null && error == null) "ECG · 30 seconds" else "ECG recording",
                    fontSize = 14.sp, lineHeight = 17.sp, textAlign = TextAlign.Center)
                when {
                    running -> {
                        Text("${30 - seconds}s", fontSize = 30.sp, lineHeight = 34.sp)
                        Text(contact, fontSize = 12.sp, lineHeight = 14.sp, textAlign = TextAlign.Center)
                        FilledTonalButton(onClick = { stop() }, modifier = Modifier.fillMaxWidth()) { Text("Stop", fontSize = 12.sp) }
                    }
                    saving -> Text("Saving recording…", fontSize = 14.sp, textAlign = TextAlign.Center)
                    needsSave -> {
                        Text("Not fully saved", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                        FilledTonalButton(onClick = { retrySave() }, modifier = Modifier.fillMaxWidth()) { Text("Retry save", fontSize = 12.sp) }
                        error?.let { Text(it, fontSize = 11.sp, lineHeight = 13.sp, textAlign = TextAlign.Center) }
                    }
                    result != null -> {
                        Text(if (needsReview) "Saved · needs review" else if (result!!.phase == "complete") "Recording saved" else "Partial recording saved", fontSize = 14.sp, textAlign = TextAlign.Center)
                        Text("Review the waveform on your phone.", fontSize = 12.sp, lineHeight = 14.sp, textAlign = TextAlign.Center)
                        FilledTonalButton(onClick = { finish() }, modifier = Modifier.fillMaxWidth()) { Text("Done", fontSize = 12.sp) }
                        syncWarning?.let { Text(it, fontSize = 11.sp, lineHeight = 13.sp, textAlign = TextAlign.Center) }
                    }
                    error != null -> {
                        Text("Not completed", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                        FilledTonalButton(onClick = { error = null }, modifier = Modifier.fillMaxWidth()) { Text("Try again", fontSize = 12.sp) }
                        Text(error!!, fontSize = 11.sp, lineHeight = 13.sp, textAlign = TextAlign.Center)
                        if (!granted) FilledTonalButton(onClick = { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) },
                            modifier = Modifier.fillMaxWidth()) { Text("Settings", fontSize = 12.sp) }
                    }
                    else -> {
                        Text("Arm still. Lightly touch the upper key with your other hand.", fontSize = 12.sp, lineHeight = 14.sp, textAlign = TextAlign.Center)
                        FilledTonalButton(onClick = { if (granted) start() else permission.launch(permissionName) }, modifier = Modifier.fillMaxWidth().testTag("start-ecg")) {
                            Text(if (granted) "Record" else "Allow access", fontSize = 12.sp)
                        }
                        Text("Signal only · no diagnosis", fontSize = 11.sp, lineHeight = 13.sp, textAlign = TextAlign.Center)
                    }
                }
            } }
        }
    }
}
