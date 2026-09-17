package com.mani.orbit.sync

import org.json.JSONObject
import org.json.JSONTokener
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.LocalDate

/** User-entered phone profile. Never label these inputs as Samsung measurements. */
data class MeasurementProfile(val birth: LocalDate?, val sex: String?, val heightCm: Double?, val weightKg: Double?) {
    init {
        require(birth == null || birth >= LocalDate.of(1900, 1, 1))
        require(sex == null || sex in setOf("female", "male"))
        require(heightCm == null || heightCm.isFinite() && heightCm in 40.0..260.0)
        require(weightKg == null || weightKg.isFinite() && weightKg in 20.0..350.0)
    }
    fun complete(today: LocalDate) = birth != null && birth <= today && sex != null && heightCm != null && weightKg != null
    override fun toString() = "MeasurementProfile(hasBirth=${birth != null}, hasSex=${sex != null}, hasHeight=${heightCm != null}, hasWeight=${weightKg != null})"
}

object MeasurementProfileWire {
    const val PATH = "/orbit/v1/measurement-profile"
    fun encode(profile: MeasurementProfile): ByteArray = JSONObject().put("version", 1).put("source", "user_profile")
        .put("birth", profile.birth?.toString() ?: JSONObject.NULL).put("sex", profile.sex ?: JSONObject.NULL)
        .put("heightCm", profile.heightCm ?: JSONObject.NULL).put("weightKg", profile.weightKg ?: JSONObject.NULL)
        .toString().toByteArray(Charsets.UTF_8)
    fun decode(bytes: ByteArray): MeasurementProfile {
        require(bytes.isNotEmpty() && bytes.size <= 1024)
        val tokens = JSONTokener(Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString())
        val row = tokens.nextValue() as? JSONObject ?: error("Expected profile")
        require(tokens.nextClean() == '\u0000')
        requireWireVersion(row)
        require(row.get("source") == "user_profile")
        fun number(key: String): Double? = if (row.isNull(key)) null else (row.get(key) as Number).toDouble()
        val birth = if (row.isNull("birth")) null else (row.get("birth") as String).let { LocalDate.parse(it).also { d -> require(d.toString() == it) } }
        return MeasurementProfile(birth, if (row.isNull("sex")) null else row.get("sex") as String, number("heightCm"), number("weightKg"))
    }
}
