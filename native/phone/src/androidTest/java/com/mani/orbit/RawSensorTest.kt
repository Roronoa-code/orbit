package com.mani.orbit

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mani.health.integration.samsungsensor.*
import com.mani.orbit.sync.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.UUID

class RawSensorTest {
    private fun id() = UUID.randomUUID().toString()
    private fun frame(probe: SensorRawProbe, count: Int = 750): RawSensorFrame {
        val start = 1_700_000_000_000L
        val reads = List(count) { i ->
            val time = SamsungFieldRead.Value<Long?>(start + i * 40)
            when (probe) {
                SensorRawProbe.PPG_CONTINUOUS -> SamsungPpgPointRead(time, SamsungFieldRead.Value(i), SamsungFieldRead.Value(0),
                    SamsungFieldRead.Value(null), SamsungFieldRead.Value(-1), SamsungFieldRead.Value(-i), SamsungFieldRead.Value(777))
                SensorRawProbe.ACCELEROMETER_CONTINUOUS -> SamsungAccelerometerPointRead(time, SamsungFieldRead.Value(i),
                    SamsungFieldRead.Value(Int.MIN_VALUE), SamsungFieldRead.Value(Int.MAX_VALUE))
                SensorRawProbe.SKIN_TEMPERATURE_CONTINUOUS -> SamsungSkinTemperaturePointRead(time, SamsungFieldRead.Value(32f),
                    SamsungFieldRead.Value(23f), SamsungFieldRead.Value(0))
            }
        }
        return RawSensorFrame(id(), id(), id(), 8, 100_000, 0, false,
            mapSamsungRawProbeChunk(probe, 0, Instant.ofEpochMilli(start + count * 40), reads, 100_000))
    }

