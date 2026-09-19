package com.mani.orbit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selectableGroup
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mani.orbit.backdrop.Backdrop
import com.mani.orbit.backdrop.backdrops.layerBackdrop
import com.mani.orbit.backdrop.backdrops.rememberCombinedBackdrop
import com.mani.orbit.backdrop.backdrops.rememberLayerBackdrop
import com.mani.orbit.backdrop.drawBackdrop
import com.mani.orbit.backdrop.effects.lens
import com.mani.orbit.backdrop.highlight.Highlight
import com.mani.orbit.backdrop.highlight.HighlightStyle
import com.mani.orbit.backdrop.shadow.Shadow
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * One control for choosing one of a few, used for the navigation bar and for every segmented control
 * in the app so that all of them look and move alike. It is the owner's US app's navigation bar: a
 * rail, a selection that is a flat pill at rest, and a lens of real glass that the pill lifts into
 * while a finger holds or carries it and while it travels (see GlassTrackMotion.kt).
 *
 * Three layers, bottom to top. The rail. The flat pill and the labels, recorded together so the lens
 * can bend them. The lens, which samples that recording: the labels under it bend at its rim the way
 * lettering does under a lifted piece of glass. The flat pill fades as the lens rises over it, so the
 * two are one selection changing material rather than two pills cross-fading.
 *
 * Each label is one rendering of its text, coloured per pixel: accent where the pill is over it, ink
 * where it is not. The boundary can sit halfway through a letter and nothing is drawn twice.
 */

/** Where a track sits, which decides what its lens can bend beyond its own rail. */
internal enum class TrackRole {
    /** Floats over the page: its lens reaches past the rail and bends the page there too, and the rail draws in beneath it. */
    Navigation,
    /** Sits inside a page: its lens stays within the rail. */
    Segment,
}

/** The flat selection at rest: the rail's shade lit about a quarter of the way to the accent. */
internal val TrackFlatFill = Color(0xFF3E3750)
internal val TrackInk = Color(0xFFB4ADBE)
internal val TrackChosenInk = Color(0xFFE9E0FF)

/**
 * How a track is dressed. [rail] false leaves only the selection, for a row of choices that has its
 * own ground; [round] makes the selection a disc of that size centred in its slot.
 */
internal data class TrackStyle(
    val rail: Boolean = true,
    val flatFill: Color = TrackFlatFill,
    val ink: Color = TrackInk,
    val chosenInk: Color = TrackChosenInk,
    val round: Dp = 0.dp,
    val inset: Dp? = null,
    val labelWeight: FontWeight = FontWeight(560),
    /**
     * Whether the track draws in 5% about a lifted lens, so the glass reads as rising off it. Every
     * track does but the Explore island's, which the owner keeps exactly as it moves today.
     */
    val recedes: Boolean = true,
    /** Whether a track that cannot be used right now shows it; one hidden while it opens need not. */
    val dims: Boolean = true,
)

/** An in-page lens stands on this where its rail is see-through, so it never shows through to the page. */
private val TrackLensBase = Color(0xFF17161C)
private val TrackRailRim = Brush.verticalGradient(listOf(Color.White.copy(alpha = .16f), Color.White.copy(alpha = .05f)))

/** How far a lifted lens reaches past its pill on every side. BitChord's hold: it rises clear of the rail. */
private val NavigationReach = 8.dp
/** In a page the lens lifts clear of its rail too, a little less than the navigation's. */
private val SegmentReach = 6.dp
/** A finger moves this far before it carries the selection rather than pressing a slot. */
private val CarryAfter = 6.dp

