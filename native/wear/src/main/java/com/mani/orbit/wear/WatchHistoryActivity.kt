package com.mani.orbit.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.wear.compose.material3.*
import com.mani.orbit.sync.ReadingJournal
import com.mani.orbit.sync.WatchWorkout
import kotlinx.coroutines.*
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class WatchHistoryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = WatchStore(this)
        setContent { WatchEnvironment { WatchHistoryScreen(getDatabasePath("watch-readings.db"), store.installation, it) } }
    }
}

/** Native paging retains only the visible session and neighbours, not a growing archive in memory. */
@Composable internal fun WatchHistoryScreen(file: File, installation: String, display: WatchDisplay) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedSession by remember { mutableStateOf<WatchWorkout?>(null) }
    var count by remember { mutableIntStateOf(0) }
    var revision by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    val pager = rememberPagerState { count.coerceAtLeast(1) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val purple = Color(0xFFBBA1ED)
    LaunchedEffect(retry) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            loading = true
            try {
                val position = withContext(Dispatchers.IO) { ReadingJournal(file).use { it.workoutHistoryPosition(installation, selectedId) } }
                count = position.first; revision++; error = null
                if (count > 0 && detailId == null) {
                    val target = position.second.coerceIn(0, count - 1)
                    if (pager.currentPage != target) pager.requestScrollToPage(target)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "History could not be loaded" }
            loading = false
            awaitCancellation()
        }
    }
    BackHandler(detailId != null) { detailId = null }
    MaterialTheme(colorScheme = ColorScheme(primary = purple, background = Color(0xFF0B0A0F), surfaceContainer = Color(0xFF201D27))) {
        if (display.ambient != null) {
            WatchAmbientScreen("Workout history", "Saved on watch", display)
            return@MaterialTheme
        }
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            when {
                count == 0 -> WorkoutPage {
                    Text("History", style = MaterialTheme.typography.titleSmall)
                    Text(when { loading -> "Loading saved sessions…"; error != null -> error!!; else -> "Your next workout starts your story." },
                        style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                    if (error != null) FilledTonalButton(onClick = { retry++ }) { Text("Retry") }
                }
                detailId != null -> {
                    val id = detailId!!
                    var detailError by remember(id) { mutableStateOf<String?>(null) }
                    val detail by produceState(selectedSession?.takeIf { it.id == id }, id, revision, retry) {
                        try {
                            value = withContext(Dispatchers.IO) { ReadingJournal(file).use { it.workout(installation, id) } }
                            detailError = if (value == null) "This session is no longer available" else null
                        }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { detailError = "This session could not be loaded" }
                    }
                    WorkoutPage {
                        detail?.let { session -> WatchHistoryDetails(session, detailError ?: error, { retry++ }, { detailId = null }) }
                            ?: run {
                                Text("Workout", style = MaterialTheme.typography.titleSmall)
                                Text(detailError ?: "Loading session…", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                                if (detailError != null) FilledTonalButton(onClick = { retry++ }) { Text("Retry") }
                                FilledTonalButton(onClick = { detailId = null }) { Text("Back to history") }
                            }
                    }
                }
                else -> Box(Modifier.fillMaxSize()) {
                    WatchPager(pager, Modifier.fillMaxSize().padding(bottom = 16.dp).testTag("watch-history-pager").semantics {
                        customActions = buildList {
                            if (pager.currentPage > 0) add(CustomAccessibilityAction("Newer workout") { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) }; true })
                            if (pager.currentPage < count - 1) add(CustomAccessibilityAction("Older workout") { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }; true })
                        }
                    }, beyondViewportPageCount = 1) { page ->
                        var pageError by remember(page) { mutableStateOf<String?>(null) }
                        val session by produceState(selectedSession?.takeIf { page == pager.currentPage }, page, revision) {
                            try {
                                value = withContext(Dispatchers.IO) { ReadingJournal(file).use { it.workoutHistoryAt(installation, page) } }
                                pageError = if (value == null) "This session is no longer available" else null
                            }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { pageError = "This session could not be loaded" }
                        }
                        LaunchedEffect(pager.settledPage, session) {
                            if (pager.settledPage == page && session != null) { selectedId = session!!.id; selectedSession = session }
                        }
                        WorkoutPage(active = pager.settledPage == page, compact = true) {
                            session?.let { saved ->
                                WatchHistoryPreview(saved, page, pageError ?: error, {
                                    selectedId = saved.id; selectedSession = saved; detailId = saved.id
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }, { retry++ })
                            } ?: run {
                                Text(pageError ?: error ?: "Loading session…", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                                if (pageError != null || error != null) FilledTonalButton(onClick = { retry++ }) { Text("Retry") }
                            }
                        }
                    }
                    if (count > 1) WatchPageIndicator(pager, Modifier.align(Alignment.BottomCenter))
                }
            }
        }
    }
}

