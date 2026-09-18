/*
 * Vendored from Kyant0/backdrop v2.0.0 (io.github.kyant0:backdrop)
 * https://github.com/Kyant0/backdrop - Copyright 2025 Kyant0, Apache License 2.0
 *
 * Re-vendored into Orbit from the copy in the owner's BitChord app, which had already
 * merged the KMP expect/actual declarations into one Android source set and added the
 * backdrop resolution scale. Orbit's changes: the package is renamed, and the IDE-only
 * @Language("AGSL") annotations are dropped so the sources need no annotations artifact.
 */
package com.mani.orbit.backdrop.highlight

import androidx.annotation.FloatRange
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class Highlight(
    val width: Dp = 0.5f.dp,
    val blurRadius: Dp = width / 2f,
    @param:FloatRange(from = 0.0, to = 1.0) val alpha: Float = 1f,
    val style: HighlightStyle = HighlightStyle.Default
) {

    companion object {

        @Stable
        val Default: Highlight = Highlight()

        @Stable
        val Ambient: Highlight = Highlight(style = HighlightStyle.Ambient)

        @Stable
        val Plain: Highlight = Highlight(style = HighlightStyle.Plain)
    }
}
