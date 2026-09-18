package com.mani.orbit.wear

import android.content.Context
import android.util.Log
import androidx.work.*
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.*
import com.mani.orbit.sync.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

class WatchSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val path = inputData.getString("family-path")
        // Existing periodic/queued work dispatches independent family retries.
        if (path == null) { schedule(applicationContext); return Result.success() }
        val family = listOf(WireFamily.READINGS, WireFamily.WORKOUTS, WireFamily.MEASUREMENTS, WireFamily.ECG, WireFamily.HEART, WireFamily.RAW, WireFamily.SWEAT).firstOrNull { it.path == path } ?: return Result.failure()
        return sending.withLock { withContext(Dispatchers.IO) {
            val store = WatchStore(applicationContext)
            try {
                if (family == WireFamily.SWEAT) WatchSweatCapture.recover(applicationContext)
                store.journal().use { journal ->
                    // Boot/periodic transfer also closes a pre-reboot session, even before its UI opens.
                    journal.interruptOtherBootWorkouts(store.installation, store.clock().boot, System.currentTimeMillis())
                    EcgJournal(journal).recoverInterrupted(store.installation)
                    fun needsLocalUpdate() = journal.pendingPaths().any { it !in setOf(ReadingWire.PATH, WorkoutWire.PATH, MeasurementWire.PATH, EcgWire.PATH, HeartWire.PATH, RawSensorWire.PATH, SweatWire.PATH) }
                    if (journal.pending(1, path).isEmpty()) {
                        if (needsLocalUpdate()) store.status("sync", "Saved on watch · Update Orbit on your Watch to sync remaining data.")
                        return@withContext Result.success()
                    }
                    val capabilities = Tasks.await(Wearable.getCapabilityClient(applicationContext)
                        .getAllCapabilities(CapabilityClient.FILTER_REACHABLE), 10, TimeUnit.SECONDS)
                    val peers = capabilities[ReadingWire.PHONE_CAPABILITY]?.nodes.orEmpty()
                    if (peers.size != 1) {
                        store.status("sync", if (peers.isEmpty()) "Saved on watch · waiting for phone" else "Multiple Orbit phones connected · connect one phone")
                        return@withContext Result.retry()
                    }
                    val peer = peers.single()
                    store.status("phone", peer.id)
                    PeerProtocol.support(capabilities.filterValues { info -> info.nodes.any { it.id == peer.id } }.keys).require(family, "phone", "Watch")
                    val deadline = android.os.SystemClock.elapsedRealtime() + 40_000
                    while (android.os.SystemClock.elapsedRealtime() < deadline) {
                        val pending = journal.pending(1, path).firstOrNull() ?: break
                        // A downgrade can leave a newer packet on disk; never assume queued bytes match this binary.
                        val bytes = try {
                            val value = if (family == WireFamily.ECG) EcgJournal(journal).outgoing(pending) else pending.bytes
                            when (family) {
                                WireFamily.READINGS -> ReadingWire.decode(value)
                                WireFamily.MEASUREMENTS -> MeasurementWire.decode(value)
                                WireFamily.ECG -> EcgWire.decode(value)
                                WireFamily.HEART -> HeartWire.decode(value)
                                WireFamily.RAW -> RawSensorWire.decode(value)
                                WireFamily.SWEAT -> SweatWire.decode(value)
                                else -> WorkoutWire.decode(value)
                            }
                            value
                        } catch (_: UnsupportedWire) { throw IncompatiblePeer("Update Orbit on your Watch for ${family.label}.") }
                        rejected = null
                        Tasks.await(Wearable.getMessageClient(applicationContext).sendMessage(peer.id, pending.path, bytes), 10, TimeUnit.SECONDS)
                        // A transport success is not a receipt. Keep bytes until the phone's committed hash arrives.
                        var confirmed = false
                        for (attempt in 0 until 20) {
                            if (!journal.isPending(pending.id, pending.hash)) { confirmed = true; break }
                            if (rejected?.matches(pending.path, pending.hash) == true)
                                throw IncompatiblePeer("Update Orbit on your phone for ${family.label}.")
                            delay(250)
                        }
                        if (!confirmed) { store.status("sync", "Saved on watch · awaiting phone confirmation"); return@withContext Result.retry() }
                    }
                    if (journal.pending(1, path).isNotEmpty()) return@withContext Result.retry()
                    if (journal.pendingCount() == 0L) store.status("sync", "Synced with phone")
                    else if (needsLocalUpdate()) store.status("sync", "Saved on watch · Update Orbit on your Watch to sync remaining data.")
                }
                Result.success()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (incompatible: IncompatiblePeer) {
                store.status("sync", "Saved on watch · ${incompatible.message}")
                Result.retry()
            }
            catch (error: Exception) {
                store.status("sync", "Saved on watch · sync will retry")
                Log.w("OrbitWatch", "Transfer incomplete: ${error.javaClass.simpleName}")
                Result.retry()
            }
        } }
    }
    companion object {
        // ponytail: one bounded sender; split locks only if paired backlog measurements need concurrent drains.
        private val sending = Mutex()
        // One serialized sender, one exact-hash negative reply. Process death simply retries the journal.
        @Volatile internal var rejected: ProtocolRejection? = null
        fun schedule(context: Context, reconnect: Boolean = false) {
            val manager = WorkManager.getInstance(context)
            for (family in listOf(WireFamily.READINGS, WireFamily.WORKOUTS, WireFamily.MEASUREMENTS, WireFamily.ECG, WireFamily.HEART, WireFamily.RAW, WireFamily.SWEAT)) manager.enqueueUniqueWork("watch-send-${family.name}",
                if (reconnect) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<WatchSyncWorker>().setInputData(workDataOf("family-path" to family.path))
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
        }
        fun periodic(context: Context) = WorkManager.getInstance(context).enqueueUniquePeriodicWork("watch-send-recovery", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<WatchSyncWorker>(15, TimeUnit.MINUTES).build())
    }
}

