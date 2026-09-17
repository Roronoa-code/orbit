package com.mani.orbit

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.mani.orbit.sync.ReadingJournal
import com.mani.orbit.sync.MeasurementPage
import com.mani.orbit.sync.measurementPage
import kotlinx.coroutines.*
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Received watch history remains source-scoped; Samsung merged totals are never incremented. */
@Composable fun WatchReadingsScreen() {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var sources by remember { mutableStateOf(emptyList<String>()) }
    var rows by remember { mutableStateOf(emptyMap<String, JSONObject?>()) }
    var status by remember { mutableStateOf("Waiting for your watch") }
    var error by remember { mutableStateOf<String?>(null) }
    var measurementId by rememberSaveable { mutableStateOf<String?>(null) }
    var measurement by remember { mutableStateOf<MeasurementPage?>(null) }
    val metrics = remember { linkedMapOf("heart" to "Heart rate", "steps" to "Watch steps", "distance" to "Watch distance", "energy" to "Total energy",
        "floors" to "Floors", "battery" to "Battery") }
    LaunchedEffect(selected, measurementId) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                try {
                    val snapshot = withContext(Dispatchers.IO) {
                        ReadingJournal(context.getDatabasePath("watch-readings.db")).use { journal ->
                            val installations = journal.installations()
                            val source = selected?.takeIf { it in installations } ?: installations.firstOrNull()
                            Triple(installations, metrics.keys.associateWith { metric -> source?.let { journal.latest(metric, it) } },
                                source?.let { journal.measurementPage(it, measurementId) })
                        }
                    }
                    sources = snapshot.first; rows = snapshot.second; measurement = snapshot.third
                    status = context.getSharedPreferences("watch-sync", 0).getString("status", "Waiting for your watch")!!
                    error = null
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { error = "Watch history could not be read. Retrying…" }
                delay(1500)
            }
        }
    }
    val scroll = rememberLazyListState()
    ObserveHeaderScroll(scroll)
    LazyColumn(state = scroll, contentPadding = PaddingValues(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text(error ?: status, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (sources.size > 1) item { Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            sources.forEachIndexed { index, source -> FilterChip(selected = (selected ?: sources.first()) == source,
                onClick = { selected = source; measurementId = null; measurement = null; rows = emptyMap() }, label = { Text("Watch ${index + 1}") }) }
        } }
        if (sources.isEmpty()) item { Text("Open Orbit on your watch. Saved readings arrive when the devices reconnect.") }
        measurement?.let { page -> item("sensor-result") { WatchSensorResult(page) { measurementId = it } } }
        (selected?.takeIf { it in sources } ?: sources.firstOrNull())?.let { source ->
            item("ecg-history-$source") { PhoneEcgHistory(source) }
            item("heart-batches-$source") { PhoneHeartBatches(source) }
            item("raw-recordings-$source") { PhoneSensorRecordings(source) }
        }
        if (sources.isNotEmpty()) metrics.entries.chunked(2).forEach { pair -> item(pair.first().key) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) { pair.forEach { (key, label) ->
            val row = rows[key]
            Card(Modifier.weight(1f)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(label, style = MaterialTheme.typography.titleMedium)
                val usable = row != null && !row.isNull("value") && row.optString("quality") == "valid"
                val value = if (key == "distance") row?.optDouble("value")?.div(1000) else row?.optDouble("value")
                Text(if (usable) String.format(Locale.UK, if (key == "distance") "%.2f" else if (key == "floors") "%.1f" else "%,.0f", value) else "—",
                    style = MaterialTheme.typography.headlineSmall)
                Text(if (key == "distance") "km" else row?.optString("unit") ?: "", style = MaterialTheme.typography.bodySmall)
                if (row != null) {
                    val format = DateTimeFormatter.ofPattern("d MMM · HH:mm:ss", Locale.UK).withZone(ZoneId.systemDefault())
                    Text(when {
                        row.optBoolean("orderingUncertain") -> "Reading order uncertain"
                        row.optBoolean("timeUncertain") || row.getLong("end") > System.currentTimeMillis() -> "Capture time uncertain"
                        else -> format.format(Instant.ofEpochMilli(row.getLong("end")))
                    },
                        style = MaterialTheme.typography.bodySmall)
                    if (!usable) Text("Quality: ${row.optString("quality").replace('_', ' ')}", style = MaterialTheme.typography.bodySmall)
                } else Text("No reading received", style = MaterialTheme.typography.bodySmall)
            } } } }
        } }
    }
}
