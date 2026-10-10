package com.artie.buster.bdd

import com.artie.buster.core.BusterRequest
import com.artie.buster.core.BusterResponse
import com.artie.buster.core.Engine
import com.artie.buster.core.Hal
import com.artie.buster.core.Headers
import com.artie.buster.core.Layer
import com.artie.buster.core.MutationLog
import com.artie.buster.core.Outcome
import com.artie.buster.core.Plugin
import com.artie.buster.dsl.buster
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

class VerifierSteps {
    private val log = MutationLog()
    private var plugins = listOf<Plugin>()
    private lateinit var engine: Engine

    @Given("a plugin {string} that expects {string} within {int} requests:")
    fun expecting(name: String, description: String, window: Int, rules: Map<String, String>) {
        val target = rules.getValue("on a GET of")
        val failure = rules.getValue("it fails")
        val expiry = rules.getValue("at expiry")
        plugins = buster {
            plugin(name) {
                on(Layer.RESOURCE)
                mutate {
                    expect(description, window) {
                        onRequest { req -> if (req.isGet && req.path == target) fail(failure) else pending() }
                        onExpire { pass(expiry) }
                    }
                }
            }
        }
    }

    @Given("a plugin {string} that expects {string} and then skips")
    fun expectsThenSkips(name: String, description: String) {
        plugins = buster {
            plugin(name) {
                on(Layer.RESOURCE)
                mutate {
                    expect(description, 1) { onExpire { pass("should never be judged") } }
                    skip("changed its mind")
                }
            }
        }
    }

    @Given("a Layer 1 response for {string} goes out to client {string}")
    @When("a Layer 1 response for {string} goes out to client {string} using only that plugin")
    fun goesOut(path: String, client: String) {
        engine = Engine(plugins, 1, log)
        val headers = Headers()
        headers["Content-Type"] = "application/hal+json"
        headers["Cache-Control"] = "no-cache"
        headers["ETag"] = "\"s1\""
        val response = BusterResponse(200, headers, """{"name":"North bed"}""".toByteArray())
        engine.respond(BusterRequest("GET", path, clientId = client), response)
    }

    @When("client {string} sends {}")
    fun sends(client: String, requests: String) {
        if (requests == "nothing") return
        requests.split(",").map { it.trim().split(" ") }.forEach { (method, path) ->
            engine.request(BusterRequest(method, path, clientId = client))
        }
    }

    @When("the run ends")
    fun runEnds() = engine.flush()

    @Then("the verdict for {string} is {word} with reason {string}")
    fun verdict(plugin: String, result: String, reason: String) {
        val outcome = engine.verdicts().single { it.record.plugin == plugin }.outcome
        val expected = when (result) {
            "pass" -> Outcome.Pass(reason)
            "FAIL" -> Outcome.Fail(reason)
            "inconclusive" -> Outcome.Inconclusive(reason)
            else -> error("unknown result $result")
        }
        assertEquals(expected, outcome)
    }

    @Then("{string} has no verdict yet")
    @Then("{string} has no verdict")
    fun noVerdict(plugin: String) {
        assertTrue(engine.verdicts().none { it.record.plugin == plugin })
    }

    @Then("the log has a verdict for sequence {int} by {string} on {string} with result {string}")
    fun loggedVerdict(seq: Int, plugin: String, expectation: String, result: String) {
        val line = log.recent().map { Hal.mapper.readTree(it) }.single { it["event"].asText() == "verdict" }
        val logged = listOf(
            line["seq"].asLong(), line["plugin"].asText(), line["expectation"].asText(), line["result"].asText(),
        )
        assertEquals(listOf(seq.toLong(), plugin, expectation, result), logged)
    }
}
