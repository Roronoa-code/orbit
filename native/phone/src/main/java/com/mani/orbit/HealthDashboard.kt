package com.mani.orbit

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import java.util.Locale

private class HealthDrag(val id: HealthCard, val grab: Offset, finger: Offset, val size: IntSize) {
    var finger by mutableStateOf(finger)
    var released by mutableStateOf(false)
}
private val CardEase = CubicBezierEasing(.2f, .8f, .2f, 1f)

/** Six stable native cards. Placement animates from the current pose; only the held layer follows the finger. */
@Composable
internal fun HealthDashboard(state: HealthScreenState, layout: HealthCardLayout, goal: Int?, reduced: Boolean,
    resize: suspend (HealthCard) -> Boolean, move: suspend (List<HealthCard>) -> Boolean, navigate: (HealthCard) -> Unit,
    settings: () -> Unit) {
    val grid = rememberScrollState()
    ObserveHeaderScroll(grid)
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val currentLayout by rememberUpdatedState(layout)
    val resizeNow by rememberUpdatedState(resize)
    val moveNow by rememberUpdatedState(move)
    val layers = HealthCard.entries.associateWith { rememberGraphicsLayer() }
    val targets = remember { mutableMapOf<HealthCard, Rect>() }
    var gridOrigin by remember { mutableStateOf(Offset.Zero) }
    val bounds = remember { mutableMapOf<HealthCard, Rect>() }
    val headers = remember { mutableMapOf<HealthCard, Rect>() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var order by remember { mutableStateOf(layout.order) }
    var wide by remember { mutableStateOf(layout.wide) }
    var selectedKey by remember { mutableStateOf<String?>(null) }
    var oxygenOpen by rememberSaveable { mutableStateOf(false) }
    var drag by remember { mutableStateOf<HealthDrag?>(null) }
    var settling by remember { mutableStateOf<Animatable<Offset, AnimationVector2D>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var gestureGeneration by remember { mutableIntStateOf(0) }
    val selected = HealthCard.entries.find { it.key == selectedKey }
    val selectedNow by rememberUpdatedState(selected)
    LaunchedEffect(layout.order, layout.wide) {
        if (drag == null && !busy) { order = layout.order; wide = layout.wide }
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) {
            gestureGeneration++; selectedKey = null
        } }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    BackHandler(selected != null && drag == null) { selectedKey = null }

    fun resizeCard(id: HealthCard) {
        if (busy || drag != null) return
        if (id == HealthCard.Oxygen && oxygenOpen) {
            oxygenOpen = false
            if (id !in wide) { haptic.performHapticFeedback(HapticFeedbackType.ToggleOff); return }
        }
        busy = true
        wide = if (id in wide) wide - id else wide + id
        haptic.performHapticFeedback(if (id in wide) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
        scope.launch {
            try { if (!resizeNow(id)) wide = currentLayout.wide }
            finally { busy = false }
        }
    }
    fun step(id: HealthCard, delta: Int): Boolean {
        if (busy || drag != null) return false
        val i = order.indexOf(id); val j = i + delta
        if (j !in order.indices) return false
        order = order.toMutableList().apply { removeAt(i); add(j, id) }
        val next = order; busy = true
        scope.launch { try { if (!moveNow(next)) order = currentLayout.order } finally { busy = false } }
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        return true
    }
    fun reorder() {
        val held = drag?.takeUnless { it.released } ?: return
        val own = targets[held.id]?.translate(gridOrigin)
        if (own?.contains(held.finger) == true) return
        val target = targets.entries.firstOrNull { (id, rect) ->
            val r = rect.translate(gridOrigin); val dx = r.width * .18f; val dy = r.height * .18f
            id != held.id && Rect(r.left + dx, r.top + dy, r.right - dx, r.bottom - dy).contains(held.finger)
        }?.key ?: return
        val from = order.indexOf(held.id); val to = order.indexOf(target)
        if (from == to) return
        order = order.toMutableList().apply { removeAt(from); add(to, held.id) }
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }
    fun finish(cancelled: Boolean) {
        val held = drag ?: return
        if (held.released) return
        held.released = true; busy = true
        val next = order
        val animation = Animatable(held.finger - held.grab, Offset.VectorConverter)
        settling = animation
        scope.launch {
            try {
                if (cancelled || !moveNow(next)) order = currentLayout.order
                withFrameNanos { }; withFrameNanos { }
                val slot = targets[held.id]?.topLeft?.plus(gridOrigin) ?: animation.value
                if (reduced) animation.snapTo(slot) else animation.animateTo(slot, tween(280, easing = CardEase))
            } finally { settling = null; drag = null; busy = false }
        }
    }
    val reorderNow by rememberUpdatedState(::reorder)
    val finishNow by rememberUpdatedState(::finish)
    val scrollDirection by remember(density) { derivedStateOf {
        val held = drag?.takeUnless { it.released }
        when {
            held == null -> 0
            held.finger.y < with(density) { 80.dp.toPx() } -> -1
            held.finger.y > viewport.height - with(density) { 110.dp.toPx() } -> 1
            else -> 0
        }
    } }
    LaunchedEffect(drag?.id, drag?.released, scrollDirection) {
        if (drag == null || drag?.released == true || scrollDirection == 0) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (drag != null && drag?.released == false) {
            val time = withFrameNanos { it }; val dt = ((time - last) / 1_000_000f).coerceAtMost(32f); last = time
            val y = drag!!.finger.y; val top = with(density) { 80.dp.toPx() }; val bottom = with(density) { 110.dp.toPx() }
            val edge = when { y < top -> y - top; y > viewport.height - bottom -> y - viewport.height + bottom; else -> 0f }
            if (edge != 0f) {
                grid.scrollBy((edge * .2f).coerceIn(-12f * density.density, 12f * density.density) * dt / 16)
                reorderNow()
            }
        }
    }

    Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }
        .onSizeChanged { if (viewport != IntSize.Zero && viewport != it) gestureGeneration++; viewport = it }
        .onPreviewKeyEvent { event ->
            when {
                event.type != KeyEventType.KeyDown -> false
                event.key == Key.Escape && selected != null -> { selectedKey = null; finish(true); true }
                event.isAltPressed && selected != null && event.key in listOf(Key.DirectionLeft, Key.DirectionUp, Key.DirectionRight, Key.DirectionDown) ->
                    step(selected, if (event.key == Key.DirectionLeft || event.key == Key.DirectionUp) -1 else 1)
                else -> false
            }
        }.pointerInput(gestureGeneration) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val id = bounds.entries.firstOrNull { it.value.contains(down.position) }?.key
                if (id != selectedNow) selectedKey = null
                if (id == null) return@awaitEachGesture
                val rect = bounds.getValue(id)
                // The corner control and expanded chart retain their own interactions.
                val corner = with(density) { 49.dp.toPx() }
                if (id == selectedNow && down.position.x > rect.right - corner && down.position.y < rect.top + corner) return@awaitEachGesture
                if (id == HealthCard.Oxygen && headers[id]?.contains(down.position) != true) return@awaitEachGesture
                val hold = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                selectedKey = id.key
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                var cancelled = true
                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == hold.id } ?: break
                        if (event.changes.count { it.pressed } > 1 || change.isConsumed) break
                        change.consume()
                        if (!change.pressed) { cancelled = false; break }
                        if (busy) continue
                        val point = change.position
                        if (drag == null && (point - hold.position).getDistance() > with(density) { 10.dp.toPx() }) {
                            val actual = bounds[id] ?: rect
                            drag = HealthDrag(id, hold.position - actual.topLeft, point, IntSize(actual.width.toInt(), actual.height.toInt()))
                        } else drag?.finger = point
                        reorder()
                    }
                } finally { finishNow(cancelled) }
            }
        }) {
        Column(Modifier.fillMaxSize().verticalScroll(grid).testTag("health-grid")
            .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 110.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(state.day.date.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK)), fontSize = 13.sp, color = HealthSecondary,
                modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
            if (state.loading || state.syncing) LinearProgressIndicator(Modifier.fillMaxWidth(), color = HealthAccent)
            HealthGrid(order, wide + if (oxygenOpen) setOf(HealthCard.Oxygen) else emptySet(), drag?.id, reduced, targets,
                Modifier.fillMaxWidth().onGloballyPositioned { gridOrigin = it.positionInRoot() - origin }) { id ->
                val isWide = id in wide || id == HealthCard.Oxygen && oxygenOpen
                val layer = layers.getValue(id)
                HealthCardContent(id, isWide, selected == id, drag?.id == id, oxygenOpen, state, goal, reduced,
                    Modifier.onGloballyPositioned { c -> bounds[id] = Rect(c.positionInRoot() - origin, c.size.toSize()) }
                        .drawWithContent {
                            layer.record { this@drawWithContent.drawContent() }
                            if (drag?.id == id) drawRoundRect(Color.White.copy(alpha = .02f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(26.dp.toPx()))
                            else drawLayer(layer)
                        }, { resizeCard(id) }, { selectedKey = id.key }, {
                        if (!busy && drag == null) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            selectedKey = null
                            if (id == HealthCard.Oxygen) oxygenOpen = !oxygenOpen else navigate(id)
                        }
                    }, { step(id, -1) }, { step(id, 1) }, { headers[id] = it.translate(-origin) })
            }
            TextButton(onClick = settings, modifier = Modifier.padding(top = 6.dp).testTag("health-source")) {
                Text(if (state.error != null) "Connection needs attention" else "Samsung Health · connection", color = HealthSecondary, fontSize = 12.sp)
            }
        }
        drag?.let { held ->
            Canvas(Modifier.offset { (settling?.value ?: (drag!!.finger - held.grab)).round() }
                .size(with(density) { held.size.width.toDp() }, with(density) { held.size.height.toDp() })
                .graphicsLayer { shadowElevation = 16.dp.toPx(); shape = RoundedCornerShape(26.dp); clip = false }
                .testTag("health-drag-layer")) { drawLayer(layers.getValue(held.id)) }
        }
    }
}

