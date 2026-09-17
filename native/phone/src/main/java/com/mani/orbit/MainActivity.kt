package com.mani.orbit

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import com.mani.orbit.sync.ReadingJournal
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Native Android entry point. Existing services and storage continue to own the data. */
class MainActivity : ComponentActivity() {
    private val model: OrbitModel by viewModels()
    private lateinit var health: NativeSamsungHealth
    private lateinit var workouts: WorkoutSession
    private lateinit var music: NativeMusic
    private var visible = false
    private var pendingLocationStart = -1L
    private val workout = MutableStateFlow(NativeWorkoutState())
    private var workoutRevision = ""
    private val workoutRefresh = Mutex()
    private var healthGeneration = 0L
    internal lateinit var glassQuality: GlassQualityMonitor
        private set

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        glassQuality = GlassQualityMonitor(this)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        window.isNavigationBarContrastEnforced = false
        pendingLocationStart = state?.getLong("pendingLocationStart", -1) ?: -1
        workouts = WorkoutSession(this)
        music = NativeMusic(this)
        healthGeneration = model.attachHealthSource()
        health = NativeSamsungHealth(this)
        PhoneHealthContextWorker.periodic(this)
        PhoneHealthContextWorker.schedule(this)
        setContent {
            val rendering = glassQuality.state.collectAsStateWithLifecycle()
            androidx.compose.runtime.CompositionLocalProvider(LocalGlassQuality provides rendering.value) {
                OrbitApp(model, workout, music.allowed, ::healthAction, ::workoutAction, music.state, music::command)
                WatchCommandFeedback()
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { model.selectedDate.collect { health.load(it) } }
                launch {
                    val projection = WatchWorkoutProjection()
                    while (true) {
                        try {
                            val records = withContext(Dispatchers.IO) {
                                ReadingJournal(getDatabasePath("watch-readings.db")).use(projection::refresh)
                            }
                            if (records != null || workout.value.watchError != null)
                                workout.value = workout.value.copy(watchRecords = records ?: workout.value.watchRecords, watchError = null)
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { workout.value = workout.value.copy(watchError = "Watch workouts could not be loaded. Retrying…") }
                        delay(1000)
                    }
                }
                launch {
                    while (true) {
                        refreshWorkout()
                        delay(if (workout.value.store.optJSONObject("active") == null) 1500 else 250)
                    }
                }
            }
        }
        openWorkoutIntent()
    }

