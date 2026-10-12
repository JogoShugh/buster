package com.artie.buster.core

/**
 * Which H.E.A.R.T. layer a response is (SPEC §6.1). OTHER is everything else, from any
 * server; only plugins that name OTHER touch it.
 */
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

    /** Makes this map hold exactly what [other] holds. */
    fun replaceWith(other: Headers) {
        values.clear()
        names.clear()
        other.toMap().forEach { (name, list) -> list.forEach { add(name, it) } }
    }
}

/**
 * An inbound request as Buster sees it. Read-only to plugins. [clientId] groups a
 * client's requests: the trace id of its W3C traceparent when it sends one, else the
 * client header, else its address. [spanId] names this one request, so a client's
 * event about it can be tied to it. [label] is how the report names the client.
 */
data class BusterRequest(
    val method: String,
    val path: String,
    val query: String? = null,
    val headers: Headers = Headers(),
    val clientId: String = "anonymous",
    val spanId: String? = null,
    val label: String = clientId,
) {
    val isAct get() = method.uppercase() !in setOf("GET", "HEAD", "OPTIONS")
    val isGet get() = method.equals("GET", ignoreCase = true)
    val ifMatch get() = headers["If-Match"]
}

/** An outbound response, mutable by plugins. */
class BusterResponse(
    var status: Int,
    val headers: Headers,
    var body: ByteArray,
) {
    /** A 2xx. */
    val isSuccess: Boolean get() = status in SUCCESS_RANGE

    /** The Content-Type says JSON: application/json, application/problem+json, application/hal+json. */
    val isJson: Boolean get() = "json" in headers["Content-Type"].orEmpty().lowercase()

    fun copy() = BusterResponse(status, headers.copy(), body.copyOf())

    /** Makes this response hold exactly what [other] holds. */
    fun replaceWith(other: BusterResponse) {
        status = other.status
        headers.replaceWith(other.headers)
        body = other.body
    }
}

private const val FIRST_SUCCESS = 200
private const val LAST_SUCCESS = 299
private val SUCCESS_RANGE = FIRST_SUCCESS..LAST_SUCCESS

/** What Buster decided a response is. */
data class Classification(val layer: Layer, val rung: Rung, val dialect: Dialect)
