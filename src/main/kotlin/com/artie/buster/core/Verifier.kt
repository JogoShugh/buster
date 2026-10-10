package com.artie.buster.core

/** Result of checking one expectation against one request, or at the end of its window. */
sealed class Outcome(
    /** How the log and the report name this outcome. A failure is in capitals so it stands out. */
    val label: String,
) {
    open val reason: String get() = ""

    /** Not settled yet: only a request or an event may answer with this, never an expiry. */
    data object Pending : Outcome("pending")

    /**
     * A verdict the wire gives: pass or FAIL. It is the only kind an expiry rule may return, so
     * a check that could not be judged cannot be written: silence has to mean one or the other.
     */
    sealed class Decided(label: String) : Outcome(label)

    data class Pass(override val reason: String) : Decided("pass")

    data class Fail(override val reason: String) : Decided("FAIL")

    /**
     * The wire showed nothing wrong, and the client's own event log said it did the right
     * thing. Weaker than [Pass], so the report keeps them apart.
     */
    data class Attested(override val reason: String) : Outcome("pass (attested)")
}

/**
 * What the client must do after a mutation, judged from the requests it sends next.
 * [step] sees each later request from the same client; [expire] runs when [window] requests pass.
 * [event] sees each line of the same client's event log, and can only attest a pass.
 */
class Expectation(
    val description: String,
    val window: Int,
    val step: (BusterRequest) -> Outcome,
    val expire: () -> Outcome.Decided,
    val event: (ClientEvent) -> Outcome = { Outcome.Pending },
)

/** Builds an [Expectation] inside a plugin's mutate block. */
@BusterDslMarker
class ExpectationBuilder(
    private val description: String,
    private val window: Int,
    /**
     * The request whose response was mutated. A plugin can judge the read that got the mutation
     * with this; the surrounding mutate block's own `request` is out of reach inside expect.
     */
    val trigger: BusterRequest,
) {
    private var step: (BusterRequest) -> Outcome = { Outcome.Pending }
    private var expire: (() -> Outcome.Decided)? = null
    private var event: (ClientEvent) -> Outcome = { Outcome.Pending }

    fun onRequest(block: (BusterRequest) -> Outcome) {
        step = block
    }

    /** Judges a line of the client's event log. Only [attest] or [pending] count; nothing else is taken from it. */
    fun onEvent(block: (ClientEvent) -> Outcome) {
        event = block
    }

    fun attest(reason: String): Outcome = Outcome.Attested(reason)

    /**
     * What silence means: the client is done, or the round has ended, and nothing settled it.
     * Required, and it can only return [pass] or [fail].
     */
    fun onExpire(block: () -> Outcome.Decided) {
        expire = block
    }

    fun pass(reason: String): Outcome.Decided = Outcome.Pass(reason)

    fun fail(reason: String): Outcome.Decided = Outcome.Fail(reason)

    fun pending(): Outcome = Outcome.Pending

    internal fun build(): Expectation {
        require(window >= 1) { "expectation '$description': window must be at least 1" }
        val silence = requireNotNull(expire) {
            "expectation '$description': no onExpire { } block, so silence has no meaning"
        }
        return Expectation(description, window, step, silence, event)
    }
}

data class Verdict(val record: MutationRecord, val expectation: String, val outcome: Outcome)

/** Tracks open expectations per client and turns them into verdicts. */
class Verifier(private val log: MutationLog) {
    private class Open(val record: MutationRecord, val exp: Expectation) {
        var seen = 0
    }

    private val open = HashMap<String, MutableList<Open>>()
    private val verdicts = mutableListOf<Verdict>()

    /**
     * In a campaign a window is not a count of requests: an expectation stays open until the
     * client is done, the round ends, or the wire or the log settles it.
     */
    @Volatile
    var windowless = false

    @Synchronized
    fun register(record: MutationRecord, exp: Expectation) {
        open.getOrPut(record.clientId) { mutableListOf() }.add(Open(record, exp))
    }

    @Synchronized
    fun observe(req: BusterRequest) {
        val list = open[req.clientId] ?: return
        val iterator = list.iterator()
        while (iterator.hasNext()) {
            val o = iterator.next()
            o.seen++
            var outcome = o.exp.step(req)
            if (!windowless && outcome == Outcome.Pending && o.seen >= o.exp.window) outcome = o.exp.expire()
            if (outcome != Outcome.Pending) {
                close(o, outcome)
                iterator.remove()
            }
        }
    }

    /**
     * A line of a client's event log. `client.done` ends that client's open windows as
     * [flush] ends every window. Any other event can only attest a pass: a failure, or a
     * plain pass, is never taken from what a client says.
     */
    @Synchronized
    fun event(e: ClientEvent) {
        val list = open[e.traceId] ?: return
        if (e.name == ClientEvent.DONE) {
            list.forEach(::end)
            open.remove(e.traceId)
            return
        }
        val iterator = list.iterator()
        while (iterator.hasNext()) {
            val o = iterator.next()
            val outcome = o.exp.event(e)
            if (outcome is Outcome.Attested) {
                close(o, outcome)
                iterator.remove()
            }
        }
    }

    /** How many expectations are still open for [clientId]. */
    @Synchronized
    fun openFor(clientId: String): Int = open[clientId]?.size ?: 0

    /** Ends every open window now, at shutdown or when a run finishes. */
    @Synchronized
    fun flush() {
        open.values.forEach { list -> list.forEach(::end) }
        open.clear()
    }

    /**
     * Ends a window before it ran out. What silence means is the expectation's own rule:
     * a client that never sent another request is judged by [Expectation.expire], like any other.
     */
    private fun end(o: Open) = close(o, o.exp.expire())

    private fun close(o: Open, outcome: Outcome) {
        val verdict = Verdict(o.record, o.exp.description, outcome)
        verdicts += verdict
        log.verdict(verdict)
    }

    @Synchronized
    fun verdicts(): List<Verdict> = verdicts.toList()
}
