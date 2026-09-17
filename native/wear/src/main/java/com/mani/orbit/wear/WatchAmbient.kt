package com.mani.orbit.wear

import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.graphics.Typeface
import android.os.SystemClock
import android.text.format.DateFormat
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.wear.compose.foundation.*
import kotlinx.coroutines.*
import java.util.Date

internal data class WatchDisplay(val mode: AmbientMode, val wall: Long, val elapsed: Long) {
    val ambient get() = mode as? AmbientMode.Ambient
}

@Composable internal fun WatchEnvironment(content: @Composable (WatchDisplay) -> Unit) {
    val manager = rememberAmbientModeManager()
    val mode = manager.currentAmbientMode
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var clock by remember { mutableStateOf(System.currentTimeMillis() to SystemClock.elapsedRealtime()) }
    fun tick() { clock = System.currentTimeMillis() to SystemClock.elapsedRealtime() }
    LaunchedEffect(mode, lifecycle) {
        tick()
        if (mode is AmbientMode.Interactive) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) { tick(); delay(1000 - SystemClock.elapsedRealtime() % 1000) }
        }
    }
    manager.AmbientTickEffect { tick() }
    val back = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    CompositionLocalProvider(LocalAmbientModeManager provides manager) {
        WatchBackSurface(mode is AmbientMode.Interactive, { back?.onBackPressed() }) {
            content(WatchDisplay(mode, clock.first, clock.second))
        }
    }
}

/** No frozen live metrics, touch actions, animation, or illuminated background while the wrist is down. */
@Composable internal fun WatchAmbientScreen(title: String, status: String, display: WatchDisplay) {
    val mode = requireNotNull(display.ambient)
    val context = LocalContext.current
    val time = DateFormat.getTimeFormat(context).format(Date(display.wall))
    Box(Modifier.fillMaxSize().background(Color.Black).testTag("watch-ambient")
        .semantics { contentDescription = "$time, $title, $status, Raise wrist to view" }) {
        WatchContentViewport(compact = true) {
            // Reserve the full burn-in displacement even on frames that do not shift.
            Box(Modifier.fillMaxSize().padding(4.dp).drawWithCache {
                fun text(value: String, pixels: Float, numerical: Boolean = false): StaticLayout {
                    val paint = TextPaint().apply {
                        isAntiAlias = !mode.isLowBitAmbientSupported
                        color = android.graphics.Color.WHITE
                        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                        textSize = pixels
                        if (numerical) fontFeatureSettings = "tnum"
                    }
                    return StaticLayout.Builder.obtain(value, 0, value.length, paint, size.width.toInt().coerceAtLeast(1))
                        .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false).build()
                }
                // Wear numeral roles stay fixed. Labels wrap and respect the user's text scale.
                val heading = text(title, 12.sp.toPx())
                val clock = text(time, 28.dp.toPx(), numerical = true)
                val state = text(status, 12.sp.toPx())
                val hint = text("Raise wrist to view", 10.sp.toPx())
                val gap = 4.dp.toPx()
                val primaryHeight = heading.height + clock.height + state.height + gap * 2
                // The gesture hint is redundant; remove it before compressing the actual state.
                val showHint = primaryHeight + hint.height + gap <= size.height
                val height = primaryHeight + if (showHint) hint.height + gap else 0f
                onDrawBehind {
                    val shift = if (mode.isBurnInProtectionRequired) ((display.elapsed / 60000) % 5).toInt() else 2
                    val dx = if (mode.isBurnInProtectionRequired) (shift - 2) * 2f else 0f
                    val dy = if (mode.isBurnInProtectionRequired) ((shift * 2 % 5) - 2) * 2f else 0f
                    val canvas = drawContext.canvas.nativeCanvas
                    canvas.save()
                    canvas.translate(dx, (size.height - height) / 2 + dy)
                    fun line(layout: StaticLayout) { layout.draw(canvas); canvas.translate(0f, layout.height + gap) }
                    line(heading); line(clock); line(state)
                    if (showHint) hint.draw(canvas)
                    canvas.restore()
                }
            })
        }
    }
}
