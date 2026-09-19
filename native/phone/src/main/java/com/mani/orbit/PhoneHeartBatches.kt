package com.mani.orbit

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.animateContentSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.mani.health.core.model.heart.IbiStatus
import com.mani.health.core.model.heart.IbiStatusMeaning
import com.mani.orbit.sync.*
import kotlinx.coroutines.*
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One original callback at a time. IBI values are not mislabeled as timestamped beats or HRV. */
@Composable internal fun PhoneHeartBatches(installation: String) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var selected by rememberSaveable(installation) { mutableStateOf<String?>(null) }
    var expanded by rememberSaveable(installation) { mutableStateOf(false) }
    var page by remember(installation) { mutableStateOf<HeartPage?>(null) }
    var error by remember(installation) { mutableStateOf(false) }
    LaunchedEffect(installation, selected) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                try {
                    val previous = page
                    page = withContext(Dispatchers.IO) { ReadingJournal(context.getDatabasePath("watch-readings.db")).use {
                        val journal = HeartJournal(it)
                        val id = selected ?: journal.latestId(installation)
                        if (id == null) null else if (selected == null && id == previous?.frame?.id) previous else journal.page(installation, id)
                    } }
                    error = false
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { error = true }
                delay(3000)
            }
        }
    }
    val current = page
    if (error && current == null) Text("Watch beat intervals could not load. Retrying…")
    current ?: return
    HeartBatchCard(current, selected != null, expanded, error, { expanded = !expanded }, { selected = it })
}

@Composable internal fun HeartBatchCard(current: HeartPage, pinned: Boolean, expanded: Boolean, error: Boolean,
    expand: () -> Unit, select: (String?) -> Unit) {
    val stats = remember(current.frame.id) {
        val points = current.frame.batch.points
        val all = points.sumOf { it.rawIbiMillis?.size ?: 0 }
        val valid = points.flatMap { point ->
            // A mismatched status array cannot qualify any interval in that point.
            if (point.rawIbiMillis?.size != point.rawIbiStatuses?.size) emptyList() else point.rawIbiMillis.orEmpty().filterIndexed { i, value ->
                value > 0 && (point.rawIbiStatuses?.get(i) as? IbiStatus.Known)?.meaning == IbiStatusMeaning.NORMAL
            }
        }
        Triple(all, valid.size, valid.takeIf { it.isNotEmpty() }?.let { "${it.min()}–${it.max()} ms" })
    }
    OrbitCard(Modifier.fillMaxWidth().testTag("heart-batch-card")) {
        Column(Modifier.then(if (LocalOrbitReducedMotion.current) Modifier else Modifier.animateContentSize()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Beat intervals", style = MaterialTheme.typography.titleMedium)
            Text(stats.third ?: "No qualified intervals", style = MaterialTheme.typography.headlineSmall)
            Text("${stats.second} of ${stats.first} intervals marked normal", style = MaterialTheme.typography.bodySmall)
            Text(current.frame.batch.receivedAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM · HH:mm:ss", Locale.UK)),
                style = MaterialTheme.typography.bodySmall)
            if (error) Text("Refresh failed · showing saved data")
            OrbitTextAction(if (expanded) "Less" else "Recording details") { expand() }
            if (expanded) {
                Text("Samsung Watch sensor · ${current.frame.batch.points.size} pulse samples · ${current.frame.batch.issues.size} capture flags",
                    style = MaterialTheme.typography.bodySmall)
                Text("Range uses Samsung’s normal-status intervals. Individual beat times were not supplied.", style = MaterialTheme.typography.bodySmall)
                if (current.frame.clockUncertain) Text("Capture clock changed · times need care", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    OrbitTextAction("Older", enabled = current.older != null) { select(current.older) }
                    OrbitTextAction("Latest", enabled = pinned) { select(null) }
                    OrbitTextAction("Newer", enabled = current.newer != null) { select(current.newer) }
                }
            }
        }
    }
}
