package com.mani.orbit.wear

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.SwipeToDismissBox
import androidx.wear.compose.foundation.SwipeToDismissValue
import androidx.wear.compose.foundation.rememberSwipeToDismissBoxState
import androidx.wear.compose.foundation.hierarchicalFocusGroup
import androidx.wear.compose.foundation.requestFocusOnHierarchyActive
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerScope
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.PagerDefaults
import kotlin.math.*
import java.util.Locale

/** One native dismiss owner forwards to existing Back handlers; it never issues workout commands. */
@Composable internal fun WatchBackSurface(enabled: Boolean, onBack: () -> Unit, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState()
    val back by rememberUpdatedState(onBack)
    val screen = rememberUpdatedState(content)
    val view = LocalViewConfiguration.current
    val accessibility = LocalContext.current.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
    val dismissView = remember(view) { object : ViewConfiguration by view {
        var fromEdge = false
        override val touchSlop get() = if (fromEdge) view.touchSlop else Float.MAX_VALUE
    } }
    LaunchedEffect(enabled) { if (!enabled) state.snapTo(SwipeToDismissValue.Default) }
    CompositionLocalProvider(LocalViewConfiguration provides dismissView) {
        SwipeToDismissBox(onDismissed = { if (enabled) back() }, state = state, userSwipeEnabled = enabled,
            modifier = Modifier.testTag("watch-back-surface").pointerInput(dismissView, accessibility) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    // Latch ownership at DOWN, not at each page/metric update or motion frame.
                    dismissView.fromEdge = down.position.x <= size.width * .15f && !accessibility.isTouchExplorationEnabled
                }
            }, backgroundScrimColor = Color.Black, contentScrimColor = Color.Black) { background ->
            if (!background) CompositionLocalProvider(LocalViewConfiguration provides view) { WatchScreenContent(screen) }
        }
    }
}

// Keep per-second readings in their own restart scope, outside the native gesture owner's composition.
@Composable private fun WatchScreenContent(content: State<@Composable () -> Unit>) { content.value() }

// Wear 1.6.2's wrapper recreates ViewConfiguration on content updates, cancelling held pointers.
// Use its underlying native pager until that wrapper preserves touch configuration identity.
@Composable internal fun WatchPager(state: PagerState, modifier: Modifier = Modifier, beyondViewportPageCount: Int = 0, content: @Composable PagerScope.(Int) -> Unit) {
    val accessibility = LocalContext.current.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
    val view = LocalViewConfiguration.current
    var cancelled by remember { mutableIntStateOf(0) }
    // Compose 1.10.4's direction detector waits for an unconsumed UP after CANCEL. Reset only its
    // pointer lifetime after a consumed synthetic release; retain page, content and scroll state.
    val configuration = remember(view, cancelled) { object : ViewConfiguration by view {} }
    var allowPaging by remember { mutableStateOf(true) }
    CompositionLocalProvider(LocalViewConfiguration provides configuration) {
        HorizontalPager(state, modifier.pointerInput(accessibility) {
            awaitEachGesture {
                allowPaging = true
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                allowPaging = accessibility.isTouchExplorationEnabled || down.position.x > size.width * .15f
                var event = awaitPointerEvent(PointerEventPass.Initial)
                while (event.changes.any { it.pressed }) event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.any { it.previousPressed && it.isConsumed }) cancelled++
            }
        }, userScrollEnabled = allowPaging, beyondViewportPageCount = beyondViewportPageCount,
            flingBehavior = PagerDefaults.flingBehavior(state, snapAnimationSpec = androidx.wear.compose.foundation.pager.PagerDefaults.SnapAnimationSpec),
            pageContent = content)
    }
}

/** Material's curved indicator is read-only; mirror position without a second scrolling owner. */
@Composable internal fun WatchPageIndicator(pager: PagerState, modifier: Modifier = Modifier) {
    val position = remember(pager.currentPage, pager.currentPageOffsetFraction, pager.pageCount) {
        androidx.wear.compose.foundation.pager.PagerState(pager.currentPage, pager.currentPageOffsetFraction) { pager.pageCount }
    }
    androidx.wear.compose.material3.HorizontalPageIndicator(position, modifier, selectedColor = MaterialTheme.colorScheme.primary)
}

