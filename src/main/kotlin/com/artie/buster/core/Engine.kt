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
) {
    private val seq = AtomicLong()

    fun respond(req: BusterRequest, res: BusterResponse) {
        val classification = Classifier.classify(res)
        val candidates = plugins.filter { it.appliesTo(classification) }
        if (classification.layer == Layer.OTHER || candidates.isEmpty()) return
        val n = seq.incrementAndGet()
        // One random source per exchange: replay needs only the seed and the sequence number.
        val random = Random(seed xor (n * MIX))
        apply(pick(candidates, random), Exchange(req, res, classification, n), random)
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

    private fun apply(plugin: Plugin, ex: Exchange, random: Random) {
        // The plugin works on a copy, so a skip leaves the real response exactly as it was.
        val work = ex.res.copy()
        val scope = MutationScope(ex.req, work, ex.classification, random)
        try {
            plugin.mutate(scope)
        } catch (skip: SkipMutation) {
            log.note("${plugin.name} skipped: ${skip.reason}")
            return
        }
        // A corrupted response must never reach a shared cache. The engine enforces this,
        // not the plugin author: a plugin keeps its Cache-Control only over unchanged bytes.
        if (!scope.keepsCacheControl || !work.body.contentEquals(ex.res.body)) {
            work.headers["Cache-Control"] = "no-store"
        }
        ex.res.replaceWith(work)
        log.mutation(
            MutationRecord(
                seq = ex.seq, seed = seed, clientId = ex.req.clientId, method = ex.req.method,
                path = ex.req.path, plugin = plugin.name, spec = plugin.spec,
                layer = ex.classification.layer, rung = ex.classification.rung,
                detail = scope.details.joinToString("; "),
            ),
        )
    }

    private companion object {
        /** Spreads consecutive sequence numbers across the seed space. */
        const val MIX = -0x61c8864680b583ebL
    }
}
