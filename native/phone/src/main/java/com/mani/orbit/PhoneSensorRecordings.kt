package com.mani.orbit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.mani.health.integration.samsungsensor.*
import com.mani.orbit.sync.*
import kotlinx.coroutines.*
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Native callback browser, using the same immutable journal and navigation as beat intervals. */
@Composable internal fun PhoneSensorRecordings(installation: String) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var selected by rememberSaveable(installation) { mutableStateOf<String?>(null) }
    var page by remember(installation) { mutableStateOf<RawSensorPage?>(null) }
    var error by remember(installation) { mutableStateOf(false) }
    LaunchedEffect(installation, selected) {
        var previous: Pair<String?, String?>? = null
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                try {
                    val current = page
                    val next = withContext(Dispatchers.IO) {
                        ReadingJournal(context.getDatabasePath("watch-readings.db")).use {
                            val journal = RawSensorJournal(it); val latest = journal.latestId(installation)
                            val signature = (selected ?: latest) to latest
                            signature to if (signature == previous) current else journal.page(installation, selected)
                        }
                    }
                    previous = next.first; page = next.second; error = false
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { error = true }
                delay(3000)
            }
        }
    }
    if (error && page == null) Text("Sensor recordings could not load. Retrying…")
    page?.let { RawSensorCard(it, selected != null, error) { id -> selected = id } }
}

