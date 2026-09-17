package com.mani.health.core.model.measurement

import java.time.Duration
import java.time.Instant

enum class MeasurementTracker { HEART_RATE, SPO2, SKIN_TEMPERATURE, ECG, BIA }
enum class MeasurementPhase {
    REQUESTED, AWAITING_USER, RUNNING, COMPLETED, CANCELLED, TIMED_OUT, FAILED, UNSUPPORTED, INTERRUPTED;
    val terminal:Boolean get()=this in setOf(COMPLETED,CANCELLED,TIMED_OUT,FAILED,UNSUPPORTED,INTERRUPTED)
}
data class MeasurementRequest(val id:String,val tracker:MeasurementTracker,val createdAt:Instant,val expiresAt:Instant,val profile:SensorProfile?=null,val eligibilityConfirmed:Boolean=false) {
    init { require(id.isNotBlank() && id.length<=128); require(Duration.between(createdAt,expiresAt) in Duration.ofSeconds(1)..Duration.ofMinutes(10)); require(tracker==MeasurementTracker.BIA || profile==null) }
}
data class MeasurementSession(val request:MeasurementRequest,val phase:MeasurementPhase=MeasurementPhase.REQUESTED,val revision:Long=0) {
    init { require(revision>=0) }
    fun advance(next:MeasurementPhase,version:Long):MeasurementSession {
        if(version<=revision || phase.terminal) return this
        val allowed=next.terminal || (next==MeasurementPhase.AWAITING_USER && phase==MeasurementPhase.REQUESTED) ||
            (next==MeasurementPhase.RUNNING && phase==MeasurementPhase.AWAITING_USER)
        require(allowed) { "Invalid measurement transition" }
        return copy(phase=next,revision=version)
    }
}

data class SensorProfile(val ageYears:Int,val genderCode:Int,val heightCm:Float,val weightKg:Float) {
    init { require(ageYears in 0..200); require(genderCode in 0..1); require(heightCm.isFinite() && heightCm in 20f..300f); require(weightKg.isFinite() && weightKg in 2f..500f) }
    override fun toString():String="SensorProfile(fieldsPresent=true)"
}

fun measurementPermission(tracker:MeasurementTracker,api:Int):String = if(api<36) "android.permission.BODY_SENSORS" else when(tracker) {
    MeasurementTracker.HEART_RATE->"android.permission.health.READ_HEART_RATE"
    MeasurementTracker.SPO2->"android.permission.health.READ_OXYGEN_SATURATION"
    MeasurementTracker.SKIN_TEMPERATURE->"android.permission.health.READ_SKIN_TEMPERATURE"
    MeasurementTracker.ECG,MeasurementTracker.BIA->"com.samsung.android.hardware.sensormanager.permission.READ_ADDITIONAL_HEALTH_DATA"
}
