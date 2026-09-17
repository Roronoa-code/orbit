package com.mani.orbit

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun SettingsRoute(model: OrbitModel, health: HealthScreenState, musicAllowed: Boolean, action: (String) -> Unit, openWatch: () -> Unit) {
    val profile by model.profile.collectAsStateWithLifecycle()
    val profileError by model.profileError.collectAsStateWithLifecycle()
    val goal by model.stepsGoal.collectAsStateWithLifecycle()
    val goalError by model.goalError.collectAsStateWithLifecycle()
    val reduced by model.reducedMotion.collectAsStateWithLifecycle()
    val rotation by model.globeRotation.collectAsStateWithLifecycle()
    val motionError by model.motionError.collectAsStateWithLifecycle()
    val readability by model.readability.collectAsStateWithLifecycle()
    val readabilityError by model.readabilityError.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var motionSaving by remember { mutableStateOf(false) }
    var motionFeedback by remember { mutableStateOf<String?>(null) }
    var readabilitySaving by remember { mutableStateOf(false) }
    var readabilityFeedback by remember { mutableStateOf<String?>(null) }
    fun saveReadability(transparency: Boolean? = null, contrast: Boolean? = null) {
        readabilitySaving = true
        scope.launch { try { readabilityFeedback = model.saveReadability(transparency, contrast) } finally { readabilitySaving = false } }
    }
    val scroll = rememberScrollState()
    ObserveHeaderScroll(scroll)
    val keyboardVisible = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
    // Keep focused/scrolled-to controls above Explore; content padding alone leaves them covered.
    Column(Modifier.fillMaxSize().testTag("settings-scroll").imePadding()
        .padding(bottom = if (keyboardVisible) 0.dp else 112.dp).drawWithCache {
            val fade = 24.dp.toPx()
            val brush = Brush.verticalGradient(listOf(Color.Transparent, Color(0xFF0B0A0F)), startY = size.height - fade, endY = size.height)
            onDrawWithContent { drawContent(); if (scroll.canScrollForward) drawRect(brush, Offset(0f, size.height - fade), Size(size.width, fade)) }
        }.verticalScroll(scroll)
        .padding(start = 22.dp, end = 22.dp, top = 8.dp, bottom = 24.dp)) {
        ProfileEditor(profile, profileError, model::reloadProfile, reduced, model::saveProfile)
        SettingsSection("Daily goal") { GoalEditor(goal, goalError, model::saveGoal) }
        SettingsSection("Motion") {
            SettingsSwitch("Reduce motion", reduced, !motionSaving) { value ->
                motionSaving = true
                scope.launch(Dispatchers.Main.immediate) { try { motionFeedback = model.saveReducedMotion(value) } finally { motionSaving = false } }
            }
            SettingsSwitch("Globe rotation", rotation && !reduced, !reduced && !motionSaving, change = model::rotateGlobe)
            SettingsMessage(motionFeedback ?: motionError)
        }
        SettingsSection("Readability") {
            SettingsSwitch("Reduce transparency", readability?.reduceTransparency ?: true, readability != null && !readabilitySaving) {
                saveReadability(transparency = it)
            }
            SettingsSwitch("Increase contrast", readability?.increaseContrast == true, readability != null && !readabilitySaving) {
                saveReadability(contrast = it)
            }
            SettingsMessage(readabilityFeedback ?: readabilityError)
            if (readabilityError != null) SettingsAction("Retry reading settings", action = model::reloadProfile)
        }
        SettingsSection("Music") { SettingsAction(if (musicAllowed) "Music access" else "Connect music") { action("music") } }
        SettingsSection("Galaxy Watch") { SettingsAction("Watch readings", action = openWatch) }
        SettingsConnection(health, reduced, action)
        SettingsDisclosure("About Orbit", reduced = reduced) {
            SettingsNote("Samsung Health supplies imported readings. Orbit keeps a private local copy and does not change Samsung Health.")
            SettingsNote("Phone–Watch sync uses Google Play services over Bluetooth, or an end-to-end encrypted Google relay when needed. There is no Orbit cloud account.")
            SettingsNote("Your profile supplies workout defaults. Past sessions keep their original values.")
        }
    }
}

