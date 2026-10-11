package com.artie.buster.dsl

import com.artie.buster.core.BusterDslMarker
import com.artie.buster.core.Layer
import com.artie.buster.core.MutationScope
import com.artie.buster.core.Plugin
import com.artie.buster.core.Rung
import com.artie.buster.core.Silence

/** The top level of a plugin script. A script is evaluated with this as its implicit receiver. */
@BusterDslMarker
class BusterDsl {
    private val declared = mutableListOf<Plugin>()

    val plugins: List<Plugin> get() = declared.toList()

    fun plugin(name: String, block: PluginBuilder.() -> Unit) {
        declared += PluginBuilder(name).apply(block).build()
    }
}

/** Declares plugins in Kotlin code, the same way a script does. */
fun buster(block: BusterDsl.() -> Unit): List<Plugin> = BusterDsl().apply(block).plugins

@BusterDslMarker
class PluginBuilder(private val name: String) {
    private val spec = mutableListOf<String>()
    private val layers = mutableSetOf<Layer>()
    private var rungs: Set<Rung> = Rung.entries.toSet()
    private var weight = 1
    private var silence = Silence.PASS
    private var mutate: (MutationScope.() -> Unit)? = null

    /** The spec sections the plugin attacks. */
    fun spec(vararg sections: String) {
        spec += sections
    }

    /** The layers the plugin applies to. OTHER is every response the H.E.A.R.T. classifier cannot place. */
    fun on(vararg layers: Layer) {
        this.layers += layers
    }

    /** The rungs the plugin applies to. Default: every rung. */
    fun rungs(vararg rungs: Rung) {
        this.rungs = rungs.toSet()
    }

    /** Relative chance of being picked. */
    fun weight(n: Int) {
        weight = n
    }

    /**
     * What a client that does nothing after a mutation means. PASS (the default) for a rule it can
     * only break by doing something; FAIL for one it must act on. A FAIL plugin is made only with a
     * dry run, and only after onlyWhere says what the client must be shown to do.
     */
    fun silenceMeans(silence: Silence) {
        this.silence = silence
    }

    fun mutate(block: MutationScope.() -> Unit) {
        mutate = block
    }

    internal fun build(): Plugin {
        require(layers.isNotEmpty()) { "plugin $name: on(...) names no layer" }
        require(rungs.isNotEmpty()) { "plugin $name: rungs(...) names no rung" }
        require(weight >= 1) { "plugin $name: weight must be at least 1" }
        val block = requireNotNull(mutate) { "plugin $name: no mutate { } block" }
        return Plugin(name, spec.toList(), layers.toSet(), rungs, weight, block).also { it.silence = silence }
    }
}
