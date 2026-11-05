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
import dev.droidtop.library.stores.StoreLaunch
import dev.droidtop.library.stores.StoreUpdateCheck
import dev.droidtop.library.StoreUpdate
import dev.droidtop.net.AtomicWrite
import dev.droidtop.pluginhost.AcquireDownloads
import dev.droidtop.pluginhost.PluginCrashPolicy
import dev.droidtop.pluginhost.PluginRecord
import dev.droidtop.pluginhost.PluginWebAccount
import dev.droidtop.pluginhost.ProvidedPoint
import dev.droidtop.runtime.SafeDelete
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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

    private val refreshScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    private val refreshQueued = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * [refresh] in the background, for callers on any thread (start, a change to the plugins). Calls that come while
     * one is still waiting to run join it; one that comes while a refresh runs queues one more after it, so the last
     * change is always read. A failure is logged and changes nothing.
     */
    fun refreshAsync(context: Context) {
        if (!refreshQueued.compareAndSet(false, true)) return
        val app = context.applicationContext
        refreshScope.launch {
            refreshQueued.set(false)
            try {
                refresh(app)
            } catch (e: Exception) {
                android.util.Log.w("droidtop.stores", "plugin stores refresh", e)
            }
        }
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

    /**
     * Where droidtop put a store's game ([PluginStoreLibrary.install]): its folder, whether the download finished
     * there, and the version it installed when the plugin named one.
     */
    internal data class Install(val path: String, val installed: Boolean, val version: String? = null)

    /** A version as a plugin may name one: one line, at most 64 characters; null for none. Pure. */
    internal fun versionOf(raw: Any?): String? =
        (raw as? String)?.trim()?.takeIf { it.isNotEmpty() && it.length <= 64 && !it.contains('\n') }

    /** The version each row of a `library` answer names (`version`), by game id. Pure. */
    internal fun versionsOf(reply: JSONObject): Map<String, String> {
        val rows = reply.optJSONArray("games") ?: return emptyMap()
        return (0 until rows.length()).mapNotNull { i ->
            val row = rows.optJSONObject(i) ?: return@mapNotNull null
            val id = row.optString("id").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            versionOf(row.opt("version"))?.let { id to it }
        }.toMap()
    }

    /**
     * Whether the version installed is the one the store lists now: a different one is an update, by the plugin's
     * word (droidtop does not order versions it did not make). Null (not known) when either side names none. Pure.
     */
    internal fun updateOf(installed: String?, listed: String?): StoreUpdateCheck? {
        if (installed.isNullOrBlank() || listed.isNullOrBlank()) return null
        return StoreUpdateCheck(if (installed == listed) StoreUpdate.CURRENT else StoreUpdate.AVAILABLE, listed)
    }

    /**
     * A `launch` answer (`{exe, args?, cwd?}`) as a start inside [folder], the game's installed folder: `exe` and `cwd`
     * are paths in it, never out of it (no `..`, not absolute, no drive), `args` a list of strings. Null for an answer
     * that names no program there. Pure.
     */
    internal fun launchOf(answer: JSONObject, folder: File): StoreLaunch? {
        fun inside(rel: String?): File? {
            val text = rel?.trim()?.replace('\\', '/')?.takeIf { it.isNotEmpty() } ?: return null
            if (text.startsWith("/") || text.contains(':')) return null
            val parts = text.split('/').filter { it.isNotEmpty() && it != "." }
            if (parts.isEmpty() || parts.any { it == ".." }) return null
            return parts.fold(folder) { at, part -> File(at, part) }
        }
        val exe = inside(answer.optString("exe")) ?: return null
        val cwd = answer.optString("cwd").takeIf { it.isNotBlank() }?.let { inside(it) ?: return null } ?: exe.parentFile ?: folder
        val args = answer.optJSONArray("args")?.let { array -> (0 until array.length()).mapNotNull { array.opt(it) as? String } }.orEmpty()
        return StoreLaunch(exe, cwd, args)
    }

    /** The installs file's records by game id; a record that is not one is dropped. Pure. */
    internal fun installsOf(text: String?): Map<String, Install> {
        val json = text?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return emptyMap()
        return json.keys().asSequence().mapNotNull { id ->
            val row = json.optJSONObject(id) ?: return@mapNotNull null
            val path = row.optString("path").takeIf { it.startsWith("/") } ?: return@mapNotNull null
            id to Install(path, row.optBoolean("installed"), versionOf(row.opt("version")))
        }.toMap()
    }

    internal fun installsJson(installs: Map<String, Install>): String = JSONObject().also { json ->
        installs.forEach { (id, install) -> json.put(id, JSONObject().put("path", install.path).put("installed", install.installed).putOpt("version", install.version)) }
    }.toString()

    /** [games] with what droidtop installed laid over them: a finished install is installed, at its folder. Pure. */
    internal fun withInstalls(games: List<StoreGame>, installs: Map<String, Install>): List<StoreGame> = games.map { game ->
        val install = installs[game.gameId]?.takeIf { it.installed } ?: return@map game
        game.copy(installed = true, installPath = install.path, installedVersion = install.version)
    }

    private val UNSAFE = Regex("[^A-Za-z0-9 ._-]")
    private val SPACES = Regex(" {2,}")

    /**
     * The folder a new install of [title] goes in under [root], as droidtop's own stores choose it: the title with what
     * a card's filesystem refuses dropped ([fallback] when nothing is left), or "Name (2)" and on when a folder of that
     * name is there and not empty. A failed install's cleanup deletes the folder it was given, so it is never handed
     * one droidtop did not make. Disk; off the main thread.
     */
    internal fun freshFolder(root: File, title: String, fallback: String): File {
        val name = title.replace(UNSAFE, "").replace(SPACES, " ").trim().trimEnd('.').ifBlank { fallback }
        var candidate = File(root, name)
        var n = 2
        while (candidate.exists() && !(candidate.isDirectory && candidate.list().isNullOrEmpty())) {
            candidate = File(root, "$name ($n)")
            n++
        }
        return candidate
    }

    internal fun rowsJson(games: List<StoreGame>, versions: Map<String, String> = emptyMap()): String = JSONArray(games.map { g ->
        JSONObject().put("id", g.gameId).put("title", g.title).put("size", g.sizeBytes).putOpt("art", g.artUrl)
            .put("externalIds", JSONObject(g.externalIds as Map<*, *>)).put("holding", g.holding.name.lowercase())
            .putOpt("version", versions[g.gameId])
    }).toString()
}

