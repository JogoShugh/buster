package com.artie.buster.bdd

import com.artie.buster.core.BusterRequest
import com.artie.buster.core.Hal
import com.artie.buster.core.Headers
import com.artie.buster.core.Layer
import com.artie.buster.core.Outcome
import com.artie.buster.dsl.buster
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

class VerifierSteps(private val world: World) {

    @Given("a plugin {string} that expects {string} within {int} requests:")
    fun expecting(name: String, description: String, window: Int, rules: Map<String, String>) {
        val target = rules.getValue("on a GET of")
        val failure = rules.getValue("it fails")
        val expiry = rules.getValue("at expiry")
        world.plugins = buster {
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
        world.plugins = buster {
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
    fun goesOut(path: String, client: String) = world.layerOneGoesOut(path, client)

    /** Requests are "METHOD /path", comma-separated; "with the mutated ETag" adds If-Match. */
    @When("client {string} sends {}")
    fun sends(client: String, requests: String) {
        if (requests == "nothing") return
        requests.split(",").map { it.trim() }.forEach { request ->
            val (method, path) = request.split(" ")
            val headers = Headers()
            if (request.endsWith("with the mutated ETag")) headers["If-Match"] = world.response.headers["ETag"]!!
            world.engine.request(BusterRequest(method, path, headers = headers, clientId = client))
        }
    }

    @When("the run ends")
    fun runEnds() = world.engine.flush()

    @Then("the verdict for {string} is {word} with reason {string}")
    fun verdict(plugin: String, result: String, reason: String) {
        val expected = when (result) {
            "pass" -> Outcome.Pass(reason)
            "FAIL" -> Outcome.Fail(reason)
            "inconclusive" -> Outcome.Inconclusive(reason)
            else -> error("unknown result $result")
        }
        assertEquals(expected, outcomeOf(plugin))
    }

    @Then("the verdict for {string} is {word}")
    fun verdictKind(plugin: String, result: String) {
        val kind = when (outcomeOf(plugin)) {
            is Outcome.Pass -> "pass"
            is Outcome.Fail -> "FAIL"
            is Outcome.Inconclusive -> "inconclusive"
            Outcome.Pending -> "pending"
        }
        assertEquals(result, kind) { "verdicts: ${world.engine.verdicts()}" }
    }

    @Then("{string} has no verdict yet")
    @Then("{string} has no verdict")
    fun noVerdict(plugin: String) {
        assertTrue(world.engine.verdicts().none { it.record.plugin == plugin })
    }

    @Then("the log has a verdict for sequence {int} by {string} on {string} with result {string}")
    fun loggedVerdict(seq: Int, plugin: String, expectation: String, result: String) {
        val line = world.log.recent().map { Hal.mapper.readTree(it) }.single { it["event"].asText() == "verdict" }
        val logged = listOf(
            line["seq"].asLong(), line["plugin"].asText(), line["expectation"].asText(), line["result"].asText(),
        )
        assertEquals(listOf(seq.toLong(), plugin, expectation, result), logged)
    }

    private fun outcomeOf(plugin: String) = world.engine.verdicts().single { it.record.plugin == plugin }.outcome
}
