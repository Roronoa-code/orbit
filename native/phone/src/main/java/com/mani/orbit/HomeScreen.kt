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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.*

/** The deck's own corner, shared by the material and by the fold that cuts it. */
private val CardCorner = 20.dp

/** How far a travelling cut fades its content, so no line of text is ever sliced in half. */
private val CardFeather = 26.dp

@Composable
internal fun HomeScreen(state: HealthScreenState, metric: HomeMetric, period: Int, goal: Int?, reduced: Boolean,
                        exploreOpen: Boolean, closeExplore: () -> Unit, chooseMetric: (HomeMetric) -> Unit,
                        choosePeriod: () -> Unit, navigate: (String) -> Unit, rotation: Boolean = true) {
    val summary = remember(state.day, state.days, state.liveStepsAt, metric, period, goal) { HomeSummary.from(state, metric, period, goal) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val motion = remember { ExploreMotion(scope, expanded) }
    val scroll = rememberScrollState()
    // Where the front card folds: the bottom of its facts block plus the card's own padding, so the
    // fold lands in the gap under the last row instead of on its baseline.
    var foldHeight by remember { mutableFloatStateOf(146f) }
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
        // Lift is contact, not travel: a card carried by a finger is thicker glass, and a card that
        // is merely unfolding is not. Driving it from the fold made every open and close pulse the
        // material thicker and then thinner again.
        val deckLift = androidx.compose.animation.core.animateFloatAsState(
            if (motion.dragging) 1f else 0f, orbitEngage(reduced), label = "Deck lift")
        val scene = rememberGlassBackdrop()
        // The deck refracts the globe, so the globe is recorded on its own: a card cannot sample
        // the recording it is drawn into.
        val hero = rememberGlassBackdrop()
        Box(Modifier.fillMaxSize().then(pointer).recordBackdrop(scene)) {
            // The globe's recording covers the whole page, not just the globe. A layer that stops
            // where the globe stops leaves every card below it sampling the edge of that recording
            // rather than the page, which is why the deck and the Explore bar read as two shades.
            Box(Modifier.fillMaxSize().recordBackdrop(hero)) {
                HomeOrb(summary, exploreOpen || busy || expanded || !rotation, reduced,
                    Modifier.fillMaxWidth().height(heroClosed), { motion.value }, travel, chooseMetric, choosePeriod)
            }
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
                        .then(if (!expanded) Modifier.clearAndSetSemantics { testTag = "home-deck" } else Modifier), content = {
                        repeat(4) { index ->
                            // The fold clips the glass itself: the shell, its rim and its shadow are the
                            // material, so the card shows the orb through it as it opens.
                            Box(Modifier.fillMaxWidth().testTag("home-card-$index")
                                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                                .drawWithContent {
                                val progress = q(index)
                                val fold = { i: Int, height: Float ->
                                    val closed = min(if (i == 0) foldHeight.dp.toPx() else 146.dp.toPx(), height)
                                    closed + (height - closed) * q(i)
                                }
                                val visibleHeight = fold(index, size.height)
                                val covered = if (index == 0) 0f else {
                                    val previousHeight = heights[index - 1].dp.toPx() - 12.dp.toPx()
                                    max(0f, cardY(index - 1).dp.toPx() + fold(index - 1, previousHeight) - cardY(index).dp.toPx())
                                }
                                val top = min(covered, visibleHeight)
                                // A card the one above still covers draws nothing at all. Stroking its
                                // rim anyway left a stray hairline lying under the folded deck.
                                if (visibleHeight - top < 1f) return@drawWithContent
                                // The fold is a window onto the card, and a window has the card's own
                                // corners: a straight cut leaves a folded card with square edges the
                                // opened one never has.
                                val radius = CornerRadius(CardCorner.toPx(), CardCorner.toPx())
                                val window = Path().apply {
                                    addRoundRect(RoundRect(Rect(0f, top, size.width, visibleHeight), radius, radius, radius, radius))
                                }
                                clipPath(window) { this@drawWithContent.drawContent() }
                                // A cut that is standing still is an edge and carries the material's
                                // hairline. A cut that is travelling is a reveal, and it feathers:
                                // a hard edge sweeping through the card sliced every line of text it
                                // passed, which is what read as the fold being broken.
                                val settled = 1f - min(1f, progress / .1f)
                                val feather = CardFeather.toPx() * (1f - settled)
                                if (feather > .5f) {
                                    if (visibleHeight < size.height - .5f) drawRect(
                                        Brush.verticalGradient(listOf(Color.Black, Color.Transparent),
                                            startY = visibleHeight - feather, endY = visibleHeight),
                                        topLeft = Offset(0f, visibleHeight - feather),
                                        size = Size(size.width, feather), blendMode = BlendMode.DstIn)
                                    if (top > .5f) drawRect(
                                        Brush.verticalGradient(listOf(Color.Transparent, Color.Black),
                                            startY = top, endY = top + feather),
                                        topLeft = Offset(0f, top), size = Size(size.width, feather),
                                        blendMode = BlendMode.DstIn)
                                }
                                val band = CardCorner.toPx()
                                val rim = GlassEdgeColor.copy(alpha = GlassEdgeColor.alpha * settled)
                                if (settled > .01f) {
                                    if (visibleHeight < size.height - .5f) clipRect(top = visibleHeight - band, bottom = visibleHeight) {
                                        drawPath(window, rim, style = Stroke(GlassEdgeWidth.toPx()))
                                    }
                                    if (top > .5f) clipRect(top = top, bottom = top + band) {
                                        drawPath(window, rim, style = Stroke(GlassEdgeWidth.toPx()))
                                    }
                                }
                            }.orbitFrost(hero, CardCorner, { deckLift.value })) {
                                Box(Modifier.graphicsLayer()) {
                                    when (index) {
                                        0 -> HomeFacts(summary, { motion.value }, expanded,
                                            { foldHeight = it }) { if (metric == HomeMetric.Sleep) navigate("Sleep") }
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
                            // Cards emerge from behind the one above rather than fading up from a
                            // ghost of themselves: they are solid objects the whole way, and the card
                            // above already clips them until they are out.
                            for (i in cards.indices.reversed()) cards[i].place(0, cardY(i).dp.toPx().roundToInt())
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
private fun HomeFacts(summary: HomeSummary, progress: () -> Float, enabled: Boolean,
                      reportFold: (Float) -> Unit, open: () -> Unit) {
    val density = LocalDensity.current
    Column(Modifier.fillMaxWidth().then(if (summary.metric == HomeMetric.Sleep && enabled) Modifier.clickable(onClick = open) else Modifier)
        .padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // The facts block plus the card's padding above and below it is where the card folds.
        Column(Modifier.fillMaxWidth().onGloballyPositioned { reportFold(it.size.height / density.density + 36f) },
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
        summary.facts.chunked(2).forEach { pair -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            pair.forEach { fact -> Column(Modifier.weight(1f)) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.Bottom) {
                    Text(fact.value, color = HomeWhite, fontSize = 26.sp, lineHeight = 32.sp, maxLines = 1)
                    if (fact.unit.isNotEmpty()) Text(" ${fact.unit}", color = HomeMuted, fontSize = 11.sp, lineHeight = 24.sp)
                }
                Text(fact.label, color = HomeMuted, fontSize = 10.sp, lineHeight = 14.sp)
            } }
        } }
        }
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
