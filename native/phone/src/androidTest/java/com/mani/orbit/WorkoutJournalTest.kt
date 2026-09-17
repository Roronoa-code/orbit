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
class WorkoutJournalTest {
    private fun id() = UUID.randomUUID().toString()
    private fun workout() = WatchWorkout(id(), 1, "Walking", id(), 10000, 1000, 14000, 5000, 4000, "active", true,
        distance = 5.0, steps = 8, heart = 80.0, heartElapsed = 4900, heartQuality = "valid")

    @Test fun durableWorkoutRouteReplayAndOldOutboxMigration() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(context.cacheDir, "workouts-${id()}").apply { check(mkdirs()) }
        val watch = File(dir, "watch.db"); val phone = File(dir, "phone.db"); val installation = id()
        val w = workout(); val point = WatchRoutePoint(4500, 51.0, -.1, 8.0, 15.0, true)
        try {
            SQLiteDatabase.openOrCreateDatabase(watch, null).use {
                it.execSQL("CREATE TABLE outbox(id TEXT PRIMARY KEY,hash TEXT NOT NULL,bytes BLOB NOT NULL,created INTEGER NOT NULL)")
                it.execSQL("INSERT INTO outbox VALUES(?,?,?,?)", arrayOf(id(), "old", byteArrayOf(1), 1))
            }
            ReadingJournal(watch).use {
                assertEquals(ReadingWire.PATH, it.pending().single().path)
                it.acknowledge(ReadingReceipt(it.pending().single().id, "old"))
                it.captureWorkout(WorkoutPacket(id(), installation, w, listOf(point)))
            }
            val packet = ReadingJournal(watch).use { it.pending().single() }
            assertEquals(WorkoutWire.PATH, packet.path)
            val receipt = ReadingJournal(phone).use {
                val projection = WatchWorkoutProjection()
                val ack = it.receiveWorkout(packet.bytes, 15000)
                assertEquals(ack, it.receiveWorkout(packet.bytes, 16000))
                assertEquals(w, it.workout(installation, w.id))
                assertEquals(listOf(point), it.workoutPoints(installation, w.id))
                val records = projection.refresh(it)!!
                assertEquals("watch:$installation:${w.id}", records.single().id)
                assertNull("No invented energy estimate", records.single().energy)
                assertNull(projection.refresh(it))
                val laterPoint = point.copy(elapsed = 4800, lat = 51.0001, breakBefore = false)
                it.receiveWorkout(WorkoutWire.encode(WorkoutPacket(id(), installation, w, listOf(laterPoint))), 17000)
                val routeChange = projection.refresh(it)!!.single()
                assertEquals(w, routeChange.watch)
                assertTrue("Route-only chunks must invalidate an open detail view", routeChange.watchChange > records.single().watchChange)
                assertEquals(listOf(laterPoint), it.workoutPoints(installation, w.id, point.elapsed))
                val finish = w.copy(revision = 3, updatedAt = 20000, updatedElapsed = 11000, activeMs = 6000, phase = "ended", endReason = 1)
                it.receiveWorkout(WorkoutWire.encode(WorkoutPacket(id(), installation, finish, emptyList())), 21000)
                it.receiveWorkout(WorkoutWire.encode(WorkoutPacket(id(), installation, w.copy(revision = 2, phase = "paused"), listOf(point))), 22000)
                assertEquals(finish, it.workout(installation, w.id))
                assertEquals(finish, projection.refresh(it)!!.single().watch)
                assertThrows(IllegalArgumentException::class.java) {
                    it.receiveWorkout(WorkoutWire.encode(WorkoutPacket(id(), installation, finish.copy(revision = 4, phase = "active", endReason = null), emptyList())), 23000)
                }
                assertThrows(IllegalArgumentException::class.java) {
                    it.receiveWorkout(WorkoutWire.encode(WorkoutPacket(id(), installation, finish.copy(revision = 4, distance = 6.0), listOf(point.copy(lat = 52.0)))), 23000)
                }
                assertEquals("A route conflict must roll back the newer summary too", finish, it.workout(installation, w.id))
                ack
            }
            ReadingJournal(watch).use {
                assertFalse(it.acknowledge(receipt.copy(hash = "wrong")))
                assertEquals(1, it.pendingCount()); assertTrue(it.acknowledge(receipt)); assertEquals(0, it.pendingCount())
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun failureInALaterRouteChunkRollsBackTheEntireCapture() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "workout-atomic-${id()}.db")
        val installation = id(); val w = workout()
        val original = WatchRoutePoint(4700, 0.0, 0.0, 5.0, null, false)
        try { ReadingJournal(file).use {
            it.captureWorkoutUpdate(installation, w, listOf(original))
            val count = it.pendingCount()
            val points = (0..64).map { index -> WatchRoutePoint(1000 + index * 50L, 0.0, 0.0, 5.0, null, false) } + original.copy(lat = 1.0)
            assertThrows(IllegalArgumentException::class.java) {
                it.captureWorkoutUpdate(installation, w.copy(revision = 2, distance = 10.0), points)
            }
            assertEquals(w, it.workout(installation, w.id))
            assertEquals(listOf(original), it.workoutPoints(installation, w.id))
            assertEquals(count, it.pendingCount())
            assertEquals(0, it.interruptOtherBootWorkouts(installation, w.boot, 20000))
            val newBoot = id()
            assertEquals(1, it.interruptOtherBootWorkouts(installation, newBoot, 20000))
            val interrupted = it.workout(installation, w.id)!!
            assertEquals("interrupted", interrupted.phase)
            assertEquals(w.activeMs, interrupted.activeMs)
            assertTrue(interrupted.timeUncertain)
            assertEquals(0, it.interruptOtherBootWorkouts(installation, newBoot, 21000))
        } } finally { file.delete() }
    }

    @Test fun malformedAndConflictingSessionsCannotChangeStoredTruth() {
        val w = workout(); val packet = WorkoutWire.encode(WorkoutPacket(id(), id(), w, emptyList()))
        assertEquals(w, WorkoutWire.decode(packet).workout)
        assertThrows(IllegalArgumentException::class.java) { WorkoutWire.decode(packet + "junk".toByteArray()) }
        val bad = JSONObject(packet.toString(Charsets.UTF_8))
        bad.getJSONObject("workout").put("activeMs", "4000")
        assertThrows(IllegalArgumentException::class.java) { WorkoutWire.decode(bad.toString().toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { w.copy(activeMs = 99999) }
        assertThrows(IllegalArgumentException::class.java) { w.copy(heart = Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { WatchRoutePoint(1500, 0.0, 0.0, 51.0, null, false) }
        assertThrows(IllegalArgumentException::class.java) { WorkoutPacket(id(), id(), w,
            listOf(WatchRoutePoint(3000, 0.0, 0.0, 1.0, null, true), WatchRoutePoint(2000, 0.0, 0.0, 1.0, null, false))) }
    }
}
