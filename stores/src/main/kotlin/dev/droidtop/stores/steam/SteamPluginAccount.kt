package dev.droidtop.stores.steam

import android.content.Context
import dev.droidtop.pluginhost.SteamAccount
import dev.droidtop.runtime.util.runCatchingCancellable
import `in`.dragonbra.javasteam.enums.EOSType
import `in`.dragonbra.javasteam.steam.handlers.steamapps.GamePlayedInfo
import `in`.dragonbra.javasteam.steam.handlers.steamapps.PICSRequest
import `in`.dragonbra.javasteam.types.KeyValue
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber

/**
 * The person's Steam account for plugins (docs/plugin-api.md 3 G8, Droidtop/tracker#379), on the session droidtop's
 * Steam store already holds: the client connection for product changes, product info, free app licences and the games
 * shown as played (`SteamApps`), and Steam's store pages with the web session droidtop makes for its own web view
 * ([SteamStore.webSession]) for free packages and product keys, the way ArchiSteamFarm does both. The broker's
 * `steam.*` ops call this off the main thread and keep Steam's limits; nothing here hands a secret out.
 */
internal class SteamPluginAccount(private val context: Context, private val store: SteamStore) : SteamAccount {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var playing: Job? = null
    private var held = false

    private fun <T> blocking(block: suspend () -> T): T = runBlocking { withTimeout(CALL_MS) { block() } }

    override fun status(): SteamAccount.Status {
        val credentials = SteamCredentials.load(context)?.takeIf { it.steamId64 != 0L } ?: return SteamAccount.Status(false, null, null)
        return SteamAccount.Status(true, credentials.steamId64, credentials.accountName)
    }

    override fun changes(since: Long): SteamAccount.Changes = blocking {
        SteamSession.use(context) {
            SteamSession.logOn(context).getOrThrow()
            val apps = SteamSession.apps ?: error("Steam is not connected")
            val answer = apps.picsGetChangesSince(since.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(), true, true).await()
            SteamAccount.Changes(
                current = answer.currentChangeNumber.toLong(),
                apps = answer.appChanges.keys.sorted(),
                packages = answer.packageChanges.keys.sorted(),
                fullUpdateNeeded = answer.isRequiresFullUpdate,
            )
        }
    }

    override fun productInfo(apps: List<Int>, packages: List<Int>): SteamAccount.ProductInfo = blocking {
        SteamSession.use(context) {
            SteamSession.logOn(context).getOrThrow()
            val handler = SteamSession.apps ?: error("Steam is not connected")
            val tokens = handler.picsGetAccessTokens(appIds = apps, packageIds = packages).await()
            val answer = handler.picsGetProductInfo(
                apps = apps.map { PICSRequest(id = it, accessToken = tokens.appTokens[it] ?: 0L) },
                packages = packages.map { PICSRequest(id = it, accessToken = tokens.packageTokens[it] ?: 0L) },
            ).await()
            val appFacts = HashMap<Int, Map<String, String>>()
            val packageFacts = HashMap<Int, Map<String, String>>()
            for (result in answer.results) {
                result.apps.forEach { (id, info) -> appFacts[id] = flatten(info.keyValues) }
                result.packages.forEach { (id, info) -> packageFacts[id] = flatten(info.keyValues) }
            }
            SteamAccount.ProductInfo(appFacts, packageFacts)
        }
    }

    override fun claimFree(apps: List<Int>, packages: List<Int>): List<SteamAccount.Claim> = blocking {
        val claims = ArrayList<SteamAccount.Claim>()
        if (apps.isNotEmpty()) {
            val answer = runCatchingCancellable {
                SteamSession.use(context) {
                    SteamSession.logOn(context).getOrThrow()
                    (SteamSession.apps ?: error("Steam is not connected")).requestFreeLicense(apps).await()
                }
            }
            answer.onSuccess { result ->
                // By name: the enum is generated at build time.
                val limited = result.result.name == "RateLimitExceeded"
                apps.forEach { id ->
                    val granted = id in result.grantedApps
                    claims += SteamAccount.Claim("app", id, granted, if (granted) null else if (limited) "rate_limited" else "not_granted", rateLimited = limited)
                }
            }.onFailure { e -> apps.forEach { claims += SteamAccount.Claim("app", it, false, e.message ?: "failed") } }
        }
        if (packages.isNotEmpty()) {
            val cookies = storeCookies()
            for (id in packages) {
                claims += if (cookies == null) {
                    SteamAccount.Claim("package", id, false, "no_session")
                } else {
                    runCatchingCancellable { addFreeLicense(cookies, id) }.getOrElse { SteamAccount.Claim("package", id, false, it.message ?: "failed") }
                }
                if (claims.last().rateLimited) break
            }
        }
        claims
    }

    override fun redeemKey(key: String): SteamAccount.Redeem = blocking {
        val cookies = storeCookies() ?: return@blocking SteamAccount.Redeem("no_session", emptyList())
        val body = FormBody.Builder().add("product_key", key).add("sessionid", cookies.second).build()
        val request = Request.Builder().url("$STORE/account/ajaxregisterkey/").header("Cookie", cookies.first).post(body).build()
        http().newCall(request).execute().use { response ->
            if (response.code == 429) return@use SteamAccount.Redeem("rate_limited", emptyList(), rateLimited = true)
            check(response.isSuccessful) { "Steam did not answer (HTTP ${response.code})" }
            redeemOf(response.body?.string().orEmpty())
        }
    }

