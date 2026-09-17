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
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt

/** One retained selection surface; taps, holds and horizontal drags all retarget its position. */
@Composable
internal fun MeasurementSelector(labels: List<String>, selected: Int, name: String, select: (Int) -> Unit) {
    val reduced = LocalOrbitReducedMotion.current
    val haptic = LocalHapticFeedback.current
    val currentSelect by rememberUpdatedState(select)
    val currentSelection by rememberUpdatedState(selected)
    var engaged by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var finger by remember { mutableFloatStateOf(selected.toFloat()) }
    val position = animateFloatAsState(if (dragging) finger else selected.toFloat(),
        if (reduced) androidx.compose.animation.core.tween(0) else spring(dampingRatio = .88f, stiffness = if (dragging) 1400f else 480f), label = "selection position")
    val lift = animateFloatAsState(if (engaged && !reduced) 1f else 0f, if (reduced) androidx.compose.animation.core.tween(0) else spring(stiffness = 600f), label = "selection engagement")
    val quiet = name == "measurement-period"
    val shape = RoundedCornerShape(if (quiet) 16.dp else 28.dp)
    BoxWithConstraints(Modifier.fillMaxWidth().testTag(name).selectableGroup()
        .background(if (quiet) Color(0x06FFFFFF) else Color(0xFF0D0D10), shape)
        .then(if (quiet) Modifier else Modifier.border(.7.dp, Color(0xFF29262F), shape))
        .pointerInput(labels) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                engaged = true
                var crossed = currentSelection
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            if (dragging && !change.isConsumed) { change.consume(); currentSelect(finger.roundToInt().coerceIn(labels.indices)) }
                            break
                        }
                        val delta = change.position - down.position
                        if (!dragging && (change.isConsumed || abs(delta.y) > viewConfiguration.touchSlop && abs(delta.y) > abs(delta.x))) break
                        if (abs(delta.x) > viewConfiguration.touchSlop) dragging = true
                        if (dragging) {
                            val inset = 4.dp.toPx()
                            val slot = (size.width - inset * 2) / labels.size
                            finger = ((change.position.x - inset) / slot - .5f).coerceIn(0f, labels.lastIndex.toFloat())
                            val next = finger.roundToInt()
                            if (next != crossed) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); crossed = next }
                            change.consume()
                        }
                    }
                } finally { engaged = false; dragging = false }
            }
        }.padding(4.dp)) {
        val slot = maxWidth / labels.size
        Box(Modifier.matchParentSize()) {
        Box(Modifier.width(slot).fillMaxHeight().testTag("$name-indicator").graphicsLayer {
            // Track the finger directly; the retained spring only settles after release.
            translationX = (if (dragging) finger else position.value) * slot.toPx()
            scaleX = 1f + lift.value * .018f
            scaleY = 1f + lift.value * .025f
        }.background(if (quiet) Color(0x20C1A8ED) else Color(0xFF08080A), RoundedCornerShape(if (quiet) 13.dp else 24.dp))
            .then(if (quiet) Modifier else Modifier.background(Brush.verticalGradient(listOf(
                Color.White.copy(alpha = lift.value * .12f), Color.White.copy(alpha = lift.value * .025f))), RoundedCornerShape(24.dp))))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            labels.forEachIndexed { index, label ->
                Box(Modifier.weight(1f).heightIn(min = if (quiet) 40.dp else 48.dp).selectable(selected == index,
                    interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Tab,
                    onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); currentSelect(index) })
                    .padding(horizontal = 3.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                    BasicText(label, style = MaterialTheme.typography.bodySmall.copy(color = if (selected == index) Color(0xFFF5F1FC) else Color(0xFFB4ADBE),
                        fontSize = 12.sp, textAlign = TextAlign.Center), maxLines = if (' ' in label) 2 else 1,
                        autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = 12.sp, stepSize = .5.sp))
                }
            }
        }
    }
}
