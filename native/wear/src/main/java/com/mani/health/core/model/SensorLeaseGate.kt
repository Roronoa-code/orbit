package com.mani.health.core.model

import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One fair, process-local lease for deliberate sensor/exercise owners across SDK boundaries. */
class SensorLeaseGate {
    private val permit=Semaphore(1,true)
    fun tryAcquire():Lease?=if(permit.tryAcquire()) Lease(permit) else null
    fun acquire(timeoutNanos:Long):Lease? { require(timeoutNanos>=0); return if(permit.tryAcquire(timeoutNanos,TimeUnit.NANOSECONDS)) Lease(permit) else null }
    class Lease internal constructor(private val permit:Semaphore):AutoCloseable {
        private val closed=AtomicBoolean(false)
        override fun close() { if(closed.compareAndSet(false,true)) permit.release() }
    }
}

val deviceSensorLeaseGate=SensorLeaseGate()
