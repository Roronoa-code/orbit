package com.mani.health.core.model.measurement

/** Observed electrode contact and SDK saturation limits, not physiological or rhythm correctness. */
data class EcgSignalQuality(
    val callbackCount:Int,
    val sampleCount:Long,
    val contactCallbackCount:Int,
    val noContactCallbackCount:Int,
    val unknownContactCallbackCount:Int,
    val limitsAvailableCallbackCount:Int,
    val unknownLimitsCallbackCount:Int,
    val saturationSampleCount:Long,
    val metadataReadFailureCallbackCount:Int,
) {
    val contactMetadataAvailableCallbackCount:Int get()=contactCallbackCount+noContactCallbackCount

    /** A null issue says only that supplied callbacks have known contact and no observed saturation. */
    fun completionIssue():String?=when {
        noContactCallbackCount>0 && contactCallbackCount==0 -> "ECG_CONTACT_REQUIRED"
        noContactCallbackCount>0 -> "ECG_CONTACT_INTERRUPTED"
        saturationSampleCount>0 -> "ECG_SIGNAL_SATURATED"
        callbackCount==0 || unknownContactCallbackCount>0 || unknownLimitsCallbackCount>0 -> "ECG_QUALITY_UNAVAILABLE"
        else -> null
    }
}

/** The first point carries metadata for its callback; later points never replace missing metadata. */
fun summarizeEcgSignal(callbacks:List<EcgCallback>,previous:EcgSignalQuality?=null):EcgSignalQuality {
    var sampleCount=previous?.sampleCount ?: 0L
    var contact=previous?.contactCallbackCount ?: 0
    var noContact=previous?.noContactCallbackCount ?: 0
    var unknownContact=previous?.unknownContactCallbackCount ?: 0
    var limitsAvailable=previous?.limitsAvailableCallbackCount ?: 0
    var unknownLimits=previous?.unknownLimitsCallbackCount ?: 0
    var saturated=previous?.saturationSampleCount ?: 0L
    var metadataFailures=previous?.metadataReadFailureCallbackCount ?: 0
    for(callback in callbacks) {
        val first=callback.points.first()
        sampleCount+=callback.points.size
        when(first.leadOff) {
            null -> unknownContact++
            0 -> contact++
            else -> noContact++
        }
        val minimum=first.minimumMillivolts
        val maximum=first.maximumMillivolts
        if(minimum!=null && maximum!=null && minimum.isFinite() && maximum.isFinite() && minimum<maximum) {
            limitsAvailable++
            saturated+=callback.points.count { it.millivolts<minimum || it.millivolts>maximum }
        } else unknownLimits++
        if(callback.points.any { it.metadataReadFailures!=0 }) metadataFailures++
    }
    return EcgSignalQuality(callbacks.size+(previous?.callbackCount ?: 0),sampleCount,contact,noContact,unknownContact,
        limitsAvailable,unknownLimits,saturated,metadataFailures)
}
