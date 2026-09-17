package com.mani.orbit

import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.mani.orbit.sync.*
import java.util.concurrent.TimeUnit

/** Data Layer authenticates package/signature; additionally require the paired watch role. */
class PhoneReadingService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path !in setOf(ReadingWire.PATH, MeasurementWire.PATH, EcgWire.PATH, HeartWire.PATH, RawSensorWire.PATH, SweatWire.PATH, WorkoutWire.PATH, WorkoutControlWire.PATH, HealthContextWire.REQUEST_PATH,
                PeerProtocol.REJECTION_PATH) || event.data.size > ReadingWire.MAX_BYTES) return
        // WearableListenerService dispatches on its background handler, never the UI thread.
        try {
            val peers = Tasks.await(Wearable.getCapabilityClient(this).getCapability(
                ReadingWire.WATCH_CAPABILITY, CapabilityClient.FILTER_REACHABLE), 10, TimeUnit.SECONDS)
            require(peers.nodes.any { it.id == event.sourceNodeId }) { "Unexpected peer role" }
            if (event.path == PeerProtocol.REJECTION_PATH) {
                WatchWorkoutControl.reject(event.sourceNodeId, ProtocolRejection.decode(event.data))
                return
            }
            if (event.path == HealthContextWire.REQUEST_PATH) {
                require(event.data.isEmpty())
                PhoneHealthContextWorker.schedule(this, urgent = true)
                return
            }
            try {
                if (event.path == WorkoutControlWire.PATH) {
                    WatchWorkoutControl.receive(this, event.sourceNodeId, WorkoutControlWire.decode(event.data))
                    return
                }
                val receipt = ReadingJournal(getDatabasePath("watch-readings.db")).use {
                    if (event.path == WorkoutWire.PATH) it.receiveWorkout(event.data, System.currentTimeMillis())
                    else if (event.path == MeasurementWire.PATH) it.receiveMeasurement(event.data, System.currentTimeMillis())
                    else if (event.path == EcgWire.PATH) EcgJournal(it).receive(event.data)
                    else if (event.path == HeartWire.PATH) HeartJournal(it).receive(event.data)
                    else if (event.path == RawSensorWire.PATH) RawSensorJournal(it).receive(event.data)
                    else if (event.path == SweatWire.PATH) SweatJournal(it).receive(event.data)
                    else it.receive(event.data, System.currentTimeMillis())
                }
                val status = getSharedPreferences("watch-sync", MODE_PRIVATE).edit().putLong("receivedAt", System.currentTimeMillis())
                    .putString("status", "Watch readings received")
                if (event.path == WorkoutWire.PATH) status.putString("node:${WorkoutWire.decode(event.data).installation}", event.sourceNodeId)
                check(status.commit())
                val ack = ReadingWire.encodeReceipt(receipt)
                Tasks.await(Wearable.getMessageClient(this).sendMessage(event.sourceNodeId, ReadingWire.ACK_PATH, ack), 10, TimeUnit.SECONDS)
            } catch (unsupported: UnsupportedWire) {
                val family = WireFamily.entries.single { it.path == event.path }
                if (family == WireFamily.CONTROL) WatchWorkoutControl.incompatibleReply(event.sourceNodeId)
                check(getSharedPreferences("watch-sync", MODE_PRIVATE).edit()
                    .putString("status", "Update Orbit on your phone for ${family.label}.").commit())
                Tasks.await(Wearable.getMessageClient(this).sendMessage(event.sourceNodeId, PeerProtocol.REJECTION_PATH,
                    ProtocolRejection(family, ReadingWire.digest(event.data), unsupported.reason).encode()), 5, TimeUnit.SECONDS)
            }
        } catch (error: Exception) {
            // No ACK on validation/storage failure. If transport alone fails, replay gets the durable receipt.
            Log.w("OrbitSync", "Watch receive incomplete: ${error.javaClass.simpleName}")
            if (!getSharedPreferences("watch-sync", MODE_PRIVATE).edit().putString("status", "Watch transfer needs retry").commit())
                Log.e("OrbitSync", "Transfer status could not be saved")
        }
    }
}
