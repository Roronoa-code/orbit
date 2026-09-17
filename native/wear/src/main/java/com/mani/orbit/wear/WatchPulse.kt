package com.mani.orbit.wear

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import org.json.JSONObject

/** Boot-scoped sensor time distinguishes this request from a recently cached reading. */
internal data class PulseRequest(val boot: String, val elapsed: Long)

@Composable internal fun WatchPulseReading(row: JSONObject?, now: Long, elapsed: Long,
    request: PulseRequest?, status: String, error: String?) {
    val value = watchValue(row, now)
    val current = request != null && row != null && row.optString("metric") == "heart" &&
        row.optString("boot") == request.boot && row.optLong("elapsedMs", -1) >= request.elapsed &&
        elapsed - row.optLong("elapsedMs", -1) in 0..10_000
    val fresh = current && value != "—"
    WatchNumber(if (request != null && !fresh || error != null) "—" else value, Modifier.testTag("pulse-value"))
    Text("bpm", style = MaterialTheme.typography.bodySmall)
    val guidance = when {
        error != null -> error
        request == null -> readingAge(row, now)
        fresh -> "Measuring"
        status == "Adjust your watch and keep still" -> status
        current && (row!!.optBoolean("timeUncertain") || row.optLong("end") > now || row.optString("quality") != "valid") -> readingAge(row, now)
        else -> "Finding your pulse…"
    }
    Text(guidance, Modifier.testTag("pulse-guidance"), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
}
