package com.artie.buster.core

import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * Picks a mutation for each response it can place, applies it, and makes the result
 * cache-safe. Buster changes responses only, never stored state.
 */
class Engine(
    val plugins: List<Plugin>,
    val seed: Long,
    private val log: MutationLog,
    private val verifier: Verifier = Verifier(log),
    /** Clients whose label matches are not recorded, planned or armed: a readiness probe, say. */
    campaignIgnore: Regex? = null,
) {
    private val seq = AtomicLong()
    /** Records a dry run, plans it, and arms a round. Without one the seed chooses. */
    val campaign = Campaign(plugins, seed, verifier, campaignIgnore)

    /** Every inbound request goes to the verifier before the server sees it. */
    fun request(req: BusterRequest) {
        campaign.observe(req)
        verifier.observe(req)
    }

    /** Ends the run: the armed round is settled, and every open expectation gets a verdict now. */
    fun flush() {
        campaign.ledger.settle()
        verifier.flush()
    }

    /** A line of a client's event log (see [Verifier.event]). */
    fun event(e: ClientEvent) = verifier.event(e)

    fun openFor(clientId: String): Int = verifier.openFor(clientId)

    fun verdicts(): List<Verdict> = verifier.verdicts()

    /** The mutations applied so far, oldest first; the most recent [MAX_KEPT] only, enough for a campaign. */
    fun mutations(): List<MutationRecord> = synchronized(applied) { applied.toList() }

    private val applied = ArrayDeque<MutationRecord>()

    /**
     * Mutates [res] in place. Returns the mutation's record, or null when the response was left
     * alone. [future] is what the client is known to send next, when a caller knows it; with a
     * campaign running the engine works it out from the dry run itself.
     */
    fun respond(req: BusterRequest, res: BusterResponse, future: List<BusterRequest>? = null): MutationRecord? {
        val classification = Classifier.classify(res)
        if (campaign.mode == Campaign.Mode.RECORDING) {
            campaign.record(req, res, classification)
            return null
        }
        val record = if (campaign.mode == Campaign.Mode.ARMED) {
            armed(req, res, classification)
        } else {
            bySeed(req, res, classification, future)
        }
        if (record == null && replacesWhatWasGiven(req, res)) {
            verifier.superseded(req.clientId, Resource.pathOf(req, res))
        }
        return record
    }


    private fun armed(req: BusterRequest, res: BusterResponse, classification: Classification): MutationRecord? {
        val combo = campaign.armedFor(req)
        val plugin = combo?.let { c -> plugins.firstOrNull { it.name == c.plugin } }
        if (combo == null || plugin == null) return null
        val n = seq.incrementAndGet()
        val random = Random(seed xor (n * SEED_MIX))
        val known = campaign.futureOf(combo.label, combo.index)
        return apply(plugin, Exchange(req, res, classification, n), random, known)
            ?.also { campaign.ledger.made(combo) }
    }

    private fun bySeed(
        req: BusterRequest,
        res: BusterResponse,
        classification: Classification,
        future: List<BusterRequest>?,
    ): MutationRecord? {
        // With a plan, the seed chooses among the combinations the plan allows here, not among
        // every plugin that fits the response.
        val planned = campaign.plannedFor(req)
        val candidates = plugins.filter { p ->
            p.appliesTo(classification) && (planned == null || planned.any { it.plugin == p.name })
        }
        if (candidates.isEmpty()) return null
        val n = seq.incrementAndGet()
        // One random source per exchange: replay needs only the seed and the sequence number.
        val random = Random(seed xor (n * SEED_MIX))
        val plugin = pick(candidates, random)
        val known = if (planned != null) campaign.futureOf(req.label, planned.first().index) else future
        return apply(plugin, Exchange(req, res, classification, n), random, known)
    }

    private class Exchange(
        val req: BusterRequest,
        val res: BusterResponse,
        val classification: Classification,
        val seq: Long,
    )

    private fun apply(plugin: Plugin, ex: Exchange, random: Random, future: List<BusterRequest>?): MutationRecord? {
        // The plugin works on a copy, so a skip leaves the real response exactly as it was.
        val work = ex.res.copy()
        val scope = MutationScope(ex.req, work, ex.classification, random, future)
        try {
            plugin.run(scope)
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
        const val MAX_KEPT = 100_000
    }
}

private const val FIRST_SUCCESS = 200
private const val LAST_SUCCESS = 299
private val SUCCESS = FIRST_SUCCESS..LAST_SUCCESS
private const val NOT_MODIFIED = 304

/**
 * Whether an answer Buster did not mutate replaces whatever the client was given before. A whole
 * representation does; so does a 304, which tells the client the copy it holds is current. An
 * act's 2xx answer is a whole representation too, when Content-Location (RFC 9110 §8.7) names
 * the resource it shows.
 */
private fun replacesWhatWasGiven(req: BusterRequest, res: BusterResponse): Boolean = when {
    req.isGet -> res.status in SUCCESS || res.status == NOT_MODIFIED
    else -> res.status in SUCCESS && res.headers["Content-Location"] != null
}

/** The campaign's state, and the gate: it fails on any FAIL, or on a plugin nothing exercised. */
fun Engine.campaignReport(): CampaignReport {
    val plan = campaign.lastPlan
    val verdicts = verdicts()
    val matrix = verdicts.groupBy { it.record.plugin }.mapValues { (_, byPlugin) ->
        byPlugin.groupBy { it.record.label }.mapValues { (_, byLabel) -> byLabel.map { it.outcome.label } }
    }
    val failures = verdicts.count { it.outcome is Outcome.Fail }
    val planned = plan?.coverage.orEmpty()
    val made = planned.keys.associateWith { campaign.ledger.madeCounts()[it] ?: 0 }
    val ran = campaign.ledger.roundsRun()
    // The rounds each plugin is planned in: a plugin not yet run is not one that ran and made nothing.
    val plannedIn = planned.keys.associateWith { name ->
        plan?.rounds.orEmpty().withIndex().filter { (_, r) -> r.any { it.plugin == name } }.map { it.index + 1 }
    }
    val holes = planned.keys.filter { name ->
        val rounds = plannedIn.getValue(name)
        rounds.isEmpty() || (rounds.any { it in ran } && made.getValue(name) == 0)
    }.sorted()
    val unrun = planned.keys.filter { name ->
        val rounds = plannedIn.getValue(name)
        ran.isNotEmpty() && rounds.isNotEmpty() && rounds.none { it in ran }
    }.sorted()
    val misaligned = campaign.ledger.misaligned()
    return CampaignReport(
        mode = campaign.mode, round = campaign.round, planned = planned, made = made,
        coverage = if (ran.isNotEmpty()) made else planned, holes = holes, unrun = unrun,
        complete = plan != null && ran.size == plan.rounds.size, misaligned = misaligned, matrix = matrix,
        failures = failures, ok = plan != null && failures == 0 && holes.isEmpty() && misaligned.isEmpty(),
    )
}

/** What a campaign shows of itself. */
data class CampaignReport(
    val mode: Campaign.Mode,
    val round: Int,
    /** Combinations the plan holds for each plugin. */
    val planned: Map<String, Int>,
    /** Combinations actually made for each plugin, across the rounds run. */
    val made: Map<String, Int>,
    /** What the gate counts: made, once a round has run; planned before that. */
    val coverage: Map<String, Int>,
    /** Plugins with none: each is a hole, and needs a live scenario that exercises it. */
    val holes: List<String>,
    /** Plugins planned only in rounds that have not run: not holes, just not yet run. */
    val unrun: List<String>,
    /** Every round of the plan has run. Without it, `ok` says nothing about the rounds left. */
    val complete: Boolean,
    /** Combinations a round armed and did not make. Any is a failure: the round proved nothing. */
    val misaligned: List<Misaligned>,
    /** plugin, then client, then every verdict that pair got. */
    val matrix: Map<String, Map<String, List<String>>>,
    val failures: Int,
    val ok: Boolean,
)

private fun pick(candidates: List<Plugin>, random: Random): Plugin {
    var ticket = random.nextInt(candidates.sumOf { it.weight })
    return candidates.first {
        ticket -= it.weight
        ticket < 0
    }
}
