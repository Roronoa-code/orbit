package com.mani.orbit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import com.mani.orbit.backdrop.backdrops.LayerBackdrop
import com.mani.orbit.backdrop.backdrops.layerBackdrop
import com.mani.orbit.backdrop.backdrops.rememberLayerBackdrop

/** The page colour the recording is laid on, so no glass surface ever samples transparency. */
private val PageFloor = Color(0xFF0A0A0C)

/**
 * The page surface every in-page glass panel samples: the app background and, on Workouts, the
 * album artwork. A card cannot sample the recording it is drawn into, so this is the layer beneath
 * the route's own content rather than the page layer the floating bar uses.
 */
internal val LocalPageBackdrop = staticCompositionLocalOf<GlassBackdrop?> { null }

/** The actual retained scene; materials remain outside this capture. */
internal class GlassBackdrop(internal val layer: LayerBackdrop)

/**
 * A page's retained recording.
 *
 * The recording is filled with the page colour before the content is drawn into it. A transparent
 * recording is the trap here: wherever a page paints nothing of its own, a glass surface would have
 * nothing to bend and the sharp screen behind it would read straight through the material.
 */
@Composable internal fun rememberGlassBackdrop(): GlassBackdrop {
    val paint: ContentDrawScope.() -> Unit = remember { { drawRect(PageFloor); drawContent() } }
    val layer = rememberLayerBackdrop(onDraw = paint)
    return remember(layer) { GlassBackdrop(layer) }
}

/** A draw boundary, not another scene: sibling labels must not rerecord an unchanged backdrop. */
internal fun Modifier.recordBackdrop(backdrop: GlassBackdrop): Modifier = layerBackdrop(backdrop.layer)
