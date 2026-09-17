package com.mani.orbit.wear

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import androidx.wear.compose.material3.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class WatchActivity : ComponentActivity() {
    private var requestedPage = 0
    private var navigationRevision by mutableIntStateOf(0)
    private var permissionRevision by mutableIntStateOf(0)
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionRevision++
        WatchCollectionWorker.schedule(this)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) route(intent) else {
            requestedPage = savedInstanceState.getInt("orbit-route-page")
            navigationRevision = savedInstanceState.getInt("orbit-route-revision")
        }
        setContent { WatchEnvironment { WatchHome(it) } }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("orbit-route-page", requestedPage)
        outState.putInt("orbit-route-revision", navigationRevision)
        super.onSaveInstanceState(outState)
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); route(intent) }
    private fun route(intent: Intent) {
        val destination = intent.getStringExtra("orbit-page")
        requestedPage = if (destination == "heart") 1 else 0
        navigationRevision++
        if (destination == "workouts") startActivity(Intent(this, WatchWorkoutActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP))
    }

    @Composable private fun WatchHome(display: WatchDisplay) {
        val ambient = display.ambient != null
        val store = remember { WatchStore(this) }
        var rows by remember { mutableStateOf(emptyMap<String, JSONObject?>()) }
        var loadingReadings by remember { mutableStateOf(true) }
        var collection by remember { mutableStateOf("") }
        var sync by remember { mutableStateOf("") }
        var measure by remember { mutableStateOf("") }
        var loadError by remember { mutableStateOf<String?>(null) }
        var connectionError by remember { mutableStateOf<String?>(null) }
        var collectionError by remember { mutableStateOf<String?>(null) }
        var measurementError by remember { mutableStateOf<String?>(null) }
        var pending by remember { mutableLongStateOf(0) }
        val now = display.wall
        var enabled by remember { mutableStateOf(store.enabled()) }
        var changingCollection by remember { mutableStateOf(false) }
        var showSyncPrivacy by rememberSaveable { mutableStateOf(false) }
        var pulseRequest by remember { mutableStateOf<PulseRequest?>(null) }
        val measuring = pulseRequest != null
        val pager = rememberPagerState(initialPage = requestedPage) { 3 }
        var handledNavigation by rememberSaveable { mutableIntStateOf(navigationRevision) }
        val scope = rememberCoroutineScope()
        val haptic = LocalHapticFeedback.current
        val activityGranted = remember(permissionRevision) { WatchPermissions.granted(this, Manifest.permission.ACTIVITY_RECOGNITION) }
        val activityRationale = remember(permissionRevision) { shouldShowRequestPermissionRationale(Manifest.permission.ACTIVITY_RECOGNITION) }
        val setup = watchStepSetup(enabled, changingCollection, activityGranted, activityRationale)
        fun changeCollection() {
            if (changingCollection) return
            val desired = !enabled
            changingCollection = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { store.enable(desired) }
                    enabled = desired; collectionError = null
                    haptic.performHapticFeedback(if (enabled) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                    // Daily activity and pulse are separate choices; heart access stays on its own action.
                    if (enabled && !WatchPermissions.granted(this@WatchActivity, Manifest.permission.ACTIVITY_RECOGNITION))
                        permissions.launch(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION))
                    WatchCollectionWorker.schedule(this@WatchActivity)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    collectionError = if (enabled == desired) "Tracking could not start. Try again." else "Tracking setting could not be saved"
                    haptic.performHapticFeedback(HapticFeedbackType.Reject)
                }
                finally { changingCollection = false }
            }
        }
        val lavender = Color(0xFFBBA1ED)
        LaunchedEffect(navigationRevision, ambient) {
            if (!ambient && handledNavigation != navigationRevision) {
                if (pager.currentPage != requestedPage) pager.requestScrollToPage(requestedPage)
                handledNavigation = navigationRevision
            }
        }
        DisposableEffect(lifecycle) {
            val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) pulseRequest = null }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer) }
        }
        LaunchedEffect(ambient) {
            if (ambient) { pulseRequest = null; return@LaunchedEffect }
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                WatchSyncWorker.schedule(this@WatchActivity, reconnect = true)
                try { withContext(Dispatchers.IO) { WatchSamples.battery(this@WatchActivity) }; connectionError = null }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { connectionError = "Battery state could not be saved" }
                while (isActive) {
                    try {
                        val snapshot = withContext(Dispatchers.IO) { store.journal().use { journal ->
                            val boot = store.clock().boot
                            listOf("heart", "steps", "distance", "energy", "floors", "battery")
                                .associateWith { journal.latest(it, store.installation, boot) } to journal.pendingCount()
                        } }
                        rows = snapshot.first; pending = snapshot.second
                        collection = store.status("collection"); sync = store.status("sync"); measure = store.status("measure")
                        loadError = null
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { loadError = "Saved readings unavailable. Retrying…" }
                    loadingReadings = false
                    delay(1000)
                }
            }
        }
        LaunchedEffect(lifecycle) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                // A rapid Stop/Measure waits for the old callback to unregister before starting again.
                snapshotFlow { pulseRequest }.collectLatest { request ->
                    if (request != null) {
                        try { HeartMeasurement(this@WatchActivity).run() }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) {
                            if (pulseRequest == request) measurementError = failure.message ?: "Measurement could not start"
                        } finally { if (pulseRequest == request) pulseRequest = null }
                    }
                }
            }
        }
        LaunchedEffect(pager.currentPage) { if (pager.currentPage != 1) pulseRequest = null }
        BackHandler(pager.settledPage != 0) { scope.launch { pager.animateScrollToPage(0) } }
        MaterialTheme(colorScheme = ColorScheme(primary = lavender, background = Color(0xFF0B0A0F), surfaceContainer = Color(0xFF201D27))) {
            if (ambient) { WatchAmbientScreen("Orbit", "Your health, on your wrist", display); return@MaterialTheme }
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), horizontalAlignment = Alignment.CenterHorizontally) {
                WatchPager(pager, Modifier.weight(1f).testTag("watch-home-pager")) { page ->
                    WorkoutPage(active = pager.settledPage == page, compact = true) {
                        Text(listOf("Watch steps", "Heart rate", "Connection")[page], style = MaterialTheme.typography.titleSmall)
                        when (page) {
                            0 -> WatchToday(rows, now, loadingReadings, loadError,
                                pulse = { scope.launch { pager.animateScrollToPage(1) } },
                                workout = { startActivity(Intent(this@WatchActivity, WatchWorkoutActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)) },
                                recovery = { startActivity(Intent(this@WatchActivity, WatchRecoveryActivity::class.java)) },
                                setup = setup, setupError = collectionError,
                                measurements = { startActivity(Intent(this@WatchActivity, WatchMeasurementActivity::class.java)) },
                                configure = { when (setup) {
                                    WatchStepSetup.Enable -> changeCollection()
                                    WatchStepSetup.Allow -> permissions.launch(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION))
                                    WatchStepSetup.Settings -> startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                                    else -> Unit
                                } })
                            1 -> {
                                WatchPulseReading(rows["heart"], now, display.elapsed, pulseRequest, measure, measurementError ?: loadError)
                                FilledTonalButton(onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    measurementError = null
                                    if (measuring) pulseRequest = null
                                    else if (WatchPermissions.granted(this@WatchActivity, WatchPermissions.heart)) {
                                        try {
                                            val boot = store.clock().boot
                                            measure = "Finding your pulse…"
                                            pulseRequest = PulseRequest(boot, android.os.SystemClock.elapsedRealtime())
                                        } catch (_: Exception) { measurementError = "Measurement could not start. Try again." }
                                    } else permissions.launch(arrayOf(WatchPermissions.heart))
                                }, modifier = Modifier.fillMaxWidth()) { Text(if (measuring) "Stop" else "Measure") }
                            }
                            else -> {
                                (collectionError ?: connectionError ?: loadError)?.let { Text(it, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center) }
                                Text(if (pending == 0L) "No pending readings" else "$pending batches saved", color = lavender, textAlign = TextAlign.Center)
                                Text(sync.ifBlank { "Readings stay on your watch until your phone confirms receipt" }, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                                FilledTonalButton(onClick = { WatchSyncWorker.schedule(this@WatchActivity, reconnect = true) }, modifier = Modifier.fillMaxWidth()) { Text("Sync now") }
                                Text(collection.ifBlank { "Background collection off" }, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                                FilledTonalButton(onClick = ::changeCollection, enabled = !changingCollection, modifier = Modifier.fillMaxWidth()) {
                                    Text(if (enabled) "Turn off" else "Collect in background", textAlign = TextAlign.Center)
                                }
                                if (enabled && !WatchPermissions.granted(this@WatchActivity, WatchPermissions.background)) FilledTonalButton(contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp), onClick = {
                                    if (WatchPermissions.granted(this@WatchActivity, WatchPermissions.heart)) {
                                        if (shouldShowRequestPermissionRationale(WatchPermissions.background!!))
                                            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                                        else permissions.launch(arrayOf(WatchPermissions.background!!))
                                    } else permissions.launch(arrayOf(WatchPermissions.heart))
                                }, modifier = Modifier.fillMaxWidth()) { Text("Allow background heart rate", style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center) }
                                Text(watchValue(rows["battery"], now) + "% battery", style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { showSyncPrivacy = !showSyncPrivacy }, modifier = Modifier.fillMaxWidth()) {
                                    Text(if (showSyncPrivacy) "Hide sync details" else "How sync works", style = MaterialTheme.typography.labelSmall)
                                }
                                if (showSyncPrivacy) {
                                    Text("Google Play services uses Bluetooth or an end-to-end encrypted Google relay when needed.", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                                    Text("Saved on your devices. No Orbit cloud account.", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                                }
                            }
                        }
                    }
                }
                Row(Modifier.padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    repeat(3) { Box(Modifier.size(if (it == pager.currentPage) 14.dp else 5.dp, 4.dp).background(if (it == pager.currentPage) lavender else Color.DarkGray, CircleShape)) }
                }
            }
        }
    }
    override fun onResume() { super.onResume(); permissionRevision++; WatchCollectionWorker.schedule(this) }
}

