package dev.droidtop.pluginhost

import org.json.JSONArray
import org.json.JSONObject

/**
 * The person's Steam account for a plugin (docs/plugin-api.md 3 G8, Droidtop/tracker#379): droidtop runs every call on
 * the Steam session it already holds, so a plugin claims free licences, redeems keys and shows games as played with
 * no sign-in of its own and never sees a password, token or cookie. Every op needs `steam.account`, a dangerous
 * permission, and is written to the plugin's activity log. droidtop, not the plugin, keeps Steam's limits
 * ([SteamLimits]), so no plugin can get the account flagged.
 */
interface SteamAccount {
    /** Who is signed in; signedIn false (and nothing else) when nobody is. */
    fun status(): Status

    /** Steam's product changes since [since] (a PICS change number; 0 for the current one only). */
    fun changes(since: Long): Changes

    /** Each app's and package's product facts, as Steam's product info gives them (a flat map per id). */
    fun productInfo(apps: List<Int>, packages: List<Int>): ProductInfo

    /** Asks Steam for free licences: apps over the client connection, packages through the store. Per id, the outcome. */
    fun claimFree(apps: List<Int>, packages: List<Int>): List<Claim>

    /** Redeems one product key on the store. */
    fun redeemKey(key: String): Redeem

    /** Shows [apps] as played until [untilMs] (epoch ms) or [stop]; refused (a sentence) when the account cannot. */
    fun play(apps: List<Int>, untilMs: Long): String?

    fun stop()

    data class Status(val signedIn: Boolean, val steamId: Long?, val accountName: String?)

    data class Changes(val current: Long, val apps: List<Int>, val packages: List<Int>, val fullUpdateNeeded: Boolean)

    data class ProductInfo(val apps: Map<Int, Map<String, String>>, val packages: Map<Int, Map<String, String>>)

    /** [kind] is "app" or "package"; [reason] says why when not [claimed]; [rateLimited] when Steam said too many. */
    data class Claim(val kind: String, val id: Int, val claimed: Boolean, val reason: String? = null, val rateLimited: Boolean = false)

    /** [result] is Steam's own word ("ok", "already_owned", "invalid", "rate_limited", ...); [items] what it added. */
    data class Redeem(val result: String, val items: List<String>, val rateLimited: Boolean = false)
}

/** The account droidtop's Steam store provides, set once at process start; null in a build without Steam. */
object SteamAccounts {
    @Volatile
    var provider: SteamAccount? = null
}

/**
 * Steam's limits on what a plugin asks of the account, kept by droidtop for every plugin together (the account is
 * one): at most [CLAIMS_PER_HOUR] free licences in any rolling hour (the figure ArchiSteamFarm's FreePackages keeps
 * under Steam's own), nothing more for [COOL_DOWN_MS] after Steam says too many, and one key at most every
 * [KEY_SPACING_MS]. Pure apart from its clock, which is given.
 */
class SteamLimits {
    private val claims = ArrayDeque<Long>()
    private var coolUntil = 0L
    private var lastKey = 0L

    /** How many claims may go now, out of [wanted]; 0 while cooling down. */
    @Synchronized
    fun claimsAllowed(nowMs: Long, wanted: Int): Int {
        if (nowMs < coolUntil) return 0
        while (claims.isNotEmpty() && claims.first() <= nowMs - HOUR_MS) claims.removeFirst()
        return minOf(wanted, CLAIMS_PER_HOUR - claims.size).coerceAtLeast(0)
    }

    @Synchronized
    fun claimed(nowMs: Long, count: Int) {
        repeat(count) { claims.addLast(nowMs) }
    }

    @Synchronized
    fun rateLimited(nowMs: Long) {
        coolUntil = nowMs + COOL_DOWN_MS
    }

    /** When a key may be redeemed again, or 0 when now is fine. */
    @Synchronized
    fun keyWaitMs(nowMs: Long): Long = (lastKey + KEY_SPACING_MS - nowMs).coerceAtLeast(0)

    @Synchronized
    fun keyRedeemed(nowMs: Long) {
        lastKey = nowMs
    }

    @Synchronized
    fun coolingFor(nowMs: Long): Long = (coolUntil - nowMs).coerceAtLeast(0)

    companion object {
        const val CLAIMS_PER_HOUR = 30
        const val HOUR_MS = 60 * 60 * 1000L
        const val COOL_DOWN_MS = 60 * 60 * 1000L
        const val KEY_SPACING_MS = 10_000L
        /** The longest a `play` lasts before droidtop stops it; a plugin asks again to go on. */
        const val MAX_PLAY_MS = 12 * 60 * 60 * 1000L
        /** The most ids one call names. */
        const val MAX_IDS = 100
    }
}

internal object HostSteamApis {
    const val PERMISSION = "steam.account"

    /** The one set of limits for every plugin: the account is one. */
    val limits = SteamLimits()

    private fun invalid(message: String): Nothing = throw BrokerException(PluginErrorCode.INVALID_ARGS, message)

    private fun account(): SteamAccount =
        SteamAccounts.provider ?: throw BrokerException(PluginErrorCode.UNSUPPORTED, "this droidtop has no Steam account to offer")

    private fun signedIn(account: SteamAccount): SteamAccount {
        if (!account.status().signedIn) throw BrokerException(PluginErrorCode.SIGN_IN_REQUIRED, "Sign in to Steam under Stores first")
        return account
    }

