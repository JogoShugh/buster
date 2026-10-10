package com.artie.buster.spring

import com.artie.buster.core.Engine
import com.artie.buster.core.Plan

/**
 * The campaign over HTTP, under /_buster/campaign:
 *
 *  - `POST record`: start a dry run. Every exchange is recorded; none is mutated.
 *  - `GET plan`: end the dry run and list the mutations the clients will exercise, in rounds.
 *  - `POST arm?round=k`: mutate exactly round k's exchanges, and end the round before it.
 *  - `POST off`: back to mutating by seed.
 */
internal class CampaignEndpoint(private val engine: Engine) {

    class Reply(val status: Int, val body: Map<String, Any?>)

    fun handle(method: String, action: String, round: String?): Reply = when (action) {
        "record" -> post(method) {
            engine.campaign.startRecording()
            Reply(OK, mapOf("mode" to engine.campaign.mode.name))
        }
        "plan" -> get(method) { Reply(OK, planJson(engine.campaign.plan())) }
        "arm" -> post(method) { arm(round) }
        "off" -> post(method) {
            engine.campaign.disarm()
            Reply(OK, mapOf("mode" to engine.campaign.mode.name))
        }
        else -> Reply(NOT_FOUND, mapOf("error" to "no such campaign action: $action"))
    }

    private fun arm(round: String?): Reply {
        val number = round?.toIntOrNull() ?: return Reply(BAD_REQUEST, mapOf("error" to "arm needs ?round=<number>"))
        return try {
            val size = engine.campaign.arm(number)
            Reply(OK, mapOf("mode" to engine.campaign.mode.name, "round" to number, "armed" to size))
        } catch (problem: IllegalStateException) {
            Reply(CONFLICT, mapOf("error" to problem.message))
        }
    }

    private fun post(method: String, block: () -> Reply) =
        if (method == "POST") block() else Reply(NOT_ALLOWED, mapOf("error" to "use POST"))

    private fun get(method: String, block: () -> Reply) =
        if (method == "GET") block() else Reply(NOT_ALLOWED, mapOf("error" to "use GET"))

    companion object {
        const val PATH = "/_buster/campaign/"
        private const val OK = 200
        private const val BAD_REQUEST = 400
        private const val NOT_FOUND = 404
        private const val NOT_ALLOWED = 405
        private const val CONFLICT = 409

        fun planJson(plan: Plan): Map<String, Any?> = mapOf(
            "rounds" to plan.rounds.mapIndexed { i, combos ->
                mapOf(
                    "round" to i + 1,
                    "combinations" to combos.map {
                        mapOf(
                            "label" to it.label, "index" to it.index, "plugin" to it.plugin,
                            "path" to it.path, "detail" to it.detail,
                        )
                    },
                )
            },
            "coverage" to plan.coverage,
            "holes" to plan.holes,
            "skipped" to plan.skipped.map {
                mapOf("label" to it.label, "index" to it.index, "plugin" to it.plugin, "reason" to it.reason)
            },
        )
    }
}