internal fun watchValue(row: JSONObject?, now: Long, daily: Boolean = false, divisor: Double = 1.0): String {
    if (row == null || row.isNull("value") || row.optString("quality") != "valid" || row.optBoolean("timeUncertain") || row.optLong("end") > now) return "—"
    if (!row.optDouble("value").isFinite()) return "—"
    if (daily && Instant.ofEpochMilli(row.getLong("end")).atZone(ZoneId.systemDefault()).toLocalDate() != Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()) return "—"
    val format = if (divisor != 1.0) "%.2f" else if (row.optString("metric") == "floors" && row.getDouble("value") % 1 != 0.0) "%.1f" else "%,.0f"
    return String.format(Locale.UK, format, row.getDouble("value") / divisor)
}
internal fun readingAge(row: JSONObject?, now: Long): String {
    if (row == null) return "No reading yet"
    if (row.optBoolean("timeUncertain") || row.optLong("end") > now) return "Reading time uncertain"
    if (row.optString("quality") != "valid") return if (row.optString("quality") == "no_contact") "Check watch contact" else "Waiting for a reliable reading"
    val minutes = (now - row.optLong("end")) / 60_000
    return if (minutes < 1) "Recorded just now" else if (minutes < 60) "Recorded ${minutes}m ago" else "Recorded ${minutes / 60}h ago"
}
