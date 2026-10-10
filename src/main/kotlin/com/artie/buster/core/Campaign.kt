package com.artie.buster.core

import kotlin.random.Random

/** Spreads consecutive sequence numbers across the seed space. */
internal const val SEED_MIX = -0x61c8864680b583ebL

/** One exchange the dry run saw: what a client asked, and what the server answered. */
class RecordedExchange(
    val label: String,
    /** The request's position among this client's requests, from 0. */
    val index: Int,
    val request: BusterRequest,
    val response: BusterResponse,
    val classification: Classification,
)

/** One mutation the plan makes: this plugin, on this client's request at this position. */
data class Combination(
    val label: String,
    val index: Int,
    val plugin: String,
    val path: String,
    val detail: String,
)

/** A plugin that fitted a recorded response and then skipped it, with its reason. */
data class Skipped(val label: String, val index: Int, val plugin: String, val reason: String)

/**
 * The combinations a dry run allows, in rounds. Round k holds the k-th combination of each
 * client, so one run mutates at most one exchange of each client and the clients' scenarios
 * go on side by side.
 */
data class Plan(
    val rounds: List<List<Combination>>,
    val coverage: Map<String, Int>,
    /** Why a plugin did not apply to a recorded exchange, for working out a coverage hole. */
    val skipped: List<Skipped> = emptyList(),
) {
    /** Plugins the dry run gave nothing to exercise. Each needs a live scenario that does. */
    val holes: List<String> get() = coverage.filterValues { it == 0 }.keys.sorted()
}

/**
 * Aims mutations. A dry run records every client's requests and responses; the plan runs each
 * plugin on each recorded response, with the client's later requests as [MutationScope.future],
 * and keeps the combinations the plugin did not skip; arming a round mutates exactly those.
 *
 * Clients are known by label, which is stable across runs; trace ids are not. Within a client,
 * an exchange is known by its request's position, so a second run that sends the same requests
 * in the same order hits the same exchanges.
 */
class Campaign(private val plugins: List<Plugin>, private val seed: Long, private val verifier: Verifier) {
    enum class Mode { OFF, RECORDING, ARMED }

    @Volatile
    var mode = Mode.OFF
        private set

    @Volatile
    var round = 0
        private set

    /** Each client's requests in the current run, in order. */
    private val live = HashMap<String, MutableList<BusterRequest>>()
    private var dryRun = emptyMap<String, List<BusterRequest>>()
    private val recorded = mutableListOf<RecordedExchange>()

    /** The plan worked out from the last dry run, if any. */
    @Volatile
    var lastPlan: Plan? = null
        private set
    private var armed = emptyMap<Pair<String, Int>, Combination>()

    @Synchronized
    fun startRecording() {
        live.clear()
        recorded.clear()
        lastPlan = null
        armed = emptyMap()
        round = 0
        verifier.windowless = false
        mode = Mode.RECORDING
    }

    /** Counts a request, so its position among its client's requests is known when it is answered. */
    @Synchronized
    fun observe(req: BusterRequest) {
        live.getOrPut(req.label) { mutableListOf() } += req
    }

    @Synchronized
    fun record(req: BusterRequest, res: BusterResponse, classification: Classification) {
        recorded += RecordedExchange(req.label, indexOf(req), req, res.copy(), classification)
    }

    /** The planned combination for this exchange, when a round is armed. */
    @Synchronized
    fun armedFor(req: BusterRequest): Combination? =
        if (mode == Mode.ARMED) armed[req.label to indexOf(req)] else null

    /** Combinations planned for this exchange, for a run that is not armed but has a plan. */
    @Synchronized
    fun plannedFor(req: BusterRequest): List<Combination>? {
        val all = lastPlan?.rounds?.flatten() ?: return null
        val index = indexOf(req)
        return all.filter { it.label == req.label && it.index == index }
    }

    /** What this client sent after the request at [index], in the dry run. */
    @Synchronized
    fun futureOf(label: String, index: Int): List<BusterRequest> = dryRun[label].orEmpty().drop(index + 1)

    /** Stops recording and works out the combinations. */
    @Synchronized
    fun plan(): Plan {
        if (mode == Mode.RECORDING) {
            dryRun = live.mapValues { it.value.toList() }
            mode = Mode.OFF
        }
        return Planner(plugins, seed, dryRun).plan(recorded).also { lastPlan = it }
    }

    /**
     * Arms round [number], counted from 1, and starts counting requests afresh. Only its exchanges
     * are mutated, and expectations stay open until the client is done. The round before it ends
     * first, so each of its windows gets a verdict. Returns how many exchanges the round arms.
     */
    @Synchronized
    fun arm(number: Int): Int {
        val chosen = checkNotNull(lastPlan) { "no plan: record a dry run and plan it first" }
            .rounds.getOrNull(number - 1) ?: error("no round $number")
        verifier.flush()
        verifier.windowless = true
        armed = chosen.associateBy { it.label to it.index }
        live.clear()
        round = number
        mode = Mode.ARMED
        return chosen.size
    }

    /** Back to mutating by seed. A plan, if there is one, still says where. */
    @Synchronized
    fun disarm() {
        verifier.flush()
        verifier.windowless = false
        armed = emptyMap()
        live.clear()
        round = 0
        mode = Mode.OFF
    }

    private fun indexOf(req: BusterRequest): Int {
        val list = live.getOrPut(req.label) { mutableListOf() }
        val found = list.indexOfLast { it === req }
        if (found >= 0) return found
        list += req
        return list.lastIndex
    }
}

/** Runs every plugin over every recorded response, as the engine would, and keeps what applies. */
internal class Planner(
    private val plugins: List<Plugin>,
    private val seed: Long,
    private val dryRun: Map<String, List<BusterRequest>>,
) {
    private sealed class Attempt {
        class Applies(val detail: String) : Attempt()
        class Skips(val reason: String) : Attempt()
    }

    fun plan(recorded: List<RecordedExchange>): Plan {
        val combos = mutableListOf<Combination>()
        val skipped = mutableListOf<Skipped>()
        for (ex in recorded) {
            for (plugin in plugins.filter { it.appliesTo(ex.classification) }) {
                when (val attempt = attempt(plugin, ex)) {
                    is Attempt.Applies ->
                        combos += Combination(ex.label, ex.index, plugin.name, ex.request.path, attempt.detail)
                    is Attempt.Skips -> skipped += Skipped(ex.label, ex.index, plugin.name, attempt.reason)
                }
            }
        }
        val byLabel = combos.groupBy { it.label }.values
        val width = byLabel.maxOfOrNull { it.size } ?: 0
        val rounds = (0 until width).map { k -> byLabel.mapNotNull { it.getOrNull(k) } }
        val coverage = plugins.associate { p -> p.name to combos.count { it.plugin == p.name } }
        return Plan(rounds, coverage, skipped)
    }

    private fun attempt(plugin: Plugin, ex: RecordedExchange): Attempt {
        val random = Random(seed xor ((ex.index + 1) * SEED_MIX) xor plugin.name.hashCode().toLong())
        val future = dryRun[ex.label].orEmpty().drop(ex.index + 1)
        val scope = MutationScope(ex.request, ex.response.copy(), ex.classification, random, future)
        return try {
            plugin.mutate(scope)
            Attempt.Applies(scope.details.joinToString("; "))
        } catch (skip: SkipMutation) {
            Attempt.Skips(skip.reason)
        }
    }
}
