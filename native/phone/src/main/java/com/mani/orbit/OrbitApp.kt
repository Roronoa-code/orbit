package com.mani.orbit

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import com.mani.orbit.sync.LiveWire
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay

private val Ink = PageInk
private val Surface = GlassOpaqueFill
private val Lavender = Color(0xFFBBA1ED)
private val Muted = Color(0xFFB3AEBE)
private val White = Color(0xFFF4F4F6)
private val DayFormat = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK)
// The approved header reads the weekday and drops the year, like the reference date button.
internal val HeaderDayFormat = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)
internal val LocalOrbitReducedMotion = staticCompositionLocalOf { false }
private fun Double?.reading(decimals: Int = 0) = this?.let { String.format(Locale.UK, "%,.${decimals}f", it) } ?: "—"

@Composable
internal fun OrbitApp(model: OrbitModel, workout: StateFlow<NativeWorkoutState>, musicAccess: StateFlow<Boolean>, healthAction: (String) -> Unit,
             workoutAction: (String, String, Int, Boolean) -> Unit, music: StateFlow<NativeMusicState>, musicCommand: (String, String, Double) -> Unit) {
    val route by model.route.collectAsStateWithLifecycle()
    val diagnosticView = androidx.compose.ui.platform.LocalView.current
    LaunchedEffect(route) {
        val tag = com.mani.orbit.sync.TraceRoute.entries.firstOrNull { it.name == route.uppercase(Locale.ROOT) }
            ?: com.mani.orbit.sync.TraceRoute.OTHER
        com.mani.orbit.sync.DiagnosticApplication.route(diagnosticView, tag)
    }
    val health by model.health.collectAsStateWithLifecycle()
    val musicAllowed by musicAccess.collectAsStateWithLifecycle()
    val session by workout.collectAsStateWithLifecycle()
    val track by music.collectAsStateWithLifecycle()
    val stepsGoal by model.stepsGoal.collectAsStateWithLifecycle()
    val reducedMotion by model.reducedMotion.collectAsStateWithLifecycle()
    val readability by model.readability.collectAsStateWithLifecycle()
    val glassReadability = rememberGlassReadability(readability)
    val rotation by model.globeRotation.collectAsStateWithLifecycle()
    val cardLayout by model.cardLayout.collectAsStateWithLifecycle()
    val profile by model.profile.collectAsStateWithLifecycle()
    var homeMetric by rememberSaveable { mutableIntStateOf(0) }
    var homePeriod by rememberSaveable { mutableIntStateOf(1) }
    var datePicker by rememberSaveable { mutableStateOf(false) }
    // The chooser stays on screen while it closes back into the date it came out of.
    var dateChooserShown by remember { mutableStateOf(false) }
    var dateAnchor by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    LaunchedEffect(datePicker) { if (datePicker) dateChooserShown = true }
    val requestedWorkout by model.requestedWorkout.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val activity = androidx.activity.compose.LocalActivity.current
    val pageState = rememberSaveableStateHolder()
    val pageLayer = rememberGlassBackdrop()
    val surfaceLayer = rememberGlassBackdrop()
    val sceneLayer = rememberGlassBackdrop()
    val headerOffsets = remember { mutableStateMapOf<String, () -> Float>() }
    val density = androidx.compose.ui.platform.LocalDensity.current
    var exploreExpanded by rememberSaveable { mutableStateOf(false) }
    var previousRoute by remember { mutableStateOf(route) }
    var workoutFocused by remember { mutableStateOf(false) }
    var workoutTitle by remember { mutableStateOf("Workouts") }
    var workoutBack by remember { mutableStateOf<(() -> Boolean)?>(null) }
    val keyboardVisible = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
    val showExplore = (route != "Workouts" || !workoutFocused) && !keyboardVisible
    LaunchedEffect(route) {
        if (previousRoute != route) { exploreExpanded = false; previousRoute = route }
    }
    LaunchedEffect(showExplore) { if (!showExplore) exploreExpanded = false }
    fun back() {
        if (datePicker) datePicker = false
        else if (exploreExpanded) exploreExpanded = false
        else if (route == "Workouts" && workoutBack?.invoke() == true) return
        else if (!model.back()) {
            if (route == "Steps" && (homeMetric != 0 || homePeriod != 1 || health.day.date != LocalDate.now())) {
                homeMetric = 0; homePeriod = 1; model.date(LocalDate.now())
            } else activity?.finish()
        }
    }
    BackHandler { back() }
    LaunchedEffect(health.error) { health.error?.let { snackbar.showSnackbar(it) } }
    // Live heart rate while Orbit is on screen. The watch is asked for its attention every twenty
    // seconds; it answers with its newest reading and then pushes each new one as it is captured.
    // Readings that come through the journal are picked up the moment they are committed. Leaving the
    // screen releases the watch, and its lease lapses on its own if that message never arrives.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val context = LocalContext.current
    val watchJournal = context.getDatabasePath("watch-readings.db")
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            // An answer from an earlier visit says nothing about the watch now; the next one comes within seconds.
            launch { PhoneLive.updates.collect { update ->
                update?.takeIf { System.currentTimeMillis() - it.sent < LiveWire.LEASE_MS }?.let(model::acceptLive)
            } }
            launch {
                PhoneLive.journalChanges.onStart { emit(Unit) }.collect {
                    withContext(Dispatchers.IO) { if (watchJournal.exists()) model.refreshWatch(watchJournal) }
                }
            }
            try {
                while (true) {
                    try { PhoneLive.request(context, System.currentTimeMillis() + LiveWire.LEASE_MS) }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (error: Exception) { android.util.Log.w("OrbitSync", "Live heart request incomplete: ${error.javaClass.simpleName}") }
                    delay(LiveWire.RENEW_MS)
                }
            } finally { try { PhoneLive.release(context) } catch (_: Exception) { } }
        }
    }
    CompositionLocalProvider(LocalOrbitReducedMotion provides reducedMotion, LocalGlassReadability provides glassReadability) {
    val workoutPlayer = rememberWorkoutPlayer(session.active?.id)
    // Stock containers (cards, chips) share the illustrated Health card material instead of the M3 default.
    CompositionLocalProvider(LocalPageBackdrop provides surfaceLayer) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Lavender, background = Ink, surface = Surface,
        onSurface = White, onBackground = White, onPrimary = Ink,
        surfaceVariant = GlassOpaqueFill, onSurfaceVariant = HealthSecondary,
        surfaceContainer = GlassOpaqueFill, surfaceContainerHigh = GlassOpaqueFill),
        typography = OrbitTypography) {
      Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().recordBackdrop(pageLayer)) {
        // What every in-page panel refracts: the app background and, on Workouts, the artwork.
        Box(Modifier.fillMaxSize().recordBackdrop(surfaceLayer)) {
            Box(Modifier.fillMaxSize().background(Ink))
            if (route == "Workouts") WorkoutArtwork(track, workoutPlayer)
        }
        Scaffold(containerColor = Color.Transparent, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                OrbitHeader(if (route == "Steps") HomeMetric.entries[homeMetric].title else if (route == "Workouts") workoutTitle else route,
                    if (route == "Steps") health.day.date.format(HeaderDayFormat) else null,
                    route != "Steps" || model.canGoBack() || homeMetric != 0 || homePeriod != 1 || health.day.date != LocalDate.now(),
                    ::back, { exploreExpanded = false; datePicker = true }, { model.navigate("Settings") }, sceneLayer) { dateAnchor = it }
                Box(Modifier.weight(1f)) {
                Box(Modifier.fillMaxSize().recordBackdrop(sceneLayer)) {
                // Pages share fixed bounds. Avoid size/lookahead transitions while live list data changes.
                Crossfade(route, modifier = Modifier.fillMaxSize(), animationSpec = tween(if (reducedMotion) 0 else 180), label = "screen") { page ->
                    pageState.SaveableStateProvider(page) {
                    val reportScroll = remember(page) { { offset: (() -> Float)? ->
                        if (offset == null) headerOffsets.remove(page) else headerOffsets[page] = offset
                        Unit
                    } }
                    CompositionLocalProvider(LocalHeaderScroll provides reportScroll) {
                    when (page) {
                        "Steps" -> HomeScreen(health, HomeMetric.entries[homeMetric], homePeriod, stepsGoal, reducedMotion,
                            exploreExpanded, { exploreExpanded = false }, { homeMetric = it.ordinal },
                            { homePeriod = when (homePeriod) { 1 -> 7; 7 -> 30; else -> 1 } }, model::navigate,
                            rotation && LocalGlassQuality.current.quality != GlassQuality.READABILITY)
                        "Settings" -> SettingsRoute(model, health, musicAllowed, healthAction) { model.navigate("Galaxy Watch") }
                        "Galaxy Watch" -> WatchReadingsScreen()
                        "Workouts" -> WorkoutScreen(session, health.workouts, profile != null, workoutAction, track, workoutPlayer, musicCommand,
                            { healthAction("music") }, requestedWorkout, { model.workoutOpened(requestedWorkout) }) { title, focused, back ->
                            workoutTitle = title; workoutFocused = focused; workoutBack = back
                        }
                        "Measurements" -> MeasurementsScreen(health)
                        "Sleep" -> SleepRoute(health, reducedMotion)
                        "Health" -> cardLayout?.let { layout -> HealthDashboard(health, layout, stepsGoal, reducedMotion,
                            model::resizeCard, model::moveCards, { card ->
                                when (card) {
                                    HealthCard.Steps, HealthCard.Heart, HealthCard.Intake -> {
                                        homeMetric = when (card) { HealthCard.Heart -> 1; HealthCard.Intake -> 3; else -> 0 }
                                        homePeriod = 1; model.navigate("Steps")
                                    }
                                    HealthCard.Sleep -> model.navigate("Sleep")
                                    HealthCard.Body -> model.navigate("Measurements")
                                    HealthCard.Oxygen -> Unit
                                }
                            }, { model.navigate("Settings") })
                        } ?: LinearProgressIndicator(Modifier.fillMaxWidth())
                        else -> HealthScreen(page, health, model::navigate) { model.date(it) }
                    }
                    }
                    }
                }
                }
                if (route != "Steps" && !(route == "Workouts" && workoutFocused)) HomeScrollFrost(sceneLayer,
                    { ((headerOffsets[route]?.invoke() ?: 0f) / (24f * density.density)).coerceIn(0f, 1f) },
                    Modifier.fillMaxWidth().height(30.dp).align(Alignment.TopCenter).testTag("header-scroll-frost"), pinnedEdge = true)
                }
            }
        }
        }
        if (showExplore && exploreExpanded) Box(Modifier.matchParentSize().pointerInput(Unit) {
            detectTapGestures { exploreExpanded = false }
        })
        if (showExplore) ExploreIsland(route, session, exploreExpanded, { exploreExpanded = it }, model::navigate,
            workoutAction, pageLayer, Modifier.align(Alignment.BottomCenter)
                .navigationBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp).fillMaxWidth()) { id ->
                    model.openWorkout(id)
                }
        if (dateChooserShown || datePicker) OrbitDateChooser(datePicker, dateAnchor, health.day.date, health.firstRecord, pageLayer,
            { dateChooserShown = false }, { datePicker = false }) { model.date(it); datePicker = false }
      }
    }
    }
    }
}
@Composable
private fun HealthScreen(page: String, state: HealthScreenState, navigate: (String) -> Unit, selectDate: (LocalDate) -> Unit) {
    val day = state.day
    val scroll = rememberLazyListState()
    ObserveHeaderScroll(scroll)
    LazyColumn(state = scroll, contentPadding = PaddingValues(start = 18.dp, top = 8.dp, end = 18.dp, bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(day.date.format(DayFormat), color = Muted, modifier = Modifier.weight(1f))
                WorkoutArrow("Previous day", -1, day.date > LocalDate.of(1970, 1, 1)) { selectDate(day.date.minusDays(1)) }
                WorkoutArrow("Next day", 1, day.date < LocalDate.now()) { selectDate(day.date.plusDays(1)) }
            }
        }
        if (state.loading || state.syncing) item {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = Lavender, trackColor = Surface)
        }
            item {
                val value = when (page) {
                    "Heart rate" -> day.heart.reading() + " bpm"
                    "Blood oxygen" -> day.oxygen.reading() + " %"
                    "Nutrition" -> day.nutrition.reading() + " kcal"
                    else -> day.asleepMinutes?.let { "${it.toInt() / 60} h ${it.toInt() % 60} m" } ?: "—"
                }
                Text(value, fontSize = 48.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(vertical = 20.dp))
            }
            if (page == "Heart rate") item { ReadingChart("Recorded heart rate", day.heartReadings, false) }
        item {
            Text(if (state.lastSync == null && state.liveStepsAt == null) state.status else "Samsung Health", color = Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ReadingChart(title: String, readings: List<Reading>, bars: Boolean) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(title, fontSize = 17.sp)
        if (readings.size < 2) {
            Text(if (readings.isEmpty()) "No recorded readings" else "One recorded reading", color = Muted, modifier = Modifier.padding(top = 14.dp))
        } else {
            Canvas(Modifier.fillMaxWidth().height(150.dp).padding(top = 22.dp, bottom = 12.dp)
                .semantics { contentDescription = "$title, ${readings.size} recorded readings" }) {
                val first = readings.first().at
                val span = (readings.last().at - first).coerceAtLeast(1).toDouble()
                val low = if (bars) 0.0 else readings.minOf { it.value }
                val high = readings.maxOf { it.value }
                val range = (high - low).coerceAtLeast(if (bars) 1.0 else 2.0)
                val centre = if (bars) low else (high + low - range) / 2
                val path = Path()
                readings.forEachIndexed { index, reading ->
                    val x = 3.dp.toPx() + ((reading.at - first) / span * (size.width - 6.dp.toPx())).toFloat()
                    val y = size.height - ((reading.value - centre) / range * size.height).toFloat()
                    if (bars) drawLine(Lavender, Offset(x, size.height), Offset(x, y), (size.width / readings.size * .5f).coerceAtMost(8.dp.toPx()))
                    else {
                        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        drawCircle(Lavender, 2.4.dp.toPx(), Offset(x, y))
                    }
                }
                if (!bars) drawPath(path, Lavender, style = Stroke(2.dp.toPx()))
            }
            val format = DateTimeFormatter.ofPattern(if (bars) "HH:mm" else "d MMM").withZone(ZoneId.systemDefault())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(format.format(Instant.ofEpochMilli(readings.first().at)), color = Muted, fontSize = 11.sp)
                Text(format.format(Instant.ofEpochMilli(readings.last().at)), color = Muted, fontSize = 11.sp)
            }
        }
    }
}
