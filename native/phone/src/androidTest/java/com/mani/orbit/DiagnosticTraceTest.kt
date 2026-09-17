package com.mani.orbit

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mani.orbit.sync.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Random
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DiagnosticTraceTest {
    @Test fun seededConfirmationReplayIdentifiesTheStalledBoundaryWithoutPersonalData() {
        fun replay(commit: Boolean): JSONObject {
            var nanos = 100_000_000_000L
            val trace = TraceLedger { nanos }
            val random = Random(915)
            fun id() = UUID(random.nextLong(), random.nextLong()).toString()
            val w = WatchWorkout(id(), 10, "Walking", id(), 10000, 1000, 15000, 6000, 5000, "active", false)
            val request = WorkoutControl(id(), id(), w.id, w.boot, w.phase, "pause")
            val op = trace.begin(TraceFeature.WATCH_CONTROL, TraceRoute.WORKOUTS)
            nanos += 16_000_000
            trace.mark(op, TraceStage.COMMAND_REQUESTED)
            val pending = PendingWatchControl(request, "synthetic-record", w.revision, "synthetic-peer", 6000)
            val ready = request.copy(stage = "ready", token = id())
            val committing = pending.response(ready, pending.node, 6001)!!
            val accepted = committing.response(ready.copy(stage = "accepted"), pending.node, 6002)!!
            nanos += 10_000_000
            trace.mark(op, TraceStage.API_ACCEPTED)
            assertFalse(accepted.confirmed(w)) // API receipt is not confirmation of recording state.
            assertNull(accepted.response(ready, "unrelated-peer", 6003))
            if (commit) {
                assertTrue(accepted.confirmed(w.copy(phase = "paused", revision = 11)))
                nanos += 30_000_000
                trace.mark(op, TraceStage.PLATFORM_CONFIRMED)
                trace.mark(op, TraceStage.DURABLE_COMMIT)
                nanos += 16_000_000
                trace.mark(op, TraceStage.DISPLAY_UPDATED)
            }
            return trace.snapshot().also { snapshot ->
                val text = snapshot.toString()
                for (secret in listOf(w.id, w.boot, request.installation, pending.recordId, pending.node, ready.token!!)) assertFalse(text.contains(secret))
            }
        }
        val stalled = replay(false)
        assertEquals("API_ACCEPTED", stalled.getJSONArray("operations").getJSONObject(0).getString("lastStage"))
        assertFalse(stalled.getJSONArray("operations").getJSONObject(0).getBoolean("complete"))
        val confirmed = replay(true)
        assertEquals(replay(true).getJSONArray("events").toString(), confirmed.getJSONArray("events").toString())
        assertEquals("DISPLAY_UPDATED", confirmed.getJSONArray("operations").getJSONObject(0).getString("lastStage"))
        assertEquals(72_000_000, confirmed.getJSONArray("events").getJSONObject(5).getLong("atNanos"))
        assertNotEquals(stalled.getString("run"), confirmed.getString("run"))
    }

    @Test fun retentionAndFrameAggregationStayBoundedWithConcurrentCallbacks() {
        val trace = TraceLedger()
        trace.quality(TraceTier.PHONE_READABILITY, TraceQualityReason.THERMAL)
        val worker = Thread { repeat(50_000) { trace.frame(TraceRoute.WORKOUTS, TraceTier.PHONE_RETAINED_FROST, TraceGesture.DRAG, it % 5 == 0, 16_000_000) } }
        worker.start()
        repeat(1000) {
            val op = trace.begin(TraceFeature.EXPLORE, TraceRoute.HEALTH)
            trace.mark(op, TraceStage.COMMAND_REQUESTED)
            trace.mark(op, TraceStage.DISPLAY_UPDATED)
            assertFalse(trace.mark(op, TraceStage.FAILED)) // A stale callback cannot rewrite a completed trace.
            if (it % 50 == 0) trace.snapshot()
        }
        worker.join(5000); assertFalse(worker.isAlive)
        val snapshot = trace.snapshot()
        assertEquals("PHONE_READABILITY", snapshot.getJSONObject("rendering").getString("tier"))
        assertEquals("THERMAL", snapshot.getJSONObject("rendering").getString("reason"))
        assertEquals(setOf("tier", "reason"), snapshot.getJSONObject("rendering").keys().asSequence().toSet())
        assertEquals(128, snapshot.getJSONArray("events").length())
        assertEquals(32, snapshot.getJSONArray("operations").length())
        assertEquals(2872, snapshot.getInt("droppedEvents"))
        val frames = snapshot.getJSONArray("frames")
        assertEquals(1, frames.length())
        assertEquals(50_000, frames.getJSONObject(0).getInt("frames"))
        assertEquals(10_000, frames.getJSONObject(0).getInt("janky"))
        assertTrue(snapshot.toString().toByteArray().size < NativeDiagnostics.MAX_BYTES)
        assertFalse(trace.mark(Long.MAX_VALUE, TraceStage.FAILED))
    }

    @Test fun installedNativeIdentityAndLocalDiagnosticFileAgree() {
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val identity = context.assets.open("orbit-native-build.json").bufferedReader().use { JSONObject(it.readText()) }
        assertEquals("orbit-native", identity.getString("product"))
        assertTrue(identity.getString("source").matches(Regex("[a-f0-9]{64}")))
        val op = NativeDiagnostics.begin(TraceFeature.EXPLORE, TraceRoute.STEPS)
        val run = NativeDiagnostics.trace.snapshot().getString("run")
        NativeDiagnostics.mark(op, TraceStage.COMMAND_REQUESTED)
        val expired = File(context.cacheDir, "diagnostics/trace.json.bak")
        expired.parentFile!!.mkdirs()
        expired.writeText("expired synthetic diagnostic")
        assertTrue(expired.setLastModified(System.currentTimeMillis() - NativeDiagnostics.RETENTION_MS - 1000))
        val file = File(context.cacheDir, "diagnostics/trace.json")
        val deadline = android.os.SystemClock.elapsedRealtime() + 7000
        var written: JSONObject? = null
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (file.exists()) {
                val value = JSONObject(file.readText())
                if (value.getString("run") == run && value.getJSONObject("build").getString("source") == identity.getString("source") &&
                    value.getJSONArray("operations").let { a -> (0 until a.length()).any { a.getJSONObject(it).getLong("op") == op } }) {
                    written = value; break
                }
            }
            Thread.sleep(50)
        }
        assertNotNull("Bounded local trace must actually be written", written)
        assertFalse("Expired diagnostics must be removed on next access", expired.exists())
        assertTrue(file.length() <= NativeDiagnostics.MAX_BYTES)
        assertEquals(setOf("schema", "run", "clock", "droppedEvents", "events", "operations", "frames", "rendering", "build", "configuration"), written!!.keys().asSequence().toSet())
        assertEquals("COMMAND_REQUESTED", written.getJSONArray("operations").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.first { it.getLong("op") == op } }.getString("lastStage"))
        NativeDiagnostics.mark(op, TraceStage.CANCELLED)
    }
}
