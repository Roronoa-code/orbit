package com.mani.orbit

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType

@Composable internal fun WatchCommandFeedback(
    events: kotlinx.coroutines.flow.SharedFlow<Boolean> = WatchWorkoutControl.feedback) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(lifecycle, events) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            events.collect { success -> haptic.performHapticFeedback(
                if (success) HapticFeedbackType.Confirm else HapticFeedbackType.Reject) }
        }
    }
}

@Composable
internal fun WatchWorkoutControls(record: WorkoutRecord, enabled: Boolean = true) {
    val w = requireNotNull(record.watch)
    val context = LocalContext.current
    val control by WatchWorkoutControl.state.collectAsStateWithLifecycle()
    WatchControlPresentation(record, control)
    var finish by rememberSaveable(record.id) { mutableStateOf(false) }
    val busy = control.busy || !enabled || w.phase !in setOf("active", "paused")
    Column(Modifier.fillMaxWidth().testTag("watch-workout-controls"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (finish) {
            Text("Finish this Watch workout?", color = WorkoutWhite, fontSize = 14.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                WorkoutButton({ finish = false }, Modifier.weight(1f), enabled = !busy) {
                    Text("Keep going", color = WorkoutWhite, modifier = Modifier.padding(14.dp))
                }
                WorkoutButton({ finish = false; WatchWorkoutControl.request(context, record, "finish") },
                    Modifier.weight(1f).testTag("watch-remote-finish"), enabled = !busy, background = WorkoutPurple) {
                    Text("Finish & save", color = androidx.compose.ui.graphics.Color(0xFF18131F), modifier = Modifier.padding(14.dp))
                }
            }
        } else WorkoutActions(w.phase == "paused", busy) { action ->
            if (action == "finish") finish = true else WatchWorkoutControl.request(context, record, action)
        }
        if (control.recordId == record.id) {
            if (control.busy) Text("Waiting for Watch confirmation…", color = WorkoutMuted, fontSize = 12.sp)
            SettingsMessage(control.error)
        }
    }
}

@Composable
internal fun WatchControlPresentation(record: WorkoutRecord, control: WatchControlState) {
    SideEffect {
        val w = record.watch
        if (control.recordId == record.id && !control.busy && control.error == null && w != null &&
            w.phase == control.confirmedPhase && w.revision > control.priorRevision)
            com.mani.orbit.sync.NativeDiagnostics.mark(control.operation, com.mani.orbit.sync.TraceStage.DISPLAY_UPDATED)
    }
}
