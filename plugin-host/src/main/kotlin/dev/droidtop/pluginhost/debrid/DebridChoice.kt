package dev.droidtop.pluginhost.debrid

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** What serving a job costs an account now, from what its service reports (docs/SPEC.md "Which account serves"). */
sealed interface DebridCost {
    /** Spends nothing limited. */
    data object Free : DebridCost

    /** Spends something that resets (a hoster's daily traffic, points, a free plan's downloads); [left] 0 to 1 when said. */
    data class Spends(val left: Double?) : DebridCost

    /** Cannot serve now: out of quota, a cooldown, its plan ended. [untilMs] when the service says it resets. */
    data class NotNow(val reason: String, val untilMs: Long? = null) : DebridCost
}

/**
 * Which of the person's debrid accounts serves a job (Droidtop/tracker#638; owner: "If services provide free
 * unrestricted premium downloads, those get used first ... as little impact on the user as possible"): the one it
 * costs least, the person's order settling a tie, or with spread mode the slot-limited account with the fewest of
 * droidtop's jobs running. An account a limit refused rests until the service's reset. Costs are asked of the
 * services and kept a few minutes, so a queue of jobs does not ask once per job.
 */
object DebridChoice {
    /** :app sets this: whether spread mode is on (Settings > Accounts and sources > Debrid services). */
    @Volatile var spreadMode: () -> Boolean = { false }

    /** One account as the choice sees it: its place in the person's order is its index in the list given. */
    data class Account(val provider: DebridProvider, val key: String, val cost: DebridCost, val running: Int = 0)

    private const val COST_TTL_MS = 5L * 60 * 1000
    private const val REST_DEFAULT_MS = 60L * 60 * 1000

    private data class Kept(val atMs: Long, val cost: DebridCost)

    private val costs = ConcurrentHashMap<String, Kept>()
    private val rests = ConcurrentHashMap<String, Long>()
    private val running = ConcurrentHashMap<String, AtomicInteger>()

    private fun id(provider: DebridProvider, key: String) = provider.id + ":" + key.hashCode()

    /**
     * [accounts] in the order they are asked, the ones that cannot serve now left out. Free before spending, the most
     * left first among spending (a known amount before an unknown one), then the person's order; with [spread], a tie
     * between slot-limited accounts goes to the fewest jobs running. Pure, for the tests.
     */
    fun rank(accounts: List<Account>, spread: Boolean): List<Account> {
        val placed = accounts.withIndex().filter { it.value.cost !is DebridCost.NotNow }
        return placed.sortedWith(
            compareBy<IndexedValue<Account>> { if (it.value.cost is DebridCost.Free) 0 else 1 }
                .thenByDescending { (it.value.cost as? DebridCost.Spends)?.left ?: -1.0 }
                .thenBy { if (spread && it.value.provider.slotLimited) it.value.running else 0 }
                .thenBy { it.index },
        ).map { it.value }
    }

    /** [accounts] (the person's order) ranked for a job: a torrent, or a link on [host]. Blocking: asks the services. */
    fun order(accounts: List<Pair<DebridProvider, String>>, spread: Boolean = spreadMode(), host: String? = null, now: () -> Long = System::currentTimeMillis): List<Pair<DebridProvider, String>> {
        if (accounts.isEmpty()) return accounts
        val seen = accounts.map { (provider, key) ->
            Account(provider, key, costOf(provider, key, host, now()), running[id(provider, key)]?.get() ?: 0)
        }
        return rank(seen, spread).map { it.provider to it.key }
    }

    private fun costOf(provider: DebridProvider, key: String, host: String?, now: Long): DebridCost {
        rests[id(provider, key)]?.let { until -> if (until > now) return DebridCost.NotNow("${provider.name} is resting", until) else rests.remove(id(provider, key)) }
        val cacheKey = id(provider, key) + "|" + host.orEmpty()
        costs[cacheKey]?.takeIf { now - it.atMs < COST_TTL_MS }?.let { return it.cost }
        // A service that cannot be asked just now is tried as if free: a refusal then rests it.
        val cost = try {
            provider.cost(key, host)
        } catch (e: IOException) {
            DebridCost.Free
        } catch (e: org.json.JSONException) {
            DebridCost.Free
        }
        costs[cacheKey] = Kept(now, cost)
        return cost
    }

    /** Notes a refusal: one of a limit ([DebridState.Failed.restUntilMs] set) rests the account until then. */
    fun refused(provider: DebridProvider, key: String, state: DebridState?, now: () -> Long = System::currentTimeMillis) {
        val failed = state as? DebridState.Failed ?: return
        if (!failed.limit) return
        rests[id(provider, key)] = failed.restUntilMs ?: (now() + REST_DEFAULT_MS)
        costs.keys.removeIf { it.startsWith(id(provider, key) + "|") }
    }

    /** Runs [block] counted as one of droidtop's jobs on [provider]'s account, for spread mode. */
    inline fun <T> serving(provider: DebridProvider, key: String, block: () -> T): T {
        val count = counter(provider, key)
        count.incrementAndGet()
        try {
            return block()
        } finally {
            count.decrementAndGet()
        }
    }

    @PublishedApi internal fun counter(provider: DebridProvider, key: String): AtomicInteger =
        running.getOrPut(id(provider, key)) { AtomicInteger() }

    /** Forgets rests and kept costs, for the tests. */
    internal fun reset() {
        costs.clear()
        rests.clear()
        running.clear()
    }
}
