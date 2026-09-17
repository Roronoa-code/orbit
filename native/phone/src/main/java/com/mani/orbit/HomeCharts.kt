package com.mani.orbit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.*

internal val HomeAccent = Color(0xFFBFA6F0)
internal val HomeMuted = Color(0xFFB8B8C0)
internal val HomeWhite = Color(0xFFF4F4F7)

@Composable
internal fun HomeChart(title: String, points: List<HomePoint>, metric: HomeMetric, bars: Boolean, enabled: Boolean) {
    var inspected by remember(points) { mutableIntStateOf(if (bars) points.indices.maxByOrNull { points[it].value ?: -1.0 } ?: 0 else points.lastIndex.coerceAtLeast(0)) }
    val selection = points.getOrNull(inspected)
    val haptic = LocalHapticFeedback.current
    val unit = metric.unit
    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, color = HomeWhite, fontSize = 14.sp, lineHeight = 19.sp)
        Text(selection?.let { "${it.label} · ${if (metric == HomeMetric.Sleep) homeDuration(it.value) else homeNumber(it.value)}${if (metric == HomeMetric.Sleep) "" else " $unit"}" } ?: "No shared readings",
            color = HomeMuted, fontSize = 11.sp, lineHeight = 16.sp)
        val maxValue = points.mapNotNull { it.value }.maxOrNull() ?: 0.0
        val increment = when (metric) { HomeMetric.Steps -> 2000.0; HomeMetric.Heart -> 20.0; HomeMetric.Sleep -> 120.0; HomeMetric.Intake -> 500.0 }
        val floor = when (metric) { HomeMetric.Steps -> if (bars) 4000.0 else 12000.0; HomeMetric.Heart -> 120.0; HomeMetric.Sleep -> 240.0; HomeMetric.Intake -> 500.0 }
        val ceiling = max(floor, ceil(maxValue / increment) * increment)
        val last = points.lastIndex.coerceAtLeast(0)
        Row(Modifier.fillMaxWidth()) {
        Column(Modifier.width(28.dp).height(140.dp).padding(top = 2.dp), verticalArrangement = Arrangement.SpaceBetween) {
            listOf(ceiling, ceiling / 2, 0.0).forEach { value ->
                val label = when {
                    metric == HomeMetric.Sleep && value > 0 -> "${homeNumber(value / 60)}h"
                    value >= 1000 -> "${homeNumber(value / 1000, if (value % 1000 == 0.0) 0 else 1)}K"
                    else -> homeNumber(value)
                }
                Text(label, color = HomeMuted, fontSize = 9.sp, lineHeight = 12.sp, maxLines = 1)
            }
        }
        Canvas(Modifier.weight(1f).height(140.dp).testTag(if (bars) "home-movement-chart" else "home-week-chart")
            .semantics {
                contentDescription = title
                stateDescription = selection?.let { "${it.label}, ${homeNumber(it.value)} $unit" } ?: "No readings"
                if (enabled && points.isNotEmpty()) {
                    progressBarRangeInfo = ProgressBarRangeInfo(inspected.toFloat(), 0f..last.toFloat(), (last - 1).coerceAtLeast(0))
                    setProgress { inspected = it.roundToInt().coerceIn(0, last); true }
                }
            }.pointerInput(points, enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var owned = false
                    fun inspect(x: Float) {
                        val index = (if (bars) floor(x / size.width * points.size).toInt() else (x / size.width * last).roundToInt()).coerceIn(0, last)
                        if (index != inspected) { inspected = index; haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                    }
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (change.isConsumed) break
                        val delta = change.position - down.position
                        if (!change.pressed) { if (owned || delta.getDistance() < viewConfiguration.touchSlop) { inspect(change.position.x); change.consume() }; break }
                        if (!owned && abs(delta.y) > viewConfiguration.touchSlop && abs(delta.y) > abs(delta.x)) break
                        if (abs(delta.x) > viewConfiguration.touchSlop) owned = true
                        if (owned) { inspect(change.position.x); change.consume() }
                    }
                }
            }) {
            val top = 10.dp.toPx(); val bottom = size.height - 8.dp.toPx(); val height = bottom - top
            for (fraction in listOf(0f, .5f, 1f)) drawLine(Color.White.copy(alpha = .08f), Offset(0f, top + height * fraction), Offset(size.width, top + height * fraction), .5.dp.toPx())
            if (points.isEmpty()) return@Canvas
            val slots = max(1, if (bars) points.size else last)
            val dx = size.width / slots
            fun position(index: Int, value: Double) = Offset(if (bars) (index + .5f) * dx else index * dx, bottom - (value / ceiling).toFloat() * height)
            if (bars) {
                val width = min(12.dp.toPx(), dx * .48f)
                points.forEachIndexed { index, point -> point.value?.let { value ->
                    val at = position(index, value)
                    drawRoundRect(Brush.verticalGradient(listOf(HomeAccent, HomeAccent.copy(alpha = .08f)), at.y, bottom),
                        Offset(at.x - width / 2, at.y), Size(width, max(.7.dp.toPx(), bottom - at.y)),
                        androidx.compose.ui.geometry.CornerRadius(1.5.dp.toPx()), alpha = if (index == inspected) 1f else .8f)
                } }
            } else {
                // Separate runs preserve missing-day gaps. The monotone curve never overshoots a reading.
                val runs = mutableListOf<MutableList<Offset>>()
                points.forEachIndexed { i, point -> if (point.value == null) { if (runs.lastOrNull()?.isNotEmpty() == true) runs += mutableListOf<Offset>() }
                    else { if (runs.isEmpty()) runs += mutableListOf<Offset>(); runs.last() += position(i, point.value) } }
                for (run in runs.filter { it.isNotEmpty() }) {
                    val path = homeCurve(run)
                    val area = Path().apply { addPath(path); lineTo(run.last().x, bottom); lineTo(run.first().x, bottom); close() }
                    drawPath(area, Brush.verticalGradient(listOf(HomeAccent.copy(alpha = .22f), Color.Transparent), top, bottom))
                    drawPath(path, HomeAccent, style = Stroke(1.7.dp.toPx(), cap = StrokeCap.Round))
                    for (point in run) drawCircle(HomeAccent, 2.3.dp.toPx(), point)
                }
            }
            points.getOrNull(inspected)?.value?.let { value ->
                val at = position(inspected, value)
                if (!bars) { drawCircle(HomeAccent.copy(alpha = .2f), 6.dp.toPx(), at); drawCircle(HomeWhite, 2.5.dp.toPx(), at) }
            }
        }
        }
        Row(Modifier.fillMaxWidth().padding(start = 28.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            val labels = if (points.size <= 7) points else listOfNotNull(points.firstOrNull(), points.getOrNull(points.size / 2), points.lastOrNull())
            labels.forEachIndexed { i, point ->
                val align = if (labels.size == 1 || i == 0) Alignment.Start else if (i == labels.lastIndex) Alignment.End else Alignment.CenterHorizontally
                Column(Modifier.weight(1f), horizontalAlignment = align) {
                    if (points.size <= 7 && point.date != null) Text(point.date.format(DateTimeFormatter.ofPattern("EEE", Locale.UK)),
                        color = HomeMuted, fontSize = 10.sp, lineHeight = 15.sp, maxLines = 1)
                    Text(point.label, color = HomeMuted, fontSize = 9.sp, lineHeight = 14.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, textAlign = if (i == 0) TextAlign.Start else if (i == labels.lastIndex) TextAlign.End else TextAlign.Center)
                }
            }
        }
    }
}

