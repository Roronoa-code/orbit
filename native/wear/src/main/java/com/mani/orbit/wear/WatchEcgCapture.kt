package com.mani.orbit.wear

import android.content.Context
import android.os.SystemClock
import com.mani.health.core.model.measurement.*
import com.mani.health.core.protocol.EcgChunk
import com.mani.health.core.protocol.EcgChunkCodec
import com.mani.health.integration.samsungsensor.*
import com.mani.orbit.sync.*
import kotlinx.coroutines.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** Adapted from the existing EcgRecording chunker; pending samples survive a retryable write failure. */
internal class WatchEcgCapture(private val context: Context) {
    private val store = WatchStore(context)
    private val id = UUID.randomUUID().toString()
    private val pending = ArrayList<EcgCallback>()
    private var pendingPoints = 0
    private var chunks = 0
    private var samples = 0
    private var phase = "recording"
    private var clock: WatchClock? = null
    private var start = 0L
    private var startedElapsed = 0L
    var needsSave = false
        private set
    var quality = summarizeEcgSignal(emptyList())
        private set
    var syncWarning: String? = null
        private set

    suspend fun run(progress: (Int, EcgSignalQuality) -> Unit): EcgRecord = withContext(Dispatchers.IO) {
        check(clock == null)
        val anchor = store.clock(); clock = anchor; startedElapsed = SystemClock.elapsedRealtime(); start = anchor.wallAt(startedElapsed)
        val source = SensorSdkOnDemandSource(context)
        val began = AtomicLong(SystemClock.elapsedRealtime())
        var lastFrame = 0L
        try {
            source.frames(MeasurementTracker.ECG, onReady = { began.set(SystemClock.elapsedRealtime()) },
                beforeStart = { requireSamsungSensorAvailable(context, store, anchor.boot) }).collect { frame ->
                require(frame.ecg.isNotEmpty())
                if (samples + pendingPoints + frame.ecg.size > 16000) throw OnDemandException("RAW_LIMIT_REACHED")
                val callback = EcgCallback(requireNotNull(frame.callbackSequence), frame.receivedAt, requireNotNull(frame.receivedElapsedNanos), frame.ecg)
                quality = summarizeEcgSignal(listOf(callback), quality)
                if (pendingPoints + callback.points.size > EcgChunkCodec.MAX_POINTS) flush()
                pending.add(callback); pendingPoints += callback.points.size; needsSave = true
                if (pendingPoints == EcgChunkCodec.MAX_POINTS) flush()
                val now = SystemClock.elapsedRealtime()
                if (now - lastFrame >= 200) {
                    lastFrame = now
                    val current = summarizeEcgSignal(listOf(callback))
                    withContext(Dispatchers.Main) { progress(((now - began.get()) / 1000).toInt().coerceIn(0, 30), current) }
                }
            }
            if (samples + pendingPoints == 0) throw OnDemandException("NO_RESULT")
            phase = "complete"
        } catch (cancelled: CancellationException) { phase = "cancelled"; throw cancelled }
        catch (failure: Exception) { phase = "failed"; throw failure }
        finally {
            withContext(NonCancellable + Dispatchers.IO) {
                source.stopGracefully()
                needsSave = true
                save()
            }
        }
        requireNotNull(store.journal().use { EcgJournal(it).record(store.installation, id) })
    }

    /** Explicit Retry save persists retained samples; it never restarts electrodes. */
    fun save(): EcgRecord {
        check(phase != "recording")
        needsSave = true
        flush()
        val anchor = requireNotNull(clock)
        store.journal().use { journal -> EcgJournal(journal).capture(EcgPacket(packetId("finished"), store.installation, id, anchor.boot, start, -1,
            phase = phase, expectedChunks = chunks, expectedSamples = samples, startedElapsedMs = startedElapsed, bootCount = anchor.bootCount)) }
        needsSave = false
        syncWarning = requestMeasurementSync(context)
        return requireNotNull(store.journal().use { EcgJournal(it).record(store.installation, id) })
    }

    private fun flush() {
        if (pending.isEmpty()) return
        val anchor = requireNotNull(clock)
        store.journal().use { journal -> EcgJournal(journal).capture(EcgPacket(packetId("chunk:$chunks"), store.installation, id, anchor.boot, start,
            chunks, EcgChunk(id, anchor.boot, pending), startedElapsedMs = startedElapsed, bootCount = anchor.bootCount)) }
        chunks++; samples += pendingPoints; pending.clear(); pendingPoints = 0
    }
    private fun packetId(part: String) = UUID.nameUUIDFromBytes("${store.installation}:$id:$part".toByteArray()).toString()
}