/** Shared full-screen round band; host-owned Tiles/complications use their own bounds. */
@Composable internal fun WatchContentViewport(modifier: Modifier = Modifier, compact: Boolean = false, content: @Composable BoxScope.() -> Unit) {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val round = LocalConfiguration.current.isScreenRound
    val window = LocalWindowInfo.current.containerSize
    val insets = WindowInsets.safeDrawing
    BoxWithConstraints(modifier.fillMaxSize()) {
        // Pixel constraints remain authoritative when display density changes without a resize.
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val left = max(with(density) { 26.dp.toPx() }, insets.getLeft(density, direction).toFloat())
        val right = max(with(density) { 26.dp.toPx() }, insets.getRight(density, direction).toFloat())
        val fullHeight = window.height.toFloat().takeIf { it > 0 } ?: height
        val edge = with(density) { 2.dp.toPx() }
        val radius = min(width, fullHeight) / 2 - edge
        val halfWidth = max(width / 2 - left, width / 2 - right)
        val cap = if (round) fullHeight / 2 - sqrt(max(0f, radius * radius - halfWidth * halfWidth)) else 0f
        val top = max(max(cap, insets.getTop(density).toFloat()), with(density) { (if (compact) 16.dp else 22.dp).toPx() })
        val bottom = max(max(height - (fullHeight - cap), insets.getBottom(density).toFloat()), with(density) { 12.dp.toPx() })
        Box(Modifier.fillMaxSize().padding(
            start = with(density) { left.toDp() }, end = with(density) { right.toDp() },
            top = with(density) { top.toDp() }, bottom = with(density) { bottom.toDp() }).testTag("watch-safe-viewport"), content = content)
    }
}


/** All full-screen Watch routes use this content band. Tiles/complications have their own host bounds. */
@Composable internal fun WorkoutPage(active: Boolean = true, compact: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val scroll = rememberScrollState()
    val focus = remember { FocusRequester() }
    WatchContentViewport(compact = compact) {
        Column(Modifier.fillMaxSize().clipToBounds().hierarchicalFocusGroup(active)
            .requestFocusOnHierarchyActive().rotaryScrollable(RotaryScrollableDefaults.behavior(scroll), focus)
            .verticalScroll(scroll),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 6.dp), content = content)
        val background = MaterialTheme.colorScheme.background
        if (scroll.canScrollBackward) Box(Modifier.fillMaxWidth().height(12.dp).align(Alignment.TopCenter)
            .background(Brush.verticalGradient(listOf(background, Color.Transparent))))
        if (scroll.canScrollForward) Box(Modifier.fillMaxWidth().height(12.dp).align(Alignment.BottomCenter)
            .background(Brush.verticalGradient(listOf(Color.Transparent, background))))
    }
}

/** Wear's large numeral roles stay fixed; labels and small readings retain user text scaling. */
@Composable internal fun WatchNumber(value: String, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    Text(value, modifier, style = MaterialTheme.typography.numeralExtraSmall.copy(
        fontSize = with(density) { 30.dp.toSp() }, lineHeight = with(density) { 34.dp.toSp() },
        fontFeatureSettings = "tnum"), color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
}

/** Pick a layout from the available type geometry, never from the current timer width. */
@Composable internal fun WatchTime(ms: Long, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val style = MaterialTheme.typography.numeralExtraSmall.copy(fontSize = with(density) { 28.dp.toSp() },
        lineHeight = with(density) { 32.dp.toSp() }, fontFeatureSettings = "tnum")
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = watchWorkoutClock(ms) }) {
        val split = measurer.measure("88:88:88", style, softWrap = false).size.width > constraints.maxWidth
        if (split) Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${ms.coerceAtLeast(0) / 3600000}h", style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
            Text("%02d:%02d".format(Locale.UK, ms.coerceAtLeast(0) / 60000 % 60, ms.coerceAtLeast(0) / 1000 % 60),
                style = style, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
        } else Text(watchWorkoutClock(ms), Modifier.fillMaxWidth(), style = style, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
    }
}

/** Missing readings keep the same slots; only available width/type scale chooses rows or a stack. */
@Composable internal fun WatchLiveMetrics(distance: Double?, heart: Int?) {
    val style = MaterialTheme.typography.numeralExtraSmall.copy(fontSize = 18.sp, lineHeight = 22.sp, fontFeatureSettings = "tnum")
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stacked = measurer.measure("888.88", style, softWrap = false).size.width > (constraints.maxWidth - with(density) { 8.dp.toPx() }) / 2
        val distanceText = distance?.let { "%.2f".format(Locale.UK, it / 1000) } ?: "—"
        @Composable fun reading(value: String, unit: String, modifier: Modifier) {
            if (stacked) Row(modifier, horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally)) {
                Text(value, Modifier.weight(1f, fill = false).alignByBaseline(), style = style, textAlign = TextAlign.Center)
                Text(unit, Modifier.alignByBaseline(), fontSize = 10.sp, lineHeight = 12.sp)
            } else Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(value, Modifier.fillMaxWidth(), style = style, textAlign = TextAlign.Center)
                Text(unit, fontSize = 10.sp, lineHeight = 12.sp)
            }
        }
        if (stacked) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            reading(distanceText, "km", Modifier.fillMaxWidth().testTag("live-distance"))
            reading(heart?.toString() ?: "—", "bpm", Modifier.fillMaxWidth().testTag("live-pulse"))
        } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            reading(distanceText, "km", Modifier.weight(1f).testTag("live-distance"))
            reading(heart?.toString() ?: "—", "bpm", Modifier.weight(1f).testTag("live-pulse"))
        }
    }
}
