package com.artie.buster.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlin.random.Random

/** Keeps DSL scopes from leaking into each other in plugin scripts. */
@DslMarker
annotation class BusterDslMarker

/**
 * What a client that does nothing after a mutation means. PASS: it was never asked to do
 * anything, so silence is fine. FAIL: it must act, so silence is a failure, and the mutation is
 * made only where the dry run shows it will.
 */
enum class Silence { PASS, FAIL }

/** One mutation Buster can apply, as built by the DSL. */
class Plugin(
    val name: String,
    val spec: List<String>,
    val layers: Set<Layer>,
    val rungs: Set<Rung>,
    val weight: Int,
    val mutate: MutationScope.() -> Unit,
) {
    /** Set by the DSL's silenceMeans; PASS for a plugin that says nothing. */
    var silence: Silence = Silence.PASS
        internal set

    fun appliesTo(classification: Classification) =
        classification.layer in layers && classification.rung in rungs

    /**
     * Runs [mutate]. A plugin whose silence is a FAIL is made only with a dry run, and only after
     * saying with onlyWhere what the client must be shown to do.
     */
    fun run(scope: MutationScope) {
        if (silence == Silence.FAIL && scope.future == null) throw SkipMutation(NO_DRY_RUN)
        mutate(scope)
        if (silence == Silence.FAIL && !scope.armed) {
            throw SkipMutation("silenceMeans(FAIL) but mutate never called onlyWhere")
        }
    }
}

internal const val NO_DRY_RUN = "no dry run, so silence would be a guess"

/** Thrown by [MutationScope.skip]; the engine discards whatever the plugin changed. */
class SkipMutation(val reason: String) : RuntimeException(reason, null, false, false)

/** What a plugin's mutate block sees and can do. */
@BusterDslMarker
class MutationScope(
    val request: BusterRequest,
    val response: BusterResponse,
    val classification: Classification,
    /** The seeded random source for this exchange. Use only this, so replay works. */
    val random: Random,
    /**
     * What this client sent after this request in the dry run, or null when there is no dry run.
     * A plugin whose silence would be a guess without it skips when it is null; one that attacks
     * a rule the client never exercises skips when the future shows it never will.
     */
    val future: List<BusterRequest>? = null,
) : Matchers {
    /**
     * The path of the resource this response is the state of: Content-Location, else the HAL
     * self link, else the request path. For an act's response it is the resource, not the action.
     */
    val resourcePath: String = Resource.pathOf(request, response)

    /** Reads as `onlyWhere(client does get("/forms/x"))`. */
    val client = Client

    internal val details = mutableListOf<String>()
    internal val expectations = mutableListOf<Expectation>()
    internal var keepsCacheControl = false
    internal var armed = false

    /**
     * Makes the mutation only where the dry run shows the client doing [matcher], so there is
     * something to judge. Without a dry run there is nothing to check against: a plugin whose
     * silence is a PASS goes on, and one whose silence is a FAIL was already skipped.
     */
    fun onlyWhere(matcher: Matcher) {
        armed = true
        val seen = future ?: return
        if (seen.none(matcher::matches)) skip("the client never does ${matcher.description} in its dry run")
    }

    fun json(): ObjectNode? = Hal.parseObject(response.body)

    /** The body as a JSON object; the mutation is skipped when it is not one. */
    val jsonBody: ObjectNode get() = json() ?: skip("body is not a JSON object")

    /** A header of the response, to replace or remove. The change is noted in the detail. */
    fun header(name: String): HeaderEdit = HeaderEdit(name, response.headers, ::detail)

    /**
     * Changes one letter inside a JSON string, chosen by the seeded random source. The body is
     * still valid JSON, so only an integrity check can catch it. Skips when there is no letter.
     */
    fun flipLetterInString() {
        val letters = Hal.lettersInStrings(response.body).ifEmpty { skip("no letters inside JSON strings") }
        val at = letters[random.nextInt(letters.size)]
        val before = response.body[at].toInt().toChar()
        val after = if (before == 'x') 'y' else 'x'
        response.body = response.body.copyOf().also { it[at] = after.code.toByte() }
        detail("byte $at of ${request.path}: '$before' became '$after'")
    }

    /** Turns the response into an RFC 9457 problem: [status], [title], and a Retry-After if asked. */
    fun answerWithProblem(status: Int, title: String, retryAfterSeconds: Int? = null) {
        response.status = status
        response.headers["Content-Type"] = "application/problem+json"
        retryAfterSeconds?.let { response.headers["Retry-After"] = it.toString() }
        response.body = """{"status":$status,"title":"$title"}""".toByteArray()
        val retry = retryAfterSeconds?.let { ", Retry-After $it" }.orEmpty()
        detail("${request.method} ${request.path} answered $status$retry")
    }

    fun writeJson(node: JsonNode) {
        response.body = Hal.write(node)
    }

    fun detail(text: String) {
        details += text
    }

    fun skip(reason: String): Nothing = throw SkipMutation(reason)

    /** Honoured only when the body bytes are unchanged; otherwise the engine still sends no-store. */
    fun keepCacheControl() {
        keepsCacheControl = true
    }

    /** What the client must do next, judged over its next [window] requests. */
    fun expect(description: String, window: Int, block: ExpectationBuilder.() -> Unit) {
        expectations += ExpectationBuilder(description, window, request, resourcePath).apply(block).build()
    }
}