@Composable
internal fun ProfileEditor(profile: JSONObject?, readError: String?, reload: () -> Unit, reduced: Boolean = false,
    save: suspend (String, String, String, String, String?) -> String?) {
    SettingsSection("Profile", first = true) {
        if (profile == null) {
            if (readError == null) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = SettingsPurple)
            else { SettingsMessage(readError); SettingsAction("Retry", action = reload) }
        } else {
            var name by rememberSaveable { mutableStateOf(profile.optString("name")) }
            var birth by rememberSaveable { mutableStateOf(profile.optString("birthDate").split('-').reversed().joinToString("")) }
            var height by rememberSaveable { mutableStateOf(profile.optDouble("heightCm").takeIf { it.isFinite() }?.toString() ?: "") }
            var weight by rememberSaveable { mutableStateOf(profile.optDouble("weightKg").takeIf { it.isFinite() }?.toString() ?: "") }
            var feedback by rememberSaveable { mutableStateOf<String?>(null) }
            var saved by rememberSaveable { mutableStateOf(false) }
            var sex by rememberSaveable { mutableStateOf(profile.optString("sex").takeIf { it in setOf("female", "male") }) }
            var busy by remember { mutableStateOf(false) }
            val scope = rememberCoroutineScope()
            val focus = LocalFocusManager.current
            SettingsNote("Workout defaults and Watch measurements.")
            fun edited() { feedback = null; saved = false }
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Name", color = SettingsMuted, fontSize = 13.sp, modifier = Modifier.weight(.42f))
                SettingsInput(name, { name = it.take(80); edited() }, "Name", Modifier.weight(.58f), !busy, "Optional", right = true)
            }
            HorizontalDivider(color = Color.White.copy(alpha = .05f))
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Date of birth", color = SettingsMuted, fontSize = 13.sp, modifier = Modifier.weight(.42f))
                SettingsInput(birth, { birth = it.filter(Char::isDigit).take(8); edited() }, "Date of birth", Modifier.weight(.58f), !busy,
                    "DD / MM / YYYY", KeyboardType.Number, right = true, mask = BirthDateMask)
            }
            HorizontalDivider(color = Color.White.copy(alpha = .05f))
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Height", color = SettingsMuted, fontSize = 13.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SettingsInput(height, { height = it; edited() }, "Height", Modifier.weight(1f), !busy, type = KeyboardType.Decimal)
                        Text("cm", color = SettingsMuted, fontSize = 12.sp)
                    }
                    HorizontalDivider(color = Color.White.copy(alpha = .08f))
                }
                Column(Modifier.weight(1f)) {
                    Text("Weight", color = SettingsMuted, fontSize = 13.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SettingsInput(weight, { weight = it; edited() }, "Weight", Modifier.weight(1f), !busy, type = KeyboardType.Decimal, done = true)
                        Text("kg", color = SettingsMuted, fontSize = 12.sp)
                    }
                    HorizontalDivider(color = Color.White.copy(alpha = .08f))
                }
            }
            SettingsDisclosure("Body composition", sex?.replaceFirstChar { it.uppercase() } ?: "Optional", reduced = reduced) {
                SettingsNote("Samsung’s body composition sensor uses sex, age, height and weight from this profile.")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("female", "male").forEach { option ->
                        FilterChip(selected = sex == option, enabled = !busy, onClick = { sex = if (sex == option) null else option; edited() },
                            label = { Text(option.replaceFirstChar { it.uppercase() }) })
                    }
                }
            }
            SettingsMessage(feedback, error = !saved)
            Spacer(Modifier.height(18.dp))
            SettingsAction(if (busy) "Saving…" else "Save profile", !busy, primary = true) {
                busy = true; saved = false
                scope.launch(Dispatchers.Main.immediate) { try {
                    val problem = save(name, birth, height, weight, sex)
                    saved = problem == null; feedback = problem ?: "Profile saved"
                    if (saved) focus.clearFocus()
                } finally { busy = false } }
            }
        }
    }
}

