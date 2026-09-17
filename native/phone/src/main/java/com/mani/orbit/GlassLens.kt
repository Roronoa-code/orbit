package com.mani.orbit

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asComposeRenderEffect

/** One compiled optical program per retained surface; foreground never enters this filter. */
internal class GlassLens(private val density: Float) {
    val bleed = 28f * density // Existing 3-sigma frost overscan plus the bounded 3.25dp lens excursion.
    private val frost = if (Build.VERSION.SDK_INT >= 31) RenderEffect.createColorFilterEffect(
        ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.5f) }),
        RenderEffect.createBlurEffect(8f * density, 8f * density, Shader.TileMode.CLAMP)) else null
    private val fallback = frost?.asComposeRenderEffect()
    private val shader = if (Build.VERSION.SDK_INT >= 33) try {
        RuntimeShader(PROGRAM).also { it.setFloatUniform("density", density) }
    } catch (unavailable: IllegalArgumentException) {
        android.util.Log.w("OrbitGlass", "Lens unavailable; retaining approved frost", unavailable)
        null
    } else null
    internal val supported: Boolean get() = shader != null
    private val previous = FloatArray(7) { Float.NaN }
    private var cached = fallback

    fun effect(width: Float, height: Float, corner: Float, engagement: Float, focus: Offset, optics: Float = 1f): androidx.compose.ui.graphics.RenderEffect? {
        if (Build.VERSION.SDK_INT < 33 || shader == null || frost == null || width <= 0f || height <= 0f) return fallback
        val optical = optics.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        if (optical == 0f) return fallback
        val radius = corner.coerceIn(0f, minOf(width, height) / 2f)
        val contact = engagement.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        val x = focus.x.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: .5f
        val y = focus.y.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: .5f
        if (previous[0] != width || previous[1] != height || previous[2] != radius ||
            previous[3] != contact || previous[4] != x || previous[5] != y || previous[6] != optical) {
            previous[0] = width; previous[1] = height; previous[2] = radius
            previous[3] = contact; previous[4] = x; previous[5] = y; previous[6] = optical
            shader.setFloatUniform("bounds", width, height, bleed, radius)
            shader.setFloatUniform("contact", contact, x * width, y * height)
            shader.setFloatUniform("optics", optical)
            // RenderEffect snapshots shader uniforms. Rebuild only the small filter when its pose changes;
            // compilation, frost and retained page recording are not recreated here.
            cached = RenderEffect.createChainEffect(RenderEffect.createRuntimeShaderEffect(shader, "backdrop"), frost)
                .asComposeRenderEffect()
        }
        return cached
    }

    private companion object {
        // Orbit's bounded rounded-rectangle model, not a claim about Apple's proprietary optics.
        const val PROGRAM = """
            uniform shader backdrop;
            uniform float density;
            uniform float4 bounds;
            uniform float3 contact;
            uniform float optics;
            half4 main(float2 coordinate) {
                float2 local = coordinate - bounds.z;
                float2 p = local - bounds.xy * 0.5;
                float2 q = abs(p) - (bounds.xy * 0.5 - bounds.w);
                float2 corner = max(q, 0.0);
                float distance = length(corner) + min(max(q.x, q.y), 0.0) - bounds.w;
                float width = min(12.0 * density, min(bounds.x, bounds.y) * 0.25);
                float t = clamp(-distance / max(width, 0.001), 0.0, 1.0);
                float profile = sin(t * 3.14159265);
                profile *= profile;
                float2 normal = length(corner) > 0.001 ? normalize(corner) * sign(p)
                    : (q.x > q.y ? float2(sign(p.x), 0.0) : float2(0.0, sign(p.y)));
                float2 toFinger = (local - contact.yz) / (48.0 * density);
                float proximity = 1.0 / (1.0 + dot(toFinger, toFinger));
                float strength = optics * density * (0.65 + 2.6 * contact.x * proximity);
                // Zero displacement outside the edge band and at both endpoints; no page-wide warp.
                return backdrop.eval(coordinate - normal * (strength * profile));
            }
        """
    }
}
