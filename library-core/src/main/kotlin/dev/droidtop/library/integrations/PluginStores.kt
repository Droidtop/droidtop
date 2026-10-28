package dev.droidtop.library.integrations

import android.content.Context
import dev.droidtop.library.stores.StoreChanges
import dev.droidtop.library.stores.StoreGame
import dev.droidtop.library.stores.StoreHolding
import dev.droidtop.library.stores.StoreLibraries
import dev.droidtop.library.stores.StoreLibrary
import dev.droidtop.library.stores.StoreProgress
import dev.droidtop.library.stores.StoreSignIn
import dev.droidtop.library.stores.StoreSignInKind
import dev.droidtop.pluginhost.PluginCrashPolicy
import dev.droidtop.pluginhost.PluginRecord
import dev.droidtop.pluginhost.PluginWebAccount
import dev.droidtop.pluginhost.ProvidedPoint
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stores a plugin provides (docs/plugin-api.md 3 A13, `library.stores@1`, Droidtop/tracker#220): each running plugin's
 * `library.stores` entry becomes one [StoreLibrary] registered beside droidtop's own, so its games join the PC library,
 * the automatic sync and every place that reads [StoreLibraries], the way a built-in store's do. The plugin answers
 * for the account and the library; droidtop keeps the rows, draws everything, and signs the person in with the
 * plugin's own site account (`web.session`), never seeing a password.
 */
object PluginStores {
    const val POINT = "library.stores"

    /** A store id: what a library id's store half is ("mystore:123"), so no colon, and not one of droidtop's own. */
    val STORE_ID = Regex("^[a-z][a-z0-9]{1,23}$")

    private val registered = HashSet<String>()

    /** The store ids among [entries] a plugin may take: well formed, not [taken] by another store, the first of each. Pure. */
    internal fun usable(entries: List<String?>, taken: Set<String>): List<String> {
        val seen = HashSet<String>()
        return entries.mapNotNull { id -> id?.takeIf { STORE_ID.matches(it) && it !in taken && seen.add(it) } }
    }

    /**
     * Registers a store for every running plugin's `library.stores` entry and drops the ones whose plugin is gone or
     * stopped. Called at start, after a change to the plugins, and before each automatic sync. Disk; off the main thread.
     */
    @Synchronized
    fun refresh(context: Context) {
        val app = context.applicationContext
        val providers = providersOf(app, POINT)
        val builtIn = StoreLibraries.all().map { it.id }.filter { it !in registered }.toSet()
        val ids = usable(providers.map { it.second.id }, builtIn)
        val wanted = ids.mapNotNull { id -> providers.firstOrNull { it.second.id == id }?.let { (record, entry) -> PluginStoreLibrary(app, record, entry) } }
        (registered - wanted.map { it.id }.toSet()).forEach { StoreLibraries.unregister(it) }
        wanted.forEach { StoreLibraries.register(it) }
        val changed = registered != wanted.map { it.id }.toSet()
        registered.clear()
        registered.addAll(wanted.map { it.id })
        if (changed) StoreChanges.announce(app)
    }

    /** The rows a `library` answer gives, for [store]: each game once, an id and a title required. Pure. */
    internal fun gamesOf(store: String, reply: JSONObject): List<StoreGame> {
        val rows = reply.optJSONArray("games") ?: return emptyList()
        val seen = HashSet<String>()
        return (0 until rows.length()).mapNotNull { i ->
            val row = rows.optJSONObject(i) ?: return@mapNotNull null
            val id = row.optString("id").trim().takeIf { it.isNotEmpty() && !it.contains('\n') && it.length <= 128 && seen.add(it) } ?: return@mapNotNull null
            val title = row.optString("title").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            StoreGame(
                store = store,
                gameId = id,
                title = title.take(200),
                installed = false,
                installPath = null,
                sizeBytes = row.optLong("size", 0L).coerceAtLeast(0L),
                artUrl = row.optString("art").takeIf { it.startsWith("https://") },
                installedVersion = null,
                externalIds = row.optJSONObject("externalIds")?.let { ids ->
                    ids.keys().asSequence().mapNotNull { key -> ids.optString(key).takeIf { it.isNotBlank() }?.let { key to it } }.toMap()
                }.orEmpty(),
                holding = when (row.optString("holding")) {
                    "family" -> StoreHolding.FAMILY
                    "free" -> StoreHolding.FREE
                    "wishlist" -> StoreHolding.WISHLIST
                    else -> StoreHolding.OWNED
                },
            )
        }
    }

    internal fun rowsJson(games: List<StoreGame>): String = JSONArray(games.map { g ->
        JSONObject().put("id", g.gameId).put("title", g.title).put("size", g.sizeBytes).putOpt("art", g.artUrl)
            .put("externalIds", JSONObject(g.externalIds as Map<*, *>)).put("holding", g.holding.name.lowercase())
    }).toString()
}

/**
 * One plugin's store (docs/plugin-api.md 3 A13). Its rows are droidtop's own copy of the plugin's last `library`
 * answer, in droidtop's files; nothing here asks the plugin to draw a list. Installing is not built yet (slice 2).
 */
class PluginStoreLibrary internal constructor(
    private val app: Context,
    private val record: PluginRecord,
    private val entry: ProvidedPoint,
) : StoreLibrary {
    override val id: String = requireNotNull(entry.id)

    override val label: String = entry.label?.takeIf { it.isNotBlank() } ?: record.manifest.label

    override val siteUrl: String? = runCatching { JSONObject(entry.extra).optString("site") }.getOrNull()?.takeIf { it.startsWith("https://") }

    private fun file(context: Context) = File(File(context.applicationContext.filesDir, "plugin-stores"), "$id.json")

    override fun signedIn(context: Context): Boolean = PluginWebAccount.of(context, record)?.signedIn == true

    override fun accountName(context: Context): String? = PluginWebAccount.of(context, record)?.name

    /** The plugin's own site account: droidtop's web view on the plugin's sign-in page, as its account row does. */
    override val signInKind: StoreSignInKind get() = StoreSignInKind.WEB_PAGE

    override fun signIn(context: Context): StoreSignIn = StoreSignIn.Delegated { ctx ->
        val signedIn = withContext(Dispatchers.IO) { PluginWebAccount.open(ctx, record, PluginWebAccount.Need.SIGN_IN) }
        if (signedIn) sync(ctx)
        signedIn
    }

    override suspend fun completeSignIn(context: Context, secret: String): Result<String?> =
        Result.failure(UnsupportedOperationException("$label signs in on its own page"))

    override suspend fun signOut(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        PluginWebAccount.signOut(context, record)
        file(context).delete()
        Result.success(Unit)
    }

    override suspend fun sync(context: Context): Result<Int> = withContext(Dispatchers.IO) {
        val policy = PluginCrashPolicy(context.applicationContext)
        try {
            val reply = policy.handle(record, newCall(PluginStores.POINT, "library", "library.stores", JSONObject().put("store", id), SYNC_MS), timeoutMs = SYNC_MS, crashOnTimeout = false, userInitiated = false)
            if (!reply.ok) return@withContext Result.failure(IllegalStateException(reply.message ?: "$label did not answer"))
            val games = PluginStores.gamesOf(id, reply.data)
            val target = file(context)
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(PluginStores.rowsJson(games))
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            StoreChanges.announce(context)
            Result.success(games.size)
        } catch (e: Exception) {
            Result.failure(IllegalStateException("$label did not answer: ${e.message ?: "no reason given"}"))
        } finally {
            policy.shutdown()
        }
    }

    override suspend fun games(context: Context): List<StoreGame> = withContext(Dispatchers.IO) {
        val text = runCatching { file(context).readText() }.getOrNull() ?: return@withContext emptyList()
        PluginStores.gamesOf(id, JSONObject().put("games", runCatching { JSONArray(text) }.getOrDefault(JSONArray())))
    }

    override suspend fun install(context: Context, gameId: String, root: File, progress: StoreProgress): String =
        throw IllegalStateException("Installing from $label is not built yet")

    override suspend fun uninstall(context: Context, gameId: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("Nothing from $label is installed by droidtop yet"))

    override fun changeStamp(context: Context): Long = file(context).lastModified()

    private companion object {
        const val SYNC_MS = 60_000L
    }
}
