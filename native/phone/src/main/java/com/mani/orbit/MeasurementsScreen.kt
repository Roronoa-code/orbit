package com.mani.orbit

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.*

private val Accent = Color(0xFFC0A6EF)
private val Secondary = Color(0xFFB2ABBC)
private val Foreground = Color(0xFFF5F1FA)
private val DateLabel = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK)
private val ShortDate = DateTimeFormatter.ofPattern("d MMM", Locale.UK)
private fun Double?.kg() = this?.let { String.format(Locale.UK, "%.1f", it.displayPrecision()) } ?: "—"

@Composable
internal fun MeasurementsScreen(state: HealthScreenState) {
    var metricIndex by rememberSaveable { mutableIntStateOf(0) }
    var periodIndex by rememberSaveable { mutableIntStateOf(1) }
    var inspectedAt by rememberSaveable { mutableStateOf<Long?>(null) }
    val metric = Measurement.entries[metricIndex]
    val period = MeasurementPeriod.entries[periodIndex]
    val history = state.day.measurements
    val all = remember(history, metric) { history.readings(metric) }
    val trend = remember(all, period, state.day.date) { MeasurementTrend.from(all, period, state.day.date) }
    val selected = trend.readings.firstOrNull { it.at == inspectedAt } ?: trend.readings.lastOrNull()
    val latest = all.lastOrNull()
    val haptic = LocalHapticFeedback.current
    val scroll = rememberLazyListState()
    ObserveHeaderScroll(scroll)
    val changeMetric: (Int) -> Unit = { metricIndex = Math.floorMod(it, Measurement.entries.size); inspectedAt = null }
    LazyColumn(Modifier.fillMaxSize().testTag("measurements-scroll"), state = scroll, contentPadding = PaddingValues(start = 14.dp, top = 8.dp, end = 14.dp, bottom = 100.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth(), color = Accent) }
        item { CompositionRing(metric, latest, history.share(metric, latest), changeMetric) }
        item { MeasurementSelector(Measurement.entries.map { it.label }, metricIndex, "measurement-metric", changeMetric) }
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp).padding(top = 22.dp).testTag("measurement-trend"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    Text("${metric.label} over time", color = Foreground, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text("${trend.readings.size} ${if (trend.readings.size == 1) "reading" else "readings"}", color = Secondary, fontSize = 11.sp)
                }
                if (selected != null) {
                    ReadingSummary(selected, trend.readings.first())
                    if (trend.hasChart) MeasurementChart(trend, selected) { index ->
                        val at = trend.readings[index].at
                        if (at != (inspectedAt ?: selected.at)) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        inspectedAt = at
                    }
                } else {
                    Text(when {
                        state.loading -> "Loading saved measurements…"
                        metric == Measurement.Fat && history.fatPercent.isNotEmpty() -> "Fat percentage is recorded. A weight taken at the same time is needed to show kilograms."
                        latest != null -> "No readings in this period. Latest: ${latest.value.kg()} kg · ${latest.day().format(DateLabel)}"
                        else -> "No ${metric.label.lowercase(Locale.UK)} readings shared yet."
                    }, color = Secondary, fontSize = 13.sp, lineHeight = 20.sp, modifier = Modifier.padding(vertical = 8.dp))
                }
                if (trend.hasStatistics) Row(Modifier.fillMaxWidth().padding(top = 8.dp).testTag("measurement-statistics")) {
                    for ((label, value) in listOf("Average" to trend.average, "Lowest" to trend.lowest, "Highest" to trend.highest)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(label, fontSize = 11.sp, color = Secondary)
                            Text("${value.kg()} kg", fontSize = 18.sp, color = Foreground)
                        }
                    }
                }
                Box(Modifier.padding(top = 8.dp)) {
                    MeasurementSelector(MeasurementPeriod.entries.map { it.label }, periodIndex, "measurement-period") {
                        periodIndex = it; inspectedAt = null
                    }
                }
                Text("${trend.start.format(if (trend.start.year == trend.end.year) ShortDate else DateLabel)} – ${trend.end.format(DateLabel)}", color = Secondary,
                    fontSize = 10.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
        }
        item { Text("Samsung Health", color = Secondary, fontSize = 11.sp) }
    }
}

