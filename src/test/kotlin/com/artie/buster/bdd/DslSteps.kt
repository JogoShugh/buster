package com.artie.buster.bdd

import com.artie.buster.core.Hal
import com.artie.buster.script.PluginHost
import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import java.nio.file.Files
import java.nio.file.Path

/** Steps for scenarios that put the plugin under test in the feature itself, as a script. */
class DslSteps(private val world: World) {
    private var folder: Path? = null

    @After
    fun removeFolder() {
        folder?.toFile()?.deleteRecursively()
    }

    @Given("the plugin script:")
    fun pluginScript(source: String) {
        val dir = Files.createTempDirectory("buster-dsl").also { folder = it }
        Files.writeString(dir.resolve("under-test.buster.kts"), source)
        val report = PluginHost(dir).reload()
        assertEquals(emptyList<Any>(), report.failures)
        world.plugins = report.plugins
    }

    @Then("{string} was skipped because {string}")
    fun skippedBecause(plugin: String, reason: String) {
        val notes = world.log.recent().map { Hal.mapper.readTree(it) }
            .filter { it["event"].asText() == "note" }.map { it["message"].asText() }
        assertTrue("$plugin skipped: $reason" in notes) { "notes: $notes" }
    }

    @Then("the response was changed")
    fun wasChanged() {
        val same = world.original.body.contentEquals(world.response.body) &&
            world.original.headers.toMap() == world.response.headers.toMap()
        assertFalse(same) { "the response went out unchanged" }
    }
}
