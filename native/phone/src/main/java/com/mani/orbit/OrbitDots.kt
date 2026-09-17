package com.mani.orbit

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlin.math.min
import kotlin.math.roundToInt

/** Same repeating dot mask as .number in the HTML; the font supplies the numeral outline. */
@Composable
internal fun OrbitDottedText(value: String, modifier: Modifier, color: Color) {
    val density = LocalDensity.current.density
    val brush = remember(density, color) {
        val side = (4 * density).roundToInt().coerceAtLeast(2)
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(bitmap).drawCircle(side / 2f, side / 2f, side * .3125f,
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { this.color = color.toArgb() })
        ShaderBrush(ImageShader(bitmap.asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        BasicText(value, modifier = Modifier.wrapContentHeight(unbounded = true), style = MaterialTheme.typography.bodyLarge.copy(brush = brush,
            fontSize = 60.sp, lineHeight = 67.sp, fontWeight = FontWeight(650), letterSpacing = (-2.8).sp),
            maxLines = 1, autoSize = TextAutoSize.StepBased(32.sp, 60.sp, 1.sp))
    }
}

// Orbit's existing hero-dots.js alphabet, rendered directly by Android's canvas.
private val DotGlyphs = mapOf(
    '0' to listOf("01110", "11011", "11011", "11011", "11011", "11011", "01110"),
    '1' to listOf("01100", "11100", "01100", "01100", "01100", "01100", "11110"),
    '2' to listOf("01110", "11011", "00011", "00110", "01100", "11000", "11111"),
    '3' to listOf("11110", "00011", "00011", "01110", "00011", "00011", "11110"),
    '4' to listOf("00011", "00111", "01111", "11011", "11111", "00011", "00011"),
    '5' to listOf("11111", "11000", "11000", "11110", "00011", "00011", "11110"),
    '6' to listOf("01110", "11000", "11000", "11110", "11011", "11011", "01110"),
    '7' to listOf("11111", "00011", "00110", "00110", "01100", "01100", "01100"),
    '8' to listOf("01110", "11011", "11011", "01110", "11011", "11011", "01110"),
    '9' to listOf("01110", "11011", "11011", "01111", "00011", "00011", "01110"),
    ':' to listOf("0", "1", "1", "0", "1", "1", "0"),
    '.' to listOf("0", "0", "0", "0", "0", "1", "1"),
    ',' to listOf("00", "00", "00", "00", "00", "01", "10"),
    '-' to listOf("000", "000", "000", "111", "000", "000", "000"),
)

@Composable
internal fun OrbitDotNumber(value: String, modifier: Modifier, color: Color) {
    val (points, width) = remember(value) {
        val dots = mutableListOf<Offset>()
        var x = 0
        for (char in value.replace('—', '-')) {
            val rows = DotGlyphs[char] ?: continue
            rows.forEachIndexed { y, row -> row.forEachIndexed { column, cell ->
                if (cell == '1') dots += Offset(x + column + .5f, y + .5f)
            } }
            x += rows.first().length + 1
        }
        dots to (x - 1).coerceAtLeast(1)
    }
    Canvas(modifier.semantics { contentDescription = value }) {
        val spacing = min(size.width / width, size.height / 7)
        val left = (size.width - width * spacing) / 2
        val top = (size.height - 7 * spacing) / 2
        points.forEach { drawCircle(color, spacing * .32f, Offset(left + it.x * spacing, top + it.y * spacing)) }
    }
}
