package com.mani.health.integration.samsungsensor

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal class SensorSessionLifecycle(
    private val sessionLease: AutoCloseable,
    private val flushTimeoutMillis: Long,
) {
    private val lock = Any()
    private val flushCompleted = CountDownLatch(1)
    private var state = State.ACTIVE
    private var connection: ConnectionCleanup? = null
    private var tracker: TrackerCleanup? = null

    init {
        require(flushTimeoutMillis >= 0) { "Flush timeout must not be negative" }
    }

    val acceptsCallbacks: Boolean
        get() = synchronized(lock) { state.acceptsCallbacks }

    fun deliverIfActive(deliver: () -> Boolean): DeliveryResult = synchronized(lock) {
        if (!state.acceptsCallbacks) return@synchronized DeliveryResult.INACTIVE
        if (deliver()) DeliveryResult.DELIVERED else DeliveryResult.REJECTED
    }

    fun attachConnection(connectionLease: AutoCloseable, disconnect: () -> Unit): Boolean =
        synchronized(lock) {
            if (!state.acceptsCallbacks) return@synchronized false
            check(connection == null) { "Connection already attached" }
            connection = ConnectionCleanup(connectionLease, disconnect)
            true
        }

    fun attachTracker(
        setListener: () -> Unit,
        flush: () -> Boolean,
        unsetListener: () -> Unit,
    ): Boolean = synchronized(lock) {
        if (!state.acceptsCallbacks) return@synchronized false
        check(tracker == null) { "Tracker already attached" }
        tracker = TrackerCleanup(flush, unsetListener)
        setListener()
        true
    }

    fun beginGracefulStop(): Boolean = synchronized(lock) {
        if (state != State.ACTIVE) return@synchronized false
        state = State.DRAINING
        true
    }

    fun flushAndStop(): Boolean {
        val flush = synchronized(lock) {
            if (state != State.DRAINING) return false
            tracker?.flush
        }
        try {
            if (flush?.invoke() == true) {
                flushCompleted.await(flushTimeoutMillis, TimeUnit.MILLISECONDS)
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: Exception) {
            // Teardown continues without surfacing vendor details.
        }
        return synchronized(lock) {
            if (state != State.DRAINING) return@synchronized false
            state = State.STOPPING
            true
        }
    }

    fun beginStop(): Boolean = synchronized(lock) {
        if (state == State.STOPPING || state == State.CLOSED) return@synchronized false
        state = State.STOPPING
        true
    }

    fun onFlushCompleted() {
        flushCompleted.countDown()
    }

    fun cleanup() {
        val cleanup = synchronized(lock) {
            if (state == State.CLOSED) return
            state = State.CLOSED
            Cleanup(tracker, connection).also {
                tracker = null
                connection = null
            }
        }

        try {
            cleanup.tracker?.unsetListener?.invoke()
        } catch (_: Exception) {
            // Teardown continues through every owned resource without surfacing vendor details.
        } finally {
            try {
                cleanup.connection?.disconnect?.invoke()
            } catch (_: Exception) {
                // Teardown continues through every owned resource without surfacing vendor details.
            } finally {
                try {
                    cleanup.connection?.lease?.close()
                } finally {
                    sessionLease.close()
                }
            }
        }
    }

    private enum class State {
        ACTIVE,
        DRAINING,
        STOPPING,
        CLOSED,
        ;

        val acceptsCallbacks: Boolean
            get() = this == ACTIVE || this == DRAINING
    }

    enum class DeliveryResult {
        INACTIVE,
        DELIVERED,
        REJECTED,
    }

    private data class ConnectionCleanup(
        val lease: AutoCloseable,
        val disconnect: () -> Unit,
    )

    private data class TrackerCleanup(
        val flush: () -> Boolean,
        val unsetListener: () -> Unit,
    )

    private data class Cleanup(
        val tracker: TrackerCleanup?,
        val connection: ConnectionCleanup?,
    )
}
