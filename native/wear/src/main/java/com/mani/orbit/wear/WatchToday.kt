package com.mani.orbit.wear

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.FilledTonalButton
import org.json.JSONObject

internal enum class WatchStepSetup(val caption: String, val action: String) {
    Enable("Tracking is off", "Turn on"), Allow("Access needed", "Allow"),
    Settings("Access needed", "Settings"), Updating("Saving…", "Wait")
}

internal fun watchStepSetup(enabled: Boolean, changing: Boolean, granted: Boolean, rationale: Boolean): WatchStepSetup? = when {
    changing -> WatchStepSetup.Updating
    !enabled -> WatchStepSetup.Enable
    granted -> null
    rationale -> WatchStepSetup.Allow
    // Enabling requests activity access. If Android no longer offers that dialog, settings can recover it.
    else -> WatchStepSetup.Settings
}

/** The recorded daily fact stays in one place through loading, refresh failures and missing data. */
@Composable internal fun WatchToday(rows: Map<String, JSONObject?>, now: Long, loading: Boolean, error: String?,
    pulse: () -> Unit, workout: () -> Unit, recovery: () -> Unit,
    setup: WatchStepSetup? = null, setupError: String? = null, configure: () -> Unit = {}, measurements: (() -> Unit)? = null) {
    val row = rows["steps"]
    val value = watchValue(row, now, daily = true)
    val age = readingAge(row, now).replace("Waiting for a reliable reading", "Waiting for reliable data")
    if (setup != null) {
        val failed = setupError != null && setup != WatchStepSetup.Updating
        Text(if (failed) "Update failed" else setup.caption, Modifier.testTag("today-setup-context"), fontSize = 12.sp, lineHeight = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        FilledTonalButton(onClick = configure, enabled = setup != WatchStepSetup.Updating,
            modifier = Modifier.fillMaxWidth().testTag("today-setup")) {
            Text(if (failed && setup == WatchStepSetup.Enable) "Retry" else setup.action, style = MaterialTheme.typography.labelMedium)
        }
        setupError?.let { Text(it, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center) }
        if (!loading && value != "—") Text("$value saved steps\n${age.removePrefix("Recorded ")}",
            Modifier.testTag("today-saved-steps"), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
    } else {
        WatchNumber(if (loading) "—" else value, Modifier.testTag("today-steps"))
        val context = when {
            loading -> "Loading readings…"
            error != null && row == null -> "Couldn't load\nRetrying…"
            error != null -> "Refresh failed\n${age.replaceFirst("Recorded ", "Saved ")}"
            row != null && value == "—" && watchValue(row, now) != "—" -> "No reading today"
            else -> age
        }
        Text(context, Modifier.testTag("today-context"), fontSize = 12.sp, lineHeight = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        // Reserve one text line while distance arrives; an absent optional measurement needs no repeated dash.
        Box(Modifier.heightIn(min = with(LocalDensity.current) { 18.sp.toDp() }).testTag("today-distance")) {
            val distance = watchValue(rows["distance"], now, daily = true, divisor = 1000.0)
            if (!loading && distance != "—") Text("$distance km", style = MaterialTheme.typography.bodySmall)
        }
        setupError?.let { Text(it, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center) }
    }
    FilledTonalButton(onClick = pulse, modifier = Modifier.fillMaxWidth().testTag("today-pulse")) { Text("Check pulse") }
    FilledTonalButton(onClick = workout, modifier = Modifier.fillMaxWidth()) { Text("Workouts") }
    watchValue(rows["floors"], now, daily = true).takeUnless { it == "—" }?.let {
        Text("$it floors", style = MaterialTheme.typography.bodySmall)
    }
    watchValue(rows["energy"], now, daily = true).takeUnless { it == "—" }?.let {
        Text("$it kcal · total", style = MaterialTheme.typography.bodySmall)
    }
    FilledTonalButton(onClick = recovery, modifier = Modifier.fillMaxWidth()) { Text("Sleep & energy") }
    measurements?.let { FilledTonalButton(onClick = it, modifier = Modifier.fillMaxWidth()) { Text("Measure") } }
}
