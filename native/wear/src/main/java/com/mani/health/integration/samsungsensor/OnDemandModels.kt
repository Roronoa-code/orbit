package com.mani.health.integration.samsungsensor

import com.mani.health.core.model.measurement.MeasurementTracker
import com.mani.health.core.model.measurement.EcgPoint
import java.time.Instant
import java.util.Collections

data class OnDemandValue(val metric:String,val value:Double,val unit:String) {
    init { require(value.isFinite()) }
    override fun toString():String="OnDemandValue(metric=$metric, unit=$unit)"
}
class OnDemandFrame(val tracker:MeasurementTracker,val receivedAt:Instant,val status:Int?,val complete:Boolean,
    values:List<OnDemandValue> = emptyList(),ecg:List<EcgPoint> = emptyList(),val errorCode:String?=null,
    val callbackSequence:Long?=null,val receivedElapsedNanos:Long?=null,val sensorProgress:Double?=null) {
    init { require(sensorProgress==null || sensorProgress.isFinite()) }
    val values:List<OnDemandValue> = Collections.unmodifiableList(ArrayList(values))
    val ecg:List<EcgPoint> = Collections.unmodifiableList(ArrayList(ecg))
    // The target BIA sensor sends an undocumented status before its result. Keep waiting within the
    // enforced session deadline; an unknown status is neither successful data nor confirmed contact.
    val terminal:Boolean get()=complete || (errorCode!=null && !(tracker==MeasurementTracker.BIA && errorCode=="UNKNOWN_STATUS"))
    override fun toString():String="OnDemandFrame(tracker=$tracker, complete=$complete, values=${values.size}, ecg=${ecg.size}, error=$errorCode)"
}
class OnDemandException(val code:String):Exception(code)

/** Documented status codes, independent per tracker. Unknown is never a successful result. */
fun onDemandStatus(tracker:MeasurementTracker,status:Int?):String = when(tracker) {
    MeasurementTracker.SPO2 -> when(status) { 2->"COMPLETE"; 0->"MEASURING"; -6->"TIMED_OUT"; -5->"LOW_SIGNAL"; -4->"MOVEMENT"; else->"UNKNOWN_STATUS" }
    MeasurementTracker.SKIN_TEMPERATURE -> when(status) { 0->"COMPLETE"; -1->"SENSOR_ERROR"; else->"UNKNOWN_STATUS" }
    MeasurementTracker.BIA -> when(status) { 0->"COMPLETE"; 4,7,8,9,10,11,14,15->"CONTACT_NEEDED"; 13,17->"UNSTABLE_SIGNAL"; 18->"PROFILE_INVALID"; else->"UNKNOWN_STATUS" }
    MeasurementTracker.ECG -> when(status) { null->"UNKNOWN_STATUS"; 0->"CONTACT_PRESENT"; else->"CONTACT_NEEDED" }
    MeasurementTracker.HEART_RATE -> "USE_CONTINUOUS_HR_SOURCE"
}
