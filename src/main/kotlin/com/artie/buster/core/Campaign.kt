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

/**
 * What the armed rounds reached and made. A combination armed and not made is misaligned: the
 * plugin skipped it at run time, or no request came at that position.
 */
class Ledger {
    private val reached = HashMap<Pair<String, Int>, String>()
    private val made = HashSet<Pair<String, Int>>()
    private val madeByPlugin = HashMap<String, Int>()
    private val misses = mutableListOf<Misaligned>()
    private var armed = emptyMap<Pair<String, Int>, Combination>()
    private var open = false
    private var rounds = 0

    /** A round is armed with these combinations. */
    @Synchronized
    fun begin(round: Map<Pair<String, Int>, Combination>) {
        reached.clear()
        made.clear()
        armed = round
        open = true
        rounds++
    }

    @Synchronized
    fun reached(key: Pair<String, Int>, path: String) {
        reached[key] = path
    }

    /** The plugin applied to the armed exchange: the combination was made. */
    @Synchronized
    fun made(combo: Combination) {
        if (made.add(combo.label to combo.index)) madeByPlugin.merge(combo.plugin, 1, Int::plus)
    }

    /** Ends the round in progress: whatever it armed that was not made is misaligned. */
    @Synchronized
    fun settle() {
        if (!open) return
        open = false
        armed.forEach { (key, combo) ->
            if (key !in made) misses += Misaligned(combo.label, combo.index, combo.plugin, combo.path, reached[key])
        }
    }

    @Synchronized
    fun reset() {
        reached.clear()
        made.clear()
        madeByPlugin.clear()
        misses.clear()
        open = false
        rounds = 0
    }

    /** What the rounds settled so far armed and did not make. */
    @Synchronized
    fun misaligned(): List<Misaligned> = misses.toList()

    /** Combinations made, for each plugin, across the rounds run. */
    @Synchronized
    fun madeCounts(): Map<String, Int> = madeByPlugin.toMap()

    @Synchronized
    fun roundsRun(): Int = rounds
}

/**
 * A combination a round armed and did not make: the plugin skipped it at run time, or no request
 * came at that position. The client did not do in the run what it did in the dry run, so the
 * round says nothing about the rule it was meant to test.
 */