@Composable internal fun RawSensorCard(current: RawSensorPage, pinned: Boolean, error: Boolean, select: (String?) -> Unit) {
    val chunk = current.frame.chunk
    var details by rememberSaveable { mutableStateOf(false) }
    var channel by rememberSaveable(chunk.probe) { mutableIntStateOf(0) }
    var samplePage by rememberSaveable(current.frame.id) { mutableIntStateOf(0) }
    val channels = when (chunk.probe) {
        SensorRawProbe.PPG_CONTINUOUS -> listOf("Green", "Infrared", "Red")
        SensorRawProbe.ACCELEROMETER_CONTINUOUS -> listOf("X", "Y", "Z")
        SensorRawProbe.SKIN_TEMPERATURE_CONTINUOUS -> listOf("Skin", "Surroundings")
    }
    val title = when (chunk.probe) {
        SensorRawProbe.PPG_CONTINUOUS -> "Optical signal"
        SensorRawProbe.ACCELEROMETER_CONTINUOUS -> "Movement signal"
        SensorRawProbe.SKIN_TEMPERATURE_CONTINUOUS -> "Skin temperature"
    }
    val unit = when (chunk.probe) {
        SensorRawProbe.PPG_CONTINUOUS -> "sensor counts"
        SensorRawProbe.ACCELEROMETER_CONTINUOUS -> "m/s²"
        SensorRawProbe.SKIN_TEMPERATURE_CONTINUOUS -> "°C"
    }
    val haptic = LocalHapticFeedback.current
    fun feedback() = haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    OrbitCard(Modifier.fillMaxWidth().testTag("raw-sensor-card")) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(chunk.receivedAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM · HH:mm:ss", Locale.UK)),
                style = MaterialTheme.typography.bodySmall)
            GlassTrack(channels, channel, { channel = it }, "raw-sensor-channel", Modifier.fillMaxWidth(), slotHeight = 36.dp, labelSize = 12.sp)
            RawSensorWaveform(chunk, channel, samplePage, unit) { feedback(); samplePage = it }
            if (error) Text("Refresh failed · showing saved data", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                OrbitTextAction("Older", enabled = current.older != null) { select(current.older) }
                OrbitTextAction("Latest", enabled = pinned) { select(null) }
                OrbitTextAction("Newer", enabled = current.newer != null) { select(current.newer) }
            }
            OrbitTextAction(if (details) "Less" else "Recording details") { details = !details }
            if (details) {
                val quality = chunk.quality
                Text("${chunk.samples.size} samples · ${chunk.issues.size} capture flags", style = MaterialTheme.typography.bodySmall)
                Text("${quality.timestampedSampleCount} timestamped · ${quality.gapCount} timing gaps · ${quality.timestampOrderViolationCount} out of order",
                    style = MaterialTheme.typography.bodySmall)
                Text("Horizontal positions count samples. Missing or unqualified readings are not joined.", style = MaterialTheme.typography.bodySmall)
                if (current.frame.clockUncertain) Text("Capture clock changed · times need care", style = MaterialTheme.typography.bodySmall)
                Text(when (chunk.probe) {
                    SensorRawProbe.PPG_CONTINUOUS -> "Original optical signal, not a heart-rate or recovery score."
                    SensorRawProbe.ACCELEROMETER_CONTINUOUS -> "Original axes converted with Samsung’s sensor scale. Not a step estimate."
                    SensorRawProbe.SKIN_TEMPERATURE_CONTINUOUS -> "Skin and surroundings readings, not core body temperature."
                }, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

internal data class RawWavePoint(val value: Double?, val join: Boolean)

/** Keep all source points; render at most 500 per page instead of throwing away peaks. */
internal fun rawWavePoints(chunk: RawProbeChunk, channel: Int, first: Int): List<RawWavePoint> {
    require(channel in 0..if (chunk.probe == SensorRawProbe.SKIN_TEMPERATURE_CONTINUOUS) 1 else 2)
    require(first >= 0 && first <= chunk.samples.size)
    return chunk.samples.subList(first, minOf(first + 500, chunk.samples.size)).mapIndexed { i, sample ->
        val value = when (sample) {
            is PpgRawSample -> when (channel) {
                0 -> sample.rawPpgGreen.takeIf { sample.rawGreenStatus == 0 }
                1 -> sample.rawPpgIr.takeIf { sample.rawIrStatus == 0 }
                else -> sample.rawPpgRed.takeIf { sample.rawRedStatus == 0 }
            }?.toDouble()
            is AccelerometerRawSample -> when (channel) {
                0 -> sample.rawAccelerometerX; 1 -> sample.rawAccelerometerY; else -> sample.rawAccelerometerZ
            }?.times(ACCELEROMETER_COUNTS_TO_METERS_PER_SECOND_SQUARED)
            is SkinTemperatureRawSample -> (if (channel == 0) sample.rawObjectTemperatureCelsius else sample.rawAmbientTemperatureCelsius)
                ?.takeIf { sample.rawStatus == 0 && it.isFinite() }?.toDouble()
        }
        val time = sample.rawSensorTimestampEpochMillis?.takeIf { it >= 0 }
        val previous = chunk.samples.getOrNull(first + i - 1)?.rawSensorTimestampEpochMillis?.takeIf { it >= 0 }
        val delta = if (time != null && previous != null) time - previous else null
        val period = chunk.probe.nominalRateHz?.let { 1500.0 / it }
        RawWavePoint(value, i > 0 && delta != null && delta > 0 && (period == null || delta <= period))
    }
}

@Composable private fun RawSensorWaveform(chunk: RawProbeChunk, channel: Int, page: Int, unit: String, move: (Int) -> Unit) {
    if (chunk.samples.isEmpty()) { Text("This callback contains no readings."); return }
    val first = (page * 500).coerceIn(0, ((chunk.samples.size - 1) / 500) * 500)
    val points = remember(chunk, channel, first) { rawWavePoints(chunk, channel, first) }
    val values = points.mapNotNull { it.value }
    if (values.isEmpty()) Text("No qualified readings in this section", style = MaterialTheme.typography.bodyMedium)
    else {
        val low = values.min(); val high = values.max(); val span = maxOf(high - low, .01)
        val ceiling = (low + high + span) / 2
        val range = String.format(Locale.UK, "%.3g–%.3g %s", low, high, unit)
        Text(range, style = MaterialTheme.typography.titleLarge)
        val color = MaterialTheme.colorScheme.primary
        Canvas(Modifier.fillMaxWidth().height(136.dp).semantics { contentDescription = "Recorded range $range. ${values.size} qualified samples." }) {
            fun position(index: Int, value: Double) = Offset(index.toFloat() / maxOf(1, points.lastIndex) * size.width,
                (8.dp.toPx() + (ceiling - value) / span * (size.height - 16.dp.toPx())).toFloat())
            val path = Path()
            points.forEachIndexed { index, point -> point.value?.let { value ->
                val p = position(index, value)
                if (point.join && points[index - 1].value != null) path.lineTo(p.x, p.y) else path.moveTo(p.x, p.y)
                if ((!point.join || points.getOrNull(index - 1)?.value == null) &&
                    (points.getOrNull(index + 1)?.join != true || points.getOrNull(index + 1)?.value == null)) drawCircle(color, 2.dp.toPx(), p)
            } }
            drawPath(path, color, style = Stroke(2.dp.toPx()))
        }
    }
    Text("Samples ${first + 1}–${first + points.size} of ${chunk.samples.size}", style = MaterialTheme.typography.labelSmall)
    if (chunk.samples.size > 500) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        WorkoutArrow("Earlier samples", -1, first > 0) { move(first / 500 - 1) }
        WorkoutArrow("Later samples", 1, first + points.size < chunk.samples.size) { move(first / 500 + 1) }
    }
}
