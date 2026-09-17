package com.mani.orbit.wear

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.wear.compose.material3.*
import com.mani.orbit.sync.WatchWorkout
import kotlinx.coroutines.*
import java.util.Locale

class WatchWorkoutActivity : ComponentActivity() {
    private var returnToLive by mutableIntStateOf(0)
    private var pendingKind: String? = null
    private var pendingGps = false
    private var permissionError by mutableStateOf<String?>(null)
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val kind = pendingKind; pendingKind = null
        if (kind != null && WatchPermissions.granted(this, Manifest.permission.ACTIVITY_RECOGNITION) &&
            (!pendingGps || WatchPermissions.granted(this, Manifest.permission.ACCESS_FINE_LOCATION))) {
            WatchWorkoutService.send(this, WatchWorkoutService.START, kind = kind, gps = pendingGps)
        } else permissionError = "Allow activity access to record. Outdoor tracking also needs precise location."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WatchEnvironment { Workout(it) } }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); returnToLive++ }

    private fun start(kind: String, gps: Boolean) {
        permissionError = null
        val needed = buildList {
            add(Manifest.permission.ACTIVITY_RECOGNITION); add(WatchPermissions.heart)
            if (kind == "Running") add(WatchSweatCapture.permission)
            if (gps) { add(Manifest.permission.ACCESS_FINE_LOCATION); add(Manifest.permission.ACCESS_COARSE_LOCATION) }
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filterNot { WatchPermissions.granted(this, it) }
        if (needed.isEmpty()) WatchWorkoutService.send(this, WatchWorkoutService.START, kind = kind, gps = gps)
        else { pendingKind = kind; pendingGps = gps; permissions.launch(needed.toTypedArray()) }
    }

    @Composable private fun Workout(display: WatchDisplay) {
        val ambient = display.ambient != null
        // Seed once; the lifecycle producer below owns all later collection and ambient throttling.
        val observed by produceState(remember { WatchWorkoutService.state.value }, ambient) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { WatchWorkoutService.state.collect {
                // Phase/error changes still matter with the wrist down. Metric callbacks do not redraw it.
                if (!ambient || it.workout?.id != value.workout?.id || it.workout?.phase != value.workout?.phase ||
                    it.attached != value.attached || it.error != value.error) value = it
            } }
        }
        SideEffect {
            if (!observed.busy && observed.error == null)
                com.mani.orbit.sync.NativeDiagnostics.mark(observed.operation, com.mani.orbit.sync.TraceStage.DISPLAY_UPDATED)
        }
        var saved by remember { mutableStateOf<WatchWorkout?>(null) }
        var loading by remember { mutableStateOf(true) }
        var loadError by remember { mutableStateOf<String?>(null) }
        var loadRetry by remember { mutableIntStateOf(0) }
        var kind by rememberSaveable { mutableStateOf<String?>(null) }
        var gps by rememberSaveable { mutableStateOf(false) }
        var dismissed by rememberSaveable { mutableStateOf<String?>(null) }
        var confirmFinish by rememberSaveable { mutableStateOf(false) }
        val elapsed = display.elapsed
        val pager = rememberPagerState { 2 }
        val scope = rememberCoroutineScope()
        val haptic = LocalHapticFeedback.current
        WatchWorkoutFeedback(ambient)
        val purple = Color(0xFFBBA1ED)
        LaunchedEffect(loadRetry) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                loading = true
                try {
                    saved = withContext(Dispatchers.IO) { val store = WatchStore(this@WatchWorkoutActivity)
                        store.journal().use { it.workouts(store.installation, 1).firstOrNull()?.second }
                    }
                    if (saved?.terminal == false && !WatchWorkoutService.state.value.attached) WatchWorkoutService.send(this@WatchWorkoutActivity, WatchWorkoutService.RECOVER)
                    loadError = null
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { loadError = "Saved workouts could not be loaded." }
                loading = false
            }
        }
        val session = (observed.workout ?: saved)?.takeUnless { it.terminal && it.id == dismissed }
        var presentedSession by rememberSaveable { mutableStateOf(session?.id) }
        var handledReturn by remember { mutableIntStateOf(returnToLive) }
        LaunchedEffect(session?.id, returnToLive) {
            if (session != null) {
                // Initial hydration must not cancel a gesture or reset the restored page.
                if (presentedSession != null && presentedSession != session.id || handledReturn != returnToLive) {
                    // Queue the reset for the next layout; a source callback must not force remeasurement.
                    pager.requestScrollToPage(0); confirmFinish = false
                }
                presentedSession = session.id
            }
            handledReturn = returnToLive
        }
        val error = permissionError ?: observed.error ?: loadError
        BackHandler(confirmFinish || kind != null && session == null || session != null && pager.settledPage != 0) {
            when { confirmFinish -> confirmFinish = false; session != null -> scope.launch { pager.animateScrollToPage(0) }; else -> kind = null }
        }
        fun action(name: String) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            WatchWorkoutService.send(this@WatchWorkoutActivity, name, id = session?.id)
        }
        MaterialTheme(colorScheme = ColorScheme(primary = purple, background = Color(0xFF0B0A0F), surfaceContainer = Color(0xFF201D27))) {
            if (ambient) {
                WatchAmbientScreen(session?.kind ?: "Workouts", when (session?.phase) {
                    "active" -> if (observed.attached) "Recording" else "Reconnecting…"
                    "paused" -> "Paused"; "starting" -> "Starting…"; "ended" -> "Saved on watch"
                    "interrupted" -> "Recording interrupted"; else -> when {
                        loading -> "Loading…"; error != null -> "Open to retry"; else -> "No workout running"
                    }
                }, display)
                return@MaterialTheme
            }
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), horizontalAlignment = Alignment.CenterHorizontally) {
                if (session == null) WorkoutPage {
                    Text(kind ?: "Workouts", style = MaterialTheme.typography.titleSmall)
                    if (loading) Text("Loading…", style = MaterialTheme.typography.bodySmall)
                    else if (loadError != null) {
                        Text("Couldn't load", style = MaterialTheme.typography.bodySmall)
                        FilledTonalButton(onClick = { loadRetry++ }, modifier = Modifier.fillMaxWidth()) { Text("Retry") }
                        FilledTonalButton(onClick = { startActivity(Intent(this@WatchWorkoutActivity, WatchHistoryActivity::class.java)) },
                            modifier = Modifier.fillMaxWidth().testTag("watch-open-history")) { Text("History") }
                    }
                    else if (kind == null) {
                        WatchWorkout.KINDS.forEach { activity ->
                        FilledTonalButton(onClick = { kind = activity; gps = false; haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) },
                            modifier = Modifier.fillMaxWidth().testTag("watch-choose-$activity"), enabled = loadError == null) { Text(activity) }
                        }
                        FilledTonalButton(onClick = { startActivity(Intent(this@WatchWorkoutActivity, WatchHistoryActivity::class.java)) },
                            modifier = Modifier.fillMaxWidth().testTag("watch-open-history")) { Text("History") }
                    } else {
                        Text("Go at your own pace", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                        if (kind != "Strength") FilledTonalButton(onClick = { gps = !gps }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (gps) "Outdoors · GPS on" else "Indoors · GPS off", style = MaterialTheme.typography.bodySmall)
                        }
                        Button(onClick = { start(kind!!, gps) }, enabled = !observed.busy && loadError == null,
                            modifier = Modifier.fillMaxWidth().testTag("watch-start")) { Text(if (error != null) "Retry start" else "Start") }
                    }
                    error?.let { Text(it, color = Color(0xFFFFB5B8), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center) }
                } else {
                    WatchPager(pager, Modifier.weight(1f).testTag("watch-workout-pager")) { page ->
                        WorkoutPage(active = pager.settledPage == page, compact = true) {
                            if (page == 0) {
                                WatchWorkoutSummary(session, observed.attached, elapsed, error,
                                    WatchPermissions.granted(this@WatchWorkoutActivity, WatchPermissions.heart)) { action(WatchWorkoutService.RECOVER) }
                            } else {
                                Text(if (error != null) "Check workout" else if (!session.terminal) when (session.phase) {
                                    "paused" -> "Paused"; "active" -> if (observed.attached) "Recording" else "Reconnecting…"; else -> "Starting…"
                                } else session.kind, fontSize = 12.sp, lineHeight = 14.sp, textAlign = TextAlign.Center)
                                if (error != null && !session.terminal) FilledTonalButton(onClick = { action(WatchWorkoutService.RECOVER) }, modifier = Modifier.fillMaxWidth()) { Text("Reconnect") }
                                if (session.terminal) {
                                    WatchSweatSummary(session)
                                    if (error != null) FilledTonalButton(onClick = { action(WatchWorkoutService.RECOVER) }, modifier = Modifier.fillMaxWidth()) { Text("Retry save") }
                                    Text(if (session.phase == "interrupted") "The last confirmed readings are saved." else "Your phone receives this when connected.",
                                        style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                                    Button(onClick = { dismissed = session.id; kind = null; confirmFinish = false }, modifier = Modifier.fillMaxWidth()) { Text("Done") }
                                    FilledTonalButton(onClick = { startActivity(Intent(this@WatchWorkoutActivity, WatchHistoryActivity::class.java)) },
                                        modifier = Modifier.fillMaxWidth().testTag("watch-open-history")) { Text("History") }
                                } else if (confirmFinish) {
                                    Text("Finish this workout?", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                                    Button(onClick = { confirmFinish = false; action(WatchWorkoutService.FINISH) }, enabled = !observed.busy,
                                        modifier = Modifier.fillMaxWidth().testTag("watch-confirm-finish")) { Text("Finish & save") }
                                    FilledTonalButton(onClick = { confirmFinish = false }, modifier = Modifier.fillMaxWidth()) { Text("Keep going") }
                                } else {
                                    Button(onClick = { action(if (session.phase == "paused") WatchWorkoutService.RESUME else WatchWorkoutService.PAUSE) },
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                                        enabled = !observed.busy && session.phase in setOf("active", "paused"), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("watch-pause-resume")) {
                                        Text(if (session.phase == "paused") "Resume" else "Pause", style = MaterialTheme.typography.labelMedium)
                                    }
                                    FilledTonalButton(onClick = { confirmFinish = true }, enabled = !observed.busy,
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("watch-finish")) { Text("Finish", style = MaterialTheme.typography.labelMedium) }
                                }
                                error?.let { Text(it, color = Color(0xFFFFB5B8), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center) }
                            }
                        }
                    }
                    Row(Modifier.padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        repeat(2) { Box(Modifier.size(if (it == pager.currentPage) 5.dp else 3.dp).background(if (it == pager.currentPage) purple else Color(0xFF5F576B), CircleShape)) }
                    }
                }
            }
        }
    }
}

