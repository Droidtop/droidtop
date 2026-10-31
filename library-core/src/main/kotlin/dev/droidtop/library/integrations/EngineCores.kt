package dev.droidtop.library.integrations

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import dev.droidtop.library.EnginehostCapabilities
import dev.droidtop.pluginhost.PluginBundleInstaller
import dev.droidtop.pluginhost.PluginCrashPolicy
import dev.droidtop.pluginhost.PluginKind
import dev.droidtop.pluginhost.PluginStore
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * One engine core an app can run games with (docs/plugin-api.md 3 A7b, `engines.cores@1`, Droidtop/tracker#752):
 * installed, or one its catalog can install. [rows] are the ids of the engines-database rows whose `enginehost`
 * mapping it serves, so droidtop matches its own detection to a core without a second copy of the rules.
 */
data class EngineCore(
    val id: String,
    val engine: String,
    val label: String,
    val rows: List<String>,
    val series: List<String>,
    val anyVersion: Boolean,
    val installed: Boolean,
    val installedVersion: String?,
    val availableVersion: String?,
    val updateAvailable: Boolean,
) {
    /** Whether this core runs a game of engine-version [version]; null (not known) matches on the row alone. */
    fun runs(version: String?): Boolean =
        anyVersion || version == null || series.isEmpty() || series.any { EnginehostCapabilities.seriesCovers(it, version) }
}

/** What one app's `list_cores` said, and when its catalog part was last checked (0: never, so "none" proves nothing). */
data class EngineCoreAnswer(val cores: List<EngineCore>, val catalogCheckedAtMs: Long)

/** What the core lists say about one game's engine row: a core installed for it, one that can be installed, or none. */
sealed class CoreVerdict {
    data class Installed(val core: EngineCore) : CoreVerdict()

    data class Installable(val core: EngineCore) : CoreVerdict()

    /** No installed core serves the row and a checked catalog offers none either. */
    data object None : CoreVerdict()
}

/**
 * The replies of `engines.cores@1` read into [EngineCore]s, and the one rule that turns them into a [CoreVerdict].
 * Capped and given defaults here, so an app that sends too much or the wrong shape never reaches a runner decision as
 * anything but ordinary rows. Pure, so the tests read it without a device.
 */
object EngineCoresProtocol {
    const val POINT = "engines.cores"
    const val LIST_OP = "list_cores"
    const val MAX_CORES = 200
    const val MAX_LABEL = 80

    fun answer(data: JSONObject): EngineCoreAnswer {
        val list = data.optJSONArray("cores") ?: JSONArray()
        val seen = HashSet<String>()
        val cores = ArrayList<EngineCore>()
        for (i in 0 until list.length()) {
            if (cores.size >= MAX_CORES) break
            val c = list.optJSONObject(i) ?: continue
            val id = c.optString("id").trim().takeIf { it.isNotEmpty() } ?: continue
            if (!seen.add(id)) continue
            val engine = c.optString("engine").trim()
            val versions = c.optJSONObject("engineVersions")
            val installed = c.optBoolean("installed", false)
            cores += EngineCore(
                id = id,
                engine = engine,
                label = c.optString("label").trim().take(MAX_LABEL).ifEmpty { engine.ifEmpty { id } },
                rows = strings(c.optJSONArray("rows")),
                series = strings(versions?.optJSONArray("series")),
                anyVersion = versions?.optBoolean("any", false) == true,
                installed = installed,
                installedVersion = text(c, "installedVersion").takeIf { installed },
                availableVersion = text(c, "availableVersion"),
                updateAvailable = installed && c.optBoolean("updateAvailable", false),
            )
        }
        return EngineCoreAnswer(cores, data.optLong("catalogCheckedAtMs", 0L).coerceAtLeast(0L))
    }

    /**
     * The verdict for a game of engines-database row [rowId] at engine-version [version] over every app's answer, or
     * null when the lists cannot decide (none read, or nothing serves the row and no catalog was ever checked): the
     * caller then keeps what it measured before. An installed core wins over an installable one; among installable
     * ones the first listed.
     */
    /**
     * Whether a plugin's last list is kept when it cannot be asked now (Droidtop/tracker#854). [installed]: its record
     * is still in droidtop's plugins; [declares]: whether that record still provides [POINT], null when droidtop cannot
     * read the record (an app plugin made for another droidtop). Only a plugin removed, or one that no longer
     * provides the point, loses its list: Enginehost waiting for approval, turned off or needing an update keeps the
     * cores it listed, so a game page does not offer "Get … support" for a core that is installed. Pure.
     */
    fun keepsUnasked(installed: Boolean, declares: Boolean?): Boolean = installed && declares != false

    fun verdict(answers: Collection<EngineCoreAnswer>, rowId: String, version: String?): CoreVerdict? {
        if (answers.isEmpty()) return null
        val serving = answers.flatMap { it.cores }.filter { rowId in it.rows && it.runs(version) }
        serving.firstOrNull { it.installed }?.let { return CoreVerdict.Installed(it) }
        serving.firstOrNull { it.availableVersion != null }?.let { return CoreVerdict.Installable(it) }
        return if (answers.any { it.catalogCheckedAtMs > 0 }) CoreVerdict.None else null
    }

    fun toJson(answer: EngineCoreAnswer): JSONObject {
        val cores = JSONArray()
        answer.cores.forEach { core ->
            cores.put(
                JSONObject()
                    .put("id", core.id)
                    .put("engine", core.engine)
                    .put("label", core.label)
                    .put("rows", JSONArray(core.rows))
                    .put("engineVersions", JSONObject().put("series", JSONArray(core.series)).put("any", core.anyVersion))
                    .put("installed", core.installed)
                    .apply { core.installedVersion?.let { put("installedVersion", it) } }
                    .apply { core.availableVersion?.let { put("availableVersion", it) } }
                    .put("updateAvailable", core.updateAvailable),
            )
        }
        return JSONObject().put("cores", cores).put("catalogCheckedAtMs", answer.catalogCheckedAtMs)
    }

    private fun text(json: JSONObject, key: String): String? =
        json.optString(key).trim().takeIf { json.has(key) && !json.isNull(key) && it.isNotEmpty() }

    private fun strings(array: JSONArray?): List<String> =
        if (array == null) emptyList() else (0 until array.length()).mapNotNull { array.optString(it).trim().takeIf { s -> s.isNotEmpty() } }
}

/**
 * The engine cores each app's plugin last listed (docs/plugin-api.md 3 A7b), kept in memory and in one small file so a
 * runner decision made before the app connects still has them. Asked when the app connects through droidtop's door,
 * when it says its list changed (`engines report_changed`) and when the app starts, never on a timer.
 */
object EngineCoreList {
    private const val FILE = "engine-cores.json"
    private const val BUDGET_MS = 5_000L
    private const val SURFACE = "engines"

    private val answers = ConcurrentHashMap<String, EngineCoreAnswer>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    @Volatile private var loaded = false

    /** Every app's last answer, keyed by plugin id. */
    fun answers(context: Context): Map<String, EngineCoreAnswer> {
        load(context)
        return answers
    }

    /** What the lists say for a game of engines-database row [rowId] at [version]; see [EngineCoresProtocol.verdict]. */
    fun verdict(context: Context, rowId: String, version: String?): CoreVerdict? =
        EngineCoresProtocol.verdict(answers(context).values, rowId, version)

    /** Asks [pluginId] for its list again, off the caller's thread: a host call or the door must not wait on the app. */
    fun changed(context: Context, pluginId: String): Boolean {
        val app = context.applicationContext
        scope.launch { refresh(app, pluginId) }
        return true
    }

    /** Asks every running app plugin that provides [EngineCoresProtocol.POINT], one after another. */
    fun refreshAll(context: Context) {
        val app = context.applicationContext
        scope.launch {
            providersOf(app, EngineCoresProtocol.POINT)
                .filter { (record, _) -> record.manifest.kind == PluginKind.APP_INTERFACE }
                .map { it.first.manifest.id }
                .distinct()
                .forEach { refresh(app, it) }
        }
    }

    /**
     * One `list_cores` to [pluginId]; a failed call keeps its last answer. A plugin that cannot be asked now keeps it
     * too, unless it is removed or no longer provides the point ([EngineCoresProtocol.keepsUnasked]).
     */
    suspend fun refresh(context: Context, pluginId: String) {
        lock.withLock {
            load(context)
            val record = providersOf(context, EngineCoresProtocol.POINT)
                .firstOrNull { (record, _) -> record.manifest.id == pluginId && record.manifest.kind == PluginKind.APP_INTERFACE }
                ?.first
            if (record == null) {
                val root = PluginStore.root(context)
                val installed = File(root, "$pluginId/record.json").isFile
                val declares = PluginBundleInstaller.readRecord(root, pluginId)?.manifest?.v2?.provides?.any { it.point == EngineCoresProtocol.POINT }
                if (EngineCoresProtocol.keepsUnasked(installed, declares)) {
                    if (answers.containsKey(pluginId)) Log.i("droidtop.engines", "$pluginId cannot be asked now; its last list of engine cores is kept")
                    return
                }
                if (answers.remove(pluginId) != null) save(context)
                return
            }
            val policy = PluginCrashPolicy(context)
            val reply = try {
                policy.handle(
                    record,
                    newCall(EngineCoresProtocol.POINT, EngineCoresProtocol.LIST_OP, SURFACE, JSONObject(), BUDGET_MS),
                    timeoutMs = BUDGET_MS,
                    crashOnTimeout = false,
                    userInitiated = false,
                )
            } finally {
                policy.shutdown()
            }
            if (!reply.ok) {
                Log.w("droidtop.engines", "$pluginId did not list its engine cores: ${reply.message}")
                return
            }
            answers[pluginId] = EngineCoresProtocol.answer(reply.data)
            save(context)
        }
    }

    private fun file(context: Context) = AtomicFile(File(context.filesDir, FILE))

    private fun load(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            runCatching {
                val json = JSONObject(String(file(context).readFully(), Charsets.UTF_8))
                json.keys().forEach { id -> json.optJSONObject(id)?.let { answers[id] = EngineCoresProtocol.answer(it) } }
            }
            loaded = true
        }
    }

    private fun save(context: Context) {
        val json = JSONObject()
        answers.forEach { (id, answer) -> json.put(id, EngineCoresProtocol.toJson(answer)) }
        val target = file(context)
        val out = try {
            target.startWrite()
        } catch (e: java.io.IOException) {
            Log.w("droidtop.engines", "could not keep the engine core lists: ${e.message}")
            return
        }
        try {
            out.write(json.toString().toByteArray(Charsets.UTF_8))
            target.finishWrite(out)
        } catch (e: java.io.IOException) {
            target.failWrite(out)
            Log.w("droidtop.engines", "could not keep the engine core lists: ${e.message}")
        }
    }
}