@Composable internal fun WatchHistoryPreview(session: WatchWorkout, page: Int, error: String?, details: () -> Unit, retry: () -> Unit) {
    Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Text(session.kind, fontSize = 12.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("history-kind"))
        if (error != null || session.phase == "interrupted") Text(if (error != null) " · saved" else " · partial", fontSize = 10.sp, lineHeight = 12.sp)
    }
    WatchTime(session.activeMs, Modifier.testTag("history-duration"))
    FilledTonalButton(onClick = details, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("history-details-$page")) { Text("Details", fontSize = 12.sp, lineHeight = 14.sp) }
    Text(if (error != null) "Update failed" else historyDate(session.start), fontSize = 11.sp, lineHeight = 13.sp,
        modifier = Modifier.testTag("history-context"))
    if (session.phase == "interrupted") Text("Interrupted", fontSize = 10.sp, lineHeight = 12.sp)
    if (error != null) {
        Text("Showing saved session", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        FilledTonalButton(onClick = retry, modifier = Modifier.fillMaxWidth().testTag("history-retry-$page")) { Text("Retry") }
    }
}

@Composable internal fun WatchHistoryDetails(session: WatchWorkout, error: String?, retry: () -> Unit, close: () -> Unit) {
    Text(session.kind, style = MaterialTheme.typography.titleSmall)
    Text(if (error != null) "Update failed" else historyDate(session.start), fontSize = 11.sp, lineHeight = 13.sp, textAlign = TextAlign.Center)
    WatchTime(session.activeMs)
    Text("Active time", fontSize = 12.sp, lineHeight = 14.sp)
    if (error != null) FilledTonalButton(onClick = retry, modifier = Modifier.fillMaxWidth()) { Text("Retry") }
    session.distance?.let { HistoryReading("%.2f km".format(Locale.UK, it / 1000), "Distance") }
    session.energy?.let { HistoryReading("%,.0f kcal".format(Locale.UK, it), "Recorded energy") }
    session.steps?.let { HistoryReading("%,d".format(Locale.UK, it), "Steps") }
    session.elevation?.let { HistoryReading("%.1f m".format(Locale.UK, it), "Elevation gained") }
    if (session.distance == null && session.energy == null && session.steps == null && session.elevation == null)
        Text("Only time recorded", style = MaterialTheme.typography.bodySmall)
    Text("${historyTime(session.start)} – ${historyTime(session.updatedAt)}", style = MaterialTheme.typography.bodySmall)
    Text(if (session.phase == "interrupted") "Interrupted · last confirmed totals" else "Recorded by this watch",
        style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
    if (session.timeUncertain) Text("Clock changed during recording", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
    WatchSweatSummary(session)
    FilledTonalButton(onClick = close, modifier = Modifier.fillMaxWidth().testTag("history-close-details")) { Text("Back to history") }
}

@Composable private fun HistoryReading(value: String, label: String) {
    WatchNumber(value)
    Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
}
private fun historyDate(at: Long) = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK))
private fun historyTime(at: Long) = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm", Locale.UK))
