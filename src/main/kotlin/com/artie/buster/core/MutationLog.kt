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
    val clientId: String,
    val method: String,
    val path: String,
    val plugin: String,
    val spec: List<String>,
    val layer: Layer,
    val rung: Rung,
    val detail: String,
)

/**
 * Every mutation and note, in memory and optionally as JSON Lines on disk.
 * The seed and sequence number in each mutation line are enough to replay the run.
 */
class MutationLog(private val file: Path? = null, private val echo: (String) -> Unit = {}) {
    private val lines = ArrayDeque<String>()

    fun mutation(r: MutationRecord) = write(
        mapOf(
            "event" to "mutation", "at" to Instant.now().toString(), "seq" to r.seq, "seed" to r.seed,
            "client" to r.clientId, "method" to r.method, "path" to r.path, "plugin" to r.plugin,
            "spec" to r.spec, "layer" to r.layer.name, "rung" to r.rung.name, "detail" to r.detail,
        ),
    )

    fun verdict(v: Verdict) {
        val (result, reason) = when (val o = v.outcome) {
            is Outcome.Pass -> "pass" to o.reason
            is Outcome.Fail -> "FAIL" to o.reason
            is Outcome.Inconclusive -> "inconclusive" to o.reason
            Outcome.Pending -> "pending" to ""
        }
        write(
            mapOf(
                "event" to "verdict", "at" to Instant.now().toString(), "seq" to v.record.seq,
                "client" to v.record.clientId, "plugin" to v.record.plugin,
                "expectation" to v.expectation, "result" to result, "reason" to reason,
            ),
        )
    }

    fun note(message: String) = write(mapOf("event" to "note", "at" to Instant.now().toString(), "message" to message))

    @Synchronized
    private fun write(entry: Map<String, Any?>) {
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
