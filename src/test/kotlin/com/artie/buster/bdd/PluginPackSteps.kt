package com.artie.buster.bdd

import com.artie.buster.core.Hal
import com.artie.buster.script.LoadReport
import com.artie.buster.script.PluginHost
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
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

    @Given("an L1 Layer 1 response for {string} goes out to client {string}")
    fun l1GoesOut(path: String, client: String) {
        val body = checkNotNull(javaClass.getResource("/fixtures/resource.json")).readText()
        world.layerOneGoesOut(path, client, applied = "forms=link", body = body)
    }

    @Then("the forms link has no integrity")
    fun noIntegrity() {
        val link = checkNotNull(Hal.link(doc(), Hal.FORMS_REL)) { "forms link missing" }
        assertFalse(link.has("integrity"))
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

    private fun doc() = checkNotNull(Hal.parseObject(world.response.body))

    private fun pack(): LoadReport = PluginHost(Path.of("plugins")).reload()
}