data class Misaligned(
    val label: String,
    val index: Int,
    val plugin: String,
    /** The path the dry run asked for at that position. */
    val planned: String,
    /** The path the run asked for there, or null when no request came. */
    val live: String?,
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
class Campaign(
    private val plugins: List<Plugin>,
    private val seed: Long,
    private val verifier: Verifier,
    /** Clients whose label matches are left out: a readiness probe is not a client under test. */
    private val ignore: Regex? = null,
) {
    enum class Mode { OFF, RECORDING, ARMED }

    @Volatile
    var mode = Mode.OFF
        private set

    @Volatile
    var round = 0
        private set

    /** Each client's requests in the current run, in order. */
    private val live = HashMap<String, MutableList<BusterRequest>>()
    private val liveTicks = HashMap<String, MutableList<Long>>()
    private var tick = 0L
    private var dryRun = emptyMap<String, List<BusterRequest>>()
    private var dryTicks = emptyMap<String, List<Long>>()
    private val recorded = mutableListOf<RecordedExchange>()

    /** The plan worked out from the last dry run, if any. */
    @Volatile
    var lastPlan: Plan? = null
        private set
    private var armed = emptyMap<Pair<String, Int>, Combination>()

    /** What the armed rounds reached and made, so what they did not make can be named. */
    val ledger = Ledger()

    @Synchronized
    fun startRecording() {
        live.clear()
        liveTicks.clear()
        recorded.clear()
        lastPlan = null
        armed = emptyMap()
        round = 0
        ledger.reset()
        verifier.windowless = false
        mode = Mode.RECORDING
    }

    /** Counts a request, so its position among its client's requests is known when it is answered. */
    @Synchronized
    fun observe(req: BusterRequest) {
        if (ignored(req)) return
        live.getOrPut(req.label) { mutableListOf() } += req
        liveTicks.getOrPut(req.label) { mutableListOf() } += tick++
    }

    @Synchronized
    fun record(req: BusterRequest, res: BusterResponse, classification: Classification) {
        if (ignored(req)) return
        recorded += RecordedExchange(req.label, indexOf(req), req, res.copy(), classification)
    }

    /** The planned combination for this exchange, when a round is armed. */
    @Synchronized
    fun armedFor(req: BusterRequest): Combination? {
        if (mode != Mode.ARMED || ignored(req)) return null
        val key = req.label to indexOf(req)
        return armed[key]?.also { ledger.reached(key, req.path) }
    }


    /** Combinations planned for this exchange, for a run that is not armed but has a plan. */
    @Synchronized
    fun plannedFor(req: BusterRequest): List<Combination>? {
        val all = lastPlan?.rounds?.flatten()
        if (all == null || ignored(req)) return null
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
            dryTicks = liveTicks.mapValues { it.value.toList() }
            mode = Mode.OFF
        }
        return Planner(plugins, seed, dryRun, dryTicks).plan(recorded).also { lastPlan = it }
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
        ledger.settle()
        verifier.flush()
        verifier.windowless = true
        armed = chosen.associateBy { it.label to it.index }
        ledger.begin(armed)
        live.clear()
        liveTicks.clear()
        round = number
        mode = Mode.ARMED
        return chosen.size
    }

    /** Back to mutating by seed. A plan, if there is one, still says where. */
    @Synchronized
    fun disarm() {
        ledger.settle()
        verifier.flush()
        verifier.windowless = false
        armed = emptyMap()
        live.clear()
        liveTicks.clear()
        round = 0
        mode = Mode.OFF
    }

    private val ignored: (BusterRequest) -> Boolean = { req -> ignore?.containsMatchIn(req.label) == true }

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
    private val ticks: Map<String, List<Long>>,
) {
    /** What one client did in the dry run: when it was active, what it touched, and what it wrote. */
    private class Footprint(val first: Long, val last: Long, val touched: Set<String>, val written: Set<String>) {
        /** Overlap in time, and a common resource that at least one of the two wrote. */
        fun interactsWith(other: Footprint): Boolean =
            first <= other.last && other.first <= last &&
                touched.intersect(other.touched).any { it in written || it in other.written }
    }

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
        val rounds = rounds(combos, footprints(recorded))
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

    /** One footprint per client: the resources its exchanges are the state of, and the paths it asked for. */
    private fun footprints(recorded: List<RecordedExchange>): Map<String, Footprint> =
        dryRun.mapValues { (label, asked) ->
            val seen = recorded.filter { it.label == label }
            val states = seen.map { Resource.pathOf(it.request, it.response) }
            val written = seen.filter { it.request.isAct }.map { Resource.pathOf(it.request, it.response) }
            val stamps = ticks[label].orEmpty()
            Footprint(
                first = stamps.minOrNull() ?: 0L,
                last = stamps.maxOrNull() ?: 0L,
                touched = (asked.map { it.path } + states).toSet(),
                written = (asked.filter { it.isAct }.map { it.path } + written).toSet(),
            )
        }

    /**
     * Round k holds the k-th combination of each group of clients. A group is clients that
     * interact in the dry run, so only one of them is armed at a time; a client alone in its
     * group is a group of one, which is the plain case.
     */
    private fun rounds(combos: List<Combination>, footprints: Map<String, Footprint>): List<List<Combination>> {
        val labels = combos.map { it.label }.distinct()
        val parent = labels.associateWith { it }.toMutableMap()
        fun root(label: String): String = if (parent.getValue(label) == label) label else root(parent.getValue(label))
        for (a in labels.indices) {
            for (b in a + 1 until labels.size) {
                val fa = footprints[labels[a]]
                val fb = footprints[labels[b]]
                if (fa != null && fb != null && fa.interactsWith(fb)) parent[root(labels[b])] = root(labels[a])
            }
        }
        val sequences = labels.groupBy { root(it) }.values.map { group ->
            group.flatMap { label -> combos.filter { it.label == label } }
        }
        val width = sequences.maxOfOrNull { it.size } ?: 0
        return (0 until width).map { k -> sequences.mapNotNull { it.getOrNull(k) } }
    }
}
