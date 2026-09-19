package com.mani.orbit

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The island's choices, dressed as the owner's US app's navigation bar (see [GlassTrack]): the section
 * you are in is a flat pill, and a finger lifts the selection into a lens of real glass that it can
 * hold or carry to another section, bending the labels at its rim as it goes. On Home, where no
 * section is chosen, the lens rises from nothing under the finger. The island itself is the rail.
 */
@Composable
internal fun ExploreChoices(route: String, enabled: Boolean, page: GlassBackdrop,
    island: com.mani.orbit.backdrop.Backdrop, select: (String) -> Unit) {
    val readability = LocalGlassReadability.current
    val labels = listOf("Body", "Workouts", "Health")
    val routes = listOf("Measurements", "Workouts", "Health")
    val icons = listOf(R.drawable.orbit_body, R.drawable.orbit_workouts, R.drawable.orbit_health).map { painterResource(it) }
    val currentSelect by rememberUpdatedState(select)
    val current = when (route) {
        "Measurements" -> 0
        "Workouts" -> 1
        "Health", "Heart rate", "Sleep", "Nutrition", "Blood oxygen" -> 2
        else -> -1
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val vertical = maxWidth < 340.dp || LocalDensity.current.fontScale > 1.25f
        // A tap on the section you are in still closes the island and takes you to the section itself.
        GlassTrack(labels, current, { currentSelect(routes[it]) }, "explore-choices", Modifier.fillMaxWidth(),
            TrackRole.Navigation, page, enabled = enabled, slotHeight = if (vertical) 60.dp else 44.dp, labelSize = 13.sp,
            reselect = true, style = TrackStyle(rail = false, ink = Color(0xFFDED9E5), chosenInk = Color.White, inset = 4.dp,
                dims = false, recedes = false), icons = icons, iconTint = readability.foreground(Color(0xFFC8B5EE)), stacked = vertical,
            yieldVertical = true, under = island)
    }
}
