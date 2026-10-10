package com.artie.buster.spring

import org.springframework.boot.SpringApplication
import org.springframework.boot.env.EnvironmentPostProcessor
import org.springframework.core.Ordered
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.Profiles

/**
 * Under the `buster` profile, Spring prints Buster's terrier instead of its own banner,
 * so the console says at a glance that this server's responses are being tampered with.
 * Runs after the config files are read, so the profile and any banner the server chose
 * are known; a server's own `spring.banner.location` wins.
 */
class BusterBanner : EnvironmentPostProcessor, Ordered {

    override fun getOrder() = Ordered.LOWEST_PRECEDENCE

    override fun postProcessEnvironment(environment: ConfigurableEnvironment, application: SpringApplication) {
        if (!environment.acceptsProfiles(Profiles.of(PROFILE))) return
        if (environment.containsProperty(LOCATION)) return
        environment.propertySources.addLast(MapPropertySource("buster-banner", mapOf(LOCATION to BANNER)))
    }

    private companion object {
        const val PROFILE = "buster"
        const val LOCATION = "spring.banner.location"
        const val BANNER = "classpath:buster-banner.txt"
    }
}