@Composable
internal fun GlassTrack(
    labels: List<String>,
    selected: Int,
    select: (Int) -> Unit,
    name: String,
    modifier: Modifier = Modifier,
    role: TrackRole = TrackRole.Segment,
    page: GlassBackdrop? = null,
    enabled: Boolean = true,
    slotHeight: Dp = 44.dp,
    labelSize: TextUnit = 13.sp,
    slotRole: Role = Role.Tab,
    /** A tap on the chosen slot is passed on too, for a choice that can be cleared. */
    reselect: Boolean = false,
    style: TrackStyle = TrackStyle(),
    allowed: (Int) -> Boolean = { true },
    describe: ((Int) -> String)? = null,
    /** Drawn in each slot beneath its label, outside the label's colouring. */
    mark: (@Composable BoxScope.(Int) -> Unit)? = null,
    /** A glyph beside each label, or above it when [stacked]; glyphs keep [iconTint] rather than the selection's colour. */
    icons: List<Painter>? = null,
    iconTint: Color = Color.Unspecified,
    stacked: Boolean = false,
    /** A vertical move gives the touch to what holds the track: a page's scroll, or the island it opens in. */
    yieldVertical: Boolean = role == TrackRole.Segment,
    /**
     * What lies under a track that sits on a glass surface: that surface's own finished glass. The lens
     * bends it with the labels, so it reads as a thicker piece of the same glass, never a hole to the page.
     */
    under: Backdrop? = null,
) {
    val reduced = LocalOrbitReducedMotion.current
    val readability = LocalGlassReadability.current
    val quality = LocalGlassQuality.current.quality
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val state = remember(labels.size) { GlassTrackState(labels.size, scope) }
    val currentSelect by rememberUpdatedState(select)
    val currentReselect by rememberUpdatedState(reselect)
    val currentAllowed by rememberUpdatedState(allowed)
    val navigation = role == TrackRole.Navigation
    val inset = style.inset ?: if (navigation) 6.dp else 4.dp
    val gap = if (navigation) 4.dp else 0.dp
    SideEffect {
        state.reduced = reduced
        state.allowed = { currentAllowed(it) }
        state.tick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
        state.commit = { index -> haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); currentSelect(index) }
        state.again = { index ->
            if (currentReselect) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); currentSelect(index) }
        }
        state.sync(selected)
    }
    val backdrop = page ?: LocalPageBackdrop.current
    val optics = trackOptics(reduced, readability, quality) && (!navigation || backdrop != null)
    val railShape = remember { RoundedCornerShape(percent = 50) }
    val trackLayer = rememberLayerBackdrop()
    val lensBackdrop = when {
        under != null -> rememberCombinedBackdrop(under, trackLayer)
        navigation && backdrop != null -> rememberCombinedBackdrop(backdrop.layer, trackLayer)
        else -> trackLayer
    }
    val box = remember { FloatArray(4) }
    val showPill by remember(state) { derivedStateOf { state.hasPill } }
    val recedes = optics && style.recedes
    val ink = readability.foreground(style.ink)
    val chosen = readability.foreground(style.chosenInk)
    // As wide as its container unless given a width of its own: the slots share whatever it has.
    Box(modifier
        .fillMaxWidth()
        .height(slotHeight + inset * 2)
        .onSizeChanged {
            with(density) {
                state.layout(it, inset.toPx(), gap.toPx(), (if (navigation) NavigationReach else SegmentReach).toPx(),
                    // A round pill with no rail keeps its lens over its own row; a railed one may rise past its rail.
                    CarryAfter.toPx(), contained = !navigation && !style.rail, round = style.round.toPx())
            }
        }
        .trackGestures(state, enabled, !yieldVertical)
        .semantics { selectableGroup() }
        .onKeyEvent { event ->
            if (!enabled || event.type != KeyEventType.KeyDown) return@onKeyEvent false
            val step = when (event.key) { Key.DirectionRight -> 1; Key.DirectionLeft -> -1; else -> 0 }
            if (step == 0) false else { state.choose((state.selected + step).coerceIn(0, labels.lastIndex)); true }
        }
        .focusable(enabled)
        .testTag(name)) {
        Box(Modifier.matchParentSize().then(if (optics) Modifier.layerBackdrop(trackLayer) else Modifier)) {
            if (style.rail) Box(Modifier.matchParentSize().recede(state, recedes).then(when {
                navigation && backdrop != null -> Modifier.orbitFrost(backdrop, slotHeight / 2 + inset)
                // In a page the rail is glass too, as the US app's rails are: the page's ground frosted, bent
                // at its curved edge and lit along its rim, with the control's faint lift over it so the
                // capsule still reads on a card made of the same glass.
                optics -> Modifier.clip(railShape).orbitPanel(slotHeight / 2 + inset)
                    .background(ControlFill, railShape).border(GlassEdgeWidth, TrackRailRim, railShape)
                else -> Modifier.background(ControlFill, railShape).border(GlassEdgeWidth, TrackRailRim, railShape)
            }))
            if (showPill) Box(Modifier.trackBox(state, box)
                .graphicsLayer { alpha = if (state.transient) 0f else if (optics) 1f - state.pickup else 1f }
                .background(style.flatFill, railShape)
                .testTag("$name-indicator"))
            Row(Modifier.matchParentSize().recede(state, recedes).padding(horizontal = inset),
                horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
                labels.forEachIndexed { index, label ->
                    TrackLabel(state, box, index, label, index == selected, enabled && allowed(index),
                        allowed(index) && (enabled || !style.dims), slotRole, labelSize, style.labelWeight, ink, chosen, recedes,
                        "$name-$index", describe?.invoke(index), mark, icons?.getOrNull(index), iconTint, stacked)
                }
            }
        }
        if (optics && showPill) TrackLens(state, box, lensBackdrop, if (navigation) null else TrackLensBase, "$name-lens")
    }
}

