package com.mani.orbit

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.animateContentSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.dp
import com.mani.orbit.sync.MeasurementPage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable internal fun WatchSensorResult(page: MeasurementPage, select: (String) -> Unit) {
    val result = page.result
    val haptic = LocalHapticFeedback.current
    val primary = result.primary
    var details by rememberSaveable(result.batch.id) { mutableStateOf(false) }
    val labels = mapOf("BODY_FAT" to "Body fat", "BODY_FAT_MASS" to "Fat mass", "BODY_WATER" to "Body water",
        "SKELETAL_MUSCLE_MASS" to "Muscle", "FAT_FREE_MASS" to "Lean mass", "BASAL_METABOLIC_RATE" to "Resting energy",
        "SPO2" to "Blood oxygen", "SKIN_TEMPERATURE" to "Skin temperature", "AMBIENT_TEMPERATURE" to "Surroundings")
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.then(if (LocalOrbitReducedMotion.current) Modifier else Modifier.animateContentSize()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Measurements", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                WorkoutArrow(if (page.orderingUncertain) "Previous measurement" else "Older measurement", -1, page.olderId != null) { page.olderId?.let(select) }
                WorkoutArrow(if (page.orderingUncertain) "Next measurement" else "Newer measurement", 1, page.newerId != null) { page.newerId?.let(select) }
            }
            Text(if (result.tracker == "BIA") "Body composition" else labels.getValue(primary.metric), style = MaterialTheme.typography.titleMedium)
            Text("${String.format(Locale.UK, "%.1f", primary.value)} ${primary.unit}", style = MaterialTheme.typography.headlineLarge)
            if (result.tracker == "BIA") Text("Body fat", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(Instant.ofEpochMilli(result.batch.readings.first().start).atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("d MMM · HH:mm", Locale.UK)), style = MaterialTheme.typography.bodySmall)
            if (page.orderingUncertain) Text("Reading order uncertain", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (details) result.values.filter { it.metric != primary.metric }.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    pair.forEach { value -> Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(labels.getValue(value.metric), style = MaterialTheme.typography.labelMedium)
                        Text("${String.format(Locale.UK, "%.1f", value.value)} ${value.unit}", style = MaterialTheme.typography.titleMedium)
                    } }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            if (result.values.size > 1) TextButton(onClick = { haptic.performHapticFeedback(HapticFeedbackType.SegmentTick); details = !details }) {
                Text(if (details) "Less detail" else "Full result")
            }
            Text("Samsung Watch sensor", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
