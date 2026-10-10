package com.artie.buster.bdd

import com.artie.buster.core.Engine
import com.artie.buster.core.Hal
import com.artie.buster.core.Layer
import com.artie.buster.core.MutationLog
import com.artie.buster.core.Plugin
import com.artie.buster.dsl.buster
import com.artie.buster.spring.BusterAutoConfiguration
import com.artie.buster.spring.BusterBanner
import com.artie.buster.spring.BusterWebFilter
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.convert.DurationStyle
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.Ordered
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.EntityExchangeResult
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.RouterFunctions
import org.springframework.web.reactive.function.server.ServerResponse
import java.time.Duration

class SpringFilterSteps {
    private var routes: RouterFunction<ServerResponse>? = null
    private val written = mutableMapOf<String, ByteArray>()
    private lateinit var filter: BusterWebFilter
    private lateinit var client: WebTestClient
    private lateinit var result: EntityExchangeResult<ByteArray>
    private lateinit var lastPath: String
    private var hasFilter = false
    private var pace: Duration = Duration.ZERO
    private var tookMs = 0L
    private var contextPace: Duration? = null

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

    @Given("Buster runs with a plugin that expects no GET of {string}, attested by {string}")
    fun expectingAttested(path: String, event: String) = start(
        buster {
            plugin("forbid") {
                on(Layer.RESOURCE)
                mutate {
                    val span = request.spanId
                    expect("never GET $path", window = 3) {
                        onRequest { req -> if (req.path == path) fail("got $path") else pending() }
                        onEvent { ev ->
                            if (ev.name == event && ev.spanId == span) attest("said $event") else pending()
                        }
                        onExpire { pass("never got $path") }
                    }
                }
            }
        },
    )

    /** The trace and span of the last traced request, for an event about it. */
    private var lastTrace = ""
    private var lastSpan = ""

    @When("the client with User-Agent {string} and trace {string} GETs {string}")
    fun getTraced(agent: String, trace: String, path: String) {
        lastTrace = trace
        lastSpan = SPAN
        fetch(null, path, mapOf("User-Agent" to agent, "traceparent" to "00-$trace-$SPAN-01"))
    }

    @When("the client POSTs a {string} event for that request to {string}")
    fun postEvent(event: String, path: String) {
        val line = """{"trace_id":"$lastTrace","span_id":"$lastSpan","event_name":"$event","attributes":{}}"""
        result = client.post().uri(path).contentType(MediaType.APPLICATION_JSON).bodyValue(line)
            .exchange().expectBody(ByteArray::class.java).returnResult()
    }

    @Then("the report lists mutation {long} for trace {string} labelled {string}")
    fun reportTrace(seq: Long, trace: String, label: String) {
        val mutations = checkNotNull(Hal.parseObject(body())).path("mutations").toList()
        assertTrue(
            mutations.any {
                it["seq"].asLong() == seq && it["trace"].asText() == trace && it["client"].asText() == label
            },
        ) { "mutations: $mutations" }
    }

    @Then("the report shows a {string} verdict for client {string}")
    fun reportQuotedVerdict(verdict: String, clientId: String) = reportVerdict(verdict, clientId)

    @When("a client GETs {string}")
    fun get(path: String) = fetch(null, path)

    @When("client {string} GETs {string}")
    fun getAs(clientId: String, path: String) = fetch(clientId, path)

    @When("the client with User-Agent {string} GETs {string}")
    fun getWithAgent(agent: String, path: String) = fetch(null, path, mapOf("User-Agent" to agent))

    @When("htmx GETs {string}")
    fun getAsHtmx(path: String) = fetch(null, path, mapOf("HX-Request" to "true"))