/** Glass needs a platform that can blur; the owner's reduce transparency and reduce motion both keep a selection flat. */
private fun trackOptics(reduced: Boolean, readability: GlassReadability, quality: GlassQuality): Boolean =
    !reduced && !readability.opaque && quality != GlassQuality.READABILITY && BlurSupported

@Composable
private fun RowScope.TrackLabel(state: GlassTrackState, box: FloatArray, index: Int, label: String, chosen: Boolean,
    enabled: Boolean, lit: Boolean, role: Role, size: TextUnit, weight: FontWeight, ink: Color, accent: Color, recedes: Boolean,
    tag: String, description: String?, mark: (@Composable BoxScope.(Int) -> Unit)?, icon: Painter?, iconTint: Color,
    stacked: Boolean) {
    Box(Modifier.weight(1f).fillMaxHeight()
        .semantics(mergeDescendants = true) {
            this.role = role
            selected = chosen
            if (description != null) contentDescription = description
            if (enabled) onClick { state.choose(index); true }
        }
        .testTag(tag), contentAlignment = Alignment.Center) {
        mark?.invoke(this, index)
        // A glyph keeps its own colour: it is laid out again, outside the words' colouring, in
        // exactly the words' arrangement, so the two can never drift apart.
        if (icon != null) SlotFace(label, size, weight, icon, iconTint, stacked, glyph = true, Modifier.graphicsLayer { alpha = if (lit) 1f else .35f })
        Box(Modifier.fillMaxSize()
            .graphicsLayer {
                compositingStrategy = CompositingStrategy.Offscreen
                alpha = if (lit) 1f else .35f
            }
            .drawWithContent {
                drawContent()
                var from = 0f
                var to = 0f
                if (state.hasPill) {
                    state.presentation(box)
                    var a = box[0]
                    var b = box[0] + box[2]
                    if (recedes) {
                        // The labels draw in with the rail about the lens, so the lens's edges are found
                        // through the same scale the labels are drawn at.
                        val scale = 1f - TrackRecede * state.pickup
                        val pivot = state.pivot()
                        a = pivot + (a - pivot) / scale
                        b = pivot + (b - pivot) / scale
                    }
                    val origin = state.slotLeft(index)
                    from = (a - origin).coerceIn(0f, this.size.width)
                    to = (b - origin).coerceIn(0f, this.size.width)
                }
                clipRect(right = from) { drawRect(ink, blendMode = BlendMode.SrcIn) }
                if (to > from) clipRect(left = from, right = to) { drawRect(accent, blendMode = BlendMode.SrcIn) }
                clipRect(left = to) { drawRect(ink, blendMode = BlendMode.SrcIn) }
            }, contentAlignment = Alignment.Center) {
            if (icon != null) SlotFace(label, size, weight, icon, iconTint, stacked, glyph = false)
            else BasicText(label, style = MaterialTheme.typography.bodySmall.copy(color = Color.White, fontSize = size,
                fontWeight = weight, textAlign = TextAlign.Center),
                maxLines = if (' ' in label) 2 else 1, modifier = Modifier.padding(horizontal = 3.dp),
                autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = size, stepSize = .5.sp))
        }
    }
}