private class HealthPlacement(private val scope: CoroutineScope) {
    private var target: Offset? = null
    val position = Animatable(Offset.Zero, Offset.VectorConverter)
    private var animation: Job? = null
    val size = Animatable(IntSize.Zero, IntSize.VectorConverter)
    private var targetSize = IntSize.Zero
    private var resizing: Job? = null
    fun resize(next: IntSize, animated: Boolean) {
        if (targetSize == next) return
        val first = targetSize == IntSize.Zero
        targetSize = next; resizing?.cancel()
        resizing = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            if (first || !animated) size.snapTo(next) else size.animateTo(next, tween(280, easing = CardEase))
        }
    }
    fun place(next: Offset, animate: Boolean) {
        if (target == next && (animate || position.value == next)) return
        val first = target == null
        target = next; animation?.cancel()
        animation = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            if (first || !animate) position.snapTo(next) else position.animateTo(next, tween(280, easing = CardEase))
        }
    }
    val translation get() = position.value - (target ?: Offset.Zero)
}

@Composable
private fun HealthGrid(order: List<HealthCard>, wide: Set<HealthCard>, dragged: HealthCard?, reduced: Boolean,
    targets: MutableMap<HealthCard, Rect>, modifier: Modifier, content: @Composable (HealthCard) -> Unit) {
    val scope = rememberCoroutineScope()
    val motions = remember { HealthCard.entries.associateWith { HealthPlacement(scope) } }
    Layout(modifier = modifier, content = {
        order.forEach { id -> key(id) {
            Box(Modifier.graphicsLayer { val offset = motions.getValue(id).translation; translationX = offset.x; translationY = offset.y },
                propagateMinConstraints = true) { content(id) }
        } }
    }) { children, constraints ->
        val gap = 12.dp.roundToPx(); val half = (constraints.maxWidth - gap) / 2
        val widths = order.map { if (it in wide) constraints.maxWidth else half }
        val heights = children.mapIndexed { i, child -> child.minIntrinsicHeight(widths[i]) }.toMutableList()
        var firstHalf: Int? = null
        order.forEachIndexed { i, id ->
            if (id in wide) firstHalf = null
            else if (firstHalf == null) firstHalf = i
            else { val j = firstHalf!!; val height = maxOf(heights[i], heights[j]); heights[i] = height; heights[j] = height; firstHalf = null }
        }
        val places = children.mapIndexed { i, child ->
            val motion = motions.getValue(order[i])
            Snapshot.withoutReadObservation { motion.resize(IntSize(widths[i], heights[i]), !reduced) }
            val size = motion.size.value
            child.measure(Constraints.fixed(size.width.coerceAtLeast(1), size.height.coerceAtLeast(1)))
        }
        var y = 0; var rowHeight = 0; var second = false
        val positions = places.mapIndexed { i, child ->
            val full = order[i] in wide
            if (full && second) { y += rowHeight + gap; second = false; rowHeight = 0 }
            val x = if (second) half + gap else 0
            val position = IntOffset(x, y)
            rowHeight = maxOf(rowHeight, heights[i])
            if (full || second) { y += rowHeight + gap; second = false; rowHeight = 0 } else second = true
            position
        }
        val height = (y + if (second) rowHeight else -gap).coerceAtLeast(0)
        layout(constraints.maxWidth, height) {
            places.forEachIndexed { i, child ->
                val id = order[i]; val pos = positions[i]
                targets[id] = Rect(pos.toOffset(), Size(child.width.toFloat(), child.height.toFloat()))
                Snapshot.withoutReadObservation { motions.getValue(id).place(pos.toOffset(), !reduced && id != dragged) }
                child.place(pos)
            }
        }
    }
}
