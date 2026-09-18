package com.mani.orbit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
        // Every row is keyed. The poll below adds and removes items while the list is measuring,
        // and an unkeyed item that shifts position under a measure in flight is asked for an index
        // its interval list no longer has.
        item("status") { Text(error ?: status, color = HealthSecondary, fontSize = 13.sp, lineHeight = 18.sp) }
        if (sources.size > 1) item("sources") { Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .testTag("watch-sources"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val current = selected ?: sources.first()
            sources.forEachIndexed { index, source ->
                WatchSourcePill("Watch ${index + 1}", source == current) {
                    selected = source; measurementId = null; measurement = null; rows = emptyMap()
                }
            }
        } }
        if (sources.isEmpty()) item("empty") { Text("Open Orbit on your watch. Saved readings arrive when the devices reconnect.",
            color = Color(0xFFE9E2F3), fontSize = 14.sp, lineHeight = 20.sp) }
        measurement?.let { page -> item("sensor-result") { WatchSensorResult(page) { measurementId = it } } }
        (selected?.takeIf { it in sources } ?: sources.firstOrNull())?.let { source ->
            item("ecg-history-$source") { PhoneEcgHistory(source) }
            item("heart-batches-$source") { PhoneHeartBatches(source) }
            item("raw-recordings-$source") { PhoneSensorRecordings(source) }
        }
        if (sources.isNotEmpty()) metrics.entries.chunked(2).forEach { pair -> item(pair.first().key) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) { pair.forEach { (key, label) ->
            val row = rows[key]
            Column(Modifier.weight(1f).clip(RoundedCornerShape(24.dp)).orbitPanel(24.dp)
                .padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(label, fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium, color = Color(0xFFE9E2F3))
                val usable = row != null && !row.isNull("value") && row.optString("quality") == "valid"
                val value = if (key == "distance") row?.optDouble("value")?.div(1000) else row?.optDouble("value")
                Text(if (usable) String.format(Locale.UK, if (key == "distance") "%.2f" else if (key == "floors") "%.1f" else "%,.0f", value) else "—",
                    color = Color(0xFFF7F2FC), fontSize = 33.sp, lineHeight = 39.sp, fontWeight = FontWeight(550))
                Text(if (key == "distance") "km" else row?.optString("unit") ?: "", color = HealthSecondary, fontSize = 12.sp, lineHeight = 17.sp)
                if (row != null) {
                    val format = DateTimeFormatter.ofPattern("d MMM · HH:mm:ss", Locale.UK).withZone(ZoneId.systemDefault())
                    Text(when {
                        row.optBoolean("orderingUncertain") -> "Reading order uncertain"
                        row.optBoolean("timeUncertain") || row.getLong("end") > System.currentTimeMillis() -> "Capture time uncertain"
                        else -> format.format(Instant.ofEpochMilli(row.getLong("end")))
                    },
                        color = HealthSecondary, fontSize = 11.sp, lineHeight = 16.sp)
                    if (!usable) Text("Quality: ${row.optString("quality").replace('_', ' ')}", color = HealthSecondary, fontSize = 11.sp, lineHeight = 16.sp)
                } else Text("No reading received", color = HealthSecondary, fontSize = 11.sp, lineHeight = 16.sp)
            } } }
        } }
    }
}

/** A quiet Orbit pill per paired watch; the row scrolls rather than squeezing its labels. */
@Composable private fun WatchSourcePill(label: String, current: Boolean, choose: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(Modifier.heightIn(min = 40.dp)
        .orbitControl(20.dp, interaction, fill = if (current) ControlSelectedFill else ControlFill)
        .clickable(interactionSource = interaction, indication = null, onClick = choose)
        .semantics { role = Role.Tab; selected = current }
        .padding(horizontal = 16.dp, vertical = 9.dp), contentAlignment = Alignment.Center) {
        Text(label, color = if (current) Color(0xFFF7F2FC) else HealthSecondary, fontSize = 13.sp, lineHeight = 18.sp,
            maxLines = 1)
    }
}
