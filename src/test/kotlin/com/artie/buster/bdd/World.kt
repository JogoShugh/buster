package com.artie.buster.bdd

import com.artie.buster.core.BusterRequest
import com.artie.buster.core.BusterResponse
import com.artie.buster.core.Engine
import com.artie.buster.core.Headers
import com.artie.buster.core.MutationLog
import com.artie.buster.core.Plugin

/** State shared by step classes within one scenario; picocontainer injects one per scenario. */
class World {
    val log = MutationLog()
    var plugins = listOf<Plugin>()
    lateinit var engine: Engine
    lateinit var original: BusterResponse
    lateinit var response: BusterResponse

    /** What the client's dry run showed it sends after the response goes out; null when unknown. */
    var dryRun: List<BusterRequest>? = null

    /** Sends a Layer 1 response for [path] through a fresh engine holding [plugins]. */
    fun layerOneGoesOut(
        path: String,
        client: String,
        applied: String? = null,
        body: String = BED,
        readWithNoCache: Boolean = false,
    ) {
        val headers = headers("application/hal+json", "no-cache")
        headers["ETag"] = "\"s1\""
        applied?.let { headers["Preference-Applied"] = it }
        requestHeaders = Headers().also { if (readWithNoCache) it["Cache-Control"] = "no-cache" }
        goesOut(path, client, BusterResponse(200, headers, body.toByteArray()))
    }

    /** Headers of the request whose response goes out next. */
    private var requestHeaders = Headers()

    /** Sends a content-addressed Layer 2 or 3 response, served immutable. */
    fun immutableGoesOut(path: String, client: String, contentType: String, body: String) =
        goesOut(path, client, BusterResponse(200, headers(contentType, IMMUTABLE), body.toByteArray()))

    /** A plain JSON 200 from an ordinary API: no ETag, nothing H.E.A.R.T. about it. */
    fun plainJsonGoesOut(path: String, client: String) =
        goesOut(path, client, BusterResponse(200, headers("application/json", "no-cache"), TODO.toByteArray()))

    /** An RFC 9457 problem response. */
    fun problemGoesOut(path: String, client: String) {
        val headers = headers("application/problem+json", "no-cache")
        goesOut(path, client, BusterResponse(404, headers, PROBLEM.toByteArray()))
    }

    private fun goesOut(path: String, client: String, res: BusterResponse) {
        engine = Engine(plugins, 1, log)
        sendThrough(path, client, res)
    }

    /** Another Layer 1 response through the same engine, so earlier expectations stay open. */
    fun anotherLayerOneGoesOut(path: String, client: String) {
        val headers = headers("application/hal+json", "no-cache")
        headers["ETag"] = "\"s1\""
        sendThrough(path, client, BusterResponse(200, headers, BED.toByteArray()))
    }

    private fun sendThrough(path: String, client: String, res: BusterResponse) {
        original = res.copy()
        response = res
        lastPath = path
        engine.respond(
            BusterRequest("GET", path, headers = requestHeaders, clientId = client, spanId = SPAN), response, dryRun,
        )
        requestHeaders = Headers()
    }

    /** The path of the last response sent out. */
    var lastPath = ""

    private fun headers(contentType: String, cacheControl: String) = Headers().also {
        it["Content-Type"] = contentType
        it["Cache-Control"] = cacheControl
    }

    companion object {
        /** The span of every request a scenario sends out; an event about "that response" names it. */
        const val SPAN = "00f067aa0ba902b7"
        private const val BED = """{"name":"North bed"}"""
        private const val TODO = """{"id":1,"title":"Walk Buster","done":false}"""
        private const val PROBLEM = """{"status":404,"title":"Not Found"}"""
        private const val IMMUTABLE = "public, max-age=31536000, immutable"
    }
}
