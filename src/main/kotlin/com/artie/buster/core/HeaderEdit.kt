package com.artie.buster.core

/**
 * The changes a plugin can make to a response with a helper. Each notes what it changed in the
 * mutation's detail as it is made, so the log says what the plugin did, not what it meant to do.
 */
interface ResponseEdits {
    val request: BusterRequest
    val response: BusterResponse
    val random: kotlin.random.Random

    fun detail(text: String)

    fun skip(reason: String): Nothing

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
}

/**
 * One header of the response a plugin is changing. Every change is noted in the mutation's
 * detail as it is made, so the log says what the plugin did, not what it meant to do.
 */
class HeaderEdit internal constructor(
    private val name: String,
    private val headers: Headers,
    private val note: (String) -> Unit,
) {
    /** What the header holds now, or null. */
    val value: String? get() = headers[name]

    /** Sets the header, and returns what it was set to. */
    fun replace(new: String): String {
        val old = headers[name]
        headers[name] = new
        note(if (old == null) "$name set to $new" else "$name $old became $new")
        return new
    }

    /** Removes the header, and returns what it held; null, with nothing noted, when it was not there. */
    fun remove(): String? {
        val old = headers[name] ?: return null
        headers.remove(name)
        note("removed $name: $old")
        return old
    }
}