/**
 * A glyph and its label, beside or above each other. Drawn twice with the same arrangement: once
 * showing only the glyph in its own colour, once showing only the words, which take the selection's.
 */
@Composable
private fun BoxScope.SlotFace(label: String, size: TextUnit, weight: FontWeight, icon: Painter, tint: Color, stacked: Boolean,
    glyph: Boolean, modifier: Modifier = Modifier) {
    val words = MaterialTheme.typography.bodySmall.copy(color = if (glyph) Color.Transparent else Color.White, fontSize = size,
        fontWeight = weight, lineHeight = size * 1.38f)
    val mark: @Composable () -> Unit = {
        androidx.compose.foundation.Image(icon, null, Modifier.size(22.dp).graphicsLayer { alpha = if (glyph) 1f else 0f },
            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(tint))
    }
    if (stacked) androidx.compose.foundation.layout.Column(modifier.align(Alignment.Center),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        mark(); BasicText(label, style = words, maxLines = 1)
    } else Row(modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        mark(); BasicText(label, style = words, maxLines = 1)
    }
}

/**
 * The lens: the selection lifted into a piece of glass. It samples what lies under it — the rail, the
 * flat pill fading beneath it and the labels, and past the rail the page — and bends it at its rim;
 * its middle stays clear. Refraction, rim light, shadow and sheen all grow with the pickup, and at
 * zero it is gone, so a flat pill never carries an optical edge.
 */
@Composable
private fun TrackLens(state: GlassTrackState, box: FloatArray, backdrop: Backdrop, base: Color?, tag: String) {
    val visible by remember(state) { derivedStateOf { state.pickup > .002f } }
    if (!visible) return
    val shape = remember { RoundedCornerShape(percent = 50) }
    val refracts = LocalGlassQuality.current.quality == GlassQuality.OPTICAL && LensSupported
    val density = LocalDensity.current
    val deepest = with(density) { 22.dp.toPx() }
    val furthest = with(density) { 26.dp.toPx() }
    Box(Modifier.trackBox(state, box).testTag(tag).drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            val lifted = state.pickup
            if (refracts && lifted > 0f) {
                lens(refractionHeight = min(size.minDimension * .38f, deepest) * lifted,
                    refractionAmount = min(size.minDimension * .45f, furthest) * lifted,
                    depthEffect = true, chromaticAberration = false)
            }
        },
        // The rim is the lens's own edge, lit from a light that swings as it crosses the rail.
        highlight = {
            val lifted = state.pickup
            Highlight(width = (GlassEdgeWidth.value + lifted).dp, blurRadius = 0.dp, alpha = lifted,
                style = HighlightStyle.Default(angle = 45f - 40f * (state.centre() - .5f)))
        },
        // A fixed spread, deepened through its alpha: growing a shadow's radius re-rasterises it every frame.
        shadow = { Shadow(radius = 30.dp, color = Color.Black.copy(alpha = .30f), alpha = state.pickup) },
        onDrawBackdrop = { draw -> if (base != null) drawRect(base); draw() },
        onDrawSurface = {
            val lifted = state.pickup
            drawRect(GlassTint.copy(alpha = .16f * lifted))
            // The US lens's sheen: lit at the top, a faint second light low down, shade at the foot.
            drawRect(Brush.linearGradient(
                0f to Color.White.copy(alpha = .16f * lifted), .38f to Color.Transparent,
                .76f to Color.White.copy(alpha = .025f * lifted), 1f to Color.Black.copy(alpha = .12f * lifted),
                start = Offset(size.width * .38f, 0f), end = Offset(size.width * .62f, size.height)))
        },
        backdropScale = 1f,
    ))
}

/**
 * A switch is the same selection on a rail of two: the thumb is the pill, it lifts into a lens that
 * bends the rail under it while it is held or carried, and it travels across on the same timeline.
 * The rail takes the accent as the thumb crosses it. The row around it owns the switch's semantics.
 */
