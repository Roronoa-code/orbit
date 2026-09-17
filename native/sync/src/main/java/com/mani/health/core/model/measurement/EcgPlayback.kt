package com.mani.health.core.model.measurement

data class EcgPlaybackChunk(val sequence:Long,val callbacks:List<EcgCallback>)
data class EcgPlayback(val phase:String,val chunks:List<EcgPlaybackChunk>,val expectedSamples:Int?,
    val receivedSamples:Int,val complete:Boolean,val issues:Set<String>,val signalQuality:EcgSignalQuality?=null) {
    override fun toString()="EcgPlayback(phase=$phase, complete=$complete, issues=$issues)"
}
