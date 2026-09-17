package com.mani.orbit

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mani.orbit.sync.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.util.UUID

class SweatEstimateTest {
    private fun id() = UUID.randomUUID().toString()
    private val profile = MeasurementProfile(LocalDate.of(1995, 2, 3), "male", 180.0, 80.0)
    private fun initial() = SweatEstimate(id(), id(), id(), 1000, 1, "tracking", 1_700_000_000_000, 1000, profile)
    private fun pending(r: SweatEstimate) = r.copy(revision = 2, phase = "pending", at = r.at + 600_000, elapsed = r.elapsed + 600_000)
    private fun final(r: SweatEstimate, status: Int = 0) = r.copy(revision = 3, phase = if (status == 0) "complete" else "unavailable",
        status = status, rawMl = if (status == 0) 240.0 else 0.0, reason = if (status == 0) null else "SDK_STATUS",
        sensorAt = r.at + 1000, at = r.at + 1000, elapsed = r.elapsed + 1000)
    private fun workout(r: SweatEstimate) = WatchWorkout(r.workout, 2, "Running", r.boot, r.at, r.startElapsed,
        r.at + 600_000, r.startElapsed + 600_000, 600_000, "ended", false)

    @Test fun independentAttachmentPreservesWorkoutAcrossReplayAndReverseDelivery() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = android.content.Intent("com.google.android.gms.wearable.MESSAGE_RECEIVED")
            .setData(android.net.Uri.parse("wear://watch${SweatWire.PATH}")).setPackage(context.packageName)
        assertTrue(context.packageManager.queryIntentServices(intent, 0).any { it.serviceInfo.name == PhoneReadingService::class.java.name })
        val dir = File(context.cacheDir, "sweat-${id()}").apply { check(mkdirs()) }
        val start = initial(); val waiting = pending(start); val done = final(waiting); val w = workout(start)
        try {
            val packets = ReadingJournal(File(dir, "watch.db")).use { j ->
                j.captureWorkoutUpdate(start.installation, w, emptyList())
                listOf(start, waiting, done).forEach { SweatJournal(j).capture(it) }
                assertEquals(w, j.workout(start.installation, w.id))
                assertEquals(1, j.pending(10, WorkoutWire.PATH).size)
                j.pending(10, SweatWire.PATH).also { assertEquals(3, it.size) }
            }
            val receipts = ReadingJournal(File(dir, "phone.db")).use { j ->
                val sweat = SweatJournal(j)
                val receipts = packets.reversed().map { sweat.receive(it.bytes) }
                assertEquals(done, sweat.latest(start.installation, w.id))
                assertEquals(receipts.first(), sweat.receive(packets.last().bytes))
                assertThrows(IllegalArgumentException::class.java) { sweat.receive(SweatWire.encode(done.copy(rawMl = 241.0))) }
                assertThrows(IllegalArgumentException::class.java) { j.captureWorkoutUpdate(start.installation, w.copy(kind = "Walking"), emptyList()) }
                j.captureWorkoutUpdate(start.installation, w, emptyList())
                assertEquals(done, sweat.forWorkout(start.installation, w))
                WatchReading.UNITS.keys.forEach { assertNull(j.latest(it)) }
                receipts
            }
            ReadingJournal(File(dir, "watch.db")).use { j ->
                assertFalse(j.acknowledge(receipts.first().copy(hash = "0".repeat(64))))
                receipts.forEach { assertTrue(j.acknowledge(it)) }
                assertEquals(0, j.pending(10, SweatWire.PATH).size)
                assertEquals(1, j.pending(10, WorkoutWire.PATH).size)
            }
            val legacy = PeerProtocol.support(setOf(ReadingWire.PHONE_CAPABILITY, "orbit_protocol_v1", "orbit_workouts_v1"))
            legacy.require(WireFamily.WORKOUTS, "phone", "Watch")
            assertThrows(IncompatiblePeer::class.java) { legacy.require(WireFamily.SWEAT, "phone", "Watch") }
        } finally { dir.deleteRecursively() }
    }

    @Test fun interruptionStatusBitsMalformedFieldsAndImmutableProfileStayQualified() {
        val start = initial(); val waiting = pending(start)
        for (code in listOf(-10, -8, -4, -3, -2, -1, 1, 3, 64, 127, 500)) {
            val r = final(waiting, code)
            assertNull(r.millilitres); assertEquals(r, SweatWire.decode(SweatWire.encode(r))); assertTrue(r.description().isNotBlank())
        }
        assertTrue(final(waiting, 3).description().contains("5 min"))
        assertTrue(final(waiting, 3).description().contains("distance"))
        assertThrows(IllegalArgumentException::class.java) { final(waiting).copy(rawMl = Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { final(waiting).copy(timeUncertain = true) }
        assertThrows(IllegalArgumentException::class.java) { SweatWire.decode(JSONObject(String(SweatWire.encode(start))).put("revision", 1.5).toString().toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { SweatWire.decode(SweatWire.encode(start) + " extra".toByteArray()) }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(context.cacheDir, "sweat-${id()}").apply { check(mkdirs()) }
        try {
            ReadingJournal(File(dir, "watch.db")).use { j ->
                val sweat = SweatJournal(j); sweat.capture(start); sweat.capture(waiting)
                assertThrows(IllegalArgumentException::class.java) { sweat.capture(final(waiting).copy(profile = profile.copy(weightKg = 90.0))) }
            }
            ReadingJournal(File(dir, "watch.db")).use { j ->
                val sweat = SweatJournal(j); sweat.recoverInterrupted(start.installation)
                val interrupted = sweat.latest(start.installation, start.workout)!!
                assertEquals("INTERRUPTED", interrupted.reason); assertNull(interrupted.millilitres)
                sweat.recoverInterrupted(start.installation)
                assertEquals(3, j.pending(10, SweatWire.PATH).size)
                assertEquals(waiting.elapsed, interrupted.elapsed)
            }
        } finally { dir.deleteRecursively() }
    }
}
