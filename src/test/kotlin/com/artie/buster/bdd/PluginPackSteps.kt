package com.artie.buster.bdd

import com.artie.buster.core.Forms
import com.artie.buster.core.Hal
import com.artie.buster.script.LoadReport
import com.artie.buster.script.PluginHost
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import java.nio.file.Path

class PluginPackSteps(private val world: World) {
    private lateinit var report: LoadReport

    @When("Buster loads the plugin pack")
    fun loadPack() {
        report = pack()
    }

    @Then("the pack has the plugins {string}")
    fun packHas(names: String) {
        assertEquals(emptyList<Any>(), report.failures)
        assertEquals(names, report.plugins.map { it.name }.sorted().joinToString(", "))
    }

    @Given("only the {string} plugin from the pack")
    fun onlyPlugin(name: String) {
        val loaded = pack()
        assertEquals(emptyList<Any>(), loaded.failures)
        world.plugins = loaded.plugins.filter { it.name == name }
        assertEquals(1, world.plugins.size) { "no plugin $name in the pack" }
    }

    @Given("an L1 Layer 1 response goes out to client {string}")
    fun l1GoesOut(client: String) = world.layerOneGoesOut("/beds/1", client, "forms=link", fixture("resource"))

    @Given("an L0 Layer 1 response goes out to client {string}")
    fun l0GoesOut(client: String) = world.layerOneGoesOut("/beds/1", client, body = fixture("resource-l0"))

    @Given("an affordance set response goes out to client {string}")
    fun setGoesOut(client: String) =
        world.immutableGoesOut("/forms/sha256-abc", client, "application/hal+json", fixture("set-embedded-schemas"))

    @Given("a schema response goes out to client {string}")
    fun schemaGoesOut(client: String) =
        world.immutableGoesOut("/schemas/sha256-def", client, "application/schema+json", fixture("schema"))

    @Then("the forms link has no integrity")
    fun noIntegrity() {
        assertFalse(formsLink().has("integrity"))
    }

    @Then("the links have no curies")
    fun noCuries() {
        assertFalse(checkNotNull(Hal.links(doc())).has("curies"))
    }

    @Then("the ETag is no longer {string}")
    fun etagChanged(original: String) {
        assertNotEquals("\"$original\"", world.response.headers["ETag"])
    }

    @Then("the response keeps Cache-Control {string}")
    fun keepsCacheControl(value: String) {
        assertEquals(value, world.response.headers["Cache-Control"])
    }

    @Then("the response has no Preference-Applied")
    fun noPreferenceApplied() {
        assertFalse(world.response.headers.has("Preference-Applied"))
    }

    @Then("the forms link has a query the server did not write")
    fun aliased() {
        assertTrue(formsLink().path("href").asText().contains("?"))
    }

    @Then("the affordance {string} is gone")
    fun affordanceGone(name: String) {
        assertFalse(Forms.embedded(doc())?.has(name) ?: false)
    }

    @Then("the body is still JSON but one character differs")
    fun oneByteFlipped() {
        assertNotNull(Hal.parseObject(world.response.body))
        val before = world.original.body
        val after = world.response.body
        assertEquals(before.size, after.size)
        assertEquals(1, before.indices.count { before[it] != after[it] })
    }

    private fun doc() = checkNotNull(Hal.parseObject(world.response.body))

    private fun formsLink() = checkNotNull(Hal.link(doc(), Hal.FORMS_REL)) { "forms link missing" }

    private fun fixture(name: String) = checkNotNull(javaClass.getResource("/fixtures/$name.json")).readText()

    private fun pack(): LoadReport = PluginHost(Path.of("plugins")).reload()
}
