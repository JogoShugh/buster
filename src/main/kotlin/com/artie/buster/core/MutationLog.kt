package com.artie.buster.core

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.time.Instant

/** One applied mutation, as logged. */
data class MutationRecord(
    val seq: Long,
    val seed: Long,
    /** The trace id the client's requests carry, or its client header or address without one. */
    val clientId: String,
    /** How the report names the client: its client header, or [clientId] without one. */
    val label: String,
    /** The span of the request whose response was mutated, if the client sent traceparent. */
    val spanId: String?,
    val method: String,
    val path: String,
    val plugin: String,
    val spec: List<String>,
    val layer: Layer,
    val rung: Rung,
    val detail: String,
) {
    /** The record as the log and the report write it. */
    fun fields(): Map<String, Any?> = mapOf(
        "seq" to seq, "seed" to seed, "client" to label, "trace" to clientId.takeIf { spanId != null },
        "span" to spanId, "method" to method, "path" to path,
        "plugin" to plugin, "spec" to spec, "layer" to layer.name, "rung" to rung.name, "detail" to detail,
    )
}

/** The verdict as the log and the report write it. */
fun Verdict.fields(): Map<String, Any?> = mapOf(
    "seq" to record.seq, "client" to record.label, "plugin" to record.plugin,
    "expectation" to expectation, "result" to outcome.label, "reason" to outcome.reason,
)

/**
 * Every mutation and note, in memory and optionally as JSON Lines on disk.
 * The seed and sequence number in each mutation line are enough to replay the run.
 */
class MutationLog(private val file: Path? = null, private val echo: (String) -> Unit = {}) {
    private val lines = ArrayDeque<String>()

    fun mutation(r: MutationRecord) = write("mutation", r.fields())

    fun verdict(v: Verdict) = write("verdict", v.fields())

    fun note(message: String) = write("note", mapOf("message" to message))

    @Synchronized
    private fun write(event: String, fields: Map<String, Any?>) {
        val entry = mapOf("event" to event, "at" to Instant.now().toString()) + fields
        val line = Hal.mapper.writeValueAsString(entry)
        lines.addLast(line)
        while (lines.size > MAX_IN_MEMORY) lines.removeFirst()
        file?.let { Files.writeString(it, line + "\n", CREATE, APPEND) }
        echo(line)
    }

    @Synchronized
    fun recent(n: Int = DEFAULT_RECENT): List<String> = lines.takeLast(n)

    private companion object {
        const val MAX_IN_MEMORY = 10_000
        const val DEFAULT_RECENT = 200
    }
}
