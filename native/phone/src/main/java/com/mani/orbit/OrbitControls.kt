package com.mani.orbit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val ActionInk = Color(0xFFD2C1FF)

/**
 * A text control on the material: no fill of its own, and the same press as every other control, so
 * a small action answers a finger the way the navigation bar does rather than with a stock ripple.
 */
@Composable
internal fun OrbitTextAction(label: String, modifier: Modifier = Modifier, enabled: Boolean = true,
    ink: Color = ActionInk, size: TextUnit = 14.sp, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val haptic = LocalHapticFeedback.current
    val readability = LocalGlassReadability.current
    Box(modifier.heightIn(min = 44.dp).orbitControl(18.dp, interaction, enabled, Color.Transparent)
        .clickable(enabled = enabled, interactionSource = interaction, indication = null, role = Role.Button) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick()
        }.padding(horizontal = 12.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(label, color = readability.foreground(ink).copy(alpha = if (enabled) 1f else .38f), fontSize = size, lineHeight = size * 1.35f)
    }
}

/** A card: the one glass every panel in the app is, rather than a stock surface of its own. */
@Composable
internal fun OrbitCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = remember { RoundedCornerShape(28.dp) }
    Column(modifier.clip(shape).orbitPanel(28.dp), content = content)
}