@Composable
internal fun GlassSwitch(checked: Boolean, enabled: Boolean, change: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val reduced = LocalOrbitReducedMotion.current
    val readability = LocalGlassReadability.current
    val quality = LocalGlassQuality.current.quality
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val state = remember { GlassTrackState(2, scope) }
    val currentChange by rememberUpdatedState(change)
    val on by rememberUpdatedState(checked)
    SideEffect {
        state.reduced = reduced
        state.commit = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); currentChange(it == 1) }
        // Anywhere on a switch is a toggle, the thumb included.
        state.again = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); currentChange(!on) }
        state.sync(if (checked) 1 else 0)
    }
    val optics = trackOptics(reduced, readability, quality)
    val layer = rememberLayerBackdrop()
    val box = remember { FloatArray(4) }
    val shape = remember { RoundedCornerShape(percent = 50) }
    Box(modifier.size(58.dp, 32.dp)
        .onSizeChanged {
            with(density) { state.layout(it, 3.dp.toPx(), 0f, 6.dp.toPx(), CarryAfter.toPx(), contained = false) }
        }
        .trackGestures(state, enabled, floating = true)
        .clearAndSetSemantics {}
        .graphicsLayer { alpha = if (enabled) 1f else .4f }) {
        // The rail draws in beneath the lifted thumb, as every track's does.
        Box(Modifier.matchParentSize().then(if (optics) Modifier.layerBackdrop(layer) else Modifier).recede(state, optics).drawBehind {
            state.presentation(box)
            val across = ((box[0] + box[2] / 2 - size.height / 2) / (size.width - size.height)).coerceIn(0f, 1f)
            drawRoundRect(lerp(SwitchOff, SettingsPurple, across), cornerRadius = CornerRadius(size.height / 2))
        })
        Box(Modifier.trackBox(state, box).graphicsLayer { alpha = if (optics) 1f - state.pickup else 1f }
            .background(if (checked) Color.White else SwitchThumbOff, shape))
        if (optics) TrackLens(state, box, layer, null, "switch-lens")
    }
}

private val SwitchOff = Color(0xFF242129)
private val SwitchThumbOff = Color(0xFFD5CFDF)

/** Places a node exactly over the selection as drawn, taking no room of its own in the track. */
private fun Modifier.trackBox(state: GlassTrackState, box: FloatArray): Modifier = layout { measurable, _ ->
    state.presentation(box)
    val placeable = measurable.measure(Constraints.fixed(box[2].roundToInt().coerceAtLeast(0), box[3].roundToInt().coerceAtLeast(0)))
    layout(0, 0) { placeable.place(box[0].roundToInt(), box[1].roundToInt()) }
}

/** The rail draws in beneath a lifted lens, about the lens, so the slot under the finger stays under it. */
private fun Modifier.recede(state: GlassTrackState, on: Boolean): Modifier = if (!on) this else graphicsLayer {
    val scale = 1f - TrackRecede * state.pickup
    scaleX = scale; scaleY = scale
    transformOrigin = TransformOrigin(state.centre(), .5f)
}

private fun Modifier.trackGestures(state: GlassTrackState, enabled: Boolean, floating: Boolean): Modifier =
    pointerInput(state, enabled, floating) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            state.press(down.position.x, down.position.y)
            val tracker = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
            var lastMove = down.uptimeMillis
            var ended = false
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (event.changes.count { it.pressed } > 1) break
                    // A cancelled contact (the system took the gesture) arrives released and already
                    // consumed: it lets go without choosing.
                    if (!change.pressed && change.isConsumed) break
                    if (!change.pressed) {
                        val velocity = if (change.uptimeMillis - lastMove < 60) tracker.calculateVelocity().x else 0f
                        val slack = 48.dp.toPx()
                        val inside = change.position.x in -slack..(size.width + slack) &&
                            change.position.y in -slack..(size.height + slack)
                        state.release(change.position.x, velocity, inside)
                        ended = true
                        change.consume()
                        break
                    }
                    val delta = change.position - down.position
                    if (!state.dragging) {
                        if (change.isConsumed) break
                        // Inside a page a vertical move belongs to the page's scroll.
                        if (!floating && abs(delta.y) > viewConfiguration.touchSlop && abs(delta.y) > abs(delta.x)) break
                    }
                    tracker.addPosition(change.uptimeMillis, change.position)
                    if (change.position != change.previousPosition) lastMove = change.uptimeMillis
                    state.move(change.position.x, change.position.y, tracker.calculateVelocity().x)
                    if (state.dragging) change.consume()
                }
            } finally {
                if (!ended) state.cancel()
            }
        }
    }
