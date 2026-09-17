package com.mani.orbit

import androidx.compose.animation.animateContentSize
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.mani.health.core.model.measurement.EcgPlayback
import com.mani.orbit.sync.*
import kotlinx.coroutines.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private data class EcgView(val record: EcgRecord, val older: String?, val newer: String?, val revision: Long, val playback: EcgPlayback?, val orderingUncertain: Boolean)

@Composable internal fun PhoneEcgHistory(installation: String) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var selected by rememberSaveable(installation) { mutableStateOf<String?>(null) }
    var expanded by rememberSaveable(installation) { mutableStateOf(false) }
    var view by remember(installation) { mutableStateOf<EcgView?>(null) }
    var error by remember(installation) { mutableStateOf(false) }
    LaunchedEffect(installation, selected, expanded) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                try {
                    val prior = view
                    val next = withContext(Dispatchers.IO) { ReadingJournal(context.getDatabasePath("watch-readings.db")).use { db ->
                        val journal = EcgJournal(db)
                        journal.page(installation, selected)?.let { page ->
                            val it = page.record
                            val revision = journal.revision(it)
                            EcgView(it, page.olderId, page.newerId, revision,
                                if (!expanded) null else if (prior?.record == it && prior.revision == revision && prior.playback != null) prior.playback else journal.playback(it), page.orderingUncertain)
                        }
                    } }
                    view = next; error = false
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { error = true }
                delay(1500)
            }
        }
    }
    view?.let { value -> EcgHistoryCard(value.record, value.older, value.newer, value.playback, expanded, error,
        orderingUncertain = value.orderingUncertain, expand = { expanded = !expanded }, select = { selected = it; expanded = false }) }
    if (view == null && error) Text("ECG history could not load. Retrying…", color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable internal fun EcgHistoryCard(record: EcgRecord, older: String?, newer: String?, playback: EcgPlayback?, expanded: Boolean,
    error: Boolean = false, orderingUncertain: Boolean = false, expand: () -> Unit, select: (String) -> Unit) {
    val haptic = LocalHapticFeedback.current
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.then(if (LocalOrbitReducedMotion.current) Modifier else Modifier.animateContentSize()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("ECG", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                WorkoutArrow(if (orderingUncertain) "Previous ECG recording" else "Older ECG recording", -1, older != null) { older?.let(select) }
                WorkoutArrow(if (orderingUncertain) "Next ECG recording" else "Newer ECG recording", 1, newer != null) { newer?.let(select) }
            }
            Text(Instant.ofEpochMilli(record.start).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM · HH:mm", Locale.UK)),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (orderingUncertain) Text("Recording order uncertain", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(when {
                playback?.complete == true -> "Recording received"
                playback != null && record.phase == "complete" -> "Recording needs review"
                record.phase == "complete" -> "Recorded signal"
                record.phase == "failed" -> "Recording could not finish"
                else -> "Partial recording"
            }, style = MaterialTheme.typography.titleMedium)
            if (error) Text("Couldn't refresh. Showing the saved recording.", style = MaterialTheme.typography.bodySmall)
            if (expanded) {
                if (playback == null) Text("Loading waveform…") else EcgWaveform(record.id, playback)
            }
            TextButton(onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); expand() }) { Text(if (expanded) "Close waveform" else "View waveform") }
            Text("Samsung Watch sensor · No rhythm diagnosis", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private data class EcgWavePoint(val millivolts: Float, val gap: Boolean)

/** Reused ECG viewer geometry: sample-index pages, exact mV values and explicit discontinuities. */
@Composable private fun EcgWaveform(id: String, playback: EcgPlayback) {
    var page by rememberSaveable(id) { mutableIntStateOf(0) }
    var overview by rememberSaveable(id) { mutableStateOf(false) }
    val points = remember(playback) { buildList {
        var lastChunk: Long? = null; var lastCallback: Long? = null
        for (chunk in playback.chunks) for (callback in chunk.callbacks) {
            val gap = lastChunk != null && chunk.sequence != lastChunk && chunk.sequence != lastChunk!! + 1 ||
                lastCallback != null && callback.callbackSequence != lastCallback!! + 1
            callback.points.forEachIndexed { index, point -> add(EcgWavePoint(point.millivolts, index == 0 && gap)) }
            lastChunk = chunk.sequence; lastCallback = callback.callbackSequence
        }
    } }
    val issue = playback.signalQuality?.completionIssue()
    if (issue != null) Text(when (issue) {
        "ECG_CONTACT_REQUIRED" -> "No finger contact was detected. This recording is not usable."
        "ECG_CONTACT_INTERRUPTED" -> "Finger contact was lost during recording."
        "ECG_SIGNAL_SATURATED" -> "The signal exceeded the sensor's range."
        else -> "Recording quality could not be confirmed."
    }, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
    if (points.isEmpty()) { Text("No waveform samples have arrived yet."); return }
    val pageSize = if (overview) 2000 else 500
    val first = (page * pageSize).coerceAtMost(((points.size - 1) / pageSize) * pageSize)
    val shown = points.subList(first, minOf(first + pageSize, points.size))
    val low = shown.minOf { it.millivolts }.toDouble()
    val high = shown.maxOf { it.millivolts }.toDouble()
    val span = maxOf(high - low, 0.01)
    val middle = (low + high) / 2
    val floor = middle - span / 2; val ceiling = middle + span / 2
    val range = "Samples ${first + 1}–${first + shown.size} of ${points.size}"
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(range, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
        TextButton(onClick = { overview = !overview; page = first / if (overview) 2000 else 500 }) { Text(if (overview) "Detail" else "Overview") }
    }
    val foreground = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .12f)
    Text(String.format(Locale.UK, "%.3g mV", ceiling), style = MaterialTheme.typography.labelSmall)
    Canvas(Modifier.fillMaxWidth().height(168.dp).semantics {
        contentDescription = "$range. Recorded voltage range ${String.format(Locale.UK, "%.3g to %.3g", low, high)} millivolts."
    }) {
        for (fraction in listOf(0f, .5f, 1f)) drawLine(grid, Offset(0f, size.height * fraction), Offset(size.width, size.height * fraction))
        fun coordinate(index: Int) = Offset(index.toFloat() / maxOf(1, shown.lastIndex) * size.width,
            ((ceiling - shown[index].millivolts) / span * size.height).toFloat())
        for (index in 1..shown.lastIndex) {
            if (!shown[index].gap) drawLine(foreground, coordinate(index - 1), coordinate(index), 1.5.dp.toPx())
            else drawLine(foreground.copy(alpha = .5f), Offset(coordinate(index).x, 0f), Offset(coordinate(index).x, size.height),
                1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())))
        }
    }
    Text(String.format(Locale.UK, "%.3g mV", floor), style = MaterialTheme.typography.labelSmall)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        WorkoutArrow("Earlier samples", -1, first > 0) { page = first / pageSize - 1 }
        WorkoutArrow("Later samples", 1, first + shown.size < points.size) { page = first / pageSize + 1 }
    }
    if (playback.issues.any { it in setOf("CHUNK_GAP", "CALLBACK_GAP", "INVALID_RAW_RECORDING", "SAMPLES_PENDING") })
        Text("Some samples are missing. Gaps are not filled in.", style = MaterialTheme.typography.bodySmall)
    Text("Horizontal positions count samples. Original sensor timing is retained but has not been calibrated.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
