package spike.envoy

import com.artie.buster.core.BusterRequest
import com.artie.buster.core.BusterResponse
import com.artie.buster.core.Engine
import com.artie.buster.core.Headers
import io.envoyproxy.envoy.config.core.v3.HeaderValue
import io.envoyproxy.envoy.config.core.v3.HeaderMap
import io.envoyproxy.envoy.service.ext_proc.v3.CommonResponse
import io.envoyproxy.envoy.service.ext_proc.v3.ExternalProcessorGrpc
import io.envoyproxy.envoy.service.ext_proc.v3.HeadersResponse
import io.envoyproxy.envoy.service.ext_proc.v3.ImmediateResponse
import io.envoyproxy.envoy.service.ext_proc.v3.ProcessingRequest
import io.envoyproxy.envoy.service.ext_proc.v3.ProcessingResponse
import io.envoyproxy.envoy.service.ext_proc.v3.BodyResponse
import io.envoyproxy.envoy.service.ext_proc.v3.HttpHeaders
import io.envoyproxy.envoy.type.v3.HttpStatus
import com.google.protobuf.ByteString
import io.grpc.stub.StreamObserver
import java.util.concurrent.atomic.AtomicInteger

/**
 * Buster as an Envoy external processor. One gRPC stream is one HTTP exchange, so the request
 * seen at request headers is the one the response answers. Envoy is asked for request headers,
 * response headers, and the whole response body (BUFFERED); everything else Envoy does itself.
 *
 * A response Buster leaves alone is answered CONTINUE. A mutated one is sent back whole as an
 * ImmediateResponse, which is the simplest way to say "this status, these headers, this body".
 */
class ExtProc(private val engine: Engine) : ExternalProcessorGrpc.ExternalProcessorImplBase() {

    val streams = AtomicInteger()

    override fun process(out: StreamObserver<ProcessingResponse>): StreamObserver<ProcessingRequest> {
        streams.incrementAndGet()
        return Exchange(engine, out)
    }
}

private class Exchange(
    private val engine: Engine,
    private val out: StreamObserver<ProcessingResponse>,
) : StreamObserver<ProcessingRequest> {

    private var request: BusterRequest? = null
    private var status = OK
    private var responseHeaders = Headers()

    override fun onNext(message: ProcessingRequest) {
        println("ext_proc <- ${message.requestCase}")
        try {
            handle(message)
        } catch (e: RuntimeException) {
            println("ext_proc failed: $e")
            throw e
        }
    }

    private fun handle(message: ProcessingRequest) {
        when (message.requestCase) {
            ProcessingRequest.RequestCase.REQUEST_HEADERS -> {
                request = toRequest(message.requestHeaders)
                engine.request(request!!)
                out.onNext(headersContinue(requestPhase = true))
            }
            ProcessingRequest.RequestCase.RESPONSE_HEADERS -> {
                val headers = message.responseHeaders
                responseHeaders = toHeaders(headers.headers)
                status = responseHeaders[":status"]?.toIntOrNull() ?: OK
                responseHeaders.remove(":status")
                if (headers.endOfStream) respond(ByteArray(0)) else out.onNext(headersContinue(requestPhase = false))
            }
            ProcessingRequest.RequestCase.RESPONSE_BODY -> respond(message.responseBody.body.toByteArray())
            else -> out.onNext(ProcessingResponse.getDefaultInstance())
        }
    }

    private fun respond(body: ByteArray) {
        val req = request
        if (req == null) {
            out.onNext(bodyContinue())
            return
        }
        val res = BusterResponse(status, responseHeaders, body)
        val before = Triple(res.status, res.headers.toMap(), res.body.copyOf())
        engine.respond(req, res)
        val changed = res.status != before.first || res.headers.toMap() != before.second ||
            !res.body.contentEquals(before.third)
        out.onNext(if (changed) immediate(res) else bodyContinue())
    }

    private fun toRequest(headers: HttpHeaders): BusterRequest {
        val all = toHeaders(headers.headers)
        val target = all[":path"] ?: "/"
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "").ifEmpty { null }
        val trace = all["traceparent"]?.split('-')?.takeIf { it.size == TRACEPARENT_PARTS }
        val named = all["user-agent"]
        val clientId = trace?.get(1) ?: named ?: "anonymous"
        return BusterRequest(
            method = all[":method"] ?: "GET",
            path = path,
            query = query,
            headers = all,
            clientId = clientId,
            spanId = trace?.get(2),
            label = named ?: clientId,
        )
    }

    private fun toHeaders(map: HeaderMap): Headers {
        val headers = Headers()
        map.headersList.forEach { h ->
            val value = if (h.rawValue.isEmpty) h.value else h.rawValue.toStringUtf8()
            headers.add(h.key, value)
        }
        return headers
    }

    private fun headersContinue(requestPhase: Boolean): ProcessingResponse {
        val reply = HeadersResponse.newBuilder().setResponse(CommonResponse.getDefaultInstance())
        return if (requestPhase) {
            ProcessingResponse.newBuilder().setRequestHeaders(reply).build()
        } else {
            ProcessingResponse.newBuilder().setResponseHeaders(reply).build()
        }
    }

    private fun bodyContinue(): ProcessingResponse =
        ProcessingResponse.newBuilder()
            .setResponseBody(BodyResponse.newBuilder().setResponse(CommonResponse.getDefaultInstance()))
            .build()

    private fun immediate(res: BusterResponse): ProcessingResponse {
        val reply = ImmediateResponse.newBuilder()
            .setStatus(HttpStatus.newBuilder().setCodeValue(res.status))
            .setBody(ByteString.copyFrom(res.body))
        val mutation = io.envoyproxy.envoy.service.ext_proc.v3.HeaderMutation.newBuilder()
        res.headers.toMap().forEach { (name, values) ->
            if (!name.equals("content-length", ignoreCase = true)) {
                values.forEach { v ->
                    mutation.addSetHeaders(
                        io.envoyproxy.envoy.config.core.v3.HeaderValueOption.newBuilder()
                            .setHeader(HeaderValue.newBuilder().setKey(name).setRawValue(ByteString.copyFromUtf8(v))),
                    )
                }
            }
        }
        reply.setHeaders(mutation)
        return ProcessingResponse.newBuilder().setImmediateResponse(reply).build()
    }

    override fun onError(t: Throwable) = Unit

    override fun onCompleted() = out.onCompleted()

    private companion object {
        const val OK = 200
        const val TRACEPARENT_PARTS = 4
    }
}
