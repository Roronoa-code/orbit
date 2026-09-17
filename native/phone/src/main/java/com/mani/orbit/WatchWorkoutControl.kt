package com.mani.orbit

import android.content.Context
import android.os.SystemClock
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import com.mani.orbit.sync.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.UUID
import java.util.concurrent.TimeUnit

internal data class WatchControlState(val recordId: String? = null, val busy: Boolean = false, val error: String? = null,
                                     val operation: Long = 0, val confirmedPhase: String? = null, val priorRevision: Long = 0)

internal data class PendingWatchControl(val message: WorkoutControl, val recordId: String, val revision: Long, val node: String,
                                       val started: Long, val stage: String = "request", val token: String? = null, val operation: Long = 0) {
    fun expired(now: Long) = now < started || now - started >= WorkoutControlWire.REQUEST_MS
    fun response(reply: WorkoutControl, source: String, now: Long): PendingWatchControl? {
        if (node != source || !message.sameRequest(reply) || expired(now)) return null
        return when {
            reply.stage == "ready" && stage == "request" -> copy(stage = "commit", token = reply.token)
            reply.stage == "accepted" && stage == "commit" && token == reply.token -> copy(stage = "accepted")
            reply.stage == "rejected" && stage != "accepted" -> copy(stage = "rejected")
            else -> null
        }
    }
    fun confirmed(actual: WatchWorkout?) = stage == "accepted" && actual != null && actual.id == message.workout &&
        actual.boot == message.boot && actual.revision > revision && actual.phase == message.desiredPhase
    fun rejects(reply: ProtocolRejection, source: String, now: Long) = node == source && !expired(now) &&
        stage in setOf("request", "commit") && reply.matches(WorkoutControlWire.PATH,
            ReadingWire.digest(WorkoutControlWire.encode(message.copy(stage = stage, token = token))))
}

/** Commands live only for this request. Durable health transfer remains in the journal. */
internal object WatchWorkoutControl {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutable = MutableStateFlow(WatchControlState())
    val state = mutable.asStateFlow()
    private val feedbackEvents = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    internal val feedback = feedbackEvents.asSharedFlow()
    private var pending: PendingWatchControl? = null

    @Synchronized fun request(context: Context, record: WorkoutRecord, action: String) {
        if (pending != null) return
        val w = requireNotNull(record.watch)
        if (w.phase !in setOf("active", "paused")) return
        val app = context.applicationContext
        val operation = NativeDiagnostics.begin(TraceFeature.WATCH_CONTROL, TraceRoute.WORKOUTS)
        val installation = requireNotNull(record.watchInstallation)
        val node = app.getSharedPreferences("watch-sync", Context.MODE_PRIVATE).getString("node:$installation", null)
        if (node == null) {
            NativeDiagnostics.mark(operation, TraceStage.FAILED)
            mutable.value = WatchControlState(record.id, error = "Connect your Watch and wait for its next update.")
            feedbackEvents.tryEmit(false); return
        }
        val message = WorkoutControl(UUID.randomUUID().toString(), installation, w.id, w.boot, w.phase, action)
        val attempt = PendingWatchControl(message, record.id, w.revision, node, SystemClock.elapsedRealtime(), operation = operation)
        pending = attempt
        mutable.value = WatchControlState(record.id, busy = true)
        NativeDiagnostics.mark(operation, TraceStage.COMMAND_REQUESTED)
        scope.launch {
            try {
                val capabilities = Tasks.await(Wearable.getCapabilityClient(app).getAllCapabilities(
                    CapabilityClient.FILTER_REACHABLE), 5, TimeUnit.SECONDS)
                val peers = capabilities[ReadingWire.WATCH_CAPABILITY]?.nodes.orEmpty()
                check(peers.any { it.id == node })
                PeerProtocol.support(capabilities.filterValues { info -> info.nodes.any { it.id == node } }.keys).require(WireFamily.CONTROL, "Watch", "phone")
                send(app, node, message)
                while (!attempt.expired(SystemClock.elapsedRealtime())) {
                    val current = synchronized(this@WatchWorkoutControl) { pending?.takeIf { it.message.id == message.id } } ?: return@launch
                    if (current.stage == "accepted") {
                        val actual = ReadingJournal(app.getDatabasePath("watch-readings.db")).use { it.workout(installation, w.id) }
                        if (current.confirmed(actual)) {
                            // Local observation of a committed Watch confirmation, never a cross-device latency.
                            NativeDiagnostics.mark(operation, TraceStage.PLATFORM_CONFIRMED)
                            NativeDiagnostics.mark(operation, TraceStage.DURABLE_COMMIT)
                            finish(message.id, null); return@launch
                        }
                    }
                    delay(250)
                }
                finish(message.id, "Watch has not confirmed yet. Check its workout before trying again.")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (incompatible: IncompatiblePeer) { finish(message.id, incompatible.message) }
            catch (_: Exception) { finish(message.id, "Watch control could not be confirmed. Check the connection and your Watch.") }
        }
    }

    fun receive(context: Context, node: String, reply: WorkoutControl) {
        val commit = synchronized(this) {
            val current = pending ?: return
            val next = current.response(reply, node, SystemClock.elapsedRealtime()) ?: return
            pending = next
            when (next.stage) {
                "commit" -> reply.copy(stage = "commit")
                "rejected" -> {
                    finish(reply.id, "Watch state changed or is unavailable. Wait for its next update."); null
                }
                "accepted" -> { NativeDiagnostics.mark(next.operation, TraceStage.API_ACCEPTED); null }
                else -> null
            }
        }
        if (commit != null) try { send(context.applicationContext, node, commit) }
        catch (_: Exception) { finish(reply.id, "Watch control could not be confirmed. Check your Watch.") }
    }

    @Synchronized fun reject(node: String, reply: ProtocolRejection) {
        val current = pending ?: return
        if (current.rejects(reply, node, SystemClock.elapsedRealtime()))
            finish(current.message.id, "Update Orbit on your Watch for workout controls.")
    }

    @Synchronized fun incompatibleReply(node: String) {
        val current = pending?.takeIf { it.node == node } ?: return
        finish(current.message.id, "Update Orbit on your phone for workout controls. Check the workout on your Watch.")
    }

    @Synchronized private fun finish(id: String, error: String?) {
        val current = pending?.takeIf { it.message.id == id } ?: return
        pending = null
        if (error != null) NativeDiagnostics.mark(current.operation, TraceStage.FAILED)
        mutable.value = WatchControlState(current.recordId, error = error, operation = current.operation,
            confirmedPhase = if (error == null) current.message.desiredPhase else null, priorRevision = current.revision)
        feedbackEvents.tryEmit(error == null)
    }
    private fun send(context: Context, node: String, message: WorkoutControl) {
        Tasks.await(Wearable.getMessageClient(context).sendMessage(node, WorkoutControlWire.PATH,
            WorkoutControlWire.encode(message)), 5, TimeUnit.SECONDS)
    }
}
