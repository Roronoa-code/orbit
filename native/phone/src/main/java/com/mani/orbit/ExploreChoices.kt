package com.mani.orbit

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/** The approved transient track has no permanent second selection surface underneath it. */
@Composable
internal fun ExploreChoices(route: String, enabled: Boolean, backdrop: GlassBackdrop, select: (String) -> Unit) {
    val readability = LocalGlassReadability.current
    val reduced by rememberUpdatedState(LocalOrbitReducedMotion.current)
    val labels = listOf("Body", "Workouts", "Health")
    val routes = listOf("Measurements", "Workouts", "Health")
    val icons = listOf(R.drawable.orbit_body, R.drawable.orbit_workouts, R.drawable.orbit_health)
    val currentSelect by rememberUpdatedState(select)
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val contact = remember { ExploreContact(scope) }
    var held by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var finger by remember { mutableFloatStateOf(0f) }
    var speed by remember { mutableFloatStateOf(0f) }
    var light by remember { mutableFloatStateOf(.5f) }
    val engagement = animateFloatAsState(if (held) 1f else 0f, if (reduced) androidx.compose.animation.core.tween(0) else spring(.86f, 650f), label = "Explore contact")
    val stretch = animateFloatAsState(if (held && dragging && !reduced) speed.coerceIn(0f, 1f) * .16f else 0f,
        if (reduced) androidx.compose.animation.core.tween(0) else spring(1f, 500f), label = "Explore flex")
    val flexing by remember { derivedStateOf { held && dragging && !reduced && speed > .001f } }
    LaunchedEffect(flexing) {
        if (flexing) {
            var previous = withFrameNanos { it }
            while (speed > .001f) {
                val now = withFrameNanos { it }
                speed *= exp(-((now - previous) / 1e9).coerceIn(0.0, .05) / .035).toFloat()
                previous = now
            }
            speed = 0f
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("explore-choices").selectableGroup()
        .pointerInput(enabled) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val slot = size.width / 3f
                val first = (down.position.x / slot).toInt().coerceIn(0, 2)
                finger = first.toFloat(); contact.press(finger, engagement.value > .01f, reduced); held = true
                var crossed = first
                val tracker = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
                var lastMove = down.uptimeMillis
                val grab = down.position.x - (first + .5f) * slot
                var selected = first
                var releaseVelocity = 0f
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (event.changes.count { it.pressed } > 1) break
                        val delta = change.position - down.position
                        if (change.isConsumed) break
                        if (!change.pressed) {
                            if (dragging) {
                                change.consume()
                                val velocity = if (change.uptimeMillis - lastMove < 60) tracker.calculateVelocity().x / slot else 0f
                                val projected = finger + (velocity * .12f).coerceIn(-.5f, .5f)
                                selected = projected.roundToInt().coerceIn(0, 2)
                                releaseVelocity = velocity
                                if (change.position.y in -48.dp.toPx()..(size.height + 48.dp.toPx())) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    currentSelect(routes[selected])
                                }
                            }
                            break
                        }
                        if (!dragging && abs(delta.y) > viewConfiguration.touchSlop && abs(delta.y) > abs(delta.x)) break
                        if (abs(delta.x) > viewConfiguration.touchSlop) dragging = true
                        if (dragging) {
                            if (change.position != change.previousPosition) lastMove = change.uptimeMillis
                            tracker.addPosition(change.uptimeMillis, change.position)
                            finger = ((change.position.x - grab) / slot - .5f).coerceIn(0f, 2f)
                            speed = abs(tracker.calculateVelocity().x / slot) / 8f
                            contact.move(finger, tracker.calculateVelocity().x / slot, reduced)
                            light = (change.position.x / size.width).coerceIn(0f, 1f)
                            val next = finger.roundToInt()
                            if (next != crossed) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); crossed = next }
                            change.consume()
                        }
                    }
                } finally { contact.settle(selected.toFloat(), releaseVelocity, reduced); held = false; dragging = false; speed = 0f }
            }
        }) {
        val slot = maxWidth / 3
        val vertical = maxWidth < 340.dp || LocalDensity.current.fontScale > 1.25f
        val height = if (vertical) 68.dp else 52.dp
        val shape = RoundedCornerShape(18.dp)
        Box(Modifier.width(slot).height(height).testTag("explore-contact").graphicsLayer {
            val lift = engagement.value.coerceIn(0f, 1f)
            alpha = lift
            scaleX = if (reduced) 1f else 1f + lift * .07f + stretch.value
            scaleY = if (reduced) 1f else 1f + lift * .20f - stretch.value * .5f
            val extra = slot.toPx() * (scaleX - 1f) / 2f
            translationX = (contact.position * slot.toPx()).coerceIn(extra, slot.toPx() * 2 - extra)
        }.clip(shape).orbitFrost(backdrop, 18.dp, { engagement.value }, { Offset(light, .5f) })
            .background(Brush.linearGradient(listOf(Color(0x805C5866), Color(0x4D302D39))), shape)
            .border(.7.dp, Color(0x558F899C), shape)) {
            Box(Modifier.matchParentSize().graphicsLayer { translationX = (light - .5f) * size.width * .5f }
                .background(Brush.radialGradient(listOf(Color.White.copy(alpha = .07f), Color.Transparent)), shape))
        }
        Row(Modifier.fillMaxWidth()) {
            labels.forEachIndexed { i, label ->
                val selected = route == routes[i] || i == 2 && route in listOf("Heart rate", "Sleep", "Nutrition", "Blood oxygen")
                Box(Modifier.weight(1f).height(height).selectable(selected, enabled = enabled,
                    role = Role.Tab, interactionSource = remember { MutableInteractionSource() }, indication = null,
                    onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); currentSelect(routes[i]) }),
                    contentAlignment = Alignment.Center) {
                    @Composable fun Glyph() = Icon(painterResource(icons[i]), null, tint = readability.foreground(Color(0xFFC8B5EE)), modifier = Modifier.size(22.dp))
                    @Composable fun Label() = Text(label, color = if (selected) Color.White else readability.foreground(Color(0xFFDED9E5)), fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1)
                    if (vertical) Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) { Glyph(); Label() }
                    else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) { Glyph(); Label() }
                }
            }
        }
    }
}
