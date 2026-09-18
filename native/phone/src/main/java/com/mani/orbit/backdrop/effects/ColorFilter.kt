/*
 * Vendored from Kyant0/backdrop v2.0.0 (io.github.kyant0:backdrop)
 * https://github.com/Kyant0/backdrop - Copyright 2025 Kyant0, Apache License 2.0
 *
 * Re-vendored into Orbit from the copy in the owner's BitChord app, which had already
 * merged the KMP expect/actual declarations into one Android source set and added the
 * backdrop resolution scale. Orbit's changes: the package is renamed, and the IDE-only
 * @Language("AGSL") annotations are dropped so the sources need no annotations artifact.
 */
package com.mani.orbit.backdrop.effects

import androidx.annotation.FloatRange
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ColorMatrixColorFilter
import com.mani.orbit.backdrop.BackdropEffectScope
import com.mani.orbit.backdrop.internal.ColorFilterEffect
import com.mani.orbit.backdrop.isRenderEffectSupported

fun BackdropEffectScope.colorFilter(colorFilter: ColorFilter) {
    if (!isRenderEffectSupported()) return

    renderEffect = ColorFilterEffect(renderEffect, colorFilter)
}

fun BackdropEffectScope.opacity(@FloatRange(from = 0.0, to = 1.0) alpha: Float) {
    val colorMatrix = ColorMatrix(
        floatArrayOf(
            1f, 0f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f, 0f,
            0f, 0f, 0f, alpha, 0f
        )
    )
    colorFilter(ColorMatrixColorFilter(colorMatrix))
}

fun BackdropEffectScope.colorControls(
    brightness: Float = 0f,
    contrast: Float = 1f,
    saturation: Float = 1f
) {
    if (brightness == 0f && contrast == 1f && saturation == 1f) {
        return
    }

    colorFilter(colorControlsColorFilter(brightness, contrast, saturation))
}

private val VibrantColorFilter = colorControlsColorFilter(saturation = 1.5f)

fun BackdropEffectScope.vibrancy() {
    colorFilter(VibrantColorFilter)
}

private fun colorControlsColorFilter(
    brightness: Float = 0f,
    contrast: Float = 1f,
    saturation: Float = 1f
): ColorFilter {
    val invSat = 1f - saturation
    val r = 0.213f * invSat
    val g = 0.715f * invSat
    val b = 0.072f * invSat

    val c = contrast
    val t = (0.5f - c * 0.5f + brightness) * 255f
    val s = saturation

    val cr = c * r
    val cg = c * g
    val cb = c * b
    val cs = c * s

    val colorMatrix = ColorMatrix(
        floatArrayOf(
            cr + cs, cg, cb, 0f, t,
            cr, cg + cs, cb, 0f, t,
            cr, cg, cb + cs, 0f, t,
            0f, 0f, 0f, 1f, 0f
        )
    )
    return ColorMatrixColorFilter(colorMatrix)
}
