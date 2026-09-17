package com.mani.orbit

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.*

@Composable
internal fun SleepChart(day: SleepDay, selection: State<Long?>, inspect: (Long?) -> Unit) {
    val labelStyle = TextStyle(fontSize = 12.sp, color = Color(0xFFB6B3BC), fontFamily = MaterialTheme.typography.bodySmall.fontFamily)
    val tickStyle = labelStyle.copy(fontSize = 11.sp, color = Color(0xFF939199))
    val measure = rememberTextMeasurer()
    val font = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val laneHeight = (43 * font).dp
    val currentInspect by rememberUpdatedState(inspect)
    Box(Modifier.fillMaxWidth().height(laneHeight * day.lanes.size + (28 * font).dp).testTag("sleep-chart")
        .semantics {
            contentDescription = "Sleep stages in five-minute blocks. Slide to inspect original recorded timings."
            val at = (selection.value ?: day.start!!).coerceIn(day.start!!, day.end!! - 1)
            val interval = day.locate(at)!!
            stateDescription = "${sleepStageLabel(interval.stage)}, ${healthTime(interval.start)} to ${healthTime(interval.end)}, ${sleepDuration((interval.end - interval.start) / 60000.0)}"
            val span = ((day.end!! - day.start!!) / 60000f).coerceAtLeast(.001f)
            progressBarRangeInfo = ProgressBarRangeInfo((at - day.start!!) / 60000f, 0f..span, 0)
            setProgress { minute ->
                if (!minute.isFinite()) false else { currentInspect(day.start!! + (minute.coerceIn(0f, span - .001f) * 60000).toLong()); true }
            }
        }.pointerInput(day) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val original = selection.value
                var owned = false; var completed = false
                fun select(x: Float) {
                    val inset = 8.dp.toPx()
                    currentInspect(day.at((x - inset) / (size.width - inset * 2)))
                }
                select(down.position.x)
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.isConsumed || event.changes.count { it.pressed } > 1) break
                        val delta = change.position - down.position
                        if (!change.pressed) { completed = true; change.consume(); select(change.position.x); break }
                        if (!owned && abs(delta.y) > viewConfiguration.touchSlop && abs(delta.y) >= abs(delta.x)) break
                        if (abs(delta.x) > viewConfiguration.touchSlop) owned = true
                        if (owned) { change.consume(); select(change.position.x) }
                    }
                } finally { if (!completed) currentInspect(original) }
            }
        }.drawWithCache {
            val inset = 8.dp.toPx(); val width = size.width - inset * 2; val lane = laneHeight.toPx()
            val labels = day.lanes.map { measure.measure(sleepStageLabel(it), labelStyle) }
            val ticks = day.ticks.map { it to measure.measure(healthTime(it), tickStyle) }
            val bars = day.blocks.filter { it.stage != "unrecorded" }.map { row ->
                val left = inset + day.fraction(row.start) * width
                val right = inset + day.fraction(row.end) * width
                Triple(Offset(left, day.lanes.indexOf(row.stage) * lane + lane - 24.dp.toPx()), Size(right - left, 16.dp.toPx()), sleepStageColor(row.stage))
            }
            val dash = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx()))
            onDrawBehind {
                ticks.forEach { (at, label) ->
                    val x = inset + day.fraction(at) * width
                    drawLine(Color.White.copy(alpha = .06f), Offset(x, 0f), Offset(x, lane * day.lanes.size), .6.dp.toPx(), pathEffect = dash)
                    drawText(label, topLeft = Offset((x - label.size.width / 2).coerceIn(inset, max(inset, size.width - inset - label.size.width)), lane * day.lanes.size + 6.dp.toPx()))
                }
                labels.forEachIndexed { i, label ->
                    drawText(label, topLeft = Offset(inset, i * lane))
                    drawLine(Color.White.copy(alpha = .07f), Offset(inset, (i + 1) * lane), Offset(size.width - inset, (i + 1) * lane), .6.dp.toPx())
                }
                bars.forEach { (position, extent, color) ->
                    val radius = min(3.dp.toPx(), extent.width / 2)
                    drawRoundRect(color, position, extent, CornerRadius(radius, radius))
                }
            }
        })
}
