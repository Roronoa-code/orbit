package com.mani.orbit

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.*

/** Drawing coordinates only. Missing samples and breaks stay as separate strokes. */
internal fun workoutPlot(points: List<WorkoutPoint>, mode: Int, elapsed: Long): List<Offset?> {
    if (points.isEmpty()) return emptyList()
    if (mode == 0) {
        val first = points.first()
        val xy = points.map { p -> Offset((((p.lon - first.lon + 540) % 360 - 180) * cos(first.lat * PI / 180)).toFloat(), -(p.lat - first.lat).toFloat()) }
        val minX = xy.minOf { it.x }; val minY = xy.minOf { it.y }
        val dx = xy.maxOf { it.x } - minX; val dy = xy.maxOf { it.y } - minY
        val scale = min(264 / max(dx, .00045f), 142 / max(dy, .00045f))
        return xy.flatMapIndexed { i, p ->
            (if (points[i].breakBefore) listOf(null) else emptyList()) + Offset(160 + (p.x - minX - dx / 2) * scale, 96 + (p.y - minY - dy / 2) * scale)
        }
    }
    val values = points.map { if (mode == 1) it.speed?.times(3.6) else it.altitude }
    val valid = values.filterNotNull(); if (valid.size < 2) return emptyList()
    val low = if (mode == 1) 0.0 else floor(valid.min()); val high = max(low + 1, valid.max())
    val duration = max(1L, max(elapsed, points.last().elapsed)).toDouble()
    return points.flatMapIndexed { i, p ->
        val value = values[i]
        if (value == null) listOf(null)
        else (if (p.breakBefore) listOf(null) else emptyList()) + Offset((28 + p.elapsed / duration * 270).toFloat(), (153 - (value - low) / (high - low) * 121).toFloat())
    }
}

@Composable
internal fun WorkoutChart(points: List<WorkoutPoint>, mode: Int, elapsed: Long, source: String = "Recorded on this phone") {
    val plot = remember(points, mode) { workoutPlot(points, mode, elapsed) }
    val known = remember(plot) { plot.filterNotNull() }
    val highest = remember(points, mode) { points.mapNotNull { if (mode == 1) it.speed?.times(3.6) else it.altitude }.maxOrNull() }
    val lastTime = remember(points, mode) { max(elapsed, points.lastOrNull()?.elapsed ?: 0) }
    val labels = listOf("Recorded route, north upwards", "Speed in kilometres per hour over active time", "Elevation in metres over active time")
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (mode != 0 && known.size > 1) Text("${workoutNumber(highest, 1)} ${if (mode == 1) "km/h" else "m"}", color = WorkoutMuted, fontSize = 11.sp)
        Box(Modifier.fillMaxWidth().height(202.dp).testTag("workout-record-chart").semantics { contentDescription = labels[mode] }
            .drawWithCache {
                val path = Path(); var connected = false
                val sx = size.width / 320; val sy = size.height / 192
                plot.forEach { point -> if (point == null) connected = false else {
                    if (connected) path.lineTo(point.x * sx, point.y * sy) else path.moveTo(point.x * sx, point.y * sy)
                    connected = true
                } }
                onDrawBehind {
                    if (known.size > 1) {
                        listOf(48f, 96f, 144f).forEach { y -> drawLine(Color.White.copy(alpha = .06f), Offset(20 * sx, y * sy), Offset(300 * sx, y * sy), 1.dp.toPx()) }
                        drawPath(path, WorkoutPurple, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
                        if (mode == 0) {
                            known.first().let { drawCircle(WorkoutWhite, 4.dp.toPx(), Offset(it.x * sx, it.y * sy), style = Stroke(1.5.dp.toPx())) }
                            known.last().let { drawCircle(WorkoutWhite, 4.dp.toPx(), Offset(it.x * sx, it.y * sy)) }
                        }
                    }
                }
            }, contentAlignment = Alignment.Center) {
            if (known.size < 2) Text(if (mode == 0) "Your route appears after a few GPS readings." else "${if (mode == 1) "Speed" else "Elevation"} appears when enough GPS readings are available.",
                color = WorkoutMuted, fontSize = 13.sp, modifier = Modifier.padding(24.dp))
        }
        if (mode != 0 && plot.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("00:00", color = WorkoutMuted, fontSize = 11.sp); Text(workoutClock(lastTime), color = WorkoutMuted, fontSize = 11.sp)
        }
        Text(if (mode == 0) "$source · north upwards · no street map" else "Active time · gaps in GPS are left open",
            fontSize = 11.sp, lineHeight = 16.sp, color = WorkoutMuted)
    }
}