    @Synchronized
    override fun play(apps: List<Int>, untilMs: Long): String? {
        val accountId = SteamCredentials.load(context)?.steamId64?.and(0xFFFFFFFFL)?.toInt() ?: return "Sign in to Steam under Stores first"
        playing?.cancel()
        if (!held) {
            SteamSession.acquire(context)
            held = true
        }
        val sent = runCatchingCancellable {
            blocking {
                SteamSession.logOn(context).getOrThrow()
                val handler = SteamSession.apps ?: error("Steam is not connected")
                handler.notifyGamesPlayed(apps.map { GamePlayedInfo(gameId = it.toLong(), processId = 0, ownerId = accountId, gameBuildId = 0) }, EOSType.WinUnknown)
            }
        }
        if (sent.isFailure) {
            stop()
            return sent.exceptionOrNull()?.message ?: "Steam is not connected"
        }
        playing = scope.launch {
            delay((untilMs - System.currentTimeMillis()).coerceAtLeast(0))
            stop()
        }
        return null
    }

    @Synchronized
    override fun stop() {
        playing?.cancel()
        playing = null
        if (!held) return
        runCatching { SteamSession.apps?.notifyGamesPlayed(emptyList(), EOSType.WinUnknown) }
            .onFailure { Timber.tag(TAG).w(it, "Could not stop the games shown as played") }
        SteamSession.release()
        held = false
    }

    private fun http() = SteamSession.httpClient ?: dev.droidtop.stores.util.StoreHttp.http

    /** The store's cookies with a session id droidtop makes (Steam takes any, matched by the form's), and that id. */
    private suspend fun storeCookies(): Pair<String, String>? {
        val login = store.webSession(context)?.get("steampowered.com") ?: return null
        val sessionId = ByteArray(12).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        return "$login; sessionid=$sessionId" to sessionId
    }

    private fun addFreeLicense(cookies: Pair<String, String>, id: Int): SteamAccount.Claim {
        val body = FormBody.Builder().add("ajax", "true").add("sessionid", cookies.second).build()
        val request = Request.Builder().url("$STORE/checkout/addfreelicense/$id").header("Cookie", cookies.first).post(body).build()
        return http().newCall(request).execute().use { response ->
            freeLicenseOf(id, response.code, response.body?.string().orEmpty())
        }
    }

    internal companion object {
        private const val TAG = "SteamPluginAccount"
        private const val STORE = "https://store.steampowered.com"
        private const val CALL_MS = 30_000L
        private const val MAX_FACTS = 400

        /** A product info tree as one flat map ("common/type" to "Game"), at most [MAX_FACTS] leaves, three levels down. Pure. */
        fun flatten(root: KeyValue): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            fun walk(node: KeyValue, path: String, depth: Int) {
                for (child in node.children) {
                    if (out.size >= MAX_FACTS) return
                    val name = child.name ?: continue
                    val key = if (path.isEmpty()) name else "$path/$name"
                    if (child.children.isEmpty()) {
                        child.value?.let { out[key] = it }
                    } else if (depth < 3) {
                        walk(child, key, depth + 1)
                    }
                }
            }
            walk(root, "", 1)
            return out
        }

        /** Steam's answer to a free package: refused when it says too many, failed on an error page, else claimed. Pure. */
        fun freeLicenseOf(id: Int, code: Int, body: String): SteamAccount.Claim {
            val text = body.lowercase()
            return when {
                code == 429 || "too many" in text || "rate limit" in text -> SteamAccount.Claim("package", id, false, "rate_limited", rateLimited = true)
                code !in 200..299 -> SteamAccount.Claim("package", id, false, "http_$code")
                "\"success\":false" in text.replace(" ", "") || "error_box" in text -> SteamAccount.Claim("package", id, false, "refused")
                else -> SteamAccount.Claim("package", id, true)
            }
        }

        /** Steam's answer to a key, as its result word and the items it added. Pure. */
        fun redeemOf(json: String): SteamAccount.Redeem {
            val root = runCatching { JSONObject(json) }.getOrNull() ?: return SteamAccount.Redeem("failed", emptyList())
            val items = root.optJSONObject("purchase_receipt_info")?.optJSONArray("line_items")?.let { list ->
                (0 until list.length()).mapNotNull { list.optJSONObject(it)?.optString("line_item_description")?.takeIf { d -> d.isNotBlank() } }
            }.orEmpty()
            if (root.optInt("success", 0) == 1) return SteamAccount.Redeem("ok", items)
            val result = when (root.optInt("purchase_result_details", -1)) {
                9 -> "already_owned"
                13 -> "region_locked"
                14 -> "invalid"
                15 -> "already_redeemed"
                24 -> "needs_base_game"
                53 -> "rate_limited"
                else -> "failed"
            }
            return SteamAccount.Redeem(result, items, rateLimited = result == "rate_limited")
        }
    }
}
