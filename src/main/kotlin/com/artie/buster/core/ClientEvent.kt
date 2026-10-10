package com.artie.buster.core

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * One line of a client's event log, read with OpenTelemetry log data model field
 * names: `trace_id` (which client), `span_id` (which of its requests the decision
 * was about), `event_name` and `attributes`. Other fields are ignored.
 */
data class ClientEvent(
    val traceId: String,
    val spanId: String,
    val name: String,
    val attributes: Map<String, String> = emptyMap(),
) {
    /** The path of the event's `url.full`, if it has one. */
    val path: String? get() = attributes["url.full"]?.let { runCatching { URI(it).path }.getOrNull() }

    companion object {
        const val DONE = "client.done"

        /** Parses one JSON line; null if it is not an event. */
        fun parse(line: String): ClientEvent? {
            val node = Hal.parseObject(line.toByteArray())?.takeIf { it.path("event_name").asText("").isNotEmpty() }
                ?: return null
            val attributes = node.path("attributes").properties().associate { (key, value) -> key to value.asText() }
            return ClientEvent(
                node.path("trace_id").asText(""), node.path("span_id").asText(""),
                node.path("event_name").asText(), attributes,
            )
        }
    }
}

/**
 * Reads a client's event log as it grows: each [poll] hands every complete new line
 * to [sink]. A partial last line waits for the next poll. Lines are cut at the newline
 * byte before decoding, so a character split between two writes is read whole.
 */
class EventTail(private val file: Path, private val sink: (ClientEvent) -> Unit) {
    private var offset = 0L
    private val pending = ByteArrayOutputStream()

    @Synchronized
    fun poll() {
        if (!Files.exists(file)) return
        FileChannel.open(file, StandardOpenOption.READ).use { channel ->
            if (channel.size() < offset) {
                offset = 0
                pending.reset()
            }
            val buffer = ByteBuffer.allocate(CHUNK)
            channel.position(offset)
            while (channel.read(buffer.clear()) > 0) pending.write(buffer.array(), 0, buffer.position())
            offset = channel.position()
        }
        val bytes = pending.toByteArray()
        var start = 0
        for (i in bytes.indices) {
            if (bytes[i] == NEWLINE) {
                ClientEvent.parse(String(bytes, start, i - start, StandardCharsets.UTF_8))?.let(sink)
                start = i + 1
            }
        }
        pending.reset()
        pending.write(bytes, start, bytes.size - start)
    }

    /** Polls every [millis] on a daemon thread until closed. */
    fun watch(millis: Long): Closeable {
        val runner = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "buster-event-tail").apply { isDaemon = true }
        }
        runner.scheduleWithFixedDelay({ runCatching { poll() } }, 0, millis, TimeUnit.MILLISECONDS)
        return Closeable { runner.shutdownNow() }
    }

    private companion object {
        const val CHUNK = 8192
        const val NEWLINE = '\n'.code.toByte()
    }
}
