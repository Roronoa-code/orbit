package com.mani.orbit

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.*

@Composable
internal fun HomeScreen(state: HealthScreenState, metric: HomeMetric, period: Int, goal: Int?, reduced: Boolean,
                        exploreOpen: Boolean, closeExplore: () -> Unit, chooseMetric: (HomeMetric) -> Unit,
                        choosePeriod: () -> Unit, navigate: (String) -> Unit, rotation: Boolean = true) {
    val summary = remember(state.day, state.days, state.liveStepsAt, metric, period, goal) { HomeSummary.from(state, metric, period, goal) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val motion = remember { ExploreMotion(scope, expanded) }
    val scroll = rememberScrollState()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current.density
    val closeLauncher by rememberUpdatedState(closeExplore)
    val busy by remember { derivedStateOf { motion.dragging || abs(motion.value - if (expanded) 1f else 0f) > .001f } }
    fun unfold(value: Boolean, speed: Float = motion.velocity) {
        if (value != expanded) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        if (value) closeLauncher()
        expanded = value; if (reduced) motion.snap(value) else motion.settle(value, speed)
    }
    LaunchedEffect(expanded, reduced) {
        if (reduced) motion.snap(expanded) else motion.settle(expanded)
        if (!expanded) launch { if (reduced) scroll.scrollTo(0) else scroll.animateScrollTo(0, spring(1f, 324f)) }
    }
    BackHandler(expanded && !exploreOpen) { unfold(false) }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("home-scene").drawWithCache {
        val atmosphere = Brush.radialGradient(listOf(Color(0x201F1F25), Color.Transparent),
            center = Offset(size.width * .15f, size.height * .5f), radius = size.width * 1.1f)
        onDrawBehind { drawRect(atmosphere) }
    }) {
        val closedDeck = if (maxHeight <= 680.dp) 254.dp else 262.dp
        val heroClosed = maxOf(215.dp, maxHeight - closedDeck)
        val heroOpen = minOf(heroClosed, 220.dp)
        val travel = heroClosed - heroOpen
        val open by rememberUpdatedState(expanded)
        val pointer = Modifier.pointerInput(travel) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val wasOpen = open
                val start = motion.value
                val startedAtTop = scroll.value <= 1
                val distance = max(220.dp.toPx(), travel.toPx() + 38.dp.toPx())
                val tracker = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
                var claimed = false
                var completed = false
                var lastMove = down.uptimeMillis
                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.isConsumed || event.changes.count { it.pressed } > 1) break
                        val delta = change.position - down.position
                        if (!change.pressed) {
                            if (claimed) {
                                change.consume()
                                val speed = if (change.uptimeMillis - lastMove < 90) tracker.calculateVelocity().y else 0f
                                val intentional = abs(delta.y) >= 24.dp.toPx() || abs(delta.y) >= 8.dp.toPx() && abs(speed) > 350 * density && sign(speed) == sign(delta.y)
                                val next = if (intentional) delta.y < 0 else wasOpen
                                unfold(next, -speed / distance); completed = true
                            }
                            break
                        }
                        if (!claimed && abs(delta.x) > viewConfiguration.touchSlop && abs(delta.x) > abs(delta.y)) break
                        if (!claimed && abs(delta.y) > viewConfiguration.touchSlop && abs(delta.y) > abs(delta.x) * 1.2f) {
                            if ((!wasOpen && delta.y < 0) || (wasOpen && delta.y > 0 && startedAtTop)) {
                                claimed = true; motion.grab(); if (!wasOpen) closeLauncher()
                            } else break
                        }
                        if (claimed) {
                            tracker.addPosition(change.uptimeMillis, change.position)
                            if (change.position != change.previousPosition) lastMove = change.uptimeMillis
                            val dy = delta.y - sign(delta.y) * viewConfiguration.touchSlop
                            motion.move(start - dy / distance, -tracker.calculateVelocity().y / distance)
                            change.consume()
                        }
                    }
                } finally { if (claimed && !completed) motion.settle(wasOpen, 0f) }
            }
        }
        val scene = rememberGlassBackdrop()
        Box(Modifier.fillMaxSize().then(pointer).recordBackdrop(scene)) {
            HomeOrb(summary, exploreOpen || busy || expanded || !rotation, reduced,
                Modifier.fillMaxWidth().height(heroClosed), { motion.value }, travel, chooseMetric, choosePeriod)
            Box(Modifier.fillMaxSize().padding(top = heroOpen)) {
                Box(Modifier.fillMaxSize().graphicsLayer { translationY = travel.toPx() * (1f - motion.value) }
                    .verticalScroll(scroll, enabled = expanded && !motion.dragging)) {
                    val heights = remember { FloatArray(4) }
                    fun q(index: Int) = reveal(motion.value, index * .045f, 1f)
                    fun cardY(index: Int): Float {
                        var target = 0f
                        for (i in 0 until index) target += heights[i]
                        val closed = 22f - index * 6f
                        return closed + (target - closed) * q(index)
                    }
                    Layout(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp)
                        .then(if (!expanded) Modifier.clearAndSetSemantics {} else Modifier), content = {
                        repeat(4) { index ->
                            Box(Modifier.fillMaxWidth().testTag("home-card-$index").drawWithContent {
                                val progress = q(index)
                                val visibleHeight = min(146.dp.toPx(), size.height) + (size.height - min(146.dp.toPx(), size.height)) * progress
                                val radius = CornerRadius(20.dp.toPx())
                                drawRoundRect(Brush.linearGradient(listOf(Color(0xE619181D), Color(0xEE111114))), size = Size(size.width, visibleHeight), cornerRadius = radius)
                                val edge = .8.dp.toPx()
                                drawRoundRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = .16f), Color.White.copy(alpha = .035f))),
                                    Offset(edge / 2, edge / 2), Size(size.width - edge, visibleHeight - edge), radius, style = Stroke(edge))
                                val covered = if (index == 0) 0f else {
                                    val previousHeight = heights[index - 1].dp.toPx() - 12.dp.toPx()
                                    val shown = min(146.dp.toPx(), previousHeight) + (previousHeight - min(146.dp.toPx(), previousHeight)) * q(index - 1)
                                    max(0f, cardY(index - 1).dp.toPx() + shown - cardY(index).dp.toPx())
                                }
                                clipRect(top = min(covered, visibleHeight), bottom = visibleHeight) { this@drawWithContent.drawContent() }
                            }) {
                                Box(Modifier.graphicsLayer()) {
                                    when (index) {
                                        0 -> HomeFacts(summary, { motion.value }, expanded) { if (metric == HomeMetric.Sleep) navigate("Sleep") }
                                        1 -> HomeChart(summary.movementHeading, summary.movement, metric, true, expanded)
                                        2 -> HomeChart("Last ${summary.week.size} days", summary.week, metric, false, expanded)
                                        3 -> HomeComparisonCard(summary)
                                    }
                                }
                            }
                        }
                    }) { measurables, constraints ->
                        val cards = measurables.map { it.measure(constraints.copy(minHeight = 0, maxHeight = androidx.compose.ui.unit.Constraints.Infinity)) }
                        cards.forEachIndexed { i, card -> heights[i] = card.height.toDp().value + 12f }
                        val contentHeight = cards.sumOf { it.height + 12.dp.roundToPx() } + 100.dp.roundToPx()
                        layout(constraints.maxWidth, contentHeight) {
                            for (i in cards.indices.reversed()) cards[i].placeWithLayer(0,
                                cardY(i).dp.toPx().roundToInt()) {
                                transformOrigin = TransformOrigin(.5f, 0f)
                                scaleX = 1f - i * .025f * (1f - q(i))
                                alpha = if (i == 0) 1f else .3f + .7f * q(i)
                            }
                        }
                    }
                }
                if (!expanded) Box(Modifier.fillMaxWidth().height(closedDeck).offset { IntOffset(0, (travel.toPx() * (1f - motion.value)).roundToInt()) }
                    .testTag("expand-home-cards").semantics { contentDescription = "Expand four panels" }
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button) { unfold(true) })
            }
        }
        HomeScrollFrost(scene, { (scroll.value / (24 * density)).coerceIn(0f, 1f) * motion.value },
            Modifier.fillMaxWidth().height(84.dp).offset(y = heroOpen - 24.dp))
    }
}