/**
 * One plugin's store (docs/plugin-api.md 3 A13). Its rows are droidtop's own copy of the plugin's last `library`
 * answer, in droidtop's files; nothing here asks the plugin to draw a list. Installing asks the plugin where the game's
 * files are (`install`, answered as a source's acquire is) and droidtop fetches them into the store's folder.
 */
class PluginStoreLibrary internal constructor(
    private val app: Context,
    private val record: PluginRecord,
    private val entry: ProvidedPoint,
) : StoreLibrary {
    override val id: String = requireNotNull(entry.id)

    override val label: String = entry.label?.takeIf { it.isNotBlank() } ?: record.manifest.label

    override val siteUrl: String? = runCatching { JSONObject(entry.extra).optString("site") }.getOrNull()?.takeIf { it.startsWith("https://") }

    /** The plugin that provides this store, whose page the store's page in Stores links to. */
    val pluginId: String get() = record.manifest.id

    /** The plugin's own label, for that link. */
    val pluginLabel: String get() = record.manifest.label

    /** Whether the plugin starts its games itself (`"launch": true` in its `library.stores` entry). */
    private val launches: Boolean = runCatching { JSONObject(entry.extra).optBoolean("launch", false) }.getOrDefault(false)

    private fun file(context: Context) = File(File(context.applicationContext.filesDir, "plugin-stores"), "$id.json")

    /** What droidtop installed from this store ([PluginStores.Install]), kept apart from the rows a sync replaces. */
    private fun installsFile(context: Context) = File(File(context.applicationContext.filesDir, "plugin-stores"), "$id.installs.json")

    private fun installs(context: Context): Map<String, PluginStores.Install> =
        PluginStores.installsOf(runCatching { installsFile(context).readText() }.getOrNull())

    private fun writeInstalls(context: Context, installs: Map<String, PluginStores.Install>) {
        AtomicWrite.text(installsFile(context), PluginStores.installsJson(installs))
    }

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
            AtomicWrite.text(file(context), PluginStores.rowsJson(games, PluginStores.versionsOf(reply.data)))
            StoreChanges.announce(context)
            Result.success(games.size)
        } catch (e: Exception) {
            Result.failure(IllegalStateException("$label did not answer: ${e.message ?: "no reason given"}"))
        } finally {
            policy.shutdown()
        }
    }

    /** The kept copy of the last `library` answer, as that answer's own shape. Disk. */
    private fun keptReply(context: Context): JSONObject? {
        val text = runCatching { file(context).readText() }.getOrNull() ?: return null
        return JSONObject().put("games", runCatching { JSONArray(text) }.getOrDefault(JSONArray()))
    }

    override suspend fun games(context: Context): List<StoreGame> = withContext(Dispatchers.IO) {
        val reply = keptReply(context) ?: return@withContext emptyList()
        PluginStores.withInstalls(PluginStores.gamesOf(id, reply), installs(context))
    }

    /**
     * The version installed against the one the store's last `library` answer names (`version`): a different one is
     * an update, which installs over the game's folder. Unknown when either names none. No call to the plugin.
     */
    override suspend fun checkUpdate(context: Context, gameId: String): StoreUpdateCheck? = withContext(Dispatchers.IO) {
        val install = installs(context)[gameId]?.takeIf { it.installed } ?: return@withContext null
        val listed = keptReply(context)?.let { PluginStores.versionsOf(it)[gameId] }
        PluginStores.updateOf(install.version, listed)
    }

    /**
     * How the plugin starts [gameId], for a store whose entry says it does (`"launch": true`): `launch {store, id}`
     * answered with a program in the game's installed folder ([PluginStores.launchOf]). Null for any other store, a
     * game not installed here, or an answer that names nothing there: droidtop then finds the executable itself.
     */
    override suspend fun launch(context: Context, gameId: String): StoreLaunch? {
        if (!launches) return null
        val folder = withContext(Dispatchers.IO) { installs(context)[gameId]?.takeIf { it.installed }?.path?.let(::File) } ?: return null
        return withContext(Dispatchers.IO) {
            val policy = PluginCrashPolicy(context.applicationContext)
            try {
                val call = newCall(PluginStores.POINT, "launch", "library.stores", JSONObject().put("store", id).put("id", gameId), LAUNCH_MS)
                val reply = policy.handle(record, call, timeoutMs = LAUNCH_MS, crashOnTimeout = false, userInitiated = true)
                if (reply.ok) PluginStores.launchOf(reply.data, folder) else null
            } catch (e: Exception) {
                android.util.Log.w("droidtop.stores", "$label gave no launch for $gameId", e)
                null
            } finally {
                policy.shutdown()
            }
        }
    }

    override suspend fun installedPath(context: Context, gameId: String): String? = withContext(Dispatchers.IO) {
        installs(context)[gameId]?.takeIf { it.installed }?.path
    }

    /**
     * Asks the plugin where [gameId]'s files are (`install {store, id}`, a job answered with `download` or `downloads` as
     * a source's acquire is, docs/plugin-api.md 3 A13) and fetches them with Downloads into a new folder for the game
     * under [root]. The folder is recorded before the download, so a Cancel knows which one is unfinished. An installed
     * game is updated the same way, into its own folder: it stays recorded as installed (at its old version) until the
     * new files are all there, so a failed update never makes Cancel remove the game. The version kept is the one the
     * `install` answer names (`version`), else the one its row named.
     */
    override suspend fun install(context: Context, gameId: String, root: File, progress: StoreProgress): String {
        val game = games(context).firstOrNull { it.gameId == gameId } ?: error("$label no longer lists this game")
        val folder = withContext(Dispatchers.IO) {
            val known = installs(context)[gameId]
            if (known?.installed == true) return@withContext File(known.path)
            val earlier = known?.path?.let(::File)
            val folder = earlier?.takeIf { it.parentFile == root } ?: PluginStores.freshFolder(root, game.title, "$id-$gameId")
            writeInstalls(context, installs(context) + (gameId to PluginStores.Install(folder.absolutePath, installed = false)))
            folder
        }
        val listed = withContext(Dispatchers.IO) { keptReply(context)?.let { PluginStores.versionsOf(it)[gameId] } }
        progress.report(-1f, "Asking $label for ${game.title}")
        val answer = PluginViews.runJob(
            context, record, PluginStores.POINT, "install", JSONObject().put("store", id).put("id", gameId), game.title,
            onStatus = { line -> progress.report(-1f, line) },
        )
        check(answer.ok) { answer.error ?: "$label did not say where ${game.title} is" }
        val descriptors = AcquireDownloads.parse(answer.values)?.takeIf { it.isNotEmpty() }
            ?: error("$label did not give a download for ${game.title}")
        val fetched = AcquireDownload.run(
            context = context,
            record = record,
            title = game.title,
            hostContext = JSONObject().put("destination", folder.absolutePath),
            // A store's game goes in the store's folder, whatever system a source would hint.
            values = answer.values - AcquireSystemHint.KEY,
            descriptors = descriptors,
            onStatus = { line -> progress.report(-1f, line) },
            onPercent = { percent -> if (percent >= 0) progress.report(percent / 100f, "Downloading ${game.title}") },
        )
        check(fetched.ok) { fetched.error ?: "the download of ${game.title} failed" }
        val version = PluginStores.versionOf(answer.values["version"]) ?: listed
        withContext(Dispatchers.IO) {
            writeInstalls(context, installs(context) + (gameId to PluginStores.Install(folder.absolutePath, installed = true, version = version)))
        }
        StoreChanges.announce(context)
        return "Installed ${game.title}"
    }

    override suspend fun discardPartial(context: Context, gameId: String) {
        withContext(Dispatchers.IO) {
            val install = installs(context)[gameId]?.takeIf { !it.installed } ?: return@withContext
            val folder = File(install.path)
            folder.parentFile?.let { SafeDelete.deleteWithin(it, folder) }
            writeInstalls(context, installs(context) - gameId)
        }
    }

    override suspend fun uninstall(context: Context, gameId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val install = installs(context)[gameId] ?: return@withContext Result.failure(IllegalStateException("droidtop did not install this game from $label"))
        runCatching {
            val folder = File(install.path)
            val parent = folder.parentFile ?: error("${install.path} is not a game folder")
            if (folder.exists()) check(SafeDelete.deleteWithin(parent, folder)) { "Could not remove ${install.path}" }
            writeInstalls(context, installs(context) - gameId)
            StoreChanges.announce(context)
        }
    }

    override fun changeStamp(context: Context): Long = maxOf(file(context).lastModified(), installsFile(context).lastModified())

    private companion object {
        const val SYNC_MS = 60_000L

        /** How long a launch waits for the plugin's answer before droidtop finds the program itself. */
        const val LAUNCH_MS = 10_000L
    }
}
