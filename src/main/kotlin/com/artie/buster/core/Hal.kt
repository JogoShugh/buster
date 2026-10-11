package com.artie.buster.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode

/** HAL helpers shared by the engine and by plugins. */
object Hal {
    /** The affordance-set link relation (SPEC §6.1). */
    const val FORMS_REL = "https://github.com/jogoshugh/heart-rise/rels/forms"

    val mapper = ObjectMapper()

    fun parseObject(bytes: ByteArray): ObjectNode? =
        runCatching { mapper.readTree(bytes) as? ObjectNode }.getOrNull()

    fun links(doc: ObjectNode): ObjectNode? = doc.get("_links") as? ObjectNode

    /** Expands a CURIE key like "hr:forms" through _links.curies; a full URI passes through. */
    fun expandRel(doc: ObjectNode, key: String): String? {
        if (key.contains("://")) return key
        val colon = key.indexOf(':')
        val curies = links(doc)?.get("curies")?.let { if (it.isArray) it.toList() else listOf(it) }.orEmpty()
        val curie = curies.firstOrNull { colon > 0 && it.path("name").asText() == key.substring(0, colon) }
        return curie?.path("href")?.asText()?.replace("{rel}", key.substring(colon + 1))
    }

    /** The _links key whose expanded relation is [rel], if any. */
    fun linkKey(doc: ObjectNode, rel: String): String? =
        links(doc)?.fieldNames()?.asSequence()?.firstOrNull { it != "curies" && expandRel(doc, it) == rel }

    fun link(doc: ObjectNode, rel: String): ObjectNode? =
        linkKey(doc, rel)?.let { links(doc)?.get(it) as? ObjectNode }

    fun write(node: JsonNode): ByteArray = mapper.writeValueAsBytes(node)

    /**
     * Offsets of the ASCII letters inside JSON string literals. Changing one of them keeps the
     * bytes valid JSON, so only an integrity check can tell.
     */
    fun lettersInStrings(bytes: ByteArray): List<Int> {
        val found = mutableListOf<Int>()
        var inString = false
        var escaped = false
        bytes.forEachIndexed { i, b ->
            val c = b.toInt().toChar()
            when {
                escaped -> escaped = false
                inString && c == '\\' -> escaped = true
                c == '"' -> inString = !inString
                inString && c.isLetter() && b >= 0 -> found += i
            }
        }
        return found
    }
}

/** The link to the affordance set, as the document wrote it. */
val ObjectNode.formsLink: ObjectNode? get() = Hal.link(this, Hal.FORMS_REL)

/** Where the forms link points, or null when it has no href. */
val ObjectNode.formsHref: String? get() = formsLink?.path("href")?.takeIf { it.isTextual }?.asText()

/** Affordances in either dialect: HAL Schema Forms _forms or HAL-FORMS _templates. */
object Forms {
    fun embedded(doc: ObjectNode): ObjectNode? =
        (doc.get("_forms") ?: doc.get("_templates")) as? ObjectNode

    /** The target template of one form. */
    fun target(form: JsonNode): String? =
        form.path("_links").path("target").path("href").takeIf { it.isTextual }?.asText()
            ?: form.path("target").takeIf { it.isTextual }?.asText()

    fun method(form: JsonNode): String = form.path("method").asText("POST").uppercase()

    /** Matches any expansion of an RFC 6570 Level 1 template; query parts are ignored. */
    fun matcher(template: String): Regex {
        val noQuery = template.replace(Regex("\\{[?&][^}]*}"), "").substringBefore('?')
        return Regex(noQuery.split(Regex("\\{[^}]*}")).joinToString("[^/]+") { Regex.escape(it) })
    }

    /** True when [req] acts on [form]: same method, and a path its target template expands to. */
    fun actsOn(req: BusterRequest, form: JsonNode): Boolean =
        req.isAct && req.method.equals(method(form), ignoreCase = true) &&
            target(form)?.let { matcher(it).matches(req.path) } == true
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
            // Layer 1 is HAL. A JSON Home service index or a plain JSON document with an ETag is not.
            res.headers.has("ETag") && isHal(contentType) ->
                Layer.RESOURCE to rungApplied(res.headers["Preference-Applied"])
            else -> null
        }
        val (layer, rung) = placed ?: (Layer.OTHER to Rung.L0)
        return Classification(layer, rung, dialect)
    }

    private fun isHal(contentType: String) = "hal+json" in contentType || "hal-forms" in contentType

    private const val SUCCESS_FIRST = 200
    private const val SUCCESS_LAST = 299
    private val SUCCESS = SUCCESS_FIRST..SUCCESS_LAST

    /** Layers 2 and 3 are the immutable ones. A set whose schemas are pointers is L2. */
    private fun immutable(doc: ObjectNode): Pair<Layer, Rung> {
        val forms = Forms.embedded(doc) ?: return Layer.SCHEMA to Rung.L2
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