    fun healthChanged() { if (!isDestroyed) model.acceptHealth(healthGeneration, health.snapshot(model.revisionFor(healthGeneration))) }
    private fun healthAction(action: String) {
        when (action) {
            "connect" -> health.connect()
            "live" -> health.connectLive()
            "refresh" -> health.sync()
            "permissions" -> health.permissions()
            "music" -> music.connect()
        }
    }
    private suspend fun refreshWorkout() = workoutRefresh.withLock {
        try {
            val (next, state) = withContext(Dispatchers.IO) {
                val next = JSONObject(workouts.snapshot(workoutRevision))
                require(!next.has("error"))
                val store = next.optString("store").takeIf { it.isNotEmpty() && it != "null" }?.let(::JSONObject)
                val prior = workout.value
                val current = store ?: prior.store
                val active = current.optJSONObject("active")
                require(next.isNull("startedAt") == (active == null))
                if (active != null) require(next.getLong("startedAt") == active.getLong("startedAt"))
                val elapsed = next.getLong("elapsedMs").also { require(it >= 0) }
                val total = next.getLong("totalMs").also { require(it >= elapsed) }
                val countdown = next.getLong("startsInMs").also { require(it >= 0) }
                next to prior.copy(store = current, elapsedMs = elapsed / 1000 * 1000, totalMs = total / 1000 * 1000,
                    startsInMs = (countdown + 999) / 1000 * 1000,
                    active = if (active == null) null else (if (store != null || prior.active == null)
                        WorkoutData.local(active, true, elapsed / 1000 * 1000, total / 1000 * 1000)
                        else prior.active.copy(elapsed = elapsed / 1000 * 1000, total = total / 1000 * 1000)),
                    history = if (store != null) WorkoutData.history(store) else prior.history, loading = false, readError = null)
            }
            workoutRevision = next.getString("revision")
            workout.value = state.copy(busy = workout.value.busy, actionError = workout.value.actionError,
                watchRecords = workout.value.watchRecords, watchError = workout.value.watchError)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { workout.value = workout.value.copy(loading = false, readError = "Saved workouts could not be read. Your history is preserved.") }
    }
    private fun workoutAction(action: String, kind: String, minutes: Int, gps: Boolean) {
        if (workout.value.busy) return
        if (action == "start" && (kind !in WorkoutKinds || minutes !in 0..1440 || model.profile.value == null)) return
        workout.value = workout.value.copy(busy = true, actionError = null)
        lifecycleScope.launch {
            try {
                val success = withContext(Dispatchers.IO) {
                    when (action) {
                        "start" -> workouts.startCountdown(kind, minutes * 60000.0, gps,
                            model.profile.value?.optDouble("weightKg", 0.0)?.takeIf { it.isFinite() } ?: 0.0) != null
                        "cancel" -> workouts.cancelStart()
                        "tracking" -> workouts.enableTracking() != null
                        "notifications" -> { workouts.enableNotifications(); true }
                        "pause", "resume", "finish" -> workouts.action(action, kind, 0.0) != null
                        else -> false
                    }
                }
                if (!success) workout.value = workout.value.copy(actionError = "The workout could not be updated. Please try again.")
                refreshWorkout()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { workout.value = workout.value.copy(actionError = "The workout could not be updated. Please try again.") }
            finally {
                workout.value = workout.value.copy(busy = false)
            }
        }
    }

    fun onWorkoutStarted(startedAt: Long, trackLocation: Boolean) {
        if (!visible) return
        pendingLocationStart = if (trackLocation) startedAt else -1L
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            && android.os.Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 41)
        } else {
            workouts.updateNotification()
            if (trackLocation) requestLocationForWorkout(startedAt)
        }
    }
    fun requestLocationForWorkout(expectedStart: Long) {
        if (!visible || !workouts.activeLocationRequested(expectedStart)) return
        if (!workouts.hasLocationPermission()) {
            pendingLocationStart = expectedStart
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 42)
        } else {
            pendingLocationStart = -1
            if (workouts.activeForLocation(expectedStart) && workouts.startLocation(expectedStart))
                WorkoutTrackingService.startForSession(this, expectedStart)
        }
    }
    fun resumeLocationTracking(expectedStart: Long) = requestLocationForWorkout(expectedStart)
    override fun onRequestPermissionsResult(code: Int, permissions: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        val expected = pendingLocationStart
        pendingLocationStart = -1
        if (expected >= 0) {
            if (code == 41 || workouts.hasLocationPermission()) requestLocationForWorkout(expected)
            else workouts.locationPermissionDenied(expected)
        }
        workouts.updateNotification()
    }
    private fun openWorkoutIntent() {
        if (intent.action != "com.mani.orbit.OPEN_WORKOUT") return
        model.openWorkout()
        val startedAt = intent.getLongExtra("resumeStartedAt", -1)
        intent.getStringExtra("resumeToken")?.let { if (startedAt >= 0) workouts.resumeFromNotification(startedAt, it) }
        intent.removeExtra("resumeStartedAt"); intent.removeExtra("resumeToken"); intent.action = null
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); openWorkoutIntent() }
    override fun onSaveInstanceState(out: Bundle) {
        out.putLong("pendingLocationStart", pendingLocationStart)
        super.onSaveInstanceState(out)
    }
    override fun onStart() { super.onStart(); visible = true }
    override fun onResume() { super.onResume(); glassQuality.start(); health.foreground(true); healthChanged(); workouts.updateNotification() }
    override fun onPause() { glassQuality.stop(); health.foreground(false); super.onPause() }
    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        glassQuality.touch(event.actionMasked)
        return super.dispatchTouchEvent(event)
    }
    override fun onStop() { visible = false; super.onStop() }
    override fun onDestroy() { health.close(); music.close(); super.onDestroy() }
}

class OrbitApplication : com.mani.orbit.sync.DiagnosticApplication() {
    override fun onFrameSample(activity: android.app.Activity, startNanos: Long, totalNanos: Long, deadlineNanos: Long, tier: com.mani.orbit.sync.TraceTier) {
        (activity as? MainActivity)?.glassQuality?.frame(startNanos, totalNanos, deadlineNanos, tier)
    }
}
