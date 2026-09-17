package com.mani.orbit.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material3.*
import com.mani.orbit.sync.HealthContext
import com.mani.orbit.sync.RecoveryDay
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class WatchRecoveryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WatchEnvironment { display ->
            var context by remember { mutableStateOf<HealthContext?>(null) }
            var loading by remember { mutableStateOf(true) }
            var error by remember { mutableStateOf<String?>(null) }
            var requestError by remember { mutableStateOf<String?>(null) }
            var attempt by remember { mutableIntStateOf(0) }
            LaunchedEffect(display.ambient != null, attempt) {
                if (display.ambient != null) return@LaunchedEffect
                lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    // Load the offline cache first. Requesting a fresh publication never blocks the saved view.
                    launch {
                        try { withContext(Dispatchers.IO) { requestPhoneHealthContext(this@WatchRecoveryActivity) }; requestError = null }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (incompatible: com.mani.orbit.sync.IncompatiblePeer) { requestError = incompatible.message }
                        catch (_: Exception) { requestError = "Phone unavailable · saved view" }
                    }
                    while (isActive) {
                        try {
                            context = withContext(Dispatchers.IO) { loadWatchHealthContext(this@WatchRecoveryActivity) }
                            error = null
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: com.mani.orbit.sync.UnsupportedWire) { error = "Update Orbit on your Watch for sleep and energy." }
                        catch (_: Exception) { error = "Saved phone readings could not be checked" }
                        finally { loading = false }
                        delay(15_000)
                    }
                }
            }
            WatchRecoveryScreen(context, loading, error ?: requestError, display) { attempt++ }
        } }
    }
}

@Composable internal fun WatchRecoveryScreen(incoming: HealthContext?, loading: Boolean, error: String?, display: WatchDisplay, refresh: () -> Unit) {
    val purple = Color(0xFFBBA1ED)
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var detail by rememberSaveable { mutableStateOf<String?>(null) }
    var presentedContext by remember { mutableStateOf(incoming) }
    val context = presentedContext
    var touching by remember { mutableStateOf(false) }
    val days = context?.days.orEmpty()
    val pager = rememberPagerState(initialPage = days.indexOfFirst { it.date.toString() == selected }.coerceAtLeast(0)) { maxOf(1, days.size) }
    var positioned by remember { mutableStateOf(false) }
    val today = Instant.ofEpochMilli(display.wall).atZone(ZoneId.of(context?.zone ?: ZoneId.systemDefault().id)).toLocalDate()
    LaunchedEffect(incoming, detail) {
        // Apply publication changes after the finger and native fling settle, using the old
        // presentation's selected identity. New rows must not replace a held page or button.
        snapshotFlow { !touching && !pager.isScrollInProgress }.first { it }
        val anchor = if (positioned && detail == null) days.getOrNull(pager.settledPage)?.date?.toString() ?: selected else selected
        positioned = false
        presentedContext = incoming
        val updated = incoming?.days.orEmpty()
        if (detail == null && updated.isNotEmpty()) {
            val index = updated.indexOfFirst { it.date.toString() == anchor }.coerceAtLeast(0)
            selected = updated[index].date.toString()
            pager.requestScrollToPage(index)
            positioned = true
        }
    }
    LaunchedEffect(pager.settledPage, positioned, detail) {
        if (positioned && detail == null) days.getOrNull(pager.settledPage)?.let { selected = it.date.toString() }
    }
    BackHandler(detail != null && days.isNotEmpty()) { detail = null }
    MaterialTheme(colorScheme = ColorScheme(primary = purple, background = Color(0xFF0B0A0F), surfaceContainer = Color(0xFF201D27))) {
        if (display.ambient != null) { WatchAmbientScreen("Sleep & energy", "Open for saved readings", display); return@MaterialTheme }
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).testTag("watch-recovery").pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                touching = true
                try { do { val event = awaitPointerEvent(PointerEventPass.Initial) } while (event.changes.any { it.pressed }) }
                finally { touching = false }
            }
        }) {
            when {
                days.isEmpty() -> WorkoutPage {
                    Text("Sleep & energy", style = MaterialTheme.typography.titleSmall)
                    Text(if (loading) "Checking saved readings…" else error ?: "No recent readings shared", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                    if (!loading) {
                        if (error == null) Text("Connect Samsung Health in Orbit on your phone.", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                        FilledTonalButton(onClick = refresh, modifier = Modifier.fillMaxWidth()) { Text("Check again") }
                    }
                }
                detail != null -> WorkoutPage {
                    val day = days.firstOrNull { it.date.toString() == detail }
                    if (day == null) Text("This day is no longer shared", style = MaterialTheme.typography.bodySmall)
                    else {
                        Text(day.date.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK)), style = MaterialTheme.typography.titleSmall)
                        Text("Samsung Health", fontSize = 11.sp, color = purple)
                        WatchNumber(recoveryDuration(day.asleepMs), Modifier.testTag("recovery-detail-sleep"))
                        Text("Time asleep", style = MaterialTheme.typography.bodySmall)
                        if (day.start != null) Text("${recoveryTime(day.start!!, context!!.zone)} – ${recoveryTime(day.end!!, context.zone)} · ${day.sessions} ${if (day.sessions == 1) "session" else "sessions"}",
                            style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                        RecoveryStages(day)
                        day.sleepScore?.let { Text("Latest sleep score · ${recoveryScore(it)}/100", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center) }
                        day.energyScore?.let { Text("Energy score · ${recoveryScore(it)}/100", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center) }
                        Text("Scores recorded by Samsung Health", fontSize = 10.sp, textAlign = TextAlign.Center)
                        Text(if (context!!.importedAt > display.wall + 60_000 || context.generatedAt > display.wall + 60_000 || day.date > today) "Phone reading time uncertain"
                            else "Phone updated ${recoveryDateTime(context.importedAt, context.zone)}", fontSize = 10.sp, textAlign = TextAlign.Center)
                        if (context.zone != ZoneId.systemDefault().id) Text("Phone time · ${context.zone}", fontSize = 10.sp, textAlign = TextAlign.Center)
                        error?.let { Text(it, fontSize = 10.sp, textAlign = TextAlign.Center) }
                    }
                    FilledTonalButton(onClick = refresh, modifier = Modifier.fillMaxWidth()) { Text("Refresh from phone") }
                    FilledTonalButton(onClick = { detail = null }, modifier = Modifier.fillMaxWidth()) { Text("Back") }
                }
                else -> {
                    WatchPager(pager, Modifier.fillMaxSize().padding(bottom = 14.dp)) { page ->
                        days.getOrNull(page)?.let { day -> WorkoutPage(active = pager.settledPage == page, compact = true) {
                            Text(day.date.format(DateTimeFormatter.ofPattern("d MMM", Locale.UK)) + if (day.asleepMs == null && day.energyScore != null) " · Energy" else " · Sleep",
                                fontSize = 11.sp, lineHeight = 13.sp, modifier = Modifier.testTag("recovery-date-$page"))
                            WatchNumber(if (day.asleepMs != null) recoveryDuration(day.asleepMs) else day.energyScore?.let(::recoveryScore) ?: "—",
                                Modifier.testTag("recovery-value-$page"))
                            if (day.asleepMs == null) Text(if (day.energyScore != null) "Energy score / 100" else "Sleep stages not shared", fontSize = 10.sp, lineHeight = 12.sp)
                            val uncertain = context!!.importedAt > display.wall + 60_000 ||
                                context.generatedAt > display.wall + 60_000 || day.date > today
                            // The saved-view marker shares the context line, so the first action stays clear of the fade.
                            val saved = if (error != null && !uncertain) " · Saved view" else ""
                            if (day.asleepMs != null && day.energyScore != null)
                                Text("Energy · ${recoveryScore(day.energyScore!!)}/100$saved", fontSize = 12.sp, lineHeight = 14.sp, textAlign = TextAlign.Center)
                            else Text("Samsung Health$saved", fontSize = 10.sp, lineHeight = 12.sp, textAlign = TextAlign.Center)
                            if (uncertain) Text("Reading time uncertain", style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                            FilledTonalButton(onClick = { selected = day.date.toString(); detail = selected },
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("recovery-details-$page")) { Text("Details", style = MaterialTheme.typography.labelMedium) }
                        } }
                    }
                    if (days.size > 1) WatchPageIndicator(pager, Modifier.align(Alignment.BottomCenter))
                }
            }
        }
    }
}

