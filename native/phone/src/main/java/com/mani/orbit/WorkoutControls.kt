package com.mani.orbit

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val WorkoutWhite = Color(0xFFF5F1FA)
internal val WorkoutMuted = Color(0xFFB9B2C5)
internal val WorkoutPurple = Color(0xFFCBB3F3)
internal val WorkoutSurface = Color(0xFF19181E)

/** Content presses stay within the same quiet surface; navigation glass is a separate retained plane. */
@Composable
internal fun WorkoutButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    background: Color = WorkoutSurface, content: @Composable () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val focused by interactions.collectIsFocusedAsState()
    val reduced = LocalOrbitReducedMotion.current
    val scale = animateFloatAsState(if (pressed && !reduced) .975f else 1f,
        if (reduced) tween(0) else spring(stiffness = 700f, dampingRatio = .85f), label = "workout press")
    val haptic = LocalHapticFeedback.current
    val shape = RoundedCornerShape(20.dp)
    Box(modifier.heightIn(min = 48.dp).graphicsLayer { scaleX = scale.value; scaleY = scale.value; alpha = if (enabled) 1f else .4f }
        .clip(shape).background(background).then(if (focused) Modifier.border(1.dp, WorkoutPurple, shape) else Modifier)
        .clickable(enabled = enabled, role = Role.Button, interactionSource = interactions, indication = null) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick()
        }, contentAlignment = Alignment.Center) { content() }
}

@Composable
internal fun WorkoutIcon(kind: String, modifier: Modifier = Modifier) {
    val id = when (kind) {
        "Walking" -> R.drawable.workout_walking; "Running" -> R.drawable.workout_running
        "Cycling" -> R.drawable.workout_cycling; "Strength" -> R.drawable.workout_strength
        else -> R.drawable.orbit_workouts
    }
    Icon(painterResource(id), null, modifier, tint = WorkoutPurple)
}

@Composable
internal fun WorkoutFacts(facts: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        facts.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                row.forEach { (label, value) -> Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(value, color = WorkoutWhite, fontSize = 23.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium)
                    Text(label, color = WorkoutMuted, fontSize = 12.sp, lineHeight = 17.sp)
                } }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}
