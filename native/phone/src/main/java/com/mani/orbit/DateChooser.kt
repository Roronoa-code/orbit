package com.mani.orbit

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt

private val DateLavender = Color(0xFFBBA1ED)
private val DateMuted = Color(0xFFB3AEBE)
private val DateWhite = Color(0xFFF4F4F6)
private val ChosenDayFormat = DateTimeFormatter.ofPattern("EEEE d MMM", Locale.UK)
private val PanelCorner = 22.dp
/** How far the panel sits out past the control it grows from, so the control's words sit inside it. */
private val PanelOutset = 6.dp

/**
 * The header's date grows into this panel and goes back into it, which is the owner's US app's rule
 * for every surface: it never appears out of thin air, it comes out of the control that opened it.
 *
 * The panel is laid out at its full size from the first frame and is real glass the whole time; what
 * moves is its outline, from exactly the control's bounds to its own. It springs out with a small
 * bounce, its contents fade in once it has formed and are never scaled, and it closes back into the
 * control without the bounce. While it moves, a hairline in the material's own edge colour traces the
 * moving outline, which fades as the panel settles and its own rim takes over.
 */
@Composable
internal fun OrbitDateChooser(open: Boolean, anchor: Rect, date: LocalDate, first: LocalDate?, page: GlassBackdrop,
    closed: () -> Unit, dismiss: () -> Unit, select: (LocalDate) -> Unit) {
    val today = remember { LocalDate.now() }
    val start = remember(first, today) { first?.takeIf { it < today } ?: today.minusDays(29) }
    val span = remember(start, today) { ChronoUnit.DAYS.between(start, today).toInt().coerceAtLeast(1) }
    var chosen by rememberSaveable(date, start) { mutableStateOf(date.coerceIn(start, today).toString()) }
    var carried by remember { mutableStateOf(false) }
    val choice = LocalDate.parse(chosen)
    val reduced = LocalOrbitReducedMotion.current
    val density = LocalDensity.current
    val grow = remember { Animatable(0f) }
    val currentClosed by rememberUpdatedState(closed)
    LaunchedEffect(open, reduced) {
        if (open) {
            if (reduced) grow.snapTo(1f) else grow.animateTo(1f, spring(dampingRatio = .72f, stiffness = 340f))
        } else {
            if (reduced) grow.snapTo(0f) else grow.animateTo(0f, tween(260, easing = FastOutSlowInEasing))
            currentClosed()
        }
    }
    // Carrying the track lifts the panel into thicker glass, and its rim light follows the thumb.
    val panelLift by animateFloatAsState(if (carried) 1f else 0f,
        if (reduced) tween(0) else tween(160), label = "Date panel lift")
    val trackFocus = (ChronoUnit.DAYS.between(start, choice).toFloat() / span).coerceIn(0f, 1f)
    // A tap outside closes the panel; the panel keeps its own touches.
    if (open) Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { dismiss() } })
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val outset = with(density) { PanelOutset.toPx() }
        val left = (anchor.left - outset).coerceAtLeast(0f)
        val top = (anchor.top - outset).coerceAtLeast(0f)
        val width = minOf(with(density) { 300.dp.toPx() }, constraints.maxWidth - left - with(density) { 13.dp.toPx() })
        Column(Modifier
            .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
            .width(with(density) { width.toDp() })
            .graphicsLayer {
                val p = grow.value
                // The bounce: a couple of percent past full size, from the corner it grew out of.
                val over = (p - 1f).coerceAtLeast(0f)
                transformOrigin = TransformOrigin(0f, 0f)
                scaleX = 1f + over * .6f; scaleY = 1f + over
                // Until it has formed, the panel shows only through the outline growing out of the control.
                clip = p < 1f
                if (clip) shape = Window(outline(anchor, left, top, size, p), PanelCorner.toPx() * p.coerceIn(0f, 1f) +
                    anchor.height / 2 * (1f - p.coerceIn(0f, 1f)))
            }
            .drawWithContent {
                drawContent()
                val p = grow.value
                val trace = 1f - reveal(p, .7f, 1f)
                if (trace > .01f) {
                    val rim = 1.dp.toPx()
                    val rect = outline(anchor, left, top, size, p).deflate(rim / 2)
                    val corner = PanelCorner.toPx() * p.coerceIn(0f, 1f) + anchor.height / 2 * (1f - p.coerceIn(0f, 1f))
                    drawPath(Path().apply { addRoundRect(RoundRect(rect, CornerRadius(corner))) },
                        GlassEdgeColor.copy(alpha = .9f * trace), style = Stroke(rim))
                }
            }
            .orbitFrost(page, PanelCorner, { panelLift }, { Offset(trackFocus, .5f) })
            .pointerInput(Unit) { detectTapGestures { } }
            .then(if (open) Modifier else Modifier.clearAndSetSemantics {})
            .testTag("date-chooser")) {
            Column(Modifier.graphicsLayer { alpha = reveal(grow.value, .3f, .85f) }
                .padding(start = 17.dp, top = 13.dp, end = 17.dp, bottom = 17.dp)) {
                Text("Choose a day", color = DateWhite, fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight(500),
                    modifier = Modifier.semantics { heading() })
                Text("Explore your shared health history.", color = Color(0xFFBCBCC4), fontSize = 11.sp, lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 10.dp))
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DateStep("‹", "Previous day", choice > start) { chosen = choice.minusDays(1).toString() }
                    Text(choice.format(ChosenDayFormat), color = DateWhite, fontSize = 12.sp, lineHeight = 17.sp,
                        textAlign = TextAlign.Center, modifier = Modifier.weight(1f).testTag("date-choice"))
                    DateStep("›", "Next day", choice < today) { chosen = choice.plusDays(1).toString() }
                }
                DateTrack(ChronoUnit.DAYS.between(start, choice).toInt(), span, choice.format(ChosenDayFormat),
                    { carried = it }) { chosen = start.plusDays(it.toLong()).toString() }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(start.format(HeaderDayFormat), color = DateMuted, fontSize = 10.sp, lineHeight = 14.sp)
                    Text(today.format(HeaderDayFormat), color = DateMuted, fontSize = 10.sp, lineHeight = 14.sp)
                }
                Row(Modifier.fillMaxWidth().padding(top = 15.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    DateAction("Cancel", false, dismiss)
                    DateAction("View day", true) { select(choice) }
                }
            }
        }
    }
}

