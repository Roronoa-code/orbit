package com.mani.orbit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToInt

internal fun sleepStageColor(stage: String): Color = when (stage) {
    "awake" -> Color(0xFFFF8098); "rem" -> Color(0xFF63C8ED); "light" -> Color(0xFF488BFA)
    "deep" -> Color(0xFF6654DB); "sleeping" -> Color(0xFF91A2C3); "unknown" -> Color(0xFF92909B)
    else -> Color.Transparent
}

@Composable
internal fun HealthSleepRibbons(nights: List<SleepNight>) {
    val windows = remember(nights) {
        val all = SleepTimeline.merge(nights)
        val bouts = mutableListOf<MutableList<SleepInterval>>()
        for (interval in all) {
            if (interval.stage == "unrecorded" && interval.end - interval.start >= 1800000) {
                if (bouts.lastOrNull()?.isNotEmpty() == true) bouts += mutableListOf<SleepInterval>()
            } else {
                if (bouts.isEmpty()) bouts += mutableListOf<SleepInterval>()
                bouts.last() += interval
            }
        }
        bouts.filter { it.isNotEmpty() }.map(SleepTimeline::blocks)
    }
    if (windows.isEmpty()) return
    // Each actual sleep window gets its own ribbon; the gap between naps is never painted as sleep.
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        windows.forEach { rows -> Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(healthTime(rows.first().start), color = HealthSecondary, fontSize = 11.sp)
                Text(healthTime(rows.last().end), color = HealthSecondary, fontSize = 11.sp)
            }
            Canvas(Modifier.padding(top = 8.dp).fillMaxWidth().height(10.dp).semantics {
                contentDescription = "Sleep stages from ${healthTime(rows.first().start)} to ${healthTime(rows.last().end)}. Five-minute blocks; totals use original readings."
            }) {
                val start = rows.first().start; val span = (rows.last().end - start).toFloat()
                for (row in rows) drawRect(sleepStageColor(row.stage),
                    Offset((row.start - start) / span * size.width, 0f), Size((row.end - row.start) / span * size.width, size.height))
            }
        } }
    }
}

@Composable
internal fun HealthOxygenChart(state: HealthScreenState) {
    val dates = remember(state.day.date) { (6 downTo 0).map { state.day.date.minusDays(it.toLong()) } }
    val values = dates.map { date -> if (date == state.day.date) state.day.oxygen else state.days[date]?.oxygen }
    var selected by rememberSaveable(state.day.date) { mutableIntStateOf(6) }
    val haptic = LocalHapticFeedback.current
    fun select(index: Int) { val next = index.coerceIn(0, 6); if (next != selected) { selected = next; haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) } }
    Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp).testTag("health-oxygen-week")) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(dates[selected].format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)), color = HealthSecondary, fontSize = 12.sp)
            Text(values[selected]?.let { "${it.healthNumber()}%" } ?: "No reading", color = Color(0xFFE7DDF3), fontSize = 15.sp)
        }
        val low = minOf(90.0, floor((values.filterNotNull().minOrNull() ?: 90.0) / 5) * 5)
        val pick = rememberUpdatedState<(Float, Float) -> Unit> { x, width -> select(((x - 8) / (width - 16) * 6).roundToInt()) }
        Canvas(Modifier.fillMaxWidth().height(128.dp).testTag("health-oxygen-chart")
            .semantics {
                contentDescription = "Blood oxygen, seven days. ${dates[selected]}, ${values[selected]?.let { "${it.healthNumber()} percent" } ?: "no reading"}"
                progressBarRangeInfo = ProgressBarRangeInfo(selected.toFloat(), 0f..6f, 5)
                setProgress { select(it.roundToInt()); true }
            }.pointerInput(Unit) { detectTapGestures { pick.value(it.x, size.width.toFloat()) } }
            .pointerInput(Unit) { detectDragGestures(onDragStart = { pick.value(it.x, size.width.toFloat()) }) { change, _ ->
                change.consume(); pick.value(change.position.x, size.width.toFloat())
            } }) {
            val inset = 8.dp.toPx(); val top = 18.dp.toPx(); val h = size.height - top * 2
            fun point(i: Int, n: Double) = Offset(inset + (size.width - inset * 2) * i / 6, top + (1 - (n - low) / (100 - low)).toFloat() * h)
            drawLine(HealthSecondary.copy(alpha = .12f), Offset(inset, top + h), Offset(size.width - inset, top + h), 1.dp.toPx())
            val path = Path(); var joined = false
            for (i in values.indices) {
                val v = values[i]
                if (v == null) { joined = false; continue }
                val p = point(i, v)
                if (!joined) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                joined = true
            }
            drawPath(path, HealthAccent.copy(alpha = .7f), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
            values.forEachIndexed { i, value -> if (value != null) {
                val p = point(i, value)
                if (i == selected) drawCircle(HealthAccent.copy(alpha = .18f), 9.dp.toPx(), p)
                drawCircle(if (i == selected) Color(0xFFECE1FF) else HealthAccent, (if (i == selected) 4 else 3).dp.toPx(), p)
            } }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { dates.forEach { date ->
            Text(date.format(DateTimeFormatter.ofPattern("EEEEE", Locale.UK)), fontSize = 11.sp, color = HealthSecondary)
        } }
    }
}
