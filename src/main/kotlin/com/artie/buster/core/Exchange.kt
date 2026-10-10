package com.artie.buster.core

/** Which H.E.A.R.T. layer a response is (SPEC §6.1). Buster leaves OTHER untouched. */
enum class Layer { RESOURCE, AFFORDANCE_SET, SCHEMA, OTHER }

/** Rendering rung of a response (SPEC §6.10). L3 to L6 are properties, not renderings. */
enum class Rung { L0, L1, L2 }

enum class Dialect { HAL_SCHEMA_FORMS, HAL_FORMS }

/** Case-insensitive, order-preserving header multimap. */
class Headers(initial: Map<String, List<String>> = emptyMap()) {
    private val values = LinkedHashMap<String, MutableList<String>>()
    private val names = LinkedHashMap<String, String>()

    init {
        initial.forEach { (name, list) -> list.forEach { add(name, it) } }
    }

    operator fun get(name: String): String? = values[name.lowercase()]?.joinToString(", ")

    fun all(name: String): List<String> = values[name.lowercase()].orEmpty()

    fun has(name: String) = values.containsKey(name.lowercase())

    operator fun set(name: String, value: String) {
        values[name.lowercase()] = mutableListOf(value)
        names[name.lowercase()] = name
    }

    fun add(name: String, value: String) {
        values.getOrPut(name.lowercase()) { mutableListOf() }.add(value)
        names.putIfAbsent(name.lowercase(), name)
    }

    fun remove(name: String) {
        values.remove(name.lowercase())
        names.remove(name.lowercase())
    }

    fun toMap(): Map<String, List<String>> = values.mapKeys { names[it.key] ?: it.key }

    fun copy() = Headers(toMap())
}

/** An outbound response, mutable by plugins. */
class BusterResponse(
    var status: Int,
    val headers: Headers,
    var body: ByteArray,
)

/** What Buster decided a response is. */
data class Classification(val layer: Layer, val rung: Rung, val dialect: Dialect)
