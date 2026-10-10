package com.artie.buster.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlin.random.Random

/** Keeps DSL scopes from leaking into each other in plugin scripts. */
@DslMarker
annotation class BusterDslMarker

/** One mutation Buster can apply, as built by the DSL. */
class Plugin(
    val name: String,
    val spec: List<String>,
    val layers: Set<Layer>,
    val rungs: Set<Rung>,
    val weight: Int,
    val mutate: MutationScope.() -> Unit,
) {
    fun appliesTo(classification: Classification) =
        classification.layer in layers && classification.rung in rungs
}

/** Thrown by [MutationScope.skip]; the engine discards whatever the plugin changed. */
class SkipMutation(val reason: String) : RuntimeException(reason, null, false, false)

/** What a plugin's mutate block sees and can do. */
@BusterDslMarker
class MutationScope(
    val request: BusterRequest,
    val response: BusterResponse,
    val classification: Classification,
    /** The seeded random source for this exchange. Use only this, so replay works. */
    val random: Random,
) {
    internal val details = mutableListOf<String>()
    internal var keepsCacheControl = false

    fun json(): ObjectNode? = Hal.parseObject(response.body)

    fun writeJson(node: JsonNode) {
        response.body = Hal.write(node)
    }

    fun detail(text: String) {
        details += text
    }

    fun skip(reason: String): Nothing = throw SkipMutation(reason)

    /** Honoured only when the body bytes are unchanged; otherwise the engine still sends no-store. */
    fun keepCacheControl() {
        keepsCacheControl = true
    }
}