@Composable
private fun ReadingSummary(selected: Reading, first: Reading) {
    val change = (selected.value.displayPrecision() - first.value.displayPrecision()).displayPrecision()
    val difference = if (abs(change) < .05) "No change" else "${if (change > 0) "+" else "−"}${abs(change).kg()} kg"
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically) {
        Column {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(selected.value.kg(), color = Foreground, fontSize = 38.sp, fontWeight = FontWeight.Medium)
                Text("kg", color = Secondary, fontSize = 14.sp, modifier = Modifier.padding(start = 5.dp, bottom = 5.dp))
            }
            Text(selected.day().format(DateLabel), color = Secondary, fontSize = 12.sp)
        }
        if (selected.at != first.at) Column(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.End) {
            Text(difference, color = Accent, fontSize = 12.sp)
            Text("since ${first.day().format(if (first.day().year == selected.day().year) ShortDate else DateLabel)}", color = Secondary, fontSize = 11.sp)
        }
    }
}

@Composable
private fun CompositionRing(metric: Measurement, reading: Reading?, share: Float?, select: (Int) -> Unit) {
    // Retarget the current amount; lean -> weight fills the remaining arc instead of emptying first.
    val fill = animateFloatAsState(share ?: 0f, tween(if (LocalOrbitReducedMotion.current) 0 else 420), label = "composition fill")
    val currentMetric by rememberUpdatedState(metric)
    val currentSelect by rememberUpdatedState(select)
    var travel by remember { mutableFloatStateOf(0f) }
    val haptic = LocalHapticFeedback.current
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
    Box(Modifier.widthIn(max = 300.dp).fillMaxWidth().aspectRatio(1f).testTag("composition-ring")
        .semantics(mergeDescendants = true) {
            contentDescription = "${metric.label}, ${reading?.value.kg()} kilograms" +
                (reading?.let { ", ${it.day().format(DateLabel)}" } ?: ", no shared reading")
            customActions = listOf(CustomAccessibilityAction("Next measurement") { select(metric.ordinal + 1); true },
                CustomAccessibilityAction("Previous measurement") { select(metric.ordinal - 1); true })
        }
        .pointerInput(Unit) { detectTapGestures { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); currentSelect(currentMetric.ordinal + 1) } }
        .pointerInput(Unit) { detectHorizontalDragGestures(onDragStart = { travel = 0f }, onDragCancel = { travel = 0f },
            onDragEnd = {
                if (abs(travel) > 28.dp.toPx()) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); currentSelect(currentMetric.ordinal + if (travel < 0) 1 else -1) }
                travel = 0f
            }) { change, amount -> change.consume(); travel += amount } }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = min(size.width, size.height) * .45f
            drawCircle(Brush.radialGradient(listOf(Color(0x221E1929), Color.Transparent)), radius = radius)
            drawCircle(Color.White.copy(alpha = .025f), radius = radius * .87f, style = Stroke(1.dp.toPx()))
            repeat(100) { i ->
                val angle = (i / 100.0 * 2 * PI - PI / 2)
                val active = (fill.value * 100 - i).coerceIn(0f, 1f)
                drawCircle(androidx.compose.ui.graphics.lerp(Color(0xFF302C39), Accent, active), 2.2.dp.toPx(),
                    Offset(center.x + cos(angle).toFloat() * radius, center.y + sin(angle).toFloat() * radius))
            }
        }
        Column(Modifier.clearAndSetSemantics { }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(metric.label, color = Secondary, fontSize = 12.sp, lineHeight = 18.sp)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                val value = reading?.value.kg()
                OrbitDotNumber(value, Modifier.width((value.length * 37).coerceAtMost(204).dp).height(48.dp), Foreground)
                Text("kg", color = Secondary, fontSize = 13.sp)
            }
            Text(when {
                reading == null -> "No shared reading"
                metric == Measurement.Weight -> "Total body weight"
                share != null -> "${(share * 100).roundToInt()}% of body weight"
                else -> reading.day().format(ShortDate)
            }, color = Secondary, fontSize = 10.5.sp, lineHeight = 16.sp)
        }
    }
    }
}

