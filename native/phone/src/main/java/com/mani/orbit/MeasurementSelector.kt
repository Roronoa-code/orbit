package com.mani.orbit

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The app's segmented control: the navigation bar's own track, inside a page, so every choice among
 * a few in the app looks and moves like the bar does.
 */
@Composable
internal fun MeasurementSelector(labels: List<String>, selected: Int, name: String, select: (Int) -> Unit) {
    val quiet = name == "measurement-period"
    GlassTrack(labels, selected, select, name, Modifier.fillMaxWidth(),
        slotHeight = if (quiet) 36.dp else 44.dp, labelSize = if (quiet) 12.sp else 13.sp)
}
