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
    lateinit var response: BusterResponse

    /** Sends a Layer 1 response for [path] through a fresh engine holding [plugins]. */
    fun layerOneGoesOut(path: String, client: String, applied: String? = null, body: String = BED) {
        engine = Engine(plugins, 1, log)
        val headers = Headers()
        headers["Content-Type"] = "application/hal+json"
        headers["Cache-Control"] = "no-cache"
        headers["ETag"] = "\"s1\""
        applied?.let { headers["Preference-Applied"] = it }
        response = BusterResponse(200, headers, body.toByteArray())
        engine.respond(BusterRequest("GET", path, clientId = client), response)
    }

    private companion object {
        const val BED = """{"name":"North bed"}"""
    }
}
