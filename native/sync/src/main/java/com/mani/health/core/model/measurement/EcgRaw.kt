package com.mani.health.core.model.measurement

import java.time.Instant
import java.util.Collections

/** SDK timestamps are milliseconds with UNKNOWN clock origin; they are never converted to UTC here. */
data class EcgPoint(val rawTimestampMillis:Long,val millivolts:Float,val leadOff:Int?,val sequence:Int?,
    val maximumMillivolts:Float?=null,val minimumMillivolts:Float?=null,val ppgGreen:Int?=null,val metadataReadFailures:Int=0) {
    init {
        require(millivolts.isFinite())
        require(maximumMillivolts==null || maximumMillivolts.isFinite())
        require(minimumMillivolts==null || minimumMillivolts.isFinite())
        require(metadataReadFailures in 0..31)
    }
    override fun toString():String="EcgPoint(metadataReadFailures=$metadataReadFailures)"
}
class EcgCallback(val callbackSequence:Long,val receivedAt:Instant,val receivedElapsedNanos:Long,points:List<EcgPoint>) {
    val points:List<EcgPoint> = Collections.unmodifiableList(ArrayList(points))
    init { require(callbackSequence>=0 && receivedElapsedNanos>=0); require(points.size in setOf(5,10)) }
    override fun toString():String="EcgCallback(sequence=$callbackSequence, points=${points.size})"
}
