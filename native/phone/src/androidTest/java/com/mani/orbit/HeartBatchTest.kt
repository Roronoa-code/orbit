package com.mani.orbit

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mani.health.core.model.heart.*
import com.mani.health.integration.samsungsensor.*
import com.mani.orbit.sync.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.UUID

class HeartBatchTest {
    private fun id() = UUID.randomUUID().toString()
    private fun frame(sequence: Long = 0, session: String = id(), installation: String = id(), count: Int = 1): HeartFrame {
        val reads = List(count) { index -> SamsungHeartRatePointRead(
            SamsungFieldRead.Value(1_700_000_000_000L + index * 1000), SamsungFieldRead.Value(80), SamsungFieldRead.Value(1),
            SamsungFieldRead.Value(if (index == 0) List(5000) { 750 } else null),
            SamsungFieldRead.Value(if (index == 0) List(5000) { 0 } else null)) }
        val at = Instant.ofEpochMilli(1_700_000_000_000L + count * 1000)
        return HeartFrame(installation, session, id(), 5, 1_000_000, 0, false, mapSamsungHeartRateBatch(sequence, at, reads))
    }

    @Test fun wholeScreenOffCallbackSurvivesOutOfOrderReplayWithoutInventingBeatTimes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = android.content.Intent("com.google.android.gms.wearable.MESSAGE_RECEIVED")
            .setData(android.net.Uri.parse("wear://paired-watch${HeartWire.PATH}")).setPackage(context.packageName)
        assertTrue(context.packageManager.queryIntentServices(intent, 0).any { it.serviceInfo.name == PhoneReadingService::class.java.name })
        val dir = File(context.cacheDir, "heart-${id()}").apply { check(mkdirs()) }
        val frame = frame(count = 90)
        try {
            val pending = ReadingJournal(File(dir, "watch.db")).use { journal ->
                HeartJournal(journal).capture(frame)
                assertEquals(frame.batch, HeartJournal(journal).page(frame.installation)!!.frame.batch)
                journal.pending(100, HeartWire.PATH).also { assertTrue(it.size > 1) }
            }
            val receipts = ReadingJournal(File(dir, "phone.db")).use { journal ->
                val reversed = pending.reversed(); val heart = HeartJournal(journal)
                heart.receive(reversed.first().bytes)
                assertNull(heart.latestId(frame.installation)); assertNull(journal.latest("heart"))
                val received = reversed.map { heart.receive(it.bytes) }
                assertEquals(frame.batch, heart.page(frame.installation)!!.frame.batch)
                assertEquals(received.first(), heart.receive(reversed.first().bytes))
                assertEquals(90, journal.readings("heart", 0, Long.MAX_VALUE).size)
                assertTrue(journal.readings("ibi", 0, Long.MAX_VALUE).isEmpty())
                assertEquals(listOf(frame.installation), journal.installations())
                val tampered = JSONObject(reversed.first().bytes.toString(Charsets.UTF_8)).put("sha256", "0".repeat(64))
                assertThrows(IllegalArgumentException::class.java) { heart.receive(tampered.toString().toByteArray()) }
                assertEquals(frame.batch, heart.page(frame.installation)!!.frame.batch)
                received
            }
            ReadingJournal(File(dir, "watch.db")).use { journal ->
                assertFalse(journal.acknowledge(receipts.first().copy(hash = "0".repeat(64))))
                receipts.forEach { assertTrue(journal.acknowledge(it)) }
                assertEquals(0L, journal.pendingCount())
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun rawFailuresMismatchesUnknownStatusesAndClockChangesStayQualified() {
        val reads = listOf(
            SamsungHeartRatePointRead(SamsungFieldRead.Value(1000L), SamsungFieldRead.Value(-1), SamsungFieldRead.Value(-3),
                SamsungFieldRead.Value(listOf(0, 750, -5)), SamsungFieldRead.Value(listOf(0, 77))),
            SamsungHeartRatePointRead(SamsungFieldRead.Failure("java.lang.IllegalStateException"), SamsungFieldRead.Value(99),
                SamsungFieldRead.Value(777), SamsungFieldRead.Value(emptyList()), SamsungFieldRead.Value(null)))
        val original = mapSamsungHeartRateBatch(1, Instant.ofEpochMilli(2000), reads)
        val frame = HeartFrame(id(), id(), id(), 6, 3000, 0, true, original)
        val decoded = HeartWire.decodeFrame(HeartWire.encodeFrame(frame))
        assertEquals(original, decoded.batch)
        assertTrue(decoded.batch.issues.any { it is HeartBeatIssue.IbiLengthMismatch })
        assertTrue(decoded.readings().single().timeUncertain)
        assertNotEquals("valid", decoded.readings().single().quality)
        assertNull(decoded.readings().single().value)
        val root = JSONObject(HeartWire.encodeFrame(frame).toString(Charsets.UTF_8)).put("elapsed", 2.5)
        assertThrows(IllegalArgumentException::class.java) { HeartWire.decodeFrame(root.toString().toByteArray()) }
        val packet = HeartWire.encode(HeartWire.packets(frame).first())
        assertThrows(IllegalArgumentException::class.java) { HeartWire.decode(packet + byteArrayOf(65)) }
        assertThrows(UnsupportedWire::class.java) { HeartWire.decode(JSONObject(packet.toString(Charsets.UTF_8)).put("version", 2).toString().toByteArray()) }
        assertFalse(WireFamily.HEART in PeerProtocol.support(setOf(ReadingWire.PHONE_CAPABILITY)).families)
    }

    @Test fun bootOrderAndStablePagingSurviveWallRollbackAndLateDelivery() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "heart-order-${id()}.db")
        val first = frame(count = 1)
        val second = first.copy(session = id(), boot = id(), bootCount = 6, receivedElapsed = 50_000,
            batch = mapSamsungHeartRateBatch(0, Instant.ofEpochMilli(1000), emptyList()))
        try { ReadingJournal(file).use { db ->
            val journal = HeartJournal(db)
            journal.capture(second); journal.capture(first)
            assertEquals(second.id, journal.latestId(first.installation))
            assertEquals(first.id, journal.page(first.installation)!!.older)
            assertEquals(second.id, journal.page(first.installation, first.id)!!.newer)
            assertTrue(journal.page(first.installation, second.id)!!.frame.batch.points.isEmpty())
            assertNull(journal.page(id()))
        } } finally { file.delete() }
    }
}