/** The panel's outline at [p], in its own pixels: the control's bounds at 0, the whole panel at 1. */
private fun outline(anchor: Rect, left: Float, top: Float, size: Size, p: Float): Rect {
    val t = p.coerceIn(0f, 1f)
    val from = if (anchor.isEmpty) Rect(0f, 0f, size.width * .3f, size.height * .15f)
        else Rect(anchor.left - left, anchor.top - top, anchor.right - left, anchor.bottom - top)
    return Rect(from.left * (1 - t), from.top * (1 - t),
        from.right + (size.width - from.right) * t, from.bottom + (size.height - from.bottom) * t)
}

private class Window(private val rect: Rect, private val corner: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(rect, CornerRadius(corner)))
}

/** One lavender track across the actual imported coverage; the thumb stays inside the panel. */
@Composable private fun DateTrack(position: Int, span: Int, label: String, carry: (Boolean) -> Unit,
    pick: (Int) -> Unit) {
    val radius = 9.dp
    val fraction = (position.toFloat() / span).coerceIn(0f, 1f)
    // The drawn track stays slim; the touch target keeps the platform minimum.
    Canvas(Modifier.fillMaxWidth().height(48.dp).testTag("date-range")
        .semantics {
            contentDescription = "Choose health history day"
            stateDescription = label
            progressBarRangeInfo = ProgressBarRangeInfo(position.toFloat(), 0f..span.toFloat(), span - 1)
            setProgress { value -> pick(value.roundToInt().coerceIn(0, span)); true }
        }
        .pointerInput(span) {
            val inset = radius.toPx()
            fun day(x: Float) = (((x - inset) / (size.width - 2 * inset)).coerceIn(0f, 1f) * span).roundToInt()
            awaitEachGesture {
                val down = awaitFirstDown()
                pick(day(down.position.x)); down.consume(); carry(true)
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        pick(day(change.position.x)); change.consume()
                    }
                } finally { carry(false) }
            }
        }) {
        val middle = size.height / 2
        val inset = radius.toPx()
        drawLine(Color.White.copy(alpha = .16f), Offset(inset, middle), Offset(size.width - inset, middle),
            8.dp.toPx(), StrokeCap.Round)
        val x = inset + (size.width - 2 * inset) * fraction
        drawLine(DateLavender, Offset(inset, middle), Offset(x, middle), 8.dp.toPx(), StrokeCap.Round)
        drawCircle(DateLavender, inset, Offset(x, middle))
    }
}

@Composable private fun DateStep(glyph: String, label: String, enabled: Boolean, action: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    Box(Modifier.size(44.dp).orbitControl(14.dp, interaction, enabled)
        .clickable(enabled = enabled, interactionSource = interaction, indication = null) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); action() }
        .semantics { contentDescription = label; role = Role.Button }, contentAlignment = Alignment.Center) {
        Text(glyph, color = if (enabled) DateWhite else DateMuted.copy(alpha = .38f), fontSize = 20.sp, lineHeight = 24.sp)
    }
}

@Composable private fun DateAction(label: String, primary: Boolean, action: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    Box(Modifier.heightIn(min = 44.dp)
        .orbitControl(14.dp, interaction, fill = if (primary) DateLavender else ControlFill)
        .clickable(interactionSource = interaction, indication = null) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); action() }
        .semantics { role = Role.Button }.padding(horizontal = 16.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center) {
        Text(label, color = if (primary) PageInk else DateWhite, fontSize = 13.sp, lineHeight = 18.sp)
    }
}

