package com.artie.buster.bdd

import com.artie.buster.core.BusterRequest
import com.artie.buster.core.BusterResponse
import com.artie.buster.core.Engine
import com.artie.buster.core.Hal
import com.artie.buster.core.Headers
import com.artie.buster.core.MutationLog
import com.artie.buster.script.LoadReport
import com.artie.buster.script.PluginHost
import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import java.nio.file.Files
import java.nio.file.Path

class ScriptHostSteps {
    private lateinit var folder: Path
    private lateinit var host: PluginHost
    private lateinit var report: LoadReport
    private lateinit var response: BusterResponse

    @Given("an empty plugins folder")
    fun emptyFolder() {
        folder = Files.createTempDirectory("buster-plugins")
        host = PluginHost(folder)
    }

    @After
    fun removeFolder() {
        if (::folder.isInitialized) folder.toFile().deleteRecursively()
    }

    @Given("the script {string}:")
    @When("the script {string} becomes:")
    fun theScript(name: String, source: String) {
        Files.writeString(folder.resolve(name), source)
    }

    @When("the script {string} is deleted")
    fun deleted(name: String) {
        Files.delete(folder.resolve(name))
    }

    @When("Buster loads the plugins folder")
    @When("Buster reloads the plugins folder")
    fun load() {
        report = host.reload()
    }

    @When("a Layer 1 response goes out through the loaded plugins")
    fun goesOut() {
        val headers = Headers()
        headers["Content-Type"] = "application/hal+json"
        headers["Cache-Control"] = "no-cache"
        headers["ETag"] = "\"s1\""
        response = BusterResponse(200, headers, """{"name":"North bed"}""".toByteArray())
        Engine(host.plugins, 1, MutationLog()).respond(BusterRequest("GET", "/beds/1"), response)
    }

    @Then("the loaded plugins are {string}")
    fun loadedPlugins(names: String) {
        val loaded = report.plugins.map { it.name }.sorted().joinToString(", ")
        assertEquals(names, loaded) { "failures: ${report.failures}" }
    }

    @Then("no script failed")
    fun noFailures() {
        assertEquals(emptyList<Any>(), report.failures)
    }

    @Then("{string} failed with a message containing {string}")
    fun failed(name: String, text: String) {
        val failure = report.failures.single()
        assertEquals(name, failure.file)
        assertTrue(failure.messages.any { text in it }) { "messages: ${failure.messages}" }
    }

    @Then("the body field {string} of that response is {string}")
    fun bodyField(field: String, value: String) {
        assertEquals(value, Hal.parseObject(response.body)?.path(field)?.asText())
    }

    @Then("the reload compiled only {string}")
    fun compiledOnly(name: String) {
        assertEquals(listOf(name), report.compiled)
    }
}
