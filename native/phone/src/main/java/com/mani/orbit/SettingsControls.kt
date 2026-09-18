package com.mani.orbit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val SettingsInk = Color(0xFFF2EDF8)
internal val SettingsMuted = Color(0xFFBDB7C9)
internal val SettingsPurple = Color(0xFFBFA7F2)

/** Eight editable digits; separators are presentation, so caret movement remains native. */
internal val BirthDateMask = VisualTransformation { text ->
    val digits = text.text
    val shown = digits.take(2) + (if (digits.length > 2) "/" + digits.drop(2).take(2) else "") +
        (if (digits.length > 4) "/" + digits.drop(4) else "")
    TransformedText(AnnotatedString(shown), object : OffsetMapping {
        override fun originalToTransformed(offset: Int) = offset + (if (offset > 2) 1 else 0) + (if (offset > 4) 1 else 0)
        override fun transformedToOriginal(offset: Int) = (offset - (if (offset > 2) 1 else 0) - (if (offset > 5) 1 else 0)).coerceIn(0, digits.length)
    })
}

@Composable
internal fun SettingsInput(value: String, change: (String) -> Unit, label: String, modifier: Modifier = Modifier,
    enabled: Boolean = true, placeholder: String = "—", type: KeyboardType = KeyboardType.Text,
    right: Boolean = false, done: Boolean = false, mask: VisualTransformation = VisualTransformation.None) {
    var focused by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    CompositionLocalProvider(LocalTextSelectionColors provides TextSelectionColors(Color.Transparent, Color.Transparent)) {
        BasicTextField(value, { if (it.length <= 80) change(it) }, enabled = enabled, singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = SettingsInk, fontSize = 16.sp, textAlign = if (right) TextAlign.End else TextAlign.Start),
            cursorBrush = SolidColor(SettingsPurple), visualTransformation = mask,
            keyboardOptions = KeyboardOptions(keyboardType = type, imeAction = if (done) ImeAction.Done else ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Next) }, onDone = { focus.clearFocus() }),
            modifier = modifier.heightIn(min = 44.dp).onFocusChanged { focused = it.isFocused }
                .semantics { contentDescription = label }.testTag("settings-input-$label"),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (focused) SettingsPurple.copy(alpha = .045f) else Color.Transparent)
                    .padding(horizontal = 8.dp, vertical = 12.dp), contentAlignment = if (right) Alignment.CenterEnd else Alignment.CenterStart) {
                    if (value.isEmpty()) Text(placeholder, color = SettingsMuted.copy(alpha = .65f), fontSize = 14.sp)
                    inner()
                }
            })
    }
}

@Composable
internal fun SettingsAction(text: String, enabled: Boolean = true, primary: Boolean = false, action: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val haptic = LocalHapticFeedback.current
    Box(Modifier.heightIn(min = if (primary) 48.dp else 44.dp).graphicsLayer { alpha = if (enabled) 1f else .4f }
        .orbitControl(24.dp, interaction, enabled,
            if (primary) (if (focused) Color(0xFFD2BCFC) else SettingsPurple) else if (focused) ControlSelectedFill else ControlFill)
        .clickable(enabled = enabled, interactionSource = interaction, indication = null) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); action()
        }.semantics { role = Role.Button }.padding(horizontal = if (primary) 22.dp else 18.dp, vertical = 11.dp), contentAlignment = Alignment.Center) {
        Text(text, color = if (primary) Color(0xFF151019) else SettingsInk, fontSize = 14.sp)
    }
}

@Composable
internal fun SettingsSwitch(label: String, checked: Boolean, enabled: Boolean = true, description: String? = null, change: (Boolean) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val haptic = LocalHapticFeedback.current
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).toggleable(checked, enabled = enabled, role = Role.Switch,
        interactionSource = interaction, indication = null) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); change(it) },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(label, color = SettingsInk.copy(alpha = if (enabled) 1f else .4f), fontSize = 13.sp, lineHeight = 20.sp)
            if (description != null) Text(description, color = Color(0xFFB9B2C5), fontSize = 12.sp, lineHeight = 18.sp)
        }
        Switch(checked, onCheckedChange = null, enabled = enabled,
            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = SettingsPurple,
                uncheckedThumbColor = Color(0xFFD5CFDF), uncheckedTrackColor = Color(0xFF242129), uncheckedBorderColor = Color.Transparent))
    }
}

@Composable
internal fun SettingsMessage(message: String?, error: Boolean = true) {
    if (message != null) Text(message, fontSize = 12.sp, lineHeight = 18.sp,
        color = if (error) Color(0xFFE0A5B2) else SettingsPurple,
        modifier = Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite })
}
