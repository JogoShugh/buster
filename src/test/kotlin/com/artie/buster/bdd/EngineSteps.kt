package com.artie.buster.bdd

import com.artie.buster.core.BusterRequest
import com.artie.buster.core.BusterResponse
import com.artie.buster.core.Engine
import com.artie.buster.core.Hal
import com.artie.buster.core.Headers
import com.artie.buster.core.Layer
import com.artie.buster.core.MutationLog
import com.artie.buster.core.MutationScope
import com.artie.buster.core.Plugin
import com.artie.buster.dsl.buster
import com.fasterxml.jackson.databind.JsonNode
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue

class EngineSteps {
    private var seed = 0L
    private val plugins = mutableListOf<Plugin>()
    private var log = MutationLog()
    private lateinit var original: BusterResponse
    private lateinit var response: BusterResponse
    private var runs = mutableListOf<List<String>>()

    @Given("the seed is {int}")
    fun theSeed(seed: Int) {
        this.seed = seed.toLong()
    }

    @Given("a plugin {string} on {word} that sets the body field {string} to {string}")
    fun setsField(name: String, layer: String, field: String, value: String) =
        add(name, layer) { setField(field, value) }

    @Given("a plugin {string} on {word} that sets the body field {string} to {string} and Cache-Control {string}")
    fun setsFieldAndCaching(name: String, layer: String, field: String, value: String, cacheControl: String) =
        add(name, layer) {
            setField(field, value)
            response.headers["Cache-Control"] = cacheControl
        }

    @Given("a plugin {string} on {word} that sets Cache-Control {string} and keeps it, leaving the body alone")
    fun keepsCaching(name: String, layer: String, cacheControl: String) =
        add(name, layer) {
            response.headers["Cache-Control"] = cacheControl
            keepCacheControl()
        }

    @Given(
        "a plugin {string} on {word} that sets Cache-Control {string} and keeps it, " +
            "and sets the body field {string} to {string}",
    )
    fun keepsCachingButChangesBody(name: String, layer: String, cacheControl: String, field: String, value: String) =
        add(name, layer) {
            response.headers["Cache-Control"] = cacheControl
            keepCacheControl()
            setField(field, value)
        }

    @Given("a plugin {string} on {word} that skips because {string}")
    fun skips(name: String, layer: String, reason: String) =
        add(name, layer) {
            setField("name", "half-chewed")
            skip(reason)
        }

    @When("a Layer 1 response for {string} goes out")
    fun layerOneGoesOut(path: String) = goOut(path, layerOne())

    @When("a 404 problem response for {string} goes out")
    fun problemGoesOut(path: String) =
        goOut(path, BusterResponse(404, headersOf("application/problem+json"), """{"status":404}""".toByteArray()))

    @When("{int} Layer 1 responses go out with seed {int}, twice")
    fun manyTwice(count: Int, seed: Int) {
        repeat(2) { runs += choices(count, seed.toLong()) }
    }

    @Then("the body field {string} is {string}")
    fun bodyField(field: String, value: String) {
        assertEquals(value, Hal.parseObject(response.body)?.path(field)?.asText())
    }

    @Then("Cache-Control is {string}")
    fun cacheControl(value: String) {
        assertEquals(value, response.headers["Cache-Control"])
    }

    @Then("the log has a mutation by {string} on {string} with seed {int} and sequence {int}")
    fun loggedMutation(plugin: String, path: String, seed: Int, seq: Int) {
        val line = entries("mutation").single()
        assertEquals(
            listOf(plugin, path, seed.toLong(), seq.toLong()),
            listOf(line["plugin"].asText(), line["path"].asText(), line["seed"].asLong(), line["seq"].asLong()),
        )
    }

    @Then("the log has no mutations")
    fun noMutations() {
        assertEquals(emptyList<JsonNode>(), entries("mutation"))
    }

    @Then("the response is unchanged")
    fun unchanged() {
        assertEquals(original.status, response.status)
        assertEquals(original.headers.toMap(), response.headers.toMap())
        assertArrayEquals(original.body, response.body)
    }

    @Then("the log notes that {string} skipped because {string}")
    fun loggedSkip(plugin: String, reason: String) {
        assertTrue(entries("note").any { it["message"].asText() == "$plugin skipped: $reason" })
    }

    @Then("both runs chose the same plugins in the same order")
    fun sameChoices() {
        assertEquals(runs[0], runs[1])
    }

    @Then("a run with seed {int} chose a different order")
    fun differentChoices(seed: Int) {
        assertNotEquals(runs[0], choices(runs[0].size, seed.toLong()))
    }

    private fun add(name: String, layer: String, block: MutationScope.() -> Unit) {
        plugins += buster {
            plugin(name) {
                on(Layer.valueOf(layer))
                mutate(block)
            }
        }
    }

    private fun MutationScope.setField(field: String, value: String) {
        val doc = checkNotNull(json())
        doc.put(field, value)
        writeJson(doc)
    }

    private fun goOut(path: String, res: BusterResponse) {
        original = res.copy()
        response = res
        Engine(plugins, seed, log).respond(BusterRequest("GET", path), response)
    }

    private fun choices(count: Int, seed: Long): List<String> {
        val runLog = MutationLog()
        val engine = Engine(plugins, seed, runLog)
        repeat(count) { engine.respond(BusterRequest("GET", "/beds/$it"), layerOne()) }
        return runLog.recent().map { Hal.mapper.readTree(it) }.map { it["plugin"].asText() }
    }

    private fun entries(event: String): List<JsonNode> =
        log.recent().map { Hal.mapper.readTree(it) }.filter { it["event"].asText() == event }

    private fun layerOne(): BusterResponse {
        val headers = headersOf("application/hal+json")
        headers["Cache-Control"] = "no-cache"
        headers["ETag"] = "\"s1\""
        val body = """{"name":"North bed","_links":{"self":{"href":"/beds/1"}}}"""
        return BusterResponse(200, headers, body.toByteArray())
    }

    private fun headersOf(contentType: String) = Headers().also { it["Content-Type"] = contentType }
}
