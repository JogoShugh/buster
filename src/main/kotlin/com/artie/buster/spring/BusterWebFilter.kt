package com.artie.buster.spring

import com.artie.buster.core.BusterRequest
import com.artie.buster.core.BusterResponse
import com.artie.buster.core.Engine
import com.artie.buster.core.Hal
import com.artie.buster.core.Headers
import com.artie.buster.core.Outcome
import org.reactivestreams.Publisher
import org.springframework.core.Ordered
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.http.server.reactive.ServerHttpResponse
import org.springframework.http.server.reactive.ServerHttpResponseDecorator
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/**
 * The first filter in the chain, so no endpoint can opt out. Every request goes to the
 * verifier; every response body is buffered, handed to the engine, and written back with
 * a correct Content-Length. GET /_buster/report answers with the verdicts so far.
 */
class BusterWebFilter(
    private val engine: Engine,
    private val clientHeader: String = DEFAULT_CLIENT_HEADER,
) : WebFilter, Ordered {

    override fun getOrder() = Ordered.HIGHEST_PRECEDENCE

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        if (exchange.request.path.value() == REPORT_PATH) return report(exchange)
        val req = toBuster(exchange.request)
        engine.request(req)
        return chain.filter(exchange.mutate().response(Mutating(exchange.response, req)).build())
    }

    private inner class Mutating(delegate: ServerHttpResponse, private val req: BusterRequest) :
        ServerHttpResponseDecorator(delegate) {

        override fun writeWith(body: Publisher<out DataBuffer>): Mono<Void> =
            DataBufferUtils.join(body)
                .map { joined -> bytesOf(joined) }
                .defaultIfEmpty(ByteArray(0))
                .flatMap { bytes -> super.writeWith(Mono.just(bufferFactory().wrap(mutate(bytes)))) }

        /** Runs the engine over the buffered response and copies the result back before commit. */
        private fun mutate(bytes: ByteArray): ByteArray {
            val res = BusterResponse(statusCode?.value() ?: OK, Headers(headers), bytes)
            engine.respond(req, res)
            statusCode = HttpStatusCode.valueOf(res.status)
            headers.clear()
            res.headers.toMap().forEach { (name, values) -> headers[name] = values }
            headers.contentLength = res.body.size.toLong()
            return res.body
        }

        private fun bytesOf(buffer: DataBuffer): ByteArray {
            val bytes = ByteArray(buffer.readableByteCount())
            buffer.read(bytes)
            DataBufferUtils.release(buffer)
            return bytes
        }

        override fun writeAndFlushWith(body: Publisher<out Publisher<out DataBuffer>>): Mono<Void> =
            writeWith(Flux.from(body).concatMap { it })
    }

    private fun toBuster(request: ServerHttpRequest) = BusterRequest(
        method = request.method.name(),
        path = request.path.value(),
        query = request.uri.rawQuery,
        headers = Headers(request.headers),
        clientId = request.headers.getFirst(clientHeader)
            ?: request.remoteAddress?.address?.hostAddress
            ?: "anonymous",
    )

    private fun report(exchange: ServerWebExchange): Mono<Void> {
        if (exchange.request.queryParams.getFirst("flush") == "true") engine.flush()
        val verdicts = engine.verdicts().map { v ->
            val (result, reason) = when (val o = v.outcome) {
                is Outcome.Pass -> "pass" to o.reason
                is Outcome.Fail -> "FAIL" to o.reason
                is Outcome.Inconclusive -> "inconclusive" to o.reason
                Outcome.Pending -> "pending" to ""
            }
            mapOf(
                "seq" to v.record.seq, "client" to v.record.clientId, "plugin" to v.record.plugin,
                "expectation" to v.expectation, "result" to result, "reason" to reason,
            )
        }
        val body = Hal.mapper.writeValueAsBytes(mapOf("seed" to engine.seed, "verdicts" to verdicts))
        val response = exchange.response
        response.headers.contentType = MediaType.APPLICATION_JSON
        response.headers.cacheControl = "no-store"
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)))
    }

    companion object {
        const val REPORT_PATH = "/_buster/report"
        const val DEFAULT_CLIENT_HEADER = "X-Buster-Client"
        private const val OK = 200
    }
}
