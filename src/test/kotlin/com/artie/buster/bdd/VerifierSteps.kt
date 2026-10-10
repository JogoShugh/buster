package com.artie.buster.bdd

import com.artie.buster.core.BusterRequest
import com.artie.buster.core.ClientEvent
import com.artie.buster.core.EventTail
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
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

class VerifierSteps(private val world: World) {

    @Given("a plugin {string} that expects {string} within {int} requests:")
    fun expecting(name: String, description: String, window: Int, rules: Map<String, String>) {
        val target = rules.getValue("on a GET of")
        val failure = rules.getValue("it fails")
        val expiry = rules.getValue("at expiry")
        val attestedBy = rules["attested by"]
        world.plugins = buster {
            plugin(name) {
                on(Layer.RESOURCE)
                mutate {
                    val span = request.spanId
                    expect(description, window) {
                        onRequest { req -> if (req.isGet && req.path == target) fail(failure) else pending() }
                        onEvent { ev ->
                            if (ev.name == attestedBy && ev.spanId == span) attest("said ${ev.name}") else pending()
                        }
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

    @Given("another Layer 1 response for {string} goes out to client {string}")
    fun anotherGoesOut(path: String, client: String) = world.anotherLayerOneGoesOut(path, client)

    /** "that response" is the last one sent out; "the forms link" is the link in it; "nothing" names no request. */
    @When("client {string} reports {string} for {}")
    fun reports(client: String, event: String, subject: String) = world.engine.event(eventOf(client, event, subject))

    @When("client {string} appends a {string} line for {} to the client event file")
    fun appends(client: String, event: String, subject: String) {
        val e = eventOf(client, event, subject)
        val line = Hal.mapper.writeValueAsString(
            mapOf(
                "trace_id" to e.traceId, "span_id" to e.spanId, "event_name" to e.name,
                "attributes" to e.attributes,
            ),
        )
        Files.writeString(eventFile, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    @When("Buster reads the client event file")
    fun readsFile() {
        EventTail(eventFile, world.engine::event).poll()
    }

    /** Bytes of a line still to be written, after its first half went out. */
    private var rest = ByteArray(0)
    private val read = mutableListOf<ClientEvent>()
    private val listTail by lazy { EventTail(eventFile) { read += it } }

    @When("the client event file gets the first half of a {string} line noting {string}")
    fun firstHalf(event: String, note: String) {
        val line = Hal.mapper.writeValueAsString(
            mapOf(
                "trace_id" to "trace-a", "span_id" to World.SPAN, "event_name" to event,
                "attributes" to mapOf("note" to note),
            ),
        ) + "\n"
        val bytes = line.toByteArray(Charsets.UTF_8)
        // Split inside the first multi-byte character, so neither half is valid UTF-8 alone.
        val cut = bytes.indexOfFirst { it < 0 } + 1
        Files.write(eventFile, bytes.copyOfRange(0, cut), StandardOpenOption.APPEND)
        rest = bytes.copyOfRange(cut, bytes.size)
    }

    @When("the client event file gets the rest of that line")
    fun restOfLine() {
        Files.write(eventFile, rest, StandardOpenOption.APPEND)
    }

    @When("Buster reads the client event file into a list")
    fun readsIntoList() = listTail.poll()

    @Then("Buster read one event, noting {string}")
    fun readOne(note: String) {
        assertEquals(1, read.size) { "read: $read" }
        assertEquals(note, read.single().attributes["note"])
    }

    @Then("client {string} still has an open expectation")
    fun stillOpen(client: String) = assertTrue(world.engine.openFor(client) > 0)

    private val eventFile: Path by lazy { Files.createTempFile("client-events", ".jsonl") }

    private fun eventOf(client: String, event: String, subject: String): ClientEvent {
        val url = when (subject) {
            "the forms link" -> "http://buster.test${formsHref()}"
            else -> "http://buster.test${world.lastPath}"
        }
        val span = when (subject) {
            "that response" -> World.SPAN
            "another response" -> "1111111111111111"
            else -> ""
        }
        return ClientEvent(client, span, event, mapOf("url.full" to url))
    }

    /**
     * Requests are "METHOD /path", comma-separated. "with the mutated ETag" adds If-Match;
     * "GET the forms link" follows the forms link in the response as it went out.
     */
    @When("client {string} sends {}")
    fun sends(client: String, requests: String) {
        if (requests == "nothing") return
        requests.split(",").map { it.trim() }.forEach { request ->
            val (method, target) = request.split(" ")
            val href = if (request == "GET the forms link") formsHref() else target
            val headers = Headers()
            if (request.endsWith("with the mutated ETag")) headers["If-Match"] = world.response.headers["ETag"]!!
            if (request.endsWith("with no-cache")) headers["Cache-Control"] = "no-cache"
            val req = BusterRequest(
                method, href.substringBefore('?'), href.substringAfter('?', "").ifEmpty { null }, headers, client,
            )
            world.engine.request(req)
        }
    }

    private fun formsHref(): String {
        val doc = checkNotNull(Hal.parseObject(world.response.body))
        return checkNotNull(Hal.link(doc, Hal.FORMS_REL)).path("href").asText()
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
            is Outcome.Attested -> "attested"
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
