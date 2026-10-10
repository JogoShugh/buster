package com.artie.buster.core

/** Result of checking one expectation against one request, or at the end of its window. */
sealed class Outcome(
    /** How the log and the report name this outcome. A failure is in capitals so it stands out. */
    val label: String,
) {
    open val reason: String get() = ""

    data object Pending : Outcome("pending")
    data class Pass(override val reason: String) : Outcome("pass")
    data class Fail(override val reason: String) : Outcome("FAIL")

    /** The client never did anything that could prove it right or wrong. */
    data class Inconclusive(override val reason: String) : Outcome("inconclusive")
}

/**
 * What the client must do after a mutation, judged from the requests it sends next.
 * [step] sees each later request from the same client; [expire] runs when [window] requests pass.
 */
class Expectation(
    val description: String,
    val window: Int,
    val step: (BusterRequest) -> Outcome,
    val expire: () -> Outcome,
)

/** Builds an [Expectation] inside a plugin's mutate block. */
@BusterDslMarker
class ExpectationBuilder(private val description: String, private val window: Int) {
    private var step: (BusterRequest) -> Outcome = { Outcome.Pending }
    private var expire: () -> Outcome = { Outcome.Inconclusive("window ended with no answer") }

    fun onRequest(block: (BusterRequest) -> Outcome) {
        step = block
    }

    fun onExpire(block: () -> Outcome) {
        expire = block
    }

    fun pass(reason: String): Outcome = Outcome.Pass(reason)

    fun fail(reason: String): Outcome = Outcome.Fail(reason)

    fun pending(): Outcome = Outcome.Pending

    fun inconclusive(reason: String): Outcome = Outcome.Inconclusive(reason)

    internal fun build(): Expectation {
        require(window >= 1) { "expectation '$description': window must be at least 1" }
        return Expectation(description, window, step, expire)
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
            if (outcome == Outcome.Pending && o.seen >= o.exp.window) outcome = o.exp.expire()
            if (outcome != Outcome.Pending) {
                close(o, outcome)
                iterator.remove()
            }
        }
    }

    /** Ends every open window now, at shutdown or when a run finishes. */
    @Synchronized
    fun flush() {
        open.values.forEach { list ->
            list.forEach {
                val outcome = if (it.seen == 0) Outcome.Inconclusive(NO_REQUEST) else it.exp.expire()
                close(it, outcome)
            }
        }
        open.clear()
    }

    private fun close(o: Open, outcome: Outcome) {
        val verdict = Verdict(o.record, o.exp.description, outcome)
        verdicts += verdict
        log.verdict(verdict)
    }

    @Synchronized
    fun verdicts(): List<Verdict> = verdicts.toList()

    private companion object {
        const val NO_REQUEST = "client sent no request before the run ended"
    }
}
