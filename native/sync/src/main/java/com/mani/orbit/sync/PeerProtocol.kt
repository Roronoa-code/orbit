package com.mani.orbit.sync

import org.json.JSONObject
import org.json.JSONTokener
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Wire families are independently compatible. Capability names belong to the signed Data Layer. */
enum class WireFamily(val capability: String, val label: String, val path: String) {
    READINGS("orbit_readings_v1", "health readings", ReadingWire.PATH),
    WORKOUTS("orbit_workouts_v1", "workout history", WorkoutWire.PATH),
    CONTROL("orbit_workout_control_v1", "workout controls", WorkoutControlWire.PATH),
    CONTEXT("orbit_health_context_v1", "sleep and energy", HealthContextWire.PATH),
    PROFILE("orbit_measurement_profile_v1", "measurement profile", MeasurementProfileWire.PATH),
    MEASUREMENTS("orbit_measurements_v1", "Samsung sensor measurements", MeasurementWire.PATH),
    ECG("orbit_ecg_v1", "ECG recordings", EcgWire.PATH),
    HEART("orbit_heart_batches_v1", "heart rate and beat intervals", HeartWire.PATH),
    SWEAT("orbit_workout_sweat_v1", "running sweat estimates", SweatWire.PATH),
    RAW("orbit_raw_sensors_v1", "sensor recordings", RawSensorWire.PATH)
}

data class PeerSupport(val families: Set<WireFamily>, val advertised: Boolean, val newer: Set<WireFamily>) {
    fun require(family: WireFamily, peer: String, local: String) {
        if (family !in families) throw IncompatiblePeer("Update Orbit on your ${if (family in newer) local else peer} for ${family.label}.")
    }
}

class IncompatiblePeer(message: String) : IllegalStateException(message)

object PeerProtocol {
    const val CAPABILITY = "orbit_protocol_v1"
    const val REJECTION_PATH = "/orbit/v1/protocol-rejected"

    /** Call only after finding this node's authenticated role in the same successful capability query. */
    fun support(capabilities: Set<String>): PeerSupport {
        require(ReadingWire.PHONE_CAPABILITY in capabilities || ReadingWire.WATCH_CAPABILITY in capabilities) { "Authenticated peer role missing" }
        // Supported pre-negotiation native baseline has all four v1 families. An unavailable query
        // is not an empty set: callers propagate that failure and keep their queues/cached display.
        val advertised = capabilities.any { name -> name.startsWith("orbit_protocol_") ||
            WireFamily.entries.any { name.startsWith(it.capability.substringBeforeLast("_v") + "_v") } }
        return PeerSupport(if (!advertised) setOf(WireFamily.READINGS, WireFamily.WORKOUTS, WireFamily.CONTROL, WireFamily.CONTEXT) else
            WireFamily.entries.filterTo(mutableSetOf()) { it.capability in capabilities }, advertised,
            WireFamily.entries.filterTo(mutableSetOf()) { family -> capabilities.any { name ->
                val prefix = family.capability.substringBeforeLast("_v") + "_v"
                name.startsWith(prefix) && (name.removePrefix(prefix).toIntOrNull() ?: 0) > 1
            } })
    }
}

/** Only an understood version reaches interpretation or a journal transaction. */
class UnsupportedWire(val reason: String) : IllegalArgumentException("Unsupported Orbit $reason")

internal fun requireWireVersion(root: JSONObject) {
    val version = root.get("version")
    require((version is Int || version is Long) && (version as Number).toLong() > 0)
    if ((version as Number).toLong() != 1L) throw UnsupportedWire("version")
}

/** A negative response never acts as a receipt. Its hash binds it to the exact attempted bytes. */
data class ProtocolRejection(val family: WireFamily, val hash: String, val reason: String) {
    init {
        require(hash.matches(Regex("[0-9a-f]{64}")))
        require(reason in setOf("version", "command"))
    }
    fun matches(path: String, digest: String) = family.path == path && hash == digest
    fun encode(): ByteArray = JSONObject().put("version", 1).put("family", family.capability)
        .put("hash", hash).put("reason", reason).toString().toByteArray(Charsets.UTF_8)
    companion object {
        fun decode(bytes: ByteArray): ProtocolRejection {
            require(bytes.isNotEmpty() && bytes.size <= 512)
            val tokens = JSONTokener(Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString())
            val root = tokens.nextValue() as? JSONObject ?: error("Expected protocol rejection")
            require(tokens.nextClean() == '\u0000' && root.get("version") == 1)
            return ProtocolRejection(WireFamily.entries.single { it.capability == root.get("family") },
                root.get("hash") as String, root.get("reason") as String)
        }
    }
}
