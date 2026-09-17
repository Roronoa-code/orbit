package com.mani.orbit.sync

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.LocalDate
import java.time.ZoneId

/** A phone-owned summary of committed Samsung originals, never a Watch sensor reading. */
data class RecoveryDay(val date: LocalDate, val start: Long?, val end: Long?, val sessions: Int,
    val stages: Map<String, Long>, val sleepScore: Double?, val energyScore: Double?) {
    init {
        require(date >= LocalDate.of(1970, 1, 1) && sessions >= 0)
        require((start == null) == (end == null))
        require(start == null && sessions == 0 && stages.isEmpty() || start != null && start >= 0 && end!! > start && sessions > 0)
        require(stages.keys.all { it in STAGES } && stages.values.all { it >= 0 })
        require(stages.values.fold(0L, Math::addExact) <= if (start == null) 0 else end!! - start)
        require(listOfNotNull(sleepScore, energyScore).all { it.isFinite() && it in 0.0..100.0 })
        require(sleepScore == null || sessions > 0)
    }
    val asleepMs: Long? get() = if (stages.keys.any { it in ASLEEP || it == "awake" }) ASLEEP.sumOf { stages[it] ?: 0L } else null
    companion object {
        val ASLEEP = setOf("light", "deep", "rem", "sleeping")
        val STAGES = setOf("awake", "rem", "light", "deep", "sleeping", "unknown", "unrecorded")
    }
}

data class HealthContext(val zone: String, val generatedAt: Long, val importedAt: Long, val days: List<RecoveryDay>) {
    init {
        ZoneId.of(zone)
        require(generatedAt >= 0 && importedAt >= 0 && days.size <= 7)
        require(days.zipWithNext().all { (a, b) -> a.date > b.date })
        require(importedAt > 0 || days.isEmpty())
    }
    override fun toString() = "HealthContext(days=${days.size})"
}

object HealthContextWire {
    const val PATH = "/orbit/v1/health-context"
    const val REQUEST_PATH = "/orbit/v1/health-context-request"
    const val MAX_BYTES = 64 * 1024
    fun encode(value: HealthContext): ByteArray = JSONObject().put("version", 1)
        .put("source", "com.sec.android.app.shealth").put("zone", value.zone)
        .put("generatedAt", value.generatedAt).put("importedAt", value.importedAt)
        .put("days", JSONArray().apply { value.days.forEach { day -> put(JSONObject()
            .put("date", day.date.toString()).put("start", day.start ?: JSONObject.NULL).put("end", day.end ?: JSONObject.NULL)
            .put("sessions", day.sessions).put("stages", JSONObject(day.stages))
            .put("sleepScore", day.sleepScore ?: JSONObject.NULL).put("energyScore", day.energyScore ?: JSONObject.NULL)) } })
        .toString().toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_BYTES) }

    fun decode(bytes: ByteArray): HealthContext {
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES)
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        val tokens = JSONTokener(text)
        val root = tokens.nextValue() as? JSONObject ?: error("Expected health context")
        require(tokens.nextClean() == '\u0000')
        requireWireVersion(root)
        require(root.get("source") == "com.sec.android.app.shealth")
        val rows = root.getJSONArray("days"); require(rows.length() <= 7)
        return HealthContext(root.getString("zone"), root.integer("generatedAt"), root.integer("importedAt"), List(rows.length()) {
            val row = rows.getJSONObject(it); val stages = row.getJSONObject("stages")
            val sessions = row.integer("sessions"); require(sessions in 0..Int.MAX_VALUE.toLong())
            RecoveryDay(LocalDate.parse(row.getString("date")), row.time("start"), row.time("end"), sessions.toInt(),
                stages.keys().asSequence().associateWith { key -> stages.integer(key) }, row.score("sleepScore"), row.score("energyScore"))
        })
    }
    private fun JSONObject.integer(key: String): Long = get(key).let {
        require(it is Int || it is Long) { "Expected integer" }; (it as Number).toLong()
    }
    private fun JSONObject.time(key: String): Long? = if (get(key) == JSONObject.NULL) null else integer(key)
    private fun JSONObject.score(key: String): Double? = if (get(key) == JSONObject.NULL) null else get(key).let {
        require(it is Number); it.toDouble().also { number -> require(number.isFinite() && number in 0.0..100.0) }
    }
}