internal fun homeCurve(points: List<Offset>): Path {
    val path = Path()
    if (points.isEmpty()) return path
    path.moveTo(points[0].x, points[0].y)
    if (points.size < 2) return path
    val slopes = FloatArray(points.size - 1) { (points[it + 1].y - points[it].y) / (points[it + 1].x - points[it].x) }
    val tangents = FloatArray(points.size) { i -> when {
        i == 0 -> slopes[0]; i == points.lastIndex -> slopes.last()
        slopes[i - 1] * slopes[i] <= 0 -> 0f
        else -> 2 * slopes[i - 1] * slopes[i] / (slopes[i - 1] + slopes[i])
    } }
    slopes.forEachIndexed { i, slope ->
        if (slope == 0f) { tangents[i] = 0f; tangents[i + 1] = 0f }
        else { val length = hypot(tangents[i] / slope, tangents[i + 1] / slope); if (length > 3) { tangents[i] *= 3 / length; tangents[i + 1] *= 3 / length } }
    }
    slopes.indices.forEach { i -> val a = points[i]; val b = points[i + 1]; val t = (b.x - a.x) / 3
        path.cubicTo(a.x + t, a.y + tangents[i] * t, b.x - t, b.y - tangents[i + 1] * t, b.x, b.y) }
    return path
}
