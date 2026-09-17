package com.mani.health.integration.samsungsensor

import com.mani.health.core.model.measurement.EcgPoint

internal enum class EcgAuxField { LEAD_OFF, SEQUENCE, MAX_THRESHOLD_MV, MIN_THRESHOLD_MV, PPG_GREEN }

/** Documented 5/10-point callback layout: only PPG is also present on the sixth point. */
internal fun copyEcgPoint(index:Int,timestamp:Long,millivolts:Float,read:(EcgAuxField)->Number?):EcgPoint {
    require(index in 0..9)
    var failures=0
    fun value(field:EcgAuxField):Number? {
        val result=try { read(field) } catch(_:Exception) { null }
        if(result==null || !result.toDouble().isFinite()) { failures=failures or (1 shl field.ordinal); return null }
        return result
    }
    val lead=if(index==0) value(EcgAuxField.LEAD_OFF)?.toInt() else null
    val sequence=if(index==0) value(EcgAuxField.SEQUENCE)?.toInt() else null
    val maximum=if(index==0) value(EcgAuxField.MAX_THRESHOLD_MV)?.toFloat() else null
    val minimum=if(index==0) value(EcgAuxField.MIN_THRESHOLD_MV)?.toFloat() else null
    val ppg=if(index==0 || index==5) value(EcgAuxField.PPG_GREEN)?.toInt() else null
    return EcgPoint(timestamp,millivolts,lead,sequence,maximum,minimum,ppg,failures)
}
