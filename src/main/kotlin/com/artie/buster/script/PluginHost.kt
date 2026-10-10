package com.artie.buster.script

import com.artie.buster.core.Plugin
import com.artie.buster.dsl.BusterDsl
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.script.experimental.api.CompiledScript
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.api.implicitReceivers
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvm.BasicJvmScriptEvaluator
import kotlin.script.experimental.jvmhost.JvmScriptCompiler

/** A script that did not compile or did not run, with what the compiler or the script said. */
data class ScriptFailure(val file: String, val messages: List<String>)

/** What one load of the plugins folder found. */
class LoadReport(
    val plugins: List<Plugin>,
    val failures: List<ScriptFailure>,
    /** The scripts compiled by this load; unchanged scripts come from the cache. */
    val compiled: List<String>,
)

/**
 * Loads every *.buster.kts script in [folder]. Compiled scripts are cached by name and
 * source hash, so a reload compiles only what changed. A failing script is reported
 * and skipped; the rest still load.
 */
class PluginHost(private val folder: Path) {
    private val compiler = JvmScriptCompiler()
    private val evaluator = BasicJvmScriptEvaluator()
    private val cache = HashMap<String, CompiledScript>()

    @Volatile
    var plugins: List<Plugin> = emptyList()
        private set

    @Synchronized
    fun reload(): LoadReport {
        val loaded = mutableListOf<Plugin>()
        val failures = mutableListOf<ScriptFailure>()
        val compiled = mutableListOf<String>()
        val live = mutableSetOf<String>()
        for (file in scripts()) {
            val name = file.fileName.toString()
            val text = Files.readString(file)
            val key = "$name:${sha256(text)}"
            live += key
            val cached = cache[key]
            val ready = if (cached != null) Outcome.Ready(cached) else compile(name, text).also { compiled += name }
            if (ready is Outcome.Ready) cache[key] = ready.compiled
            when (val result = ready.then(::evaluate)) {
                is Outcome.Loaded -> loaded += result.plugins
                is Outcome.Failed -> failures += ScriptFailure(name, result.messages)
                is Outcome.Ready -> error("evaluate always ends in Loaded or Failed")
            }
        }
        cache.keys.retainAll(live)
        plugins = loaded.toList()
        return LoadReport(plugins, failures, compiled)
    }

    private fun scripts(): List<Path> = Files.list(folder).use { paths ->
        paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(EXTENSION) }
            .sorted()
            .toList()
    }

    private sealed interface Outcome {
        class Ready(val compiled: CompiledScript) : Outcome
        class Loaded(val plugins: List<Plugin>) : Outcome
        class Failed(val messages: List<String>) : Outcome

        fun then(next: (CompiledScript) -> Outcome): Outcome = if (this is Ready) next(compiled) else this
    }

    private fun compile(name: String, text: String): Outcome =
        when (val result = runBlocking { compiler(text.toScriptSource(name), BusterScriptCompilation) }) {
            is ResultWithDiagnostics.Success -> Outcome.Ready(result.value)
            is ResultWithDiagnostics.Failure -> Outcome.Failed(errors(result.reports))
        }

    private fun evaluate(script: CompiledScript): Outcome {
        val dsl = BusterDsl()
        val config = ScriptEvaluationConfiguration { implicitReceivers(dsl) }
        val result = runBlocking { evaluator(script, config) }
        val returned = (result as? ResultWithDiagnostics.Success)?.value?.returnValue
        return when {
            result is ResultWithDiagnostics.Failure -> Outcome.Failed(errors(result.reports))
            returned is ResultValue.Error -> Outcome.Failed(listOf(returned.error.toString()))
            else -> Outcome.Loaded(dsl.plugins)
        }
    }

    private fun errors(reports: List<ScriptDiagnostic>): List<String> =
        reports.filter { it.severity >= ScriptDiagnostic.Severity.ERROR }
            .map { report -> report.location?.let { "${it.start.line}:${it.start.col} " }.orEmpty() + report.message }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val EXTENSION = ".buster.kts"
    }
}
