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

    /** Sends a Layer 1 response for [path] through a fresh engine holding [plugins]. */
    fun layerOneGoesOut(path: String, client: String, applied: String? = null, body: String = BED) {
        val headers = headers("application/hal+json", "no-cache")
        headers["ETag"] = "\"s1\""
        applied?.let { headers["Preference-Applied"] = it }
        goesOut(path, client, BusterResponse(200, headers, body.toByteArray()))
    }

    /** Sends a content-addressed Layer 2 or 3 response, served immutable. */
    fun immutableGoesOut(path: String, client: String, contentType: String, body: String) =
        goesOut(path, client, BusterResponse(200, headers(contentType, IMMUTABLE), body.toByteArray()))

    private fun goesOut(path: String, client: String, res: BusterResponse) {
        engine = Engine(plugins, 1, log)
        original = res.copy()
        response = res
        engine.respond(BusterRequest("GET", path, clientId = client), response)
    }

    private fun headers(contentType: String, cacheControl: String) = Headers().also {
        it["Content-Type"] = contentType
        it["Cache-Control"] = cacheControl
    }

    private companion object {
        const val BED = """{"name":"North bed"}"""
        const val IMMUTABLE = "public, max-age=31536000, immutable"
    }
}
