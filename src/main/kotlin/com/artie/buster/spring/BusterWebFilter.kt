package com.artie.buster.spring

import com.artie.buster.core.BusterRequest
import com.artie.buster.core.BusterResponse
import com.artie.buster.core.ClientEvent
import com.artie.buster.core.Engine
import com.artie.buster.core.Hal
import com.artie.buster.core.Headers
import com.artie.buster.core.campaignReport
import com.artie.buster.core.fields
import org.reactivestreams.Publisher
import org.springframework.core.Ordered
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.HttpMethod
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
import java.time.Duration

/**
 * The first filter in the chain, so no endpoint can opt out. Every request goes to the
 * verifier; every response body is buffered, handed to the engine, and written back with
 * a correct Content-Length. GET /_buster/report answers with the mutations and verdicts so
 * far; GET /_buster is a live view of it for a browser.
 *
 * A non-zero [pace] holds each mutated response that long before it goes out, so a run can
 * be watched case by case. Responses Buster leaves alone are never held.
 */
class BusterWebFilter(
    private val engine: Engine,
    private val clientHeader: String = DEFAULT_CLIENT_HEADER,
    val pace: Duration = Duration.ZERO,
) : WebFilter, Ordered {

    override fun getOrder() = Ordered.HIGHEST_PRECEDENCE

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> =
        when (exchange.request.path.value()) {
            REPORT_PATH -> report(exchange)
            VIEW_PATH -> write(exchange, HTML, LiveView.PAGE.toByteArray())
            EVENTS_PATH -> events(exchange)
            else -> if (exchange.request.path.value().startsWith(CampaignEndpoint.PATH)) {
                campaign(exchange)
            } else {
                val req = toBuster(exchange.request)
                engine.request(req)
                chain.filter(exchange.mutate().response(Mutating(exchange.response, req)).build())
            }
        }

    private inner class Mutating(delegate: ServerHttpResponse, private val req: BusterRequest) :
        ServerHttpResponseDecorator(delegate) {

        override fun writeWith(body: Publisher<out DataBuffer>): Mono<Void> =
            DataBufferUtils.join(body)
                .map { joined -> bytesOf(joined) }
                .defaultIfEmpty(ByteArray(0))
                .flatMap { bytes ->
                    val result = mutate(bytes)
                    val out = super.writeWith(Mono.just(bufferFactory().wrap(result.body)))
                    if (result.mutated && !pace.isZero) Mono.delay(pace).then(out) else out
                }

        /** Runs the engine over the buffered response and copies the result back before commit. */
        private fun mutate(bytes: ByteArray): Result {
            val res = BusterResponse(statusCode?.value() ?: OK, Headers(headers), bytes)
            val record = engine.respond(req, res)
            statusCode = HttpStatusCode.valueOf(res.status)
            headers.clear()
            res.headers.toMap().forEach { (name, values) -> headers[name] = values }
            headers.contentLength = res.body.size.toLong()
            return Result(res.body, record != null)
        }

        override fun writeAndFlushWith(body: Publisher<out Publisher<out DataBuffer>>): Mono<Void> =
            writeWith(Flux.from(body).concatMap { it })
    }

    private class Result(val body: ByteArray, val mutated: Boolean)

    /**
     * A client is its W3C trace id when it sends traceparent (version-traceid-spanid-flags),
     * else its client header, else its address. The client header still labels it.
     */
    private fun toBuster(request: ServerHttpRequest): BusterRequest {
        val trace = request.headers.getFirst(TRACEPARENT)?.split('-')?.takeIf { it.size == TRACEPARENT_PARTS }
        val named = request.headers.getFirst(clientHeader)
        val clientId = trace?.get(1) ?: named ?: request.remoteAddress?.address?.hostAddress ?: "anonymous"
        return BusterRequest(
            method = request.method.name(),
            path = request.path.value(),
            query = request.uri.rawQuery,
            headers = Headers(request.headers),
            clientId = clientId,
            spanId = trace?.get(2),
            label = named ?: clientId,
        )
    }

    private fun campaign(exchange: ServerWebExchange): Mono<Void> {
        val request = exchange.request
        val action = request.path.value().removePrefix(CampaignEndpoint.PATH)
        val round = request.queryParams.getFirst("round")
        val reply = CampaignEndpoint(engine).handle(request.method.name(), action, round)
        exchange.response.statusCode = HttpStatusCode.valueOf(reply.status)
        return write(exchange, MediaType.APPLICATION_JSON, Hal.mapper.writeValueAsBytes(reply.body))
    }

    /** POST /_buster/events: one or more client event lines (JSON Lines), handed to the verifier. */
    private fun events(exchange: ServerWebExchange): Mono<Void> {
        if (exchange.request.method != HttpMethod.POST) {
            exchange.response.statusCode = HttpStatusCode.valueOf(METHOD_NOT_ALLOWED)
            exchange.response.headers.allow = setOf(HttpMethod.POST)
            return exchange.response.setComplete()
        }
        return DataBufferUtils.join(exchange.request.body)
            .map { String(bytesOf(it), Charsets.UTF_8) }
            .defaultIfEmpty("")
            .flatMap { text ->
                text.lineSequence().filter { it.isNotBlank() }.mapNotNull(ClientEvent::parse).forEach(engine::event)
                exchange.response.statusCode = HttpStatusCode.valueOf(NO_CONTENT)
                exchange.response.setComplete()
            }
    }

    /** Reads a joined buffer whole and releases it. */
    private fun bytesOf(buffer: DataBuffer): ByteArray {
        val bytes = ByteArray(buffer.readableByteCount())
        buffer.read(bytes)
        DataBufferUtils.release(buffer)
        return bytes
    }

    /** JSON for people and tools; HTML rows when htmx asks, for the live view. */
    private fun report(exchange: ServerWebExchange): Mono<Void> {
        if (exchange.request.queryParams.getFirst("flush") == "true") engine.flush()
        val mutations = engine.mutations()
        val verdicts = engine.verdicts()
        if (exchange.request.headers.getFirst(HX_REQUEST) == "true") {
            val rows = LiveView.rows(engine.seed, pace.toMillis(), mutations, verdicts)
            return write(exchange, HTML, rows.toByteArray())
        }
        val body = Hal.mapper.writeValueAsBytes(
            mapOf(
                "seed" to engine.seed, "paceMs" to pace.toMillis(),
                "mutations" to mutations.map { it.fields() }, "verdicts" to verdicts.map { it.fields() },
                "campaign" to campaignJson(),
            ),
        )
        return write(exchange, MediaType.APPLICATION_JSON, body)
    }

    /** The campaign gate: no FAIL, and a combination for every plugin. */
    private fun campaignJson(): Map<String, Any?> {
        val report = engine.campaignReport()
        return mapOf(
            "mode" to report.mode.name, "round" to report.round, "coverage" to report.coverage,
            "holes" to report.holes, "matrix" to report.matrix, "failures" to report.failures, "ok" to report.ok,
        )
    }

    private fun write(exchange: ServerWebExchange, type: MediaType, body: ByteArray): Mono<Void> {
        val response = exchange.response
        response.headers.contentType = type
        response.headers.cacheControl = "no-store"
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)))
    }

    companion object {
        const val REPORT_PATH = "/_buster/report"
        const val VIEW_PATH = "/_buster"
        const val EVENTS_PATH = "/_buster/events"
        private const val TRACEPARENT = "traceparent"
        private const val TRACEPARENT_PARTS = 4
        private const val NO_CONTENT = 204
        private const val METHOD_NOT_ALLOWED = 405
        /** User-Agent names the client software, so each client should send its own. */
        const val DEFAULT_CLIENT_HEADER = "User-Agent"
        private val HTML = MediaType(MediaType.TEXT_HTML, Charsets.UTF_8)
        private const val HX_REQUEST = "HX-Request"
        private const val OK = 200
    }
}