@Composable
private fun MeasurementChart(trend: MeasurementTrend, selected: Reading, select: (Int) -> Unit) {
    val index = trend.readings.indexOf(selected).coerceAtLeast(0)
    val currentSelect by rememberUpdatedState(select)
    val currentIndex by rememberUpdatedState(index)
    Column {
        Box(Modifier.fillMaxWidth().height(126.dp).testTag("measurement-chart")
            .semantics {
                contentDescription = "Measurement history"
                stateDescription = "${selected.value.kg()} kilograms, ${selected.day().format(DateLabel)}"
                progressBarRangeInfo = ProgressBarRangeInfo(index.toFloat(), 0f..trend.readings.lastIndex.toFloat(), (trend.readings.size - 2).coerceAtLeast(0))
                setProgress { value ->
                    if (!value.isFinite()) false
                    else { currentSelect(value.roundToInt().coerceIn(trend.readings.indices)); true }
                }
            }.onKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.key in listOf(Key.DirectionLeft, Key.DirectionRight)) {
                    currentSelect((currentIndex + if (it.key == Key.DirectionRight) 1 else -1).coerceIn(trend.readings.indices)); true
                } else false
            }.focusable()
            .pointerInput(trend) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var dragging = false
                    fun inspect(x: Float) {
                        val inset = 5.dp.toPx()
                        currentSelect(trend.nearest((x - inset) / (size.width - inset * 2)))
                    }
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) { if (!change.isConsumed) { inspect(change.position.x); change.consume() }; break }
                        val delta = change.position - down.position
                        if (!dragging && (change.isConsumed || abs(delta.y) > viewConfiguration.touchSlop && abs(delta.y) > abs(delta.x))) break
                        if (abs(delta.x) > viewConfiguration.touchSlop) dragging = true
                        if (dragging) { inspect(change.position.x); change.consume() }
                    }
                }
            }.drawWithCache {
                val inset = 5.dp.toPx()
                val width = size.width - inset * 2
                val points = trend.readings.indices.map { Offset(inset + trend.x(it) * width, 10.dp.toPx() + trend.y(it) * (size.height - 20.dp.toPx())) }
                val line = Path().apply {
                    moveTo(points.first().x, points.first().y)
                    points.zipWithNext().forEach { (a, b) ->
                        val half = (b.x - a.x) / 2
                        cubicTo(a.x + half, a.y, b.x - half, b.y, b.x, b.y)
                    }
                }
                val area = Path().apply { addPath(line); lineTo(points.last().x, size.height); lineTo(points.first().x, size.height); close() }
                val shade = Brush.verticalGradient(listOf(Accent.copy(alpha = .17f), Color.Transparent), endY = size.height)
                onDrawBehind {
                    drawPath(area, shade)
                    drawPath(line, Accent, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
                    points.forEach { drawCircle(Accent.copy(alpha = .5f), 1.5.dp.toPx(), it) }
                    drawCircle(Foreground, 3.dp.toPx(), points[currentIndex])
                }
            })
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            val axisDate = if (trend.readings.first().day().year == trend.readings.last().day().year) ShortDate else DateLabel
            Text(trend.readings.first().day().format(axisDate), color = Secondary, fontSize = 11.sp)
            Text(trend.readings.last().day().format(axisDate), color = Secondary, fontSize = 11.sp)
        }
    }
}
