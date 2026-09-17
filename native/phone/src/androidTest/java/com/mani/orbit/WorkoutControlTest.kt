package com.mani.orbit

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mani.orbit.sync.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WorkoutControlTest {
    private fun id() = UUID.randomUUID().toString()
    private fun rejected(block: () -> Unit) { try { block(); fail("Unsafe control accepted") } catch (_: IllegalArgumentException) { } }
    @Test fun strictWireAndSingleUseHandshakeRejectDelayReplayChangesAndRestarts() {
        val installation = id()
        val w = WatchWorkout(id(), 10, "Walking", id(), 10000, 1000, 15000, 6000, 5000, "active", false)
        val request = WorkoutControl(id(), installation, w.id, w.boot, "active", "pause")
        assertEquals(request, WorkoutControlWire.decode(WorkoutControlWire.encode(request)))
        rejected { WorkoutControlWire.decode(WorkoutControlWire.encode(request) + "{}".toByteArray()) }
        rejected { WorkoutControlWire.decode(ByteArray(WorkoutControlWire.MAX_BYTES + 1)) }
        rejected { request.copy(action = "resume") }
        rejected { request.copy(stage = "commit") }
        rejected { request.copy(token = id()) }
        val gate = WorkoutControlGate()
        val ready = gate.prepare(request, w, installation, w.boot, 6000)
        val commit = WorkoutControlWire.decode(WorkoutControlWire.encode(ready.copy(stage = "commit")))
        rejected { gate.consume(commit.copy(token = id()), w, installation, w.boot, 6001) }
        gate.consume(commit, w.copy(revision = 11, updatedElapsed = 6100), installation, w.boot, 6100)
        rejected { gate.consume(commit, w, installation, w.boot, 6101) }
        rejected { WorkoutControlGate().consume(commit, w, installation, w.boot, 6101) }
        fun fresh() = gate.prepare(request, w, installation, w.boot, 7000).copy(stage = "commit")
        rejected { gate.consume(fresh(), w, installation, w.boot, 7000 + WorkoutControlWire.OFFER_MS + 1) }
        rejected { gate.consume(fresh(), w, id(), w.boot, 7100) }
        rejected { gate.consume(fresh(), w, installation, id(), 7100) }
        rejected { gate.consume(fresh(), w.copy(id = id()), installation, w.boot, 7100) }
        rejected { gate.consume(fresh(), w.copy(phase = "paused"), installation, w.boot, 7100) }
        val cancelled = fresh(); gate.invalidate()
        rejected { gate.consume(cancelled, w, installation, w.boot, 7100) }
        // A long pause does not depend on synchronized wall clocks or frequent sensor callbacks.
        val paused = w.copy(phase = "paused")
        val resume = request.copy(id = id(), phase = "paused", action = "resume")
        val resumeReady = gate.prepare(resume, paused, installation, w.boot, 9_000_000)
        gate.consume(resumeReady.copy(stage = "commit"), paused, installation, w.boot, 9_000_001)
    }

    @Test fun phoneRequiresMatchingPeerLiveHandshakeAndNewConfirmedSourceState() {
        val w = WatchWorkout(id(), 10, "Walking", id(), 10000, 1000, 15000, 6000, 5000, "active", false)
        val request = WorkoutControl(id(), id(), w.id, w.boot, w.phase, "pause")
        val pending = PendingWatchControl(request, "record", w.revision, "paired-node", 6000)
        val ready = request.copy(stage = "ready", token = id())
        assertNull(pending.response(ready, "other-node", 6001))
        assertNull(pending.response(ready.copy(id = id()), pending.node, 6001))
        assertNull(pending.response(ready, pending.node, 6000 + WorkoutControlWire.REQUEST_MS))
        assertNull(pending.response(ready, pending.node, 5999))
        assertNull(pending.response(ready.copy(stage = "accepted"), pending.node, 6001))
        val committing = pending.response(ready, pending.node, 6001)!!
        assertNull(committing.response(ready, pending.node, 6002))
        assertNull(committing.response(ready.copy(stage = "accepted", token = id()), pending.node, 6002))
        val actual = w.copy(phase = "paused", revision = 11)
        assertFalse(committing.confirmed(actual))
        val accepted = committing.response(ready.copy(stage = "accepted"), pending.node, 6002)!!
        assertFalse(accepted.confirmed(w))
        assertFalse(accepted.confirmed(actual.copy(revision = 10)))
        assertFalse(accepted.confirmed(actual.copy(boot = id())))
        assertFalse(accepted.confirmed(actual.copy(id = id())))
        assertTrue(accepted.confirmed(actual))
        assertNull(accepted.response(request.copy(stage = "rejected"), pending.node, 6003))
    }
}