    /** The ids in `args[name]`: positive whole numbers, each once, at most [SteamLimits.MAX_IDS]. Pure. */
    fun ids(args: JSONObject, name: String): List<Int> {
        val array = args.optJSONArray(name) ?: return emptyList()
        if (array.length() > SteamLimits.MAX_IDS) invalid("$name names at most ${SteamLimits.MAX_IDS} ids")
        return (0 until array.length()).map { i ->
            val value = array.opt(i)
            val id = (value as? Number)?.toLong() ?: invalid("$name holds numbers only")
            if (id <= 0 || id > Int.MAX_VALUE) invalid("$name holds positive ids")
            id.toInt()
        }.distinct()
    }

    /** A Steam product key: letters, digits and dashes, 5 to 64 of them. Pure. */
    fun key(args: JSONObject): String {
        val key = args.optString("key").trim().uppercase()
        if (!key.matches(Regex("[A-Z0-9-]{5,64}"))) invalid("key is a Steam product key")
        return key
    }

    fun claimJson(claim: SteamAccount.Claim): JSONObject = JSONObject().put("kind", claim.kind).put("id", claim.id).put("claimed", claim.claimed)
        .put("reason", claim.reason ?: JSONObject.NULL)

    val ops: List<HostOp> = listOf(
        HostOp("steam", "get_status", permission = PERMISSION) { _, _, _ ->
            val status = account().status()
            JSONObject().put("signedIn", status.signedIn).put("steamId", status.steamId?.toString() ?: JSONObject.NULL)
                .put("accountName", status.accountName ?: JSONObject.NULL)
        },
        HostOp("steam", "list_changes", permission = PERMISSION) { _, _, args ->
            val since = args.optLong("since", 0L).coerceAtLeast(0L)
            val changes = signedIn(account()).changes(since)
            JSONObject().put("current", changes.current).put("apps", JSONArray(changes.apps)).put("packages", JSONArray(changes.packages))
                .put("fullUpdateNeeded", changes.fullUpdateNeeded)
        },
        HostOp("steam", "get_product_info", permission = PERMISSION) { _, _, args ->
            val apps = ids(args, "apps")
            val packages = ids(args, "packages")
            if (apps.isEmpty() && packages.isEmpty()) invalid("name some apps or packages")
            val info = signedIn(account()).productInfo(apps, packages)
            fun asJson(map: Map<Int, Map<String, String>>) = JSONObject().also { o -> map.forEach { (id, facts) -> o.put(id.toString(), JSONObject(facts as Map<*, *>)) } }
            JSONObject().put("apps", asJson(info.apps)).put("packages", asJson(info.packages))
        },
        // Free licences, within Steam's hourly limit for the whole account: what does not fit is answered "later".
        HostOp(
            "steam", "claim_free", permission = PERMISSION, alwaysAudit = true,
            target = { "apps ${it.optJSONArray("apps")?.length() ?: 0}, packages ${it.optJSONArray("packages")?.length() ?: 0}" },
        ) { env, _, args ->
            val apps = ids(args, "apps")
            val packages = ids(args, "packages")
            if (apps.isEmpty() && packages.isEmpty()) invalid("name some apps or packages")
            val account = signedIn(account())
            val now = env.nowMs()
            val allowed = limits.claimsAllowed(now, apps.size + packages.size)
            val goApps = apps.take(allowed)
            val goPackages = packages.take(allowed - goApps.size)
            val claims = if (goApps.isEmpty() && goPackages.isEmpty()) emptyList() else account.claimFree(goApps, goPackages)
            limits.claimed(now, claims.size)
            if (claims.any { it.rateLimited }) limits.rateLimited(now)
            val later = (apps - goApps.toSet()).map { SteamAccount.Claim("app", it, false, "later") } +
                (packages - goPackages.toSet()).map { SteamAccount.Claim("package", it, false, "later") }
            JSONObject().put("results", JSONArray((claims + later).map(::claimJson)))
                .put("retryAfterMs", if (later.isEmpty()) 0 else maxOf(limits.coolingFor(now), 60_000L))
        },
        HostOp("steam", "redeem_key", permission = PERMISSION, alwaysAudit = true, target = { "a product key" }) { env, _, args ->
            val key = key(args)
            val account = signedIn(account())
            val now = env.nowMs()
            val wait = limits.keyWaitMs(now)
            if (wait > 0) throw BrokerException(PluginErrorCode.RATE_LIMITED, "one key every ${SteamLimits.KEY_SPACING_MS / 1000} s; try again in ${wait / 1000 + 1} s")
            if (limits.coolingFor(now) > 0) throw BrokerException(PluginErrorCode.RATE_LIMITED, "Steam asked to slow down; try again later")
            limits.keyRedeemed(now)
            val redeem = account.redeemKey(key)
            if (redeem.rateLimited) limits.rateLimited(now)
            JSONObject().put("result", redeem.result).put("items", JSONArray(redeem.items))
        },
        // Shown as playing (card drops, play time), for at most [SteamLimits.MAX_PLAY_MS] at a time.
        HostOp("steam", "play", permission = PERMISSION, alwaysAudit = true, target = { "apps ${it.optJSONArray("apps")?.length() ?: 0}" }) { env, _, args ->
            val apps = ids(args, "apps")
            if (apps.isEmpty() || apps.size > 32) invalid("apps names 1 to 32 games")
            val minutes = args.optLong("minutes", 120L).coerceIn(1L, SteamLimits.MAX_PLAY_MS / 60_000L)
            val refused = signedIn(account()).play(apps, env.nowMs() + minutes * 60_000L)
            JSONObject().put("playing", refused == null).put("reason", refused ?: JSONObject.NULL)
        },
        HostOp("steam", "stop", permission = PERMISSION, alwaysAudit = true) { _, _, _ ->
            account().stop()
            JSONObject().put("stopped", true)
        },
    )
}
