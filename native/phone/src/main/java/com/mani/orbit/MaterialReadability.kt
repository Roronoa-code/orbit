package com.mani.orbit

import android.app.UiModeManager
import android.os.Build
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.json.JSONObject

internal data class MaterialReadability(val reduceTransparency: Boolean = false, val increaseContrast: Boolean = false) {
    companion object {
        const val KEY = "orbit-material-readability-v1"
        fun document(raw: String?): JSONObject {
            if (raw == null) return JSONObject().put("version", 1).put("reduceTransparency", false).put("increaseContrast", false)
            require(raw.length <= 4096)
            return JSONObject(raw).also {
                require(it.get("version") is Int && it.getInt("version") == 1)
                require(it.get("reduceTransparency") is Boolean && it.get("increaseContrast") is Boolean)
            }
        }
        fun read(raw: String?): MaterialReadability = document(raw).let {
            MaterialReadability(it.getBoolean("reduceTransparency"), it.getBoolean("increaseContrast"))
        }
    }
}

internal data class GlassReadability(val opaque: Boolean = false, val contrast: Float = 0f) {
    init { require(contrast.isFinite() && contrast in 0f..1f) }
    fun foreground(color: Color): Color = lerp(color, Color.White.copy(alpha = color.alpha), contrast * .8f)
    companion object {
        fun resolve(preference: MaterialReadability?, systemContrast: Float, highContrastText: Boolean): GlassReadability =
            GlassReadability(preference?.reduceTransparency ?: true,
                if (preference?.increaseContrast == true || highContrastText) 1f
                else if (systemContrast.isFinite()) systemContrast.coerceIn(0f, 1f) else 0f)
    }
}

internal val LocalGlassReadability = compositionLocalOf { GlassReadability() }

/** Public platform signals only. Android has no matching public reduce-transparency signal. */
@Composable
internal fun rememberGlassReadability(preference: MaterialReadability?): GlassReadability {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val ui = remember(context) { context.getSystemService(UiModeManager::class.java) }
    val accessibility = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    fun readContrast() = if (Build.VERSION.SDK_INT >= 34) ui?.contrast ?: 0f else 0f
    fun readHighText() = Build.VERSION.SDK_INT >= 36 && accessibility?.isHighContrastTextEnabled == true
    var contrast by remember(context) { mutableFloatStateOf(readContrast()) }
    var highText by remember(context) { mutableStateOf(readHighText()) }
    DisposableEffect(context, lifecycle) {
        val contrastListener = if (Build.VERSION.SDK_INT >= 34 && ui != null) {
            UiModeManager.ContrastChangeListener { contrast = it }.also { ui.addContrastChangeListener(context.mainExecutor, it) }
        } else null
        val textListener = if (Build.VERSION.SDK_INT >= 36 && accessibility != null) {
            AccessibilityManager.HighContrastTextStateChangeListener { highText = it }.also {
                accessibility.addHighContrastTextStateChangeListener(context.mainExecutor, it)
            }
        } else null
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) { contrast = readContrast(); highText = readHighText() }
        }
        lifecycle.addObserver(observer)
        contrast = readContrast(); highText = readHighText()
        onDispose {
            lifecycle.removeObserver(observer)
            if (Build.VERSION.SDK_INT >= 34 && contrastListener != null) ui?.removeContrastChangeListener(contrastListener)
            if (Build.VERSION.SDK_INT >= 36 && textListener != null) accessibility?.removeHighContrastTextStateChangeListener(textListener)
        }
    }
    return GlassReadability.resolve(preference, contrast, highText)
}
