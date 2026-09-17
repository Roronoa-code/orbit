package com.mani.health.core.protocol

import com.mani.health.core.model.measurement.EcgCallback
import com.mani.health.core.model.measurement.EcgPoint
import java.io.*
import java.time.Instant
import java.util.Collections

class EcgChunk(val requestId:String,val bootId:String,callbacks:List<EcgCallback>) {
    val callbacks:List<EcgCallback> = Collections.unmodifiableList(ArrayList(callbacks))
    init {
        require(requestId.isNotBlank() && requestId.length<=128 && bootId.isNotBlank() && bootId.length<=128)
        require(callbacks.isNotEmpty() && callbacks.sumOf { it.points.size }<=EcgChunkCodec.MAX_POINTS)
        require(callbacks.zipWithNext().all { (a,b)->b.callbackSequence>a.callbackSequence })
    }
    override fun toString():String="EcgChunk(callbacks=${callbacks.size})"
}

/** Lossless raw observations, not an ECG interpretation, resampler, or raw-HRV input. */
object EcgChunkCodec {
    const val MAX_POINTS=500
    const val MAX_BYTES=128*1024
    const val FORMAT="ecg-sdk"
    const val VERSION=1
    const val TIMING="SDK_MILLISECONDS_CLOCK_UNKNOWN;RECEIPT_UTC_AND_ELAPSED"
    private const val MAGIC=0x4d484543

    fun encode(chunk:EcgChunk):ByteArray {
        val bytes=ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC); out.writeInt(VERSION); out.writeUTF(chunk.requestId); out.writeUTF(chunk.bootId)
            out.writeInt(chunk.callbacks.size)
            for(callback in chunk.callbacks) {
                out.writeLong(callback.callbackSequence); out.writeLong(callback.receivedAt.epochSecond); out.writeInt(callback.receivedAt.nano)
                out.writeLong(callback.receivedElapsedNanos); out.writeInt(callback.points.size)
                for(point in callback.points) {
                    out.writeLong(point.rawTimestampMillis); out.writeFloat(point.millivolts)
                    out.optionalInt(point.leadOff); out.optionalInt(point.sequence)
                    out.optionalFloat(point.maximumMillivolts); out.optionalFloat(point.minimumMillivolts); out.optionalInt(point.ppgGreen)
                    out.writeByte(point.metadataReadFailures)
                }
            }
        }
        return bytes.toByteArray().also { require(it.size<=MAX_BYTES) }
    }

    fun decode(bytes:ByteArray):EcgChunk {
        require(bytes.size<=MAX_BYTES)
        val input=DataInputStream(ByteArrayInputStream(bytes))
        require(input.readInt()==MAGIC && input.readInt()==VERSION) { "Unsupported ECG raw format" }
        val request=input.readUTF(); val boot=input.readUTF()
        val count=input.readInt(); require(count in 1..MAX_POINTS/5)
        var total=0
        val callbacks=List(count) {
            val sequence=input.readLong(); val seconds=input.readLong(); val nano=input.readInt(); require(nano in 0..999_999_999)
            val received=Instant.ofEpochSecond(seconds,nano.toLong()); val elapsed=input.readLong()
            val points=input.readInt(); require(points in setOf(5,10)); total+=points; require(total<=MAX_POINTS)
            EcgCallback(sequence,received,elapsed,List(points) {
                EcgPoint(input.readLong(),input.readFloat(),input.optionalInt(),input.optionalInt(),input.optionalFloat(),input.optionalFloat(),input.optionalInt(),input.readUnsignedByte())
            })
        }
        require(input.available()==0)
        return EcgChunk(request,boot,callbacks)
    }
    private fun DataOutputStream.optionalInt(value:Int?) { writeBoolean(value!=null); value?.let(::writeInt) }
    private fun DataOutputStream.optionalFloat(value:Float?) { writeBoolean(value!=null); value?.let(::writeFloat) }
    private fun DataInputStream.present():Boolean=when(readUnsignedByte()) { 0->false; 1->true; else->throw IllegalArgumentException("Invalid presence marker") }
    private fun DataInputStream.optionalInt():Int?=if(present()) readInt() else null
    private fun DataInputStream.optionalFloat():Float?=if(present()) readFloat() else null
}
