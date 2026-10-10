package com.artie.buster.spring

import com.artie.buster.core.Engine
import com.artie.buster.core.EventTail
import com.artie.buster.core.MutationLog
import com.artie.buster.script.PluginHost
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Profile
import org.springframework.web.server.WebFilter
import java.io.Closeable
import java.nio.file.Path
import java.time.Duration
import kotlin.random.Random

/** Settings under `buster.*`. */
@ConfigurationProperties("buster")
class BusterProperties {
    /** Seed for the run. Unset means a random one, which is logged so the run can be replayed. */
    var seed: Long? = null

    /** The folder of *.buster.kts plugin scripts. */
    var plugins: String = "plugins"

    /** A file for the JSON Lines log; unset keeps the log in memory only. */
    var log: String? = null

    /** The request header that names the client, for judging expectations per client. */
    var clientHeader: String = BusterWebFilter.DEFAULT_CLIENT_HEADER

    /** Slow motion: how long to hold each mutated response, such as 2s. Zero holds nothing. */
    var pace: Duration = Duration.ZERO

    /**
     * Clients whose label (User-Agent) matches are left out of a campaign: a readiness probe is
     * not a client under test. Empty ignores none.
     */
    var campaignIgnore: String = "^(curl|Wget)/"

    /** A client's event log (JSON Lines) to read as it grows; unset reads none. Events can also be POSTed. */
    var clientEvents: String? = null
}

/**
 * Buster's one switch is the `buster` profile. Without it none of these beans exist,
 * so Buster cannot run in production by accident.
 */
@AutoConfiguration
@Profile("buster")
@ConditionalOnClass(WebFilter::class)
@EnableConfigurationProperties(BusterProperties::class)
class BusterAutoConfiguration {

    @Bean
    fun busterLog(properties: BusterProperties) = MutationLog(properties.log?.let { Path.of(it) })

    @Bean
    fun busterEngine(properties: BusterProperties, log: MutationLog): Engine {
        val report = PluginHost(Path.of(properties.plugins)).reload()
        report.failures.forEach { log.note("plugin script ${it.file} failed: ${it.messages.joinToString("; ")}") }
        val seed = properties.seed ?: Random.nextLong()
        log.note("seed $seed; plugins ${report.plugins.joinToString { it.name }}")
        val ignore = properties.campaignIgnore.takeIf { it.isNotBlank() }?.let { Regex(it) }
        return Engine(report.plugins, seed, log, campaignIgnore = ignore)
    }

    @Bean
    fun busterWebFilter(engine: Engine, properties: BusterProperties) =
        BusterWebFilter(engine, properties.clientHeader, properties.pace)

    /** Reads `buster.client-events` as it grows, when it is set. Closed with the context. */
    @Bean(destroyMethod = "close")
    fun busterEventTail(engine: Engine, properties: BusterProperties): Closeable =
        properties.clientEvents?.let { EventTail(Path.of(it), engine::event).watch(TAIL_EVERY_MS) } ?: Closeable { }

    private companion object {
        const val TAIL_EVERY_MS = 250L
    }
}
