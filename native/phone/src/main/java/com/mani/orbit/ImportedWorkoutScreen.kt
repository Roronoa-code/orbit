package com.mani.orbit

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

internal fun readImportedWorkout(database: File, id: String): WorkoutRecord? {
    require(id.startsWith("samsung:") && id.length <= 520)
    return HealthRecordStore(database).use { store ->
        store.record("exercise", id.removePrefix("samsung:"))?.let { WorkoutData.imported(JSONArray().put(it)).single() }
    }
}

/** History owns only summaries. Read the selected record off the main thread, without touching the source. */
@Composable
internal fun ImportedWorkoutScreen(summary: WorkoutRecord, database: File = LocalContext.current.getDatabasePath("samsung-health.db")) {
    var detailed by remember(summary.id, summary.detailRevision) { mutableStateOf<WorkoutRecord?>(null) }
    var error by remember(summary.id, summary.detailRevision) { mutableStateOf<String?>(null) }
    var loading by remember(summary.id, summary.detailRevision) { mutableStateOf(summary.detailsDeferred) }
    var attempt by remember(summary.id) { mutableIntStateOf(0) }
    LaunchedEffect(summary.id, summary.detailRevision, summary.detailsDeferred, attempt) {
        if (!summary.detailsDeferred) return@LaunchedEffect
        loading = true; error = null
        try {
            detailed = withContext(Dispatchers.IO) { readImportedWorkout(database, summary.id) }
            if (detailed == null) error = "This workout is no longer available. Refresh your history."
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "Recorded details could not be loaded. Your saved data is preserved." }
        finally { loading = false }
    }
    WorkoutRecordScreen(detailed ?: summary, false, null, {}, loading, error) { attempt++ }
}
