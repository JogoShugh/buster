package com.artie.buster.bdd

import com.artie.buster.core.BusterRequest
import com.artie.buster.core.BusterResponse
import com.artie.buster.core.Engine
import com.artie.buster.core.Headers
import com.artie.buster.core.Plan
import com.artie.buster.core.campaignReport
import com.artie.buster.script.PluginHost
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import java.nio.file.Path

/** Drives a campaign through the engine: record a dry run, plan, arm a round, run again. */
class CampaignSteps(private val world: World) {
    private var plan: Plan? = null

    @Given("a campaign over the plugin pack")
    fun overPack() = start(pack())

    @Given("a campaign over the plugins {string}")
    fun overPlugins(names: String) {
        val wanted = names.split(",").map { it.trim() }.toSet()
        start(pack().filter { it.name in wanted })
    }

    @Given("a recorded dry run of alice and bob over the plugin pack")
    fun recordedDryRun() {
        start(pack())
        world.engine.campaign.startRecording()
        exchange("alice", "GET", "/beds/1", "an L1 Layer 1")
        exchange("alice", "PUT", "/beds/1/name", "an L1 Layer 1", mapOf("If-Match" to "\"s1\""))
        exchange("bob", "GET", "/beds/1", "an L1 Layer 1")
        plan = world.engine.campaign.plan()
    }

    @Given("the campaign starts recording")
    fun startsRecording() = world.engine.campaign.startRecording()

    @Given("the campaign is planned")
    fun planned() {
        plan = world.engine.campaign.plan()
    }

    @Given("round {int} is armed")
    fun armed(round: Int) {
        world.engine.campaign.arm(round)
    }

    @Given("client {string} requests {word} {string} and gets {}")
    fun requests(client: String, method: String, path: String, kind: String) = exchange(client, method, path, kind)

    @Given("client {string} requests {word} {string} with the original ETag and gets {}")
    fun requestsWithEtag(client: String, method: String, path: String, kind: String) =
        exchange(client, method, path, kind, mapOf("If-Match" to "\"s1\""))

    @When("client {string} requests {word} {string} with no-cache and gets {}")
    fun requestsNoCache(client: String, method: String, path: String, kind: String) =
        exchange(client, method, path, kind, mapOf("Cache-Control" to "no-cache"))

    @Given("client {string} requests GET the forms link and gets {}")
    fun requestsFormsLink(client: String, kind: String) = exchange(client, "GET", FORMS_HREF, kind)

    @When("client {string} reads {int} more other beds")
    fun readsOthers(client: String, count: Int) =
        repeat(count) { exchange(client, "GET", "/other/$it", "an L1 Layer 1") }

    @Then("the last response went out unchanged")
    fun unchanged() {
        assertEquals(world.original.status, world.response.status)
        assertTrue(world.original.body.contentEquals(world.response.body))
        assertEquals(world.original.headers.toMap(), world.response.headers.toMap())
    }

    @Then("the last response went out mutated by {string}")
    fun mutatedBy(plugin: String) {
        assertEquals(plugin, world.engine.mutations().last().plugin)
    }

    @Then("the plan arms {string} for client {string} on request {int}")
    fun arms(plugin: String, client: String, request: Int) {
        val found = combinations().any { it.plugin == plugin && it.label == client && it.index == request - 1 }
        assertTrue(found) { "plan: ${combinations()}" }
    }

    @Then("the plan does not arm {string} for client {string}")
    fun doesNotArm(plugin: String, client: String) {
        assertFalse(combinations().any { it.plugin == plugin && it.label == client }) { "plan: ${combinations()}" }
    }

    @Then("the plan has {int} rounds")
    fun roundCount(count: Int) = assertEquals(count, checkNotNull(plan).rounds.size)

    @Then("round {int} arms one combination each for {string}")
    fun roundArms(round: Int, clients: String) {
        val labels = checkNotNull(plan).rounds[round - 1].map { it.label }
        assertEquals(clients.split(",").map { it.trim() }.sorted(), labels.sorted())
    }

    @Then("the plan has a coverage hole for {string}")
    fun hole(plugin: String) = assertTrue(plugin in checkNotNull(plan).holes) { "holes: ${plan?.holes}" }

    @Then("the plan has no coverage hole for {string}")
    fun noHole(plugin: String) = assertFalse(plugin in checkNotNull(plan).holes)

    @Then("the campaign is ok")
    fun ok() = assertTrue(world.engine.campaignReport().ok) { "${world.engine.campaignReport()}" }

    @Then("the campaign is not ok")
    fun notOk() = assertFalse(world.engine.campaignReport().ok) { "${world.engine.campaignReport()}" }

    private fun combinations() = checkNotNull(plan).rounds.flatten()

    private fun start(plugins: List<com.artie.buster.core.Plugin>) {
        world.plugins = plugins
        world.engine = Engine(plugins, SEED, world.log)
    }

    private fun exchange(
        client: String,
        method: String,
        path: String,
        kind: String,
        extra: Map<String, String> = emptyMap(),
    ) {
        val headers = Headers()
        extra.forEach { (name, value) -> headers[name] = value }
        val request = BusterRequest(method, path, null, headers, client)
        val response = respond(kind)
        world.original = response.copy()
        world.response = response
        world.engine.request(request)
        world.engine.respond(request, response)
    }

    private fun respond(kind: String): BusterResponse = when (kind) {
        "an L1 Layer 1" -> resource("resource", "forms=link")
        "an L0 Layer 1" -> resource("resource-l0", null)
        "an affordance set" -> immutable("application/hal+json", "set-embedded-schemas")
        "a schema" -> immutable("application/schema+json", "schema")
        else -> error("unknown response kind: $kind")
    }

    private fun resource(fixture: String, applied: String?): BusterResponse {
        val headers = Headers()
        headers["Content-Type"] = "application/hal+json"
        headers["Cache-Control"] = "no-cache"
        headers["ETag"] = "\"s1\""
        applied?.let { headers["Preference-Applied"] = it }
        return BusterResponse(200, headers, fixture(fixture).toByteArray())
    }

    private fun immutable(contentType: String, fixture: String): BusterResponse {
        val headers = Headers()
        headers["Content-Type"] = contentType
        headers["Cache-Control"] = "public, max-age=31536000, immutable"
        return BusterResponse(200, headers, fixture(fixture).toByteArray())
    }

    private fun fixture(name: String) = checkNotNull(javaClass.getResource("/fixtures/$name.json")).readText()

    private fun pack() = PluginHost(Path.of("plugins")).reload().plugins

    private companion object {
        const val SEED = 7L
        const val FORMS_HREF = "/forms/sha256-abc"
    }
}
