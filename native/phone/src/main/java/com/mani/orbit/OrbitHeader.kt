package com.mani.orbit

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// A page owns its scroll state; the header reads its offset only during drawing.
internal val LocalHeaderScroll = staticCompositionLocalOf<((() -> Float)?) -> Unit> { {} }

@Composable internal fun ObserveHeaderScroll(scroll: ScrollState) {
    val report = LocalHeaderScroll.current
    DisposableEffect(scroll, report) { report { scroll.value.toFloat() }; onDispose { report(null) } }
}

@Composable internal fun ObserveHeaderScroll(scroll: LazyListState) {
    val report = LocalHeaderScroll.current
    DisposableEffect(scroll, report) {
        report { if (scroll.firstVisibleItemIndex > 0) Float.MAX_VALUE else scroll.firstVisibleItemScrollOffset.toFloat() }
        onDispose { report(null) }
    }
}

/** Native title and controls keep their own hit area above the page's feathered scroll edge. */
@Composable
internal fun OrbitHeader(title: String, date: String?, showBack: Boolean, back: () -> Unit,
    chooseDate: () -> Unit, settings: () -> Unit, scene: GlassBackdrop, dateBounds: (androidx.compose.ui.geometry.Rect) -> Unit = {}) {
    val haptic = LocalHapticFeedback.current
    Row(Modifier.fillMaxWidth().heightIn(min = 74.dp).padding(start = 14.dp, end = 14.dp, top = 9.dp, bottom = 8.dp)
        .testTag("orbit-header"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (showBack) HeaderButton("Back", true, back, scene)
        if (date != null) Column(Modifier.weight(1f).heightIn(min = 54.dp)
            // The date chooser grows out of exactly this control and goes back into it.
            .onGloballyPositioned { dateBounds(it.boundsInRoot()) }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); chooseDate()
            }.semantics { contentDescription = "Choose date"; role = Role.Button }, verticalArrangement = Arrangement.Center) {
            HeaderTitle(title)
            Text(date, color = Color(0xFFB9B3C3), fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp))
        } else Box(Modifier.weight(1f)) { HeaderTitle(title) }
        if (date != null) HeaderButton("Settings", false, settings, scene)
    }
}

@Composable private fun HeaderTitle(title: String) {
    Text(title, fontSize = 24.sp, lineHeight = 29.sp, letterSpacing = (-.6).sp, fontWeight = FontWeight(550),
        color = Color(0xFFF2EFF6), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
}

@Composable private fun HeaderButton(label: String, back: Boolean, action: () -> Unit, scene: GlassBackdrop) {
    val readability = LocalGlassReadability.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val reduced = LocalOrbitReducedMotion.current
    val contact = animateFloatAsState(if (pressed) 1f else 0f, orbitEngage(reduced), label = "Header contact")
    // The US press: the glass comes up to meet the finger.
    val lift = animateFloatAsState(if (pressed && !reduced) 1f else 0f,
        if (reduced) androidx.compose.animation.core.tween(0) else if (pressed)
            androidx.compose.animation.core.tween(OrbitPressMillis, easing = OrbitPressEasing) else orbitRelease(),
        label = "Header press")
    var light by remember { mutableStateOf(Offset(.5f, .5f)) }
    val haptic = LocalHapticFeedback.current
    Box(Modifier.size(48.dp).testTag("header-${label.lowercase()}").graphicsLayer {
            scaleX = 1f + (OrbitPressScale - 1f) * lift.value; scaleY = scaleX
            translationY = -OrbitPressLiftDp.dp.toPx() * lift.value
        }.clip(CircleShape).orbitFrost(scene, 24.dp, { contact.value }, { light })
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                light = Offset((down.position.x / size.width).coerceIn(0f, 1f), (down.position.y / size.height).coerceIn(0f, 1f))
                do {
                    val event = awaitPointerEvent()
                    val point = event.changes.firstOrNull { it.id == down.id } ?: break
                    light = Offset((point.position.x / size.width).coerceIn(0f, 1f), (point.position.y / size.height).coerceIn(0f, 1f))
                } while (event.changes.any { it.pressed })
            }
        }.drawWithCache {
            val rim = 1.dp.toPx()
            onDrawWithContent {
                val engagement = contact.value
                if (engagement > 0f) drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = .08f * engagement), Color.Transparent),
                    Offset(size.width * light.x, size.height * light.y), size.width * .8f))
                drawContent()
                drawCircle(Color.White.copy(alpha = .12f + .08f * engagement), radius = (size.minDimension - rim) / 2, style = Stroke(rim))
                if (focused && !pressed) drawCircle(Color(0xFFBFA7F2), radius = size.minDimension / 2 - 3.dp.toPx(), style = Stroke(1.5.dp.toPx()))
            }
        }.clickable(interactionSource = interaction, indication = null) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); action()
        }.semantics { contentDescription = label; role = Role.Button }, contentAlignment = Alignment.Center) {
        if (back) Canvas(Modifier.size(24.dp)) {
            val line = Path().apply { moveTo(size.width * .62f, size.height * .22f); lineTo(size.width * .34f, size.height * .5f); lineTo(size.width * .62f, size.height * .78f) }
            drawPath(line, readability.foreground(Color(0xFFF2EFF6)), style = Stroke(1.7.dp.toPx(), cap = StrokeCap.Round))
        } else Icon(painterResource(R.drawable.orbit_settings), contentDescription = null, tint = readability.foreground(Color(0xFFF2EFF6)), modifier = Modifier.size(24.dp))
    }
}