    @Test fun everyRawTrackerSurvivesReversedPacketsReopenAndExactReplay() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = android.content.Intent("com.google.android.gms.wearable.MESSAGE_RECEIVED")
            .setData(android.net.Uri.parse("wear://paired-watch${RawSensorWire.PATH}")).setPackage(context.packageName)
        assertTrue(context.packageManager.queryIntentServices(intent, 0).any { it.serviceInfo.name == PhoneReadingService::class.java.name })
        for (probe in SensorRawProbe.entries) {
            val dir = File(context.cacheDir, "raw-${id()}").apply { check(mkdirs()) }
            val frame = frame(probe)
            try {
                val pending = ReadingJournal(File(dir, "watch.db")).use { db ->
                    RawSensorJournal(db).capture(frame)
                    db.pending(100, RawSensorWire.PATH).also { assertTrue(it.size > 1) }
                }.reversed()
                ReadingJournal(File(dir, "phone.db")).use { db ->
                    RawSensorJournal(db).receive(pending.first().bytes)
                    assertNull(RawSensorJournal(db).latestId(frame.installation))
                    assertTrue(db.installations().isEmpty())
                }
                val receipts = ReadingJournal(File(dir, "phone.db")).use { db ->
                    val raw = RawSensorJournal(db)
                    val receipts = pending.map { raw.receive(it.bytes) }
                    assertEquals(frame, raw.page(frame.installation)!!.frame)
                    assertEquals(listOf(frame.installation), db.installations())
                    assertEquals(receipts.first(), raw.receive(pending.first().bytes))
                    assertEquals(if (probe == SensorRawProbe.SKIN_TEMPERATURE_CONTINUOUS) 750 else 0,
                        db.readings("skinTemperature", 0, Long.MAX_VALUE).size)
                    assertTrue(db.readings("heart", 0, Long.MAX_VALUE).isEmpty())
                    assertTrue(db.readings("steps", 0, Long.MAX_VALUE).isEmpty())
                    val tampered = JSONObject(pending.first().bytes.toString(Charsets.UTF_8)).put("sha256", "0".repeat(64))
                    assertThrows(IllegalArgumentException::class.java) { raw.receive(tampered.toString().toByteArray()) }
                    assertEquals(frame, raw.page(frame.installation)!!.frame)
                    receipts
                }
                ReadingJournal(File(dir, "watch.db")).use { db ->
                    receipts.forEach { assertTrue(db.acknowledge(it)) }
                    assertEquals(0L, db.pendingCount())
                    assertEquals(frame, RawSensorJournal(db).page(frame.installation)!!.frame)
                }
            } finally { dir.deleteRecursively() }
        }
    }

    @Test fun invalidAndMissingValuesRemainExactAndNeverBecomeQualifiedOrJoined() {
        val base = frame(SensorRawProbe.SKIN_TEMPERATURE_CONTINUOUS, 1)
        val bits = listOf(0x7fc00001, Float.POSITIVE_INFINITY.toRawBits(), (-0.0f).toRawBits(), 31f.toRawBits())
        val reads = bits.mapIndexed { i, value -> SamsungSkinTemperaturePointRead(
            SamsungFieldRead.Value(1000L + i * 40), SamsungFieldRead.Value(Float.fromBits(value)),
            SamsungFieldRead.Failure("java.lang.IllegalStateException"), SamsungFieldRead.Value(if (i == 3) 77 else 0)) }
        val original = base.copy(clockUncertain = true, chunk = mapSamsungRawProbeChunk(base.chunk.probe, 1, Instant.ofEpochMilli(2000), reads, base.receivedElapsed))
        val decoded = RawSensorWire.decodeFrame(RawSensorWire.encodeFrame(original))
        assertEquals(bits, decoded.chunk.samples.map { (it as SkinTemperatureRawSample).rawObjectTemperatureCelsius!!.toRawBits() })
        assertEquals(4, decoded.chunk.issues.size)
        assertEquals(listOf("unavailable", "unavailable", "valid", "unknown"), decoded.readings().map { it.quality })
        assertTrue(decoded.readings().all { it.timeUncertain })
        assertEquals(listOf(null, null, -0.0, null), rawWavePoints(decoded.chunk, 0, 0).map { it.value })
        assertTrue(rawWavePoints(decoded.chunk, 1, 0).all { it.value == null })
        val ppg = frame(SensorRawProbe.PPG_CONTINUOUS, 3)
        assertTrue(rawWavePoints(ppg.chunk, 2, 0).all { it.value == null })
        assertEquals(listOf(false, true, true), rawWavePoints(ppg.chunk, 0, 0).map { it.join })
        val movement = frame(SensorRawProbe.ACCELEROMETER_CONTINUOUS, 3)
        assertEquals(ACCELEROMETER_COUNTS_TO_METERS_PER_SECOND_SQUARED, rawWavePoints(movement.chunk, 0, 0)[1].value!!, 0.0)
        val malformed = JSONObject(RawSensorWire.encodeFrame(ppg).toString(Charsets.UTF_8)).put("elapsed", 1.5)
        assertThrows(IllegalArgumentException::class.java) { RawSensorWire.decodeFrame(malformed.toString().toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { original.copy(receivedElapsed = original.receivedElapsed + 1) }
        assertThrows(IllegalArgumentException::class.java) { RawSensorWire.decode(RawSensorWire.encode(RawSensorWire.packets(ppg).first()) + byteArrayOf(65)) }
        assertFalse(WireFamily.RAW in PeerProtocol.support(setOf(ReadingWire.PHONE_CAPABILITY, WireFamily.HEART.capability)).families)
        val odd = mapSamsungRawProbeChunk(SensorRawProbe.ACCELEROMETER_CONTINUOUS, 0, Instant.ofEpochMilli(2000),
            listOf(1000L, 1040L, 1800L, 1700L, null).map { t -> SamsungAccelerometerPointRead(SamsungFieldRead.Value(t),
                SamsungFieldRead.Value(0), SamsungFieldRead.Value(0), SamsungFieldRead.Value(0)) })
        assertEquals(listOf(false, true, false, false, false), rawWavePoints(odd, 0, 0).map { it.join })
    }

    @Test fun nativeBootOrderPreservesPinnedCallbackAcrossWallRollback() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(context.cacheDir, "raw-order-${id()}").apply { check(mkdirs()) }
        val first = frame(SensorRawProbe.ACCELEROMETER_CONTINUOUS, 1)
        val later = first.copy(session = id(), boot = id(), bootCount = 9, receivedElapsed = 1000,
            chunk = mapSamsungRawProbeChunk(first.chunk.probe, 0, Instant.ofEpochMilli(1000), emptyList(), 1000))
        try { ReadingJournal(File(dir, "db")).use { db ->
            val journal = RawSensorJournal(db)
            journal.capture(later); journal.capture(first)
            assertEquals(later.id, journal.latestId(first.installation))
            assertEquals(first.id, journal.page(first.installation)!!.older)
            assertEquals(later.id, journal.page(first.installation, first.id)!!.newer)
            assertTrue(journal.page(first.installation)!!.frame.chunk.samples.isEmpty())
            assertNull(journal.page(id()))
        } } finally { dir.deleteRecursively() }
    }
}