@Composable
private fun HomeFacts(summary: HomeSummary, progress: () -> Float, enabled: Boolean, open: () -> Unit) {
    Column(Modifier.fillMaxWidth().then(if (summary.metric == HomeMetric.Sleep && enabled) Modifier.clickable(onClick = open) else Modifier)
        .padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        summary.facts.chunked(2).forEach { pair -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            pair.forEach { fact -> Column(Modifier.weight(1f)) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.Bottom) {
                    Text(fact.value, color = HomeWhite, fontSize = 26.sp, lineHeight = 32.sp, maxLines = 1)
                    if (fact.unit.isNotEmpty()) Text(" ${fact.unit}", color = HomeMuted, fontSize = 11.sp, lineHeight = 24.sp)
                }
                Text(fact.label, color = HomeMuted, fontSize = 10.sp, lineHeight = 14.sp)
            } }
        } }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp).graphicsLayer { alpha = reveal(progress(), .4f, .94f); translationY = (1f - alpha) * 7.dp.toPx() }, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            summary.footer.forEach { fact -> Column(Modifier.weight(1f)) {
                Text(fact.value, color = HomeWhite, fontSize = 13.sp, lineHeight = 18.sp)
                Text(fact.label, color = HomeMuted, fontSize = 10.sp, lineHeight = 15.sp)
            } }
        }
    }
}

@Composable
private fun HomeComparisonCard(summary: HomeSummary) {
    val comparison = summary.comparison
    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("Daily average comparison", color = HomeWhite, fontSize = 14.sp, lineHeight = 20.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            listOf(comparison.current to comparison.currentDays, comparison.previous to comparison.previousDays).forEachIndexed { i, (value, recorded) ->
                Column(Modifier.weight(1f)) {
                    Text(if (summary.metric == HomeMetric.Sleep) homeDuration(value) else homeNumber(value), color = HomeWhite, fontSize = 27.sp, lineHeight = 34.sp)
                    Text(summary.metric.unit, color = HomeMuted, fontSize = 11.sp)
                    val end = summary.date.minusDays(if (i == 0) 0 else summary.week.size.toLong())
                    Text("${homeDate(end.minusDays(summary.week.size - 1L))}–${homeDate(end)}", color = HomeMuted, fontSize = 10.sp, lineHeight = 15.sp)
                    Text("$recorded recorded days", color = HomeMuted, fontSize = 10.sp, lineHeight = 15.sp)
                }
            }
        }
        val difference = comparison.difference
        Text(if (difference == null) "Comparison unavailable" else if (difference == 0.0) "No change" else
            "${homeNumber(abs(difference))} ${summary.metric.unit} ${if (difference > 0) "higher" else "lower"}", color = HomeAccent, fontSize = 15.sp)
    }
}
