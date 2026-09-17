package com.mani.orbit

import org.json.JSONArray
import org.json.JSONObject

internal enum class HealthCard(val key: String, val label: String) {
    Sleep("sleep", "Sleep"), Steps("steps", "Steps"), Heart("heart", "Heart rate"),
    Body("body", "Measurements"), Intake("intake", "Nutrition"), Oxygen("oxygen", "Blood oxygen");
    companion object {
        fun from(value: Any): HealthCard = entries.singleOrNull { it.key == value }
            ?: throw IllegalArgumentException("Unknown health card")
    }
}

internal data class HealthCardLayout(
    val order: List<HealthCard> = HealthCard.entries.toList(),
    val wide: Set<HealthCard> = setOf(HealthCard.Sleep, HealthCard.Oxygen),
) {
    init { require(order.size == 6 && order.toSet() == HealthCard.entries.toSet()) }
    fun sizeJson(): String = JSONObject().also { json -> HealthCard.entries.forEach { json.put(it.key, it in wide) } }.toString()
    fun orderJson(): String = JSONArray(order.map { it.key }).toString()
    companion object {
        const val SIZE_KEY = "orbit-health-layout-v1"
        const val ORDER_KEY = "orbit-health-order-v1"
        fun sizes(raw: String?): Set<HealthCard> {
            if (raw == null) return HealthCardLayout().wide
            require(raw.length <= 4096)
            val json = JSONObject(raw)
            val wide = HealthCardLayout().wide.toMutableSet()
            for (key in json.keys()) {
                val id = HealthCard.from(key); val value = json.get(key)
                require(value is Boolean)
                if (value) wide += id else wide -= id
            }
            return wide
        }
        fun order(raw: String?): List<HealthCard> {
            if (raw == null) return HealthCard.entries.toList()
            require(raw.length <= 4096)
            val json = JSONArray(raw)
            return List(json.length()) { HealthCard.from(json.get(it)) }.also {
                require(it.size == 6 && it.toSet().size == 6)
            }
        }
    }
}
