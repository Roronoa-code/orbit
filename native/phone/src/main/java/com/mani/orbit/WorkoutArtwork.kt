package com.mani.orbit

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag

/** One retained square cover travels from its thumbnail to the physical top of the window. */
@Composable internal fun WorkoutArtwork(music: NativeMusicState, player: WorkoutPlayerMotion) {
    val reduced = LocalOrbitReducedMotion.current
    val visible = animateFloatAsState(if (player.shown) 1f else 0f, tween(if (reduced) 0 else 180), label = "workout artwork presence")
    BoxWithConstraints(Modifier.fillMaxSize().testTag("workout-artwork-plane")) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val crop = remember { Path() }
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = visible.value * player.motion.value.coerceIn(0f, 1f) }
            .background(Brush.verticalGradient(listOf(Color(0xFF302A37), Color(0xFF151119)))))
        Crossfade(music.artwork, Modifier.fillMaxWidth().aspectRatio(1f)
            .graphicsLayer {
                val p = player.motion.value.coerceIn(0f, 1f)
                val start = player.thumb
                val scale = if (start.width > 0) (start.width + (width - start.width) * p) / width else 1f
                transformOrigin = TransformOrigin(0f, 0f)
                scaleX = scale; scaleY = scale
                translationX = start.left * (1f - p); translationY = start.top * (1f - p)
                alpha = if (start.width > 0) visible.value else 0f
            }.drawWithContent {
                val p = player.motion.value.coerceIn(0f, 1f)
                val scale = (player.thumb.width + (width - player.thumb.width) * p) / width
                val radius = 12f * density * (1f - p) / scale.coerceAtLeast(.01f)
                crop.reset(); crop.addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, size.width, size.height, radius, radius))
                clipPath(crop) { this@drawWithContent.drawContent() }
            }, tween(if (reduced) 0 else 400), label = "shared artwork") { bitmap ->
            val image = remember(bitmap) { bitmap?.asImageBitmap() }
            if (image != null) Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = visible.value * player.motion.value.coerceIn(0f, 1f) }) {
            // Feather the square cover into the dark canvas; no hard status-bar edge or timer box.
            drawRect(Brush.verticalGradient(0f to Color.Black.copy(alpha = .28f), .3f to Color.Transparent,
                .68f to Color(0x00151119), 1f to Color(0xFF151119), endY = width))
            if (height > width) drawRect(Color(0xFF151119), topLeft = Offset(0f, width), size = androidx.compose.ui.geometry.Size(width, height - width))
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .24f))))
        }
    }
}
