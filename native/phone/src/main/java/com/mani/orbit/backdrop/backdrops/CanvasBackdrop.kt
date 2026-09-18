/*
 * Vendored from Kyant0/backdrop v2.0.0 (io.github.kyant0:backdrop)
 * https://github.com/Kyant0/backdrop - Copyright 2025 Kyant0, Apache License 2.0
 *
 * Re-vendored into Orbit from the copy in the owner's BitChord app, which had already
 * merged the KMP expect/actual declarations into one Android source set and added the
 * backdrop resolution scale. Orbit's changes: the package is renamed, and the IDE-only
 * @Language("AGSL") annotations are dropped so the sources need no annotations artifact.
 */
package com.mani.orbit.backdrop.backdrops

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Density
import com.mani.orbit.backdrop.Backdrop

@Composable
fun rememberCanvasBackdrop(
    onDraw: DrawScope.() -> Unit
): Backdrop {
    return remember(onDraw) {
        CanvasBackdrop(onDraw)
    }
}

@Immutable
private class CanvasBackdrop(
    val onDraw: DrawScope.() -> Unit
) : Backdrop {

    override val isCoordinatesDependent: Boolean = false

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?
    ) {
        onDraw()
    }
}
