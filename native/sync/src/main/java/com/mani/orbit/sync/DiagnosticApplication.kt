package com.mani.orbit.sync

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.View
import androidx.metrics.performance.JankStats
import androidx.metrics.performance.FrameDataApi31
import androidx.metrics.performance.PerformanceMetricsState
import java.util.WeakHashMap

/** Observe each native window only while visible. Callback data is consumed, never retained. */
open class DiagnosticApplication : Application(), Application.ActivityLifecycleCallbacks {
    private val windows = WeakHashMap<Activity, JankStats>()
    override fun onCreate() {
        super.onCreate()
        NativeDiagnostics.install(this)
        registerActivityLifecycleCallbacks(this)
    }
    override fun onActivityResumed(activity: Activity) {
        val view = activity.window.decorView
        val watch = packageManager.hasSystemFeature("android.hardware.type.watch")
        val tier = if (watch) TraceTier.WATCH_NATIVE else TraceTier.PHONE_RETAINED_FROST
        val initialRoute = when (activity.javaClass.simpleName) {
            "MainActivity" -> TraceRoute.STEPS
            "WatchActivity" -> TraceRoute.WATCH_HOME
            "WatchWorkoutActivity" -> TraceRoute.WATCH_WORKOUT
            "WatchRecoveryActivity" -> TraceRoute.WATCH_RECOVERY
            "WatchHistoryActivity" -> TraceRoute.WATCH_HISTORY
            "WatchMeasurementActivity" -> TraceRoute.WATCH_MEASURE
            else -> TraceRoute.OTHER
        }
        try {
            val stats = windows.getOrPut(activity) {
                JankStats.createAndTrack(activity.window) { frame ->
                    var frameRoute = TraceRoute.OTHER
                    var gesture = TraceGesture.IDLE
                    var actualTier = tier
                    for (state in frame.states) {
                        if (state.key == "route") frameRoute = TraceRoute.entries.firstOrNull { it.name == state.value } ?: TraceRoute.OTHER
                        if (state.key == "gesture") gesture = TraceGesture.entries.firstOrNull { it.name == state.value } ?: TraceGesture.IDLE
                        if (state.key == "tier") actualTier = TraceTier.entries.firstOrNull { it.name == state.value } ?: tier
                    }
                    NativeDiagnostics.trace.frame(frameRoute, actualTier, gesture, frame.isJank, frame.frameDurationUiNanos)
                    val exact = frame as? FrameDataApi31
                    onFrameSample(activity, frame.frameStartNanos, exact?.frameDurationTotalNanos ?: frame.frameDurationUiNanos,
                        exact?.let { it.frameDurationTotalNanos - it.frameOverrunNanos } ?: -1L, actualTier)
                }
            }
            stats.isTrackingEnabled = true
            val state = PerformanceMetricsState.getHolderForHierarchy(view).state
            // Compose owns route changes after the window's first composition.
            if (view.rootView.getTag(R.id.orbit_trace_route) == null) route(view, initialRoute)
            state?.putState("route", (view.rootView.getTag(R.id.orbit_trace_route) as TraceRoute).name)
            state?.putState("tier", (view.rootView.getTag(R.id.orbit_trace_tier) as? TraceTier ?: tier).name)
            state?.putState("gesture", TraceGesture.IDLE.name)
        } catch (_: IllegalStateException) {
            android.util.Log.w("OrbitDiagnostics", "Window frame tracking unavailable")
        }
    }
    override fun onActivityPaused(activity: Activity) { windows[activity]?.isTrackingEnabled = false; NativeDiagnostics.changed() }
    override fun onActivityDestroyed(activity: Activity) { windows.remove(activity)?.isTrackingEnabled = false }
    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    protected open fun onFrameSample(activity: Activity, startNanos: Long, totalNanos: Long, deadlineNanos: Long, tier: TraceTier) = Unit

    companion object {
        fun quality(view: View, tier: TraceTier, reason: TraceQualityReason) {
            val root = view.rootView
            if (root.getTag(R.id.orbit_trace_tier) == tier && root.getTag(R.id.orbit_trace_quality_reason) == reason) return
            root.setTag(R.id.orbit_trace_tier, tier); root.setTag(R.id.orbit_trace_quality_reason, reason)
            PerformanceMetricsState.getHolderForHierarchy(view).state?.putState("tier", tier.name)
            NativeDiagnostics.trace.quality(tier, reason)
            NativeDiagnostics.changed()
        }
        fun route(view: View, route: TraceRoute) {
            view.rootView.setTag(R.id.orbit_trace_route, route)
            PerformanceMetricsState.getHolderForHierarchy(view).state?.putState("route", route.name)
        }
        fun gesture(view: View, gesture: TraceGesture) {
            PerformanceMetricsState.getHolderForHierarchy(view).state?.putState("gesture", gesture.name)
        }
    }
}
