package com.artie.buster.core

import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * Picks a mutation for each response it can place, applies it, and makes the result
 * cache-safe. Buster changes responses only, never stored state.
 */
class Engine(
    private val plugins: List<Plugin>,
    val seed: Long,
    private val log: MutationLog,
    private val verifier: Verifier = Verifier(log),
) {
    private val seq = AtomicLong()

    /** Every inbound request goes to the verifier before the server sees it. */
    fun request(req: BusterRequest) = verifier.observe(req)

    /** Ends the run: every open expectation gets a verdict now. */
    fun flush() = verifier.flush()

    /** A line of a client's event log (see [Verifier.event]). */
    fun event(e: ClientEvent) = verifier.event(e)

    fun openFor(clientId: String): Int = verifier.openFor(clientId)

    fun verdicts(): List<Verdict> = verifier.verdicts()

    /** The mutations applied so far, oldest first; the most recent [MAX_KEPT] only. */
    fun mutations(): List<MutationRecord> = synchronized(applied) { applied.toList() }

    private val applied = ArrayDeque<MutationRecord>()

    /** Mutates [res] in place. Returns the mutation's record, or null when the response was left alone. */
    fun respond(req: BusterRequest, res: BusterResponse): MutationRecord? {
        val classification = Classifier.classify(res)
        val candidates = plugins.filter { it.appliesTo(classification) }
        if (candidates.isEmpty()) return null
        val n = seq.incrementAndGet()
        // One random source per exchange: replay needs only the seed and the sequence number.
        val random = Random(seed xor (n * MIX))
        return apply(pick(candidates, random), Exchange(req, res, classification, n), random)
    }

    private class Exchange(
        val req: BusterRequest,
        val res: BusterResponse,
        val classification: Classification,
        val seq: Long,
    )

    private fun pick(candidates: List<Plugin>, random: Random): Plugin {
        var ticket = random.nextInt(candidates.sumOf { it.weight })
        return candidates.first {
            ticket -= it.weight
            ticket < 0
        }
    }

    private fun apply(plugin: Plugin, ex: Exchange, random: Random): MutationRecord? {
        // The plugin works on a copy, so a skip leaves the real response exactly as it was.
        val work = ex.res.copy()
        val scope = MutationScope(ex.req, work, ex.classification, random)
        try {
            plugin.mutate(scope)
        } catch (skip: SkipMutation) {
            log.note("${plugin.name} skipped: ${skip.reason}")
            return null
        }
        // A corrupted response must never reach a shared cache. The engine enforces this,
        // not the plugin author: a plugin keeps its Cache-Control only over unchanged bytes.
        if (!scope.keepsCacheControl || !work.body.contentEquals(ex.res.body)) {
            work.headers["Cache-Control"] = "no-store"
        }
        ex.res.replaceWith(work)
        val record = MutationRecord(
            seq = ex.seq, seed = seed, clientId = ex.req.clientId, label = ex.req.label, spanId = ex.req.spanId,
            method = ex.req.method,
            path = ex.req.path, plugin = plugin.name, spec = plugin.spec,
            layer = ex.classification.layer, rung = ex.classification.rung,
            detail = scope.details.joinToString("; "),
        )
        log.mutation(record)
        synchronized(applied) {
            applied.addLast(record)
            while (applied.size > MAX_KEPT) applied.removeFirst()
        }
        scope.expectations.forEach { verifier.register(record, it) }
        return record
    }

    private companion object {
        const val MAX_KEPT = 1_000

        /** Spreads consecutive sequence numbers across the seed space. */
        const val MIX = -0x61c8864680b583ebL
    }
}
