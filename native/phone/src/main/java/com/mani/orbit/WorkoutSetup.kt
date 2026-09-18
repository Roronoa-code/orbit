package com.mani.orbit

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun WorkoutSetup(kind: String, busy: Boolean, ready: Boolean, error: String?, start: (Int, Boolean) -> Unit) {
    var timed by rememberSaveable(kind) { mutableStateOf(false) }
    var minutes by rememberSaveable(kind) { mutableStateOf("30") }
    var gps by rememberSaveable(kind) { mutableStateOf(true) }
    val focus = LocalFocusManager.current
    val scroll = rememberScrollState()
    ObserveHeaderScroll(scroll)
    val reduced = LocalOrbitReducedMotion.current
    val target = if (timed) minutes.toIntOrNull()?.takeIf { it in 1..1440 } else 0
    Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Column(Modifier.weight(1f).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.Center) { WorkoutIcon(kind, Modifier.size(68.dp)) }
            Text("Choose a goal", fontSize = 12.sp, lineHeight = 18.sp, color = WorkoutMuted)
            MeasurementSelector(listOf("No target", "Time"), if (timed) 1 else 0, "workout-target") { timed = it == 1; focus.clearFocus() }
            Crossfade(timed, Modifier.fillMaxWidth().heightIn(min = 146.dp), animationSpec = tween(if (reduced) 0 else 180), label = "workout goal") { time ->
                Box(Modifier.fillMaxWidth().heightIn(min = 146.dp).padding(vertical = 22.dp), contentAlignment = Alignment.Center) {
                    if (time) Row(Modifier.widthIn(max = 220.dp), verticalAlignment = Alignment.CenterVertically) {
                        CompositionLocalProvider(LocalTextSelectionColors provides TextSelectionColors(Color.Transparent, Color.Transparent)) {
                            BasicTextField(minutes, { if (it.length <= 4 && it.all { c -> c in '0'..'9' }) minutes = it }, singleLine = true,
                                textStyle = OrbitTypography.bodyLarge.copy(color = WorkoutWhite, fontSize = 58.sp, textAlign = TextAlign.End),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }), cursorBrush = SolidColor(WorkoutPurple),
                                modifier = Modifier.weight(1f).heightIn(min = 76.dp).testTag("workout-minutes").semantics { contentDescription = "Workout duration in minutes" })
                        }
                        Text("min", color = WorkoutMuted, fontSize = 17.sp, modifier = Modifier.padding(start = 8.dp, end = 16.dp))
                    } else Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Go at your own pace.", color = WorkoutWhite, fontSize = 24.sp, lineHeight = 32.sp, textAlign = TextAlign.Center)
                        Text("Finish whenever you’re ready.", color = WorkoutMuted, fontSize = 13.sp, textAlign = TextAlign.Center)
                    }
                }
            }
            if (kind != "Strength") {
                Column {
                    androidx.compose.material3.HorizontalDivider(color = Color.White.copy(alpha = .06f))
                    SettingsSwitch("Track outdoors", gps, !busy, description = "Phone GPS · route and distance") { gps = it }
                }
            }
            Text(if (kind == "Strength") "Active time and pauses are saved with your workout." else "Turn GPS off for an indoor workout.",
                color = WorkoutMuted, fontSize = 12.sp, lineHeight = 18.sp)
            SettingsMessage(error)
            if (timed && target == null) SettingsMessage("Choose between 1 and 1,440 minutes.")
            Spacer(Modifier.height(12.dp))
        }
        WorkoutButton({ focus.clearFocus(); start(requireNotNull(target), kind != "Strength" && gps) },
            Modifier.fillMaxWidth().padding(top = 12.dp).testTag("workout-start"), enabled = ready && !busy && target != null,
            background = WorkoutPurple) {
            Text(if (busy) "Starting…" else "Start ${kind.lowercase()}", color = Color(0xFF18131F), fontSize = 16.sp,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 18.dp))
        }
    }
}