@Composable private fun RecoveryStages(day: RecoveryDay) {
    val colours = mapOf("awake" to Color(0xFFFF719D), "rem" to Color(0xFF66C8E5), "light" to Color(0xFF628EFC), "deep" to Color(0xFF8764DA), "sleeping" to Color(0xFFBBA1ED), "unknown" to Color.Gray, "unrecorded" to Color.DarkGray)
    val stages = listOf("awake", "rem", "light", "deep", "sleeping", "unknown", "unrecorded").filter { (day.stages[it] ?: 0) > 0 }
    if (stages.isEmpty()) return
    Text("Stage totals", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
    for (pair in stages.chunked(2)) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (stage in pair) Column(Modifier.weight(1f).padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.width(20.dp).height(3.dp).background(colours.getValue(stage), RoundedCornerShape(2.dp)))
            Text(when (stage) { "rem" -> "REM"; "unrecorded" -> "Gap"; "sleeping" -> "Sleep"; else -> stage.replaceFirstChar { it.uppercase() } }, fontSize = 10.sp)
            Text(recoveryDuration(day.stages[stage]), fontSize = 12.sp)
        }
        if (pair.size == 1) Spacer(Modifier.weight(1f))
    }
}
internal fun recoveryDuration(ms: Long?): String = ms?.let { val minutes = it / 60_000; if (minutes < 60) "${minutes}m" else "${minutes / 60}h ${minutes % 60}m" } ?: "—"
private fun recoveryScore(score: Double) = String.format(Locale.UK, "%.0f", score)
private fun recoveryTime(at: Long, zone: String) = Instant.ofEpochMilli(at).atZone(ZoneId.of(zone)).format(DateTimeFormatter.ofPattern("HH:mm", Locale.UK))
private fun recoveryDateTime(at: Long, zone: String) = Instant.ofEpochMilli(at).atZone(ZoneId.of(zone)).format(DateTimeFormatter.ofPattern("d MMM · HH:mm", Locale.UK))