@Composable internal fun WatchWorkoutSummary(session: WatchWorkout, attached: Boolean, elapsed: Long,
    error: String?, heartGranted: Boolean, reconnect: () -> Unit) {
    Text(if (error != null) "Check workout" else session.kind, fontSize = 12.sp, lineHeight = 14.sp, textAlign = TextAlign.Center)
    val liveClock = session.activeMs + if (session.phase == "active" && attached)
        (elapsed - session.updatedElapsed).coerceAtLeast(0) else 0
    WatchTime(liveClock, Modifier.testTag("watch-workout-clock"))
    if (error != null && !session.terminal) FilledTonalButton(onClick = reconnect, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("watch-reconnect")) { Text("Reconnect", fontSize = 12.sp, lineHeight = 14.sp) }
    Text(when (session.phase) {
        "active" -> if (attached) "Recording" else "Reconnecting…"
        "starting" -> "Starting…"; "paused" -> "Paused"; "ended" -> "Saved on watch"; else -> "Recording interrupted"
    }, fontSize = 10.sp, lineHeight = 12.sp, modifier = Modifier.testTag("watch-workout-phase"))
    val freshHeart = session.heartQuality == "valid" && session.heartElapsed?.let { elapsed - it in 0..15_000 } == true && !session.terminal
    WatchLiveMetrics(session.distance, if (freshHeart) session.heart?.toInt() else null)
    session.energy?.let { Text("${it.toInt()} kcal", style = MaterialTheme.typography.bodySmall) }
    session.steps?.let { Text("$it steps", style = MaterialTheme.typography.bodySmall) }
    if (session.gps) Text("Outdoor tracking", style = MaterialTheme.typography.bodySmall)
    if (!heartGranted)
        Text("Heart access off", style = MaterialTheme.typography.bodySmall)
    error?.let { Text(it, color = Color(0xFFFFB5B8), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center) }
}

@Composable internal fun WatchWorkoutFeedback(ambient: Boolean,
    events: kotlinx.coroutines.flow.SharedFlow<Boolean> = WatchWorkoutService.feedback) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(ambient, lifecycle, events) {
        if (!ambient) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            events.collect { success -> haptic.performHapticFeedback(
                if (success) HapticFeedbackType.Confirm else HapticFeedbackType.Reject) }
        }
    }
}

internal fun watchWorkoutClock(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return (if (seconds >= 3600) "${seconds / 3600}:" else "") + "%02d:%02d".format(Locale.UK, seconds / 60 % 60, seconds % 60)
}
