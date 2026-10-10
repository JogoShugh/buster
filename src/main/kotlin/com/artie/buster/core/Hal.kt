package com.artie.buster.core

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode

/** HAL helpers shared by the engine and by plugins. */
object Hal {
    val mapper = ObjectMapper()

    fun parseObject(bytes: ByteArray): ObjectNode? =
        runCatching { mapper.readTree(bytes) as? ObjectNode }.getOrNull()

    /** _forms (HAL Schema Forms) or _templates (HAL-FORMS). */
    fun embeddedForms(doc: ObjectNode): ObjectNode? =
        (doc.get("_forms") ?: doc.get("_templates")) as? ObjectNode
}

/** Decides layer, rung and dialect from the response alone, as a client would. */
object Classifier {
    fun classify(res: BusterResponse): Classification {
        val contentType = res.headers["Content-Type"].orEmpty().lowercase()
        val dialect = if (contentType.contains("hal-forms")) Dialect.HAL_FORMS else Dialect.HAL_SCHEMA_FORMS
        val doc = res.takeIf { it.status in SUCCESS && contentType.contains("json") }
            ?.let { Hal.parseObject(it.body) }
        val placed = when {
            doc == null -> null
            res.headers["Cache-Control"].orEmpty().lowercase().contains("immutable") -> immutable(doc)
            res.headers.has("ETag") -> Layer.RESOURCE to rungApplied(res.headers["Preference-Applied"])
            else -> null
        }
        val (layer, rung) = placed ?: (Layer.OTHER to Rung.L0)
        return Classification(layer, rung, dialect)
    }

    private const val SUCCESS_FIRST = 200
    private const val SUCCESS_LAST = 299
    private val SUCCESS = SUCCESS_FIRST..SUCCESS_LAST

    /** Layers 2 and 3 are the immutable ones. A set whose schemas are pointers is L2. */
    private fun immutable(doc: ObjectNode): Pair<Layer, Rung> {
        val forms = Hal.embeddedForms(doc) ?: return Layer.SCHEMA to Rung.L2
        val pointers = forms.elements().asSequence().any { it.path("schema").has("\$ref") }
        return Layer.AFFORDANCE_SET to if (pointers) Rung.L2 else Rung.L1
    }

    /** The rung comes from Preference-Applied, never from what the client asked for (SPEC §6.4). */
    private fun rungApplied(applied: String?): Rung {
        val tokens = applied.orEmpty().lowercase().split(',').map { it.trim() }.toSet()
        return when {
            "forms=link" in tokens && "schema=link" in tokens -> Rung.L2
            "forms=link" in tokens -> Rung.L1
            else -> Rung.L0
        }
    }
}
