package com.mani.orbit

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mani.orbit.sync.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PeerProtocolTest {
    private fun id() = UUID.randomUUID().toString()
    private fun reading() = WatchReading(id(), 1, 1000, 1000, 3600, "heart", 74.0, "bpm", "valid", "instant", id(), 500)
    private fun workout() = WatchWorkout(id(), 1, "Walking", id(), 1000, 100, 2000, 1100, 1000, "ended", false)
    private fun version2(bytes: ByteArray) = JSONObject(bytes.toString(Charsets.UTF_8)).put("version", 2).toString().toByteArray(Charsets.UTF_8)

    @Test fun authenticatedLegacyCurrentPartialAndFuturePeersNegotiateIndependently() {
        for (role in listOf(ReadingWire.PHONE_CAPABILITY, ReadingWire.WATCH_CAPABILITY)) {
            val legacy = PeerProtocol.support(setOf(role))
            assertFalse(legacy.advertised)
            assertEquals(setOf(WireFamily.READINGS, WireFamily.WORKOUTS, WireFamily.CONTROL, WireFamily.CONTEXT), legacy.families)
            assertFalse(WireFamily.PROFILE in legacy.families)
            val current = PeerProtocol.support(setOf(role, PeerProtocol.CAPABILITY) + WireFamily.entries.map { it.capability })
            assertTrue(current.advertised)
            assertEquals(WireFamily.entries.toSet(), current.families)
            assertFalse(WireFamily.MEASUREMENTS in legacy.families)
            val future = PeerProtocol.support(setOf(role, "orbit_protocol_v2", WireFamily.READINGS.capability,
                WireFamily.WORKOUTS.capability, "orbit_workout_control_v2"))
            future.require(WireFamily.READINGS, "Watch", "phone")
            future.require(WireFamily.WORKOUTS, "Watch", "phone")
            assertEquals("Update Orbit on your phone for workout controls.", assertThrows(IncompatiblePeer::class.java) {
                future.require(WireFamily.CONTROL, "Watch", "phone")
            }.message)
            assertEquals("Update Orbit on your Watch for sleep and energy.", assertThrows(IncompatiblePeer::class.java) {
                future.require(WireFamily.CONTEXT, "Watch", "phone")
            }.message)
            // Lost/partial advertisements cannot accidentally enable the complete legacy profile.
            assertEquals(setOf(WireFamily.READINGS), PeerProtocol.support(setOf(role, WireFamily.READINGS.capability)).families)
            assertTrue(PeerProtocol.support(setOf(role, PeerProtocol.CAPABILITY)).families.isEmpty())
            assertEquals(legacy.families, PeerProtocol.support(setOf(role, "unrelated_capability")).families)
        }
        assertThrows(IllegalArgumentException::class.java) { PeerProtocol.support(emptySet()) }
        assertThrows(IllegalArgumentException::class.java) { PeerProtocol.support(setOf(PeerProtocol.CAPABILITY)) }
    }

    @Test fun everyVersionedFamilyRejectsNewMandatoryFormatsBeforeInterpretation() {
        val batch = ReadingWire.encode(ReadingBatch(id(), id(), listOf(reading())))
        val history = WorkoutWire.encode(WorkoutPacket(id(), id(), workout(), emptyList()))
        val control = WorkoutControlWire.encode(WorkoutControl(id(), id(), id(), id(), "active", "pause"))
        val context = HealthContextWire.encode(HealthContext("Europe/London", 0, 0, emptyList()))
        assertEquals("version", assertThrows(UnsupportedWire::class.java) { ReadingWire.decode(version2(batch)) }.reason)
        assertEquals("version", assertThrows(UnsupportedWire::class.java) { WorkoutWire.decode(version2(history)) }.reason)
        assertEquals("version", assertThrows(UnsupportedWire::class.java) { WorkoutControlWire.decode(version2(control)) }.reason)
        assertEquals("version", assertThrows(UnsupportedWire::class.java) { HealthContextWire.decode(version2(context)) }.reason)
        for (key in listOf("action", "stage")) {
            val unknown = JSONObject(control.toString(Charsets.UTF_8)).put(key, "future-command").toString().toByteArray(Charsets.UTF_8)
            assertEquals("command", assertThrows(UnsupportedWire::class.java) { WorkoutControlWire.decode(unknown) }.reason)
        }
        // Malformed input remains invalid, not an accepted or coerced protocol version.
        for (version in listOf<Any>("1", 1.5, -1, JSONObject.NULL)) assertThrows(IllegalArgumentException::class.java) {
            ReadingWire.decode(JSONObject(batch.toString(Charsets.UTF_8)).put("version", version).toString().toByteArray(Charsets.UTF_8))
        }
    }

    @Test fun blockedHistoryHeadDoesNotBlockReadingsOrDeleteOriginalsOnRejection() {
        val directory = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "protocol-${id()}").apply { check(mkdirs()) }
        val watch = File(directory, "watch.db")
        val phone = File(directory, "phone.db")
        val owner = id()
        try {
            val history = ReadingJournal(watch).use { it.captureWorkout(WorkoutPacket(id(), owner, workout(), emptyList())) }
            val futureBytes = version2(history.bytes)
            val futureHash = ReadingWire.digest(futureBytes)
            // Model a downgrade retaining a future version in the durable outbox; original bytes are untouched by drain selection.
            SQLiteDatabase.openOrCreateDatabase(watch, null).use {
                it.execSQL("UPDATE outbox SET bytes=?,hash=? WHERE id=?", arrayOf(futureBytes, futureHash, history.id))
            }
            val sample = ReadingJournal(watch).use { it.enqueue(ReadingBatch(id(), owner, listOf(reading()))) }
            ReadingJournal(watch).use {
                assertEquals(history.id, it.pending(1).single().id)
                assertEquals(sample.id, it.pending(1, ReadingWire.PATH).single().id)
                assertEquals(setOf(ReadingWire.PATH, WorkoutWire.PATH), it.pendingPaths())
            }
            ReadingJournal(phone).use { journal ->
                assertThrows(UnsupportedWire::class.java) { journal.receiveWorkout(futureBytes, 2100) }
                assertTrue(journal.workouts().isEmpty())
                val receipt = journal.receive(sample.bytes, 2200)
                assertEquals(receipt, journal.receive(sample.bytes, 2300)) // Lost receipt / reconnect.
                ReadingJournal(watch).use { assertTrue(it.acknowledge(receipt)) }
            }
            ReadingJournal(watch).use {
                assertEquals(1L, it.pendingCount())
                val original = it.pending(1, WorkoutWire.PATH).single()
                assertArrayEquals(futureBytes, original.bytes)
                assertEquals(futureHash, original.hash)
                val negative = ProtocolRejection(WireFamily.WORKOUTS, futureHash, "version")
                assertTrue(negative.matches(original.path, original.hash))
                assertFalse(negative.matches(ReadingWire.PATH, original.hash))
                assertThrows(org.json.JSONException::class.java) { ReadingWire.decodeReceipt(negative.encode()) }
                assertTrue(it.isPending(original.id, original.hash))
            }
            // A future path stays on disk too, and is visible to update guidance rather than a false "Synced" state.
            SQLiteDatabase.openOrCreateDatabase(watch, null).use { it.execSQL("UPDATE outbox SET path='/orbit/v2/workouts'") }
            ReadingJournal(watch).use {
                assertEquals(setOf("/orbit/v2/workouts"), it.pendingPaths())
                assertArrayEquals(futureBytes, it.pending().single().bytes)
            }
        } finally { directory.listFiles()?.forEach { check(it.delete()) }; check(directory.delete()) }
    }

    @Test fun explicitRejectionsAreBoundedAndBindToTheLiveCommandPeerBytesAndLifetime() {
        val message = WorkoutControl(id(), id(), id(), id(), "active", "pause")
        val pending = PendingWatchControl(message, "record", 1, "paired-node", 6000)
        val reply = ProtocolRejection(WireFamily.CONTROL, ReadingWire.digest(WorkoutControlWire.encode(message)), "command")
        assertEquals(reply, ProtocolRejection.decode(reply.encode()))
        assertTrue(pending.rejects(reply, pending.node, 6001))
        assertFalse(pending.rejects(reply, "other-node", 6001))
        assertFalse(pending.rejects(reply.copy(hash = "a".repeat(64)), pending.node, 6001))
        assertFalse(pending.rejects(reply, pending.node, 5999))
        assertFalse(pending.rejects(reply, pending.node, 6000 + WorkoutControlWire.REQUEST_MS))
        val committing = pending.response(message.copy(stage = "ready", token = id()), pending.node, 6001)!!
        assertFalse(committing.rejects(reply, pending.node, 6002))
        val commitReply = reply.copy(hash = ReadingWire.digest(WorkoutControlWire.encode(message.copy(stage = "commit", token = committing.token))))
        assertTrue(committing.rejects(commitReply, pending.node, 6002))
        assertFalse(committing.copy(stage = "accepted").rejects(commitReply, pending.node, 6003))
        assertThrows(IllegalArgumentException::class.java) { ProtocolRejection.decode(ByteArray(513)) }
        assertThrows(IllegalArgumentException::class.java) { ProtocolRejection.decode(reply.encode() + "x".toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { ProtocolRejection(WireFamily.CONTROL, "bad", "command") }
        assertThrows(IllegalArgumentException::class.java) { ProtocolRejection(WireFamily.CONTROL, reply.hash, "success") }
    }
}
