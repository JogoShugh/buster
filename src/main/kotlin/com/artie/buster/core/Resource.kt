package com.artie.buster.core

import java.net.URI

/**
 * Which resource a response is the state of. A read answers at the resource's own URL, but an
 * act answers at its action URL with the resource's new state, and a plugin that took the
 * action URL for the resource would wait for a read of a URL no client reads.
 *
 * Content-Location names it (RFC 9110 §8.7); the HAL self link says it too, for a server that
 * sends no Content-Location; the request path is the last resort.
 */
object Resource {
    fun pathOf(req: BusterRequest, res: BusterResponse): String =
        listOfNotNull(contentLocation(res), selfLink(res)).firstOrNull() ?: req.path

    private fun contentLocation(res: BusterResponse): String? = res.headers["Content-Location"]?.let(::pathPart)

    private fun selfLink(res: BusterResponse): String? =
        Hal.parseObject(res.body)?.let { Hal.links(it) }?.path("self")?.path("href")?.asText()?.let(::pathPart)

    private fun pathPart(reference: String): String? =
        runCatching { URI(reference).path }.getOrNull()?.takeIf { it.isNotEmpty() }
}
