package com.mani.orbit

import com.mani.orbit.sync.TraceQualityReason
import com.mani.orbit.sync.TraceTier
import org.junit.Assert.*
import org.junit.Test

class GlassQualityPolicyTest {
    @Test fun productionWindowStopsObservingWhenPausedAndRestoresThermalPolicyAfterRecreation() {
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
        fun await(message: String, predicate: () -> Boolean) {
            val until = android.os.SystemClock.elapsedRealtime() + 7000
            while (!predicate() && android.os.SystemClock.elapsedRealtime() < until) Thread.sleep(25)
            assertTrue(message, predicate())
        }
        val prior = shell("dumpsys thermalservice")
        val overridden = Regex("IsStatusOverride: (true|false)").find(prior)!!.groupValues[1].toBoolean()
        val status = Regex("Thermal Status: ([0-6])").find(prior)!!.groupValues[1]
        val restore = if (overridden) "cmd thermalservice override-status $status" else "cmd thermalservice reset"
        try {
            shell("cmd thermalservice override-status 0")
            androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var monitor: GlassQualityMonitor
                scenario.onActivity {
                    assertTrue("The installed application owns the native frame callback", it.application is OrbitApplication)
                    monitor = it.glassQuality
                }
                await("A rendered production surface must report its actual tier") {
                    com.mani.orbit.sync.NativeDiagnostics.trace.snapshot().optJSONObject("rendering") != null
                }
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                val paused = monitor.state.value
                assertNotEquals("The cool fixture must not begin with severe thermal pressure", TraceQualityReason.THERMAL, paused.reason)
                shell("cmd thermalservice override-status 3")
                Thread.sleep(250)
                assertEquals("Background windows must not keep their thermal observer active", paused, monitor.state.value)
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                fun severe() = monitor.state.value == GlassQualityState(GlassQuality.READABILITY, TraceQualityReason.THERMAL)
                await("Resume must apply the actual public thermal signal", ::severe)
                val trace = java.io.File(instrumentation.targetContext.cacheDir, "diagnostics/trace.json")
                val run = com.mani.orbit.sync.NativeDiagnostics.trace.snapshot().getString("run")
                await("The chosen rendering tier/reason must persist without another navigation action") {
                    if (!trace.isFile) false else org.json.JSONObject(trace.readText()).let {
                        val rendering = it.optJSONObject("rendering")
                        it.optString("run") == run && rendering?.optString("tier") == TraceTier.PHONE_READABILITY.name &&
                            rendering.optString("reason") == TraceQualityReason.THERMAL.name
                    }
                }
                val old = monitor
                scenario.recreate()
                scenario.onActivity { monitor = it.glassQuality }
                assertNotSame(old, monitor)
                await("A recreated window must reattach the public thermal observer", ::severe)
            }
        } finally { shell(restore) }
    }

    @Test fun actualDeadlinesLowerQualityAtReleaseAndRecoveryCannotFlutter() {
        val policy = GlassQualityPolicy(true)
        policy.conditions(0, true, false)
        var time = 1_000_000_000L
        fun frames(count: Int, late: Boolean = false, interval: Long = 16_666_667L) {
            repeat(count) {
                time += interval
                policy.frame(time, if (late) interval + 1 else interval / 2, interval, policy.state.value.quality.traceTier)
            }
        }
        frames(330)
        assertEquals(GlassQuality.OPTICAL, policy.state.value.quality)
        policy.touch(true); frames(3, late = true)
        assertEquals("No normal tier replacement under an owned finger", GlassQuality.OPTICAL, policy.state.value.quality)
        policy.touch(false)
        assertEquals(GlassQualityState(GlassQuality.FROST, TraceQualityReason.FRAME_PRESSURE), policy.state.value)
        repeat(20) { frames(20); frames(1, late = true) }
        assertEquals("Alternating deadline pressure must not oscillate", GlassQuality.FROST, policy.state.value.quality)
        frames(60, interval = 100_000_000L)
        assertEquals("Recovery follows elapsed successful rendering, not a presumed 60Hz count", GlassQuality.OPTICAL, policy.state.value.quality)
        frames(3, late = true); frames(3, late = true)
        assertEquals(GlassQuality.READABILITY, policy.state.value.quality)
        policy.pause(); time += 20_000_000_000L; frames(1)
        assertEquals("Idle/background time is not healthy rendering evidence", GlassQuality.READABILITY, policy.state.value.quality)
        frames(330)
        assertEquals("Recover one tier at a time", GlassQuality.FROST, policy.state.value.quality)
        frames(330)
        assertEquals(GlassQuality.OPTICAL, policy.state.value.quality)
    }

    @Test fun missingSignalsPreferencesCorruptFramesAndUrgentPressureRemainConservative() {
        val policy = GlassQualityPolicy(true)
        policy.conditions(0, false, false)
        var time = 1_000_000_000L
        repeat(600) { time += 16_666_667; policy.frame(time, 1_000_000, 16_666_667, TraceTier.PHONE_RETAINED_FROST) }
        assertEquals(GlassQualityState(GlassQuality.FROST, TraceQualityReason.THERMAL_UNKNOWN), policy.state.value)
        policy.conditions(0, true, false)
        repeat(600) { time += 16_666_667; policy.frame(time, 1_000_000, 16_666_667, TraceTier.PHONE_READABILITY) }
        assertEquals("Cheap opaque override frames cannot qualify full optics", GlassQuality.FROST, policy.state.value.quality)
        repeat(600) { time += 16_666_667; policy.frame(time, -1, 0, TraceTier.PHONE_RETAINED_FROST) }
        assertEquals(GlassQuality.FROST, policy.state.value.quality)
        policy.touch(true); policy.conditions(3, true, false)
        assertEquals("Urgent heat can remove optics without waiting for release", GlassQuality.READABILITY, policy.state.value.quality)
        policy.touch(false); policy.conditions(0, true, true)
        assertEquals(TraceQualityReason.POWER_SAVER, policy.state.value.reason)
        policy.conditions(1, true, false)
        assertEquals("Cooling does not instantly restore expensive effects", GlassQuality.READABILITY, policy.state.value.quality)
        val unsupported = GlassQualityPolicy(false, false)
        unsupported.conditions(0, true, false)
        assertEquals(GlassQualityState(GlassQuality.READABILITY, TraceQualityReason.UNSUPPORTED), unsupported.state.value)
    }
}
