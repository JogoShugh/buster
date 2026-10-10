package com.artie.buster.script

import com.artie.buster.dsl.BusterDsl
import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.defaultImports
import kotlin.script.experimental.api.implicitReceivers
import kotlin.script.experimental.jvm.dependenciesFromCurrentContext
import kotlin.script.experimental.jvm.jvm

/** A *.buster.kts plugin script. The Buster DSL is its implicit receiver. */
@KotlinScript(
    fileExtension = "buster.kts",
    compilationConfiguration = BusterScriptCompilation::class,
)
abstract class BusterScript

object BusterScriptCompilation : ScriptCompilationConfiguration({
    defaultImports("com.artie.buster.core.*", "com.artie.buster.dsl.*")
    implicitReceivers(BusterDsl::class)
    jvm {
        dependenciesFromCurrentContext(wholeClasspath = true)
    }
})
