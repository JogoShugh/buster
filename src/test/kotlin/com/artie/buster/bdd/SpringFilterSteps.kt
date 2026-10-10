package com.artie.buster.bdd

import com.artie.buster.core.Engine
import com.artie.buster.core.Hal
import com.artie.buster.core.Layer
import com.artie.buster.core.MutationLog
import com.artie.buster.core.Plugin
import com.artie.buster.dsl.buster
import com.artie.buster.spring.BusterAutoConfiguration
import com.artie.buster.spring.BusterWebFilter
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.Ordered
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.EntityExchangeResult
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.RouterFunctions
import org.springframework.web.reactive.function.server.ServerResponse

class SpringFilterSteps {
    private var routes: RouterFunction<ServerResponse>? = null
    private val written = mutableMapOf<String, ByteArray>()
    private lateinit var filter: BusterWebFilter
    private lateinit var client: WebTestClient
    private lateinit var result: EntityExchangeResult<ByteArray>
    private lateinit var lastPath: String
    private var hasFilter = false

    @Given("a WebFlux server whose {string} answers a Layer 1 bed")
    fun bedRoute(path: String) {
        val body = """{"name":"North bed","_links":{"self":{"href":"$path"}}}""".toByteArray()
        written[path] = body
        route(path) {
            ServerResponse.ok().contentType(HAL).header("ETag", "\"s1\"").header("Cache-Control", "no-cache")
                .bodyValue(body)
        }
    }

    @Given("whose {string} answers 404 with a problem body")
    fun problemRoute(path: String) {
        val body = """{"status":404,"title":"Not Found"}""".toByteArray()
        written[path] = body
        route(path) { ServerResponse.status(404).contentType(MediaType.APPLICATION_PROBLEM_JSON).bodyValue(body) }
    }

    @Given("Buster runs with a plugin that renames every bed to {string}")
    fun renaming(name: String) = start(
        buster {
            plugin("rename") {
                on(Layer.RESOURCE)
                mutate {
                    val doc = checkNotNull(json())
                    doc.put("name", name)
                    writeJson(doc)
                }
            }
        },
    )

    @Given("Buster runs with a plugin that expects no GET of {string}")
    fun expecting(path: String) = start(
        buster {
            plugin("forbid") {
                on(Layer.RESOURCE)
                mutate {
                    expect("never GET $path", window = 3) {
                        onRequest { req -> if (req.path == path) fail("got $path") else pending() }
                        onExpire { pass("never got $path") }
                    }
                }
            }
        },
    )

    @When("a client GETs {string}")
    fun get(path: String) = fetch(null, path)

    @When("client {string} GETs {string}")
    fun getAs(clientId: String, path: String) = fetch(clientId, path)

    private fun fetch(clientId: String?, path: String) {
        lastPath = path
        val spec = client.get().uri(path)
        clientId?.let { spec.header(BusterWebFilter.DEFAULT_CLIENT_HEADER, it) }
        result = spec.exchange().expectBody(ByteArray::class.java).returnResult()
    }

    @Then("the client receives a bed named {string}")
    fun bedNamed(name: String) {
        assertEquals(name, Hal.parseObject(body())?.path("name")?.asText())
    }

    @Then("the response has Cache-Control {string}")
    fun cacheControl(value: String) {
        assertEquals(value, result.responseHeaders.cacheControl)
    }

    @Then("the response's Content-Length matches its body")
    fun contentLength() {
        assertEquals(body().size.toLong(), result.responseHeaders.contentLength)
    }

    @Then("the client receives exactly what the server wrote")
    fun untouched() {
        assertArrayEquals(written.getValue(lastPath), body())
    }

    @Then("the report shows a(n) {word} verdict for client {string}")
    fun reportVerdict(verdict: String, clientId: String) {
        val verdicts = checkNotNull(Hal.parseObject(body())).path("verdicts").toList()
        assertTrue(verdicts.any { it["client"].asText() == clientId && it["result"].asText() == verdict }) {
            "verdicts: $verdicts"
        }
    }

    @Then("the Buster filter has the highest precedence")
    fun highestPrecedence() {
        assertEquals(Ordered.HIGHEST_PRECEDENCE, filter.order)
    }

    @Given("a Spring context with profiles {string} and the plugin pack folder")
    fun context(profiles: String) {
        var runner = ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BusterAutoConfiguration::class.java))
            .withPropertyValues("buster.plugins=plugins")
        if (profiles.isNotBlank()) runner = runner.withPropertyValues("spring.profiles.active=$profiles")
        runner.run { context ->
            assertEquals(null, context.startupFailure)
            hasFilter = context.getBeansOfType(BusterWebFilter::class.java).isNotEmpty()
        }
    }

    @Then("the context has a Buster filter")
    fun hasBuster() = assertTrue(hasFilter)

    @Then("the context does not have a Buster filter")
    fun hasNoBuster() = assertTrue(!hasFilter)

    private fun route(path: String, handler: () -> reactor.core.publisher.Mono<ServerResponse>) {
        val added = RouterFunctions.route().GET(path) { handler() }.build()
        routes = routes?.and(added) ?: added
    }

    private fun start(plugins: List<Plugin>) {
        filter = BusterWebFilter(Engine(plugins, 1, MutationLog()))
        client = WebTestClient.bindToRouterFunction(checkNotNull(routes))
            .webFilter<WebTestClient.RouterFunctionSpec>(filter)
            .build()
    }

    private fun body() = result.responseBody ?: ByteArray(0)

    private companion object {
        val HAL: MediaType = MediaType.parseMediaType("application/hal+json")
    }
}
