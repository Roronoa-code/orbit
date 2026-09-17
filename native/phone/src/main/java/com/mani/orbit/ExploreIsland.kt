package com.mani.orbit

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt
import java.util.Locale

@Composable
internal fun ExploreIsland(route: String, session: NativeWorkoutState, expanded: Boolean,
                           onExpanded: (Boolean) -> Unit, navigate: (String) -> Unit,
                           workoutAction: (String, String, Int, Boolean) -> Unit,
                           page: GlassBackdrop, modifier: Modifier = Modifier,
                           openWorkout: (String) -> Unit = { navigate("Workouts") }) {
    val readability = LocalGlassReadability.current
    val reduced by rememberUpdatedState(LocalOrbitReducedMotion.current)
    val scope = rememberCoroutineScope()
    val motion = remember { ExploreMotion(scope, expanded) }
    val diagnosticView = androidx.compose.ui.platform.LocalView.current
    LaunchedEffect(motion.phase) { com.mani.orbit.sync.DiagnosticApplication.gesture(diagnosticView, motion.phase) }
    SideEffect {
        motion.route = com.mani.orbit.sync.TraceRoute.entries.firstOrNull { it.name == route.uppercase(Locale.ROOT) } ?: com.mani.orbit.sync.TraceRoute.OTHER
        com.mani.orbit.sync.NativeDiagnostics.mark(motion.settledOperation, com.mani.orbit.sync.TraceStage.DISPLAY_UPDATED)
    }
    DisposableEffect(motion) { onDispose {
        motion.dispose()
        com.mani.orbit.sync.DiagnosticApplication.gesture(diagnosticView, com.mani.orbit.sync.TraceGesture.IDLE)
    } }
    val haptic = LocalHapticFeedback.current
    val open by rememberUpdatedState(expanded)
    val setOpen by rememberUpdatedState(onExpanded)
    val density = LocalDensity.current
    var bodyPixels by remember { mutableFloatStateOf(with(density) { 72.dp.toPx() }) }
    val bodyHeight = animateFloatAsState(bodyPixels, if (reduced) androidx.compose.animation.core.tween(0) else spring(1f, 289f), label = "Explore contents height")
    val active = session.store.optJSONObject("active")
    val watchRecord = session.watchRecords.firstOrNull { it.watch?.terminal == false }
    val watch = watchRecord?.watch?.takeIf { active == null }
    val hasWorkout = active != null || watch != null
    val kind = active?.optString("kind")?.replaceFirstChar { it.titlecase(Locale.UK) } ?: watch?.kind
    val paused = if (watch != null) watch.phase == "paused" else active?.isNull("resumedAt") == true && session.startsInMs == 0L
    LaunchedEffect(expanded, reduced) { motion.settle(expanded, reduced = reduced) }
    BackHandler(expanded || motion.dragging) { motion.settle(false, reduced = reduced); onExpanded(false) }
    fun changeOpen(value: Boolean, speed: Float = motion.velocity) {
        motion.engage()
        if (expanded != value) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        motion.settle(value, speed, reduced)
        onExpanded(value)
    }
    val canChoose by remember { derivedStateOf { open && !motion.dragging && motion.value > .94f } }
    Layout(modifier = modifier.testTag("explore-island")
        .clip(RoundedCornerShape(31.dp)).orbitFrost(page, 31.dp, { if (motion.dragging) 1f else abs(motion.velocity).coerceIn(0f, 1f) })
        .drawWithContent {
            drawContent()
            val radius = (31f - 3f * motion.value.coerceIn(0f, 1f)).dp.toPx()
            val stroke = 1.dp.toPx()
            drawRoundRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = .18f), Color.White.copy(alpha = .04f))),
                Offset(stroke / 2, stroke / 2), Size(size.width - stroke, size.height - stroke),
                CornerRadius(radius, radius), style = Stroke(stroke))
        }
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                com.mani.orbit.sync.DiagnosticApplication.gesture(diagnosticView, com.mani.orbit.sync.TraceGesture.TOUCH)
                val wasOpen = open
                val start = motion.value
                val travel = bodyPixels.coerceAtLeast(72.dp.toPx())
                val tracker = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
                var claimed = false
                var released = false
                var lastMove = down.uptimeMillis
                // The island's top moves during expansion. Track window coordinates, not that moving origin.
                val downGlobalY = down.position.y - motion.value * travel
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (event.changes.count { it.pressed } > 1 || change.isConsumed) break
                        val globalY = change.position.y - motion.value * travel
                        val dy = globalY - downGlobalY
                        val dx = change.position.x - down.position.x
                        if (!change.pressed) {
                            if (claimed) {
                                change.consume()
                                val velocity = if (change.uptimeMillis - lastMove < 90) -tracker.calculateVelocity().y / travel else 0f
                                val next = exploreRelease(motion.value, velocity, dy / density.density, wasOpen)
                                motion.settle(next, velocity, reduced); setOpen(next)
                                if (next != wasOpen) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                released = true
                            }
                            break
                        }
                        if (!claimed && abs(dx) > viewConfiguration.touchSlop && abs(dx) > abs(dy)) break
                        if (!claimed && abs(dy) > viewConfiguration.touchSlop && abs(dy) > abs(dx) * 1.2f) {
                            claimed = true; motion.grab()
                        }
                        if (claimed) {
                            tracker.addPosition(change.uptimeMillis, Offset(change.position.x, globalY))
                            if (change.position != change.previousPosition) lastMove = change.uptimeMillis
                            val beyondSlop = dy - kotlin.math.sign(dy) * viewConfiguration.touchSlop
                            motion.move(start - beyondSlop / travel, -tracker.calculateVelocity().y / travel)
                            change.consume()
                        }
                    }
                } finally {
                    if (claimed && !released) { motion.cancel(); motion.settle(wasOpen, 0f, reduced); setOpen(wasOpen) }
                    com.mani.orbit.sync.DiagnosticApplication.gesture(diagnosticView, motion.phase)
                }
            }
        }, content = {
        Column(Modifier.fillMaxWidth()
            .graphicsLayer { alpha = reveal(motion.value, .56f, .98f); translationY = (1f - alpha) * 8.dp.toPx() }
            .drawWithContent { clipRect(bottom = size.height * motion.value.coerceIn(0f, 1f)) { this@drawWithContent.drawContent() } }
            .then(if (!canChoose) Modifier.clearAndSetSemantics {} else Modifier)
            .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 8.dp)) {
            ExploreChoices(route, canChoose, page) { changeOpen(false); navigate(it) }
            if (active != null) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExploreAction(if (paused) "Resume" else "Pause", canChoose && session.startsInMs == 0L,
                        Modifier.weight(1f).fillMaxHeight()) { workoutAction(if (paused) "resume" else "pause", "", 0, false) }
                    ExploreAction("Finish & save", canChoose, Modifier.weight(1f).fillMaxHeight()) { workoutAction("finish", "", 0, false) }
                }
                ExploreAction("Open workout", canChoose, Modifier.fillMaxWidth().padding(top = 8.dp), plain = true) {
                    changeOpen(false); openWorkout("active")
                }
            }
            if (watchRecord != null) {
                if (watch != null) Box(Modifier.padding(top = 8.dp)) { WatchWorkoutControls(watchRecord, canChoose) }
                ExploreAction("Open Watch workout", canChoose, Modifier.fillMaxWidth().padding(top = 8.dp), plain = true) {
                    changeOpen(false); openWorkout(watchRecord.id)
                }
            }
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 62.dp).testTag("explore-bar")
            .semantics { contentDescription = if (!hasWorkout) "Explore" else "Active workout, $kind${if (watch != null) ", Watch" else ""}"; stateDescription = if (expanded) "Expanded" else "Collapsed" }
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key in listOf(Key.DirectionUp, Key.DirectionDown)) {
                    changeOpen(event.key == Key.DirectionUp); true
                } else false
            }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                role = Role.Button, onClickLabel = if (expanded) "Collapse Explore" else "Expand Explore") { changeOpen(!expanded) }
            .padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(if (!hasWorkout) R.drawable.orbit_explore else R.drawable.orbit_workouts), null,
                tint = readability.foreground(Color(0xFFC8B5EE)), modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(kind ?: "Explore", color = readability.foreground(Color(0xFFF7F4FC)), fontSize = 15.sp, fontWeight = FontWeight.Medium,
                    lineHeight = 21.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (!hasWorkout) "Body · Workouts · Health" else if (watch != null) {
                    if (paused) "Watch workout paused" else "Watch · last confirmed time"
                } else if (session.startsInMs > 0) "Starting workout" else if (paused) "Workout paused" else "Workout in progress",
                    color = readability.foreground(Color(0xFFB3AEBE)), fontSize = 11.sp, lineHeight = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (hasWorkout) {
                val seconds = (watch?.activeMs ?: session.elapsedMs) / 1000
                Text(if (seconds >= 3600) "%d:%02d:%02d".format(Locale.UK, seconds / 3600, seconds / 60 % 60, seconds % 60)
                    else "%02d:%02d".format(Locale.UK, seconds / 60, seconds % 60), color = readability.foreground(Color(0xFFF7F4FC)), fontSize = 23.sp,
                    modifier = Modifier.padding(horizontal = 8.dp), maxLines = 1)
            }
            Canvas(Modifier.size(20.dp).graphicsLayer { rotationZ = motion.value.coerceIn(0f, 1f) * 180f }) {
                val path = Path().apply { moveTo(size.width * .2f, size.height * .62f); lineTo(size.width * .5f, size.height * .32f); lineTo(size.width * .8f, size.height * .62f) }
                drawPath(path, readability.foreground(Color(0xFFBFA6F0)), style = Stroke(1.4.dp.toPx(), cap = StrokeCap.Round))
            }
        }
    }) { measurables, constraints ->
        val loose = constraints.copy(minHeight = 0)
        val bar = measurables[1].measure(loose)
        val body = measurables[0].measure(loose.copy(maxHeight = (constraints.maxHeight - bar.height).coerceAtLeast(0)))
        bodyPixels = body.height.toFloat()
        val visible = bar.height + (bodyHeight.value * motion.value.coerceIn(0f, 1f)).roundToInt()
        layout(constraints.maxWidth, visible) { body.place(0, 0); bar.place(0, visible - bar.height) }
    }
}

@Composable
private fun ExploreAction(label: String, enabled: Boolean, modifier: Modifier, plain: Boolean = false, action: () -> Unit) {
    val readability = LocalGlassReadability.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val contact = animateFloatAsState(if (pressed) 1f else 0f, spring(.86f, 650f), label = "Workout action contact")
    val haptic = LocalHapticFeedback.current
    val shape = RoundedCornerShape(18.dp)
    Box(modifier.heightIn(min = 48.dp).clip(shape)
        .background(if (plain) Color.Transparent else Color.White.copy(alpha = .05f))
        .then(if (plain) Modifier else Modifier.border(.7.dp, Color.White.copy(alpha = .08f), shape))
        .drawWithContent { drawRect(Color.White.copy(alpha = contact.value.coerceIn(0f, 1f) * .09f)); drawContent() }
        .clickable(enabled = enabled, interactionSource = interaction, indication = null, role = Role.Button) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); action()
        }.padding(horizontal = 12.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(label, fontSize = 13.sp, lineHeight = 18.sp, textAlign = TextAlign.Center,
            color = readability.foreground(if (plain) Color(0xFFD2C1FF) else Color(0xFFECE9F1)).copy(alpha = if (enabled) 1f else .45f))
    }
}