class WatchTransferService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path !in setOf(ReadingWire.ACK_PATH, WorkoutControlWire.PATH, PeerProtocol.REJECTION_PATH, LiveWire.PATH) ||
            event.data.size > WorkoutControlWire.MAX_BYTES) return
        try {
            val store = WatchStore(this)
            val paired = store.status("phone")
            // A live request can come before this watch has ever synced; the phone role is still checked below.
            require(event.sourceNodeId == paired || event.path == LiveWire.PATH && paired.isEmpty()) { "Receipt from another phone" }
            val peers = Tasks.await(Wearable.getCapabilityClient(this).getCapability(
                ReadingWire.PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE), 10, TimeUnit.SECONDS).nodes
            require(peers.any { it.id == event.sourceNodeId }) { "Unexpected peer role" }
            if (event.path == LiveWire.PATH) {
                // Live updates are best effort: a request this build cannot read is simply not answered.
                val request = try { LiveWire.decodeRequest(event.data) } catch (_: UnsupportedWire) { return }
                WatchLive.request(this, event.sourceNodeId, request)
            } else if (event.path == PeerProtocol.REJECTION_PATH) {
                WatchSyncWorker.rejected = ProtocolRejection.decode(event.data)
            } else if (event.path == WorkoutControlWire.PATH) {
                val message = try { WorkoutControlWire.decode(event.data) } catch (unsupported: UnsupportedWire) {
                    Tasks.await(Wearable.getMessageClient(this).sendMessage(event.sourceNodeId, PeerProtocol.REJECTION_PATH,
                        ProtocolRejection(WireFamily.CONTROL, ReadingWire.digest(event.data), unsupported.reason).encode()), 5, TimeUnit.SECONDS)
                    return
                }
                require(message.stage in setOf("request", "commit"))
                val reply = WatchWorkoutService.remote(message).get(15, TimeUnit.SECONDS)
                Tasks.await(Wearable.getMessageClient(this).sendMessage(event.sourceNodeId,
                    WorkoutControlWire.PATH, WorkoutControlWire.encode(reply)), 5, TimeUnit.SECONDS)
            } else {
                val receipt = ReadingWire.decodeReceipt(event.data)
                store.journal().use { it.acknowledge(receipt) }
            }
        } catch (error: Exception) {
            Log.w("OrbitWatch", "Watch message incomplete: ${error.javaClass.simpleName}")
        }
    }
    override fun onCapabilityChanged(info: CapabilityInfo) {
        // Also observe removal/new version names when the peer updates without disconnecting.
        if (info.name.startsWith("orbit_")) WatchSyncWorker.schedule(this, reconnect = true)
    }
}
