package com.mani.orbit

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced

/** Coordinates of the draw modifier, including any preceding inset or transform. */
internal class GlassCoordinates {
    private var coordinates: LayoutCoordinates? = null
    private val observed = Matrix()
    private val previous = FloatArray(16) { Float.NaN }
    private var size = androidx.compose.ui.unit.IntSize.Zero
    private var revision by mutableIntStateOf(0)

    fun placed(value: LayoutCoordinates) {
        coordinates = value
        observed.reset()
        value.transformToScreen(observed)
        // Coordinate reads can request another placement pass. Only an actual pose change invalidates drawing.
        if (!previous.contentEquals(observed.values) || size != value.size) {
            observed.values.copyInto(previous); size = value.size; revision++
        }
    }

    fun transformTo(target: GlassCoordinates, matrix: Matrix): Boolean {
        revision; target.revision // Observe geometry in drawing, never composition.
        val source = coordinates ?: return false
        val destination = target.coordinates ?: return false
        if (!source.isAttached || !destination.isAttached) return false
        matrix.reset()
        destination.transformFrom(source, matrix)
        return matrix.values.all { it.isFinite() }
    }
}

/** The actual retained scene; materials remain outside this capture. */
internal class GlassBackdrop(val layer: GraphicsLayer) {
    val coordinates = GlassCoordinates()
}

@Composable internal fun rememberGlassBackdrop(): GlassBackdrop {
    val layer = rememberGraphicsLayer()
    return remember(layer) { GlassBackdrop(layer) }
}

// A draw boundary, not another scene/texture: sibling labels must not rerecord an unchanged backdrop.
internal fun Modifier.recordBackdrop(backdrop: GlassBackdrop): Modifier = graphicsLayer().onPlaced(backdrop.coordinates::placed)
    .drawWithContent { backdrop.layer.record { this@drawWithContent.drawContent() }; drawLayer(backdrop.layer) }