@Composable
private fun GoalEditor(goal: Int?, readError: String?, save: suspend (String) -> String?) {
    var value by rememberSaveable { mutableStateOf(goal?.toString() ?: "") }
    var dirty by rememberSaveable { mutableStateOf(false) }
    var feedback by rememberSaveable { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    LaunchedEffect(goal) { if (!dirty) value = goal?.toString() ?: "" }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Steps", color = SettingsMuted, fontSize = 13.sp)
        SettingsInput(value, { value = it; dirty = true; feedback = null; saved = false }, "Daily step goal", Modifier.weight(1f), !busy, type = KeyboardType.Number, right = true, done = true)
        SettingsAction(if (busy) "Saving…" else "Save", !busy) {
            busy = true
            scope.launch(Dispatchers.Main.immediate) { try {
                val problem = save(value); saved = problem == null; feedback = problem ?: "Daily goal saved"
                if (saved) { dirty = false; focus.clearFocus() }
            } finally { busy = false } }
        }
    }
    SettingsMessage(feedback ?: readError, error = !saved)
}

@Composable
private fun SettingsConnection(health: HealthScreenState, reduced: Boolean, action: (String) -> Unit) {
    SettingsDisclosure("Samsung Health", health.status, !health.permitted, reduced) {
        SettingsNote(health.liveStatus)
        SettingsAction(if (health.liveConnected) "Live steps access" else "Connect live steps") { action("live") }
        SettingsNote(health.status + if (health.syncing && health.scanned > 0) " · ${health.scanned} records" else "")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsAction(if (health.permitted) "Access" else "Connect", health.available && !health.syncing) { action("connect") }
            SettingsAction("Import now", health.permitted && !health.syncing) { action("refresh") }
        }
        SettingsDisclosure("Connection & history", reduced = reduced) {
            SettingsNote("Connect directly to Samsung Health and choose the readings Orbit can read.")
            SettingsNote(if (health.lastSync == null) "No records imported yet." else {
                val at = Instant.ofEpochMilli(health.lastSync).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.UK))
                "${health.recordCount?.let { "$it records saved · " } ?: ""}$at. Import now refreshes all available history."
            })
            SettingsNote("Live steps use Samsung’s combined phone and watch total. Watch steps appear after the watch syncs.")
            SettingsNote("This personal development build needs Samsung Health → Settings → About Samsung Health → tap the version ten times → Developer mode → Data Read, then Connect.")
            SettingsNote("Available Samsung records are saved here. Missing readings stay empty.")
            SettingsAction("Health permissions", health.available) { action("permissions") }
        }
    }
}

@Composable
private fun SettingsSection(title: String, first: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = if (first) 8.dp else 20.dp, bottom = 20.dp)) {
        Text(title, color = SettingsInk, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 10.dp))
        content()
    }
    HorizontalDivider(color = Color.White.copy(alpha = .06f))
}

@Composable
private fun SettingsDisclosure(title: String, subtitle: String? = null, initial: Boolean = false, reduced: Boolean, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(initial) }
    val haptic = LocalHapticFeedback.current
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
            expanded = !expanded; haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }.semantics { stateDescription = if (expanded) "Expanded" else "Collapsed"; role = Role.Button }, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = SettingsInk, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                if (subtitle != null) Text(subtitle, color = SettingsMuted, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp))
            }
            Text(if (expanded) "−" else "+", color = SettingsPurple, fontSize = 18.sp, modifier = Modifier.padding(start = 14.dp))
        }
        AnimatedVisibility(expanded, enter = expandVertically(tween(if (reduced) 0 else 210)) + fadeIn(tween(if (reduced) 0 else 120)),
            exit = shrinkVertically(tween(if (reduced) 0 else 210)) + fadeOut(tween(if (reduced) 0 else 100))) {
            Column(Modifier.fillMaxWidth().padding(top = 6.dp), content = content)
        }
    }
    HorizontalDivider(color = Color.White.copy(alpha = .06f))
}

@Composable
private fun SettingsNote(text: String) { Text(text, color = SettingsMuted, fontSize = 12.sp, lineHeight = 19.sp, modifier = Modifier.padding(bottom = 12.dp, top = 4.dp)) }
