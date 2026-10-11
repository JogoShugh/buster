package com.artie.buster.core

/** The outcomes an expectation's rules return. */
interface Outcomes {
    fun attest(reason: String): Outcome = Outcome.Attested(reason)

    fun pass(reason: String): Outcome.Decided = Outcome.Pass(reason)

    fun fail(reason: String): Outcome.Decided = Outcome.Fail(reason)

    fun pending(): Outcome = Outcome.Pending
}

/**
 * What a verb settles to. Used for `silence` (the client is done and nothing settled it) and for
 * `superseded` (the client was given the resource again, whole and unmutated): both can only be
 * pass or FAIL, so both read as `silence passes "..."` or `superseded fails { "..." }`.
 */
class Settles internal constructor() {
    internal var decide: (() -> Outcome.Decided)? = null

    infix fun passes(reason: String) {
        decide = { Outcome.Pass(reason) }
    }

    infix fun passes(reason: () -> String) {
        decide = { Outcome.Pass(reason()) }
    }

    infix fun fails(reason: String) {
        decide = { Outcome.Fail(reason) }
    }

    infix fun fails(reason: () -> String) {
        decide = { Outcome.Fail(reason()) }
    }
}

/** What a request that matches means, as `seeing(get("/x")) fails "..."`. */
class Seeing internal constructor(
    private val matcher: Matcher,
    private val add: (Matcher, (BusterRequest) -> Outcome) -> Unit,
) {
    infix fun passes(reason: String) = add(matcher) { Outcome.Pass(reason) }

    infix fun passes(reason: (BusterRequest) -> String) = add(matcher) { Outcome.Pass(reason(it)) }

    infix fun fails(reason: String) = add(matcher) { Outcome.Fail(reason) }

    infix fun fails(reason: (BusterRequest) -> String) = add(matcher) { Outcome.Fail(reason(it)) }
}

/**
 * A test over a line of the client's event log, built from `logs(name)`. An event only attests:
 * the wire is what proves a pass, and the log only says what the client thought it did.
 */
class EventMatcher internal constructor(
    val description: String,
    private val span: String?,
    private val test: (ClientEvent) -> Boolean,
) {
    fun matches(event: ClientEvent): Boolean = test(event)

    /** About the request whose response was mutated, not a later one. */
    val thisResponse: EventMatcher
        get() = narrowed("of this response") { span != null && it.spanId == span }

    /** About a resource, by the path of the event's `url.full`. */
    fun about(path: String): EventMatcher = narrowed("about $path") { it.path == path }

    /** Carrying an attribute, such as `heart.rel`. */
    fun with(key: String, value: String): EventMatcher =
        narrowed("with $key=$value") { it.attributes[key] == value }

    private fun narrowed(note: String, also: (ClientEvent) -> Boolean) =
        EventMatcher("$description $note", span) { test(it) && also(it) }

    override fun toString() = description
}

/** The rules an expectation was given, and what its client did so far. */
internal class Rulebook {
    var step: (BusterRequest) -> Outcome = { Outcome.Pending }
    var event: (ClientEvent) -> Outcome = { Outcome.Pending }
    private val requestRules = mutableListOf<Pair<Matcher, (BusterRequest) -> Outcome>>()
    private val eventRules = mutableListOf<Pair<EventMatcher, String>>()
    private val earlier = mutableListOf<BusterRequest>()

    fun addRequestRule(matcher: Matcher, decide: (BusterRequest) -> Outcome) {
        requestRules += matcher to decide
    }

    fun addEventRule(matcher: EventMatcher, reason: String) {
        eventRules += matcher to reason
    }

    fun already(matcher: Matcher): Boolean = earlier.any(matcher::matches)

    /** The first rule that matches decides; with none, onRequest. What was seen is kept for [already]. */
    fun judge(req: BusterRequest): Outcome {
        val rule = requestRules.firstOrNull { it.first.matches(req) }
        val outcome = rule?.second?.invoke(req) ?: step(req)
        earlier += req
        return outcome
    }

    fun attestFrom(ev: ClientEvent): Outcome =
        eventRules.firstOrNull { it.first.matches(ev) }?.let { Outcome.Attested(it.second) } ?: event(ev)
}

/** What a line of the log that matches means, as `seeing(logs("x")) attests "..."`. */
class SeeingEvent internal constructor(
    private val matcher: EventMatcher,
    private val add: (EventMatcher, String) -> Unit,
) {
    infix fun attests(reason: String) = add(matcher, reason)
}
