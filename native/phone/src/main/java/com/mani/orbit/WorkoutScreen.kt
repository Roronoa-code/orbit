package com.mani.orbit

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

data class NativeWorkoutState(
    val store: JSONObject = JSONObject(), val elapsedMs: Long = 0, val startsInMs: Long = 0, val totalMs: Long = 0,
    val history: List<WorkoutRecord> = emptyList(), val active: WorkoutRecord? = null,
    val loading: Boolean = true, val busy: Boolean = false, val readError: String? = null, val actionError: String? = null,
    val watchRecords: List<WorkoutRecord> = emptyList(), val watchError: String? = null,
)

@Composable
internal fun WorkoutScreen(state: NativeWorkoutState, imported: List<WorkoutRecord>, profileReady: Boolean,
    action: (String, String, Int, Boolean) -> Unit, music: NativeMusicState = NativeMusicState(),
    player: WorkoutPlayerMotion = rememberWorkoutPlayer(state.active?.id), command: (String, String, Double) -> Unit = { _, _, _ -> },
    connectMusic: () -> Unit = {}, openRecord: String? = null, onOpened: () -> Unit = {},
    chrome: (String, Boolean, () -> Boolean) -> Unit) {
    var kind by rememberSaveable { mutableStateOf<String?>(null) }
    var recordId by rememberSaveable { mutableStateOf<String?>(null) }
    var previousActive by rememberSaveable { mutableStateOf<String?>(null) }
    var lastKind by rememberSaveable { mutableStateOf("Walking") }
    var lastActive by remember { mutableStateOf<WorkoutRecord?>(null) }
    var lastRecord by remember { mutableStateOf<WorkoutRecord?>(null) }
    LaunchedEffect(openRecord) { if (openRecord != null) {
        kind = null; recordId = openRecord.takeUnless { it == "active" }; onOpened()
    } }
    val records = remember(state.history, imported, state.watchRecords) { (state.history + imported + state.watchRecords).sortedByDescending { it.start } }
    val active = state.active
    val finished = if (active == null) state.history.firstOrNull { it.id == previousActive } else null
    val visibleRecordId = if (finished != null && (recordId == null || recordId == "active")) finished.id else recordId
    val record = if (visibleRecordId == "active") active else records.firstOrNull { it.id == visibleRecordId }
    val recordVisible by rememberUpdatedState(visibleRecordId != null)
    val stateNow by rememberUpdatedState(state)
    val actionNow by rememberUpdatedState(action)
    val currentPlayer by rememberUpdatedState(player)
    val back = remember { {
        when {
            currentPlayer.shown && currentPlayer.back() -> true
            recordVisible -> { recordId = null; previousActive = null; true }
            kind != null -> { kind = null; true }
            stateNow.startsInMs > 0 -> { actionNow("cancel", "", 0, false); true }
            else -> false
        }
    } }
    LaunchedEffect(active?.id, state.history, state.loading) {
        if (state.loading) return@LaunchedEffect
        if (active != null) { kind = null; previousActive = active.id }
        else if (previousActive != null) {
            if (state.history.any { it.id == previousActive }) recordId = previousActive
            previousActive = null
        }
    }
    val focused = kind != null || active != null && (recordId == null || recordId == "active")
    SideEffect {
        kind?.let { lastKind = it }; active?.let { lastActive = it }; record?.let { lastRecord = it }
        chrome(record?.kind ?: active?.kind ?: kind ?: "Workouts", focused, back)
    }
    val page = when { visibleRecordId != null -> "record"; active != null -> "active"; kind != null -> "setup"; else -> "hub" }
    val saved = rememberSaveableStateHolder()
    Crossfade(page, Modifier.fillMaxSize(), tween(if (LocalOrbitReducedMotion.current) 0 else 180), label = "workout screen") { screen ->
        saved.SaveableStateProvider(screen) {
            when (screen) {
                "setup" -> saved.SaveableStateProvider("setup-${kind ?: lastKind}") {
                    WorkoutSetup(kind ?: lastKind, state.busy, profileReady && state.readError == null && !state.loading,
                        state.actionError ?: state.readError ?: if (!profileReady) "Your saved profile is not available yet." else null) {
                        minutes, gps -> action("start", kind ?: lastKind, minutes, gps)
                    }
                }
                "active" -> (active ?: lastActive)?.let { session ->
                    if (state.startsInMs > 0) Column(Modifier.fillMaxSize().padding(24.dp).testTag("workout-countdown"),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text("Get ready", color = WorkoutMuted, fontSize = 16.sp)
                        OrbitDotNumber(((state.startsInMs + 999) / 1000).toString(), Modifier.size(170.dp).padding(24.dp), WorkoutWhite)
                        SettingsMessage(state.actionError)
                        WorkoutButton({ action("cancel", "", 0, false) }, enabled = !state.busy) {
                            Text("Cancel", color = WorkoutPurple, modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp))
                        }
                    } else WorkoutLive(session, state.busy, state.actionError ?: state.readError,
                        { action(it, "", 0, false) }, { recordId = "active" }, music, player, command, connectMusic)
                }
                "record" -> if (record != null || page != "record" && lastRecord != null) {
                    val shown = record ?: lastRecord!!
                    saved.SaveableStateProvider("record-${shown.id}") {
                        if (shown.watch != null) WatchWorkoutRecordScreen(shown)
                        else if (shown.imported) ImportedWorkoutScreen(shown)
                        else WorkoutRecordScreen(shown, state.busy, state.actionError, { action(it, "", 0, false) })
                    }
                }
                    else Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("This workout is no longer available.", color = WorkoutMuted)
                        SettingsAction("Back to workouts") { recordId = null }
                    }
                else -> WorkoutHub(records, !state.loading && state.readError == null, state.readError ?: state.watchError,
                    { kind = it; recordId = null }, { recordId = it })
            }
        }
    }
}

@Composable
internal fun WorkoutActions(paused: Boolean, busy: Boolean, action: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        WorkoutButton({ action(if (paused) "resume" else "pause") }, Modifier.weight(1f).fillMaxHeight(), !busy, background = WorkoutPurple) {
            Text(if (paused) "Resume workout" else "Pause workout", color = androidx.compose.ui.graphics.Color(0xFF18131F), fontSize = 15.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 17.dp))
        }
        WorkoutButton({ action("finish") }, Modifier.weight(1f).fillMaxHeight(), !busy) {
            Text("Finish", color = WorkoutWhite, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 17.dp))
        }
    }
}