    private fun fetch(clientId: String?, path: String, headers: Map<String, String> = emptyMap()) {
        lastPath = path
        val spec = client.get().uri(path)
        clientId?.let { spec.header(BusterWebFilter.DEFAULT_CLIENT_HEADER, it) }
        headers.forEach { (name, value) -> spec.header(name, value) }
        val started = System.nanoTime()
        result = spec.exchange().expectBody(ByteArray::class.java).returnResult()
        tookMs = (System.nanoTime() - started) / NANOS_PER_MILLI
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

    @Given("Buster paces mutations at {string}")
    fun pacing(value: String) {
        pace = DurationStyle.detectAndParse(value)
    }

    @Then("the response took at least {long} ms")
    fun tookAtLeast(ms: Long) = assertTrue(tookMs >= ms) { "took $tookMs ms" }

    @Then("the response took less than {long} ms")
    fun tookLessThan(ms: Long) = assertTrue(tookMs < ms) { "took $tookMs ms" }

    @Then("the report lists mutation {long} by {string} on {string} for client {string}")
    fun reportMutation(seq: Long, plugin: String, path: String, clientId: String) {
        val mutations = checkNotNull(Hal.parseObject(body())).path("mutations").toList()
        assertTrue(
            mutations.any {
                it["seq"].asLong() == seq && it["plugin"].asText() == plugin &&
                    it["path"].asText() == path && it["client"].asText() == clientId
            },
        ) { "mutations: $mutations" }
    }

    @Then("the client receives an HTML representation that polls {string} with htmx")
    fun liveView(reportPath: String) {
        assertTrue(result.responseHeaders.contentType?.isCompatibleWith(MediaType.TEXT_HTML) == true)
        val html = String(body())
        assertTrue(html.contains("htmx.org@")) { "no htmx script" }
        assertTrue(Regex("""hx-get="${Regex.escape(reportPath)}"[^>]*hx-trigger="[^"]*every""").containsMatchIn(html)) {
            "nothing polls $reportPath"
        }
    }

    @Then("the client receives an HTML fragment with a {string} verdict for plugin {string}")
    fun fragmentVerdict(verdict: String, plugin: String) {
        assertTrue(result.responseHeaders.contentType?.isCompatibleWith(MediaType.TEXT_HTML) == true)
        val row = Regex("""<tr data-plugin="${Regex.escape(plugin)}">.*?</tr>""").find(String(body()))?.value
        assertTrue(row?.contains("""class="verdict $verdict">$verdict<""") == true) { "row: $row" }
    }

    @Then("the HTML fragment shows {string}")
    fun fragmentShows(shown: String) {
        val html = String(body(), Charsets.UTF_8)
        assertTrue(html.contains(shown)) { html }
    }

    @Then("the response is HTML in UTF-8")
    fun htmlUtf8() {
        val type = checkNotNull(result.responseHeaders.contentType)
        assertTrue(type.isCompatibleWith(MediaType.TEXT_HTML) && type.charset == Charsets.UTF_8) { "type: $type" }
    }

    @Then("the response status is {int}")
    fun status(code: Int) = assertEquals(code, result.status.value())

    @Then("the report shows no FAIL verdict")
    fun noFail() {
        val verdicts = checkNotNull(Hal.parseObject(body())).path("verdicts").toList()
        assertTrue(verdicts.none { it["result"].asText() == "FAIL" }) { "verdicts: $verdicts" }
    }

    @Then("the HTML fragment shows {string} and never {string}")
    fun escaped(shown: String, never: String) {
        val html = String(body())
        assertTrue(html.contains(shown) && !html.contains(never)) { html }
    }

    @Given("Buster runs with a plugin that notes {string}")
    fun noting(note: String) = start(
        buster {
            plugin("noter") {
                on(Layer.RESOURCE)
                mutate { detail(note) }
            }
        },
    )

    @Given("a Spring context with profiles {string}, the plugin pack folder and {string}")
    fun contextWith(profiles: String, property: String) {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BusterAutoConfiguration::class.java))
            .withPropertyValues("buster.plugins=plugins", "spring.profiles.active=$profiles", property)
            .run { context ->
                assertEquals(null, context.startupFailure)
                contextPace = context.getBean(BusterWebFilter::class.java).pace
            }
    }

    @Then("the Buster filter holds each mutation for {long} ms")
    fun holds(ms: Long) = assertEquals(ms, contextPace?.toMillis())

    private var environment = StandardEnvironment()

    @Given("a Spring environment with profiles {string}")
    fun springEnvironment(profiles: String) {
        environment = StandardEnvironment()
        environment.setActiveProfiles(*profiles.split(",").map { it.trim() }.toTypedArray())
    }

    @Given("a Spring environment with profiles {string} and {string}")
    fun springEnvironmentWith(profiles: String, property: String) {
        springEnvironment(profiles)
        val (key, value) = property.split("=", limit = 2)
        environment.propertySources.addFirst(MapPropertySource("test", mapOf(key to value)))
    }

    @When("Buster prepares the environment")
    fun prepares() = BusterBanner().postProcessEnvironment(environment, SpringApplication())

    @Then("the banner is Buster's terrier")
    fun bustersBanner() {
        val location = checkNotNull(environment.getProperty(BANNER_LOCATION))
        val banner = DefaultResourceLoader().getResource(location)
        assertTrue(banner.exists()) { "no banner at $location" }
        assertTrue(banner.getContentAsString(Charsets.UTF_8).contains("Terrierizing Every Representation"))
    }

    @Then("the banner is not Buster's")
    fun notBustersBanner() = assertEquals(null, environment.getProperty(BANNER_LOCATION))

    @Then("the banner location is {string}")
    fun bannerLocation(location: String) = assertEquals(location, environment.getProperty(BANNER_LOCATION))

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
        filter = BusterWebFilter(Engine(plugins, 1, MutationLog()), pace = pace)
        client = WebTestClient.bindToRouterFunction(checkNotNull(routes))
            .webFilter<WebTestClient.RouterFunctionSpec>(filter)
            .build()
    }

    private fun body() = result.responseBody ?: ByteArray(0)

    private companion object {
        val HAL: MediaType = MediaType.parseMediaType("application/hal+json")
        const val SPAN = "00f067aa0ba902b7"
        const val NANOS_PER_MILLI = 1_000_000L
        const val BANNER_LOCATION = "spring.banner.location"
    }
}
