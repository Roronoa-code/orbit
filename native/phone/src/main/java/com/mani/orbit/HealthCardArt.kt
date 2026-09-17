package com.mani.orbit

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.graphics.vector.PathParser
import kotlin.math.*

/** The approved six illustrations, drawn natively and cached independently of live readings. */
@Composable
internal fun HealthCardArt(id: HealthCard, progress: Float?, modifier: Modifier) {
    val paths = remember(id) {
        val data = when (id) {
            HealthCard.Sleep -> listOf("M52 13a28 28 0 1 0 14 45A29 29 0 0 1 52 13Z", "M62 12v10m-5-5h10M70 32v6m-3-3h6")
            HealthCard.Heart -> listOf("M40 65C29 56 12 44 12 28a15 15 0 0 1 28-7 15 15 0 0 1 28 7c0 16-17 28-28 37Z", "M22 28c0-5 4-8 8-7")
            HealthCard.Body -> listOf("M18 38v17c0 8 10 13 22 13h27V51H40", "M43 54v7m8-7v4m8-4v7M20 33l6 2m-3-12 5 4m5-9 2 5m13-5-2 5m12 0-5 4")
            HealthCard.Intake -> listOf("M14 39h52c-2 16-11 24-26 24S16 55 14 39Z", "M11 38h58M29 67h22", "M40 32c-15-1-16-16-16-16s17-1 16 16Zm3-4c0-17 16-18 16-18s1 16-16 18Z")
            HealthCard.Oxygen -> listOf("M50 51C51 45 62 45 60 52L51 61H62")
            else -> emptyList()
        }
        data.map { PathParser().parsePathString(it).toPath() }
    }
    Canvas(modifier) {
        scale(size.width / 80f, size.height / 80f, Offset.Zero) {
            val lavender = Color(0xFFBEA4E7)
            val line = Stroke(2f, cap = StrokeCap.Round)
            when (id) {
                HealthCard.Sleep -> {
                    drawPath(paths[0], Color(0xFFBEA2ED)); drawPath(paths[1], lavender, style = line)
                }
                HealthCard.Steps -> {
                    repeat(32) { i ->
                        val angle = i * PI / 16 - PI / 2
                        drawCircle(lavender.copy(alpha = if (progress != null && i / 32f < progress) 1f else .16f), 2.1f,
                            Offset(40 + cos(angle).toFloat() * 31, 40 + sin(angle).toFloat() * 31))
                    }
                    rotate(-18f, Offset(40f, 40f)) {
                        drawOval(lavender, Offset(29f, 25f), Size(8f, 16f)); drawCircle(lavender, 3.5f, Offset(33f, 46f))
                        drawOval(lavender, Offset(42f, 36f), Size(8f, 16f)); drawCircle(lavender, 3.5f, Offset(46f, 57f))
                    }
                }
                HealthCard.Heart -> {
                    drawPath(paths[0], Color(0xFFC7A2EC)); drawPath(paths[1], Color(0xFFF1E7FF), style = Stroke(3f, cap = StrokeCap.Round))
                }
                HealthCard.Body -> rotate(-12f, Offset(40f, 40f)) {
                    drawPath(paths[0], lavender.copy(alpha = .58f))
                    drawOval(lavender, Offset(15f, 17f), Size(50f, 38f))
                    drawOval(Color(0xFF211D2A), Offset(27f, 26f), Size(26f, 16f))
                    drawPath(paths[1], Color(0xFFF4EBFF), style = line)
                }
                HealthCard.Intake -> {
                    drawPath(paths[0], lavender); drawPath(paths[1], lavender, style = Stroke(4f, cap = StrokeCap.Round))
                    drawPath(paths[2], lavender.copy(alpha = .65f))
                }
                HealthCard.Oxygen -> {
                    drawCircle(lavender.copy(alpha = .16f), 24f, Offset(40f, 43f))
                    drawCircle(lavender.copy(alpha = .65f), 7f, Offset(64f, 16f)); drawCircle(lavender, 4f, Offset(17f, 18f))
                    drawOval(lavender, Offset(25f, 29f), Size(21f, 27f), style = Stroke(2.7f))
                    drawPath(paths[0], lavender, style = line)
                }
            }
        }
    }
}

@Composable
internal fun HealthResizeIcon(wide: Boolean, modifier: Modifier) {
    val path = remember(wide) { PathParser().parsePathString(if (wide)
        "M4 9h5V4m6 0v5h5M4 15h5v5m6 0v-5h5" else "M9 4H4v5m16 0V4h-5M4 15v5h5m6 0h5v-5").toPath() }
    Canvas(modifier) { scale(size.width / 24, size.height / 24, Offset.Zero) {
        drawPath(path, Color(0xFFCBB4E7), style = Stroke(1.5f, cap = StrokeCap.Round))
    } }
}
