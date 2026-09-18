package com.mani.orbit

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.Wearable
import com.mani.orbit.sync.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * The phone's half of live heart rate: see [LiveWire]. While Orbit is on screen the phone asks the
 * watch for its attention; the watch answers with what it can offer and then pushes each new heart
 * reading as it is captured. Answers land here from [PhoneReadingService]; the screen collects them.
 */
internal object PhoneLive {
    private val latest = MutableStateFlow<LiveUpdate?>(null)
    val updates: StateFlow<LiveUpdate?> = latest.asStateFlow()

    private val journal = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** Watch readings were committed to the phone's journal. */
    val journalChanges: SharedFlow<Unit> = journal.asSharedFlow()

    fun receive(update: LiveUpdate, now: Long = System.currentTimeMillis()) {
        // A reading from the future is the watch's clock talking, not a heart rate.
        if ((update.at ?: 0L) > now + 60_000) return
        latest.update { current -> if (current == null || update.sent >= current.sent) update else current }
    }

    fun journalChanged() { journal.tryEmit(Unit) }

    /** Ask every reachable Orbit watch that understands live heart rate. Returns how many were asked. */
    suspend fun request(context: Context, until: Long): Int = withContext(Dispatchers.IO) {
        val capabilities = Tasks.await(Wearable.getCapabilityClient(context)
            .getAllCapabilities(CapabilityClient.FILTER_REACHABLE), 10, TimeUnit.SECONDS)
        val bytes = LiveWire.encode(LiveRequest(until))
        val watches = watches(capabilities)
        for (node in watches) Tasks.await(Wearable.getMessageClient(context).sendMessage(node, LiveWire.PATH, bytes), 10, TimeUnit.SECONDS)
        watches.size
    }

    /** Release the watch without waiting on it; its lease lapses on its own if this never arrives. */
    fun release(context: Context) {
        val app = context.applicationContext
        val bytes = LiveWire.encode(LiveRequest(0))
        Wearable.getCapabilityClient(app).getAllCapabilities(CapabilityClient.FILTER_REACHABLE)
            .addOnSuccessListener { capabilities -> watches(capabilities).forEach { Wearable.getMessageClient(app).sendMessage(it, LiveWire.PATH, bytes) } }
    }

    /** Watches in the watch role that also advertise live heart rate; an older watch app is never sent it. */
    private fun watches(capabilities: Map<String, CapabilityInfo>): List<String> {
        val live = capabilities[WireFamily.LIVE.capability]?.nodes.orEmpty().mapTo(HashSet()) { it.id }
        return capabilities[ReadingWire.WATCH_CAPABILITY]?.nodes.orEmpty().map { it.id }.filter { it in live }
    }

    /** Checks start from a clean slate. */
    internal fun reset() { latest.value = null }
}
