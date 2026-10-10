package com.artie.buster.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertFalse
import org.junit.jupiter.api.Test

// bdd-gates: the core boundary as a failing test, not a paragraph.
// Core (exchange model, HAL helpers, verifier, log) stays framework-free, so the
// same engine can sit behind a Spring WebFilter or any other HTTP adapter.
class ArchitectureBoundaryTest {

    private val corePackage = "com.artie.buster.core.."
    private val forbiddenPrefixes = listOf("org.springframework", "reactor.", "io.netty")

    @Test
    fun `core files import no framework or server types`() {
        Konsist.scopeFromProject()
            .files
            .filter { it.hasPackage(corePackage) }
            .assertFalse { file ->
                file.hasImport { import -> forbiddenPrefixes.any { import.name.startsWith(it) } }
            }
    }
}
