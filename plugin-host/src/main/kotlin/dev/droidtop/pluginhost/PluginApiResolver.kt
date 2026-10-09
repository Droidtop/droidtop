package dev.droidtop.pluginhost

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONObject

/** One runnable plugin's export of an API (docs/plugin-api.md 2.3). */
data class ApiProvider(val plugin: PluginRecord, val export: ExportedApi)

/**
 * The exports/requires graph over the runnable plugins (docs/plugin-api.md
 * 2.3): who provides each API, and which plugins are **Waiting** because a
 * required API has no runnable provider. Waiting is not disabled: it ends
 * by itself when a provider appears.
 */
data class ApiResolution(
    /** Runnable providers per API id, sorted by plugin id: the order of the first default. */
    val providers: Map<String, List<ApiProvider>>,
    /** Plugin id to the required (not optional) APIs no runnable provider satisfies. */
    val waiting: Map<String, List<RequiredApi>>,
) {
    fun isWaiting(pluginId: String): Boolean = waiting.containsKey(pluginId)
}

object PluginApiResolver {
    /** Levels a `priv.shell` provider states in its `level` attribute, weakest first; a caller's `minLevel` excludes providers below it. */
    private val LEVELS = listOf("adb", "root")

    /** `1.1` asks for major 1, minor at least 1 (docs/plugin-api.md 2.2). */
    fun versionSatisfies(required: String, offered: String): Boolean {
        val (rMajor, rMinor) = parse(required) ?: return false
        val (oMajor, oMinor) = parse(offered) ?: return false
        return rMajor == oMajor && oMinor >= rMinor
    }

    private fun parse(version: String): Pair<Int, Int>? {
        val parts = version.split('.')
        val major = parts.getOrNull(0)?.toIntOrNull() ?: return null
        return major to (parts.getOrNull(1)?.toIntOrNull() ?: 0)
    }

    /** True when [export] serves a caller that wants [required]: same API, compatible version, and a `level` attribute at or above `minLevel`. */
    fun satisfies(export: ExportedApi, required: RequiredApi): Boolean {
        if (export.api != required.api || !versionSatisfies(required.version, export.version)) return false
        val minLevel = required.minLevel ?: return true
        val level = runCatching { JSONObject(export.attributes).optString("level") }.getOrDefault("")
        val have = LEVELS.indexOf(level)
        val need = LEVELS.indexOf(minLevel)
        return have >= 0 && need >= 0 && have >= need
    }

    /**
     * Recomputes the graph. A plugin that is Waiting stops providing, which
     * can make another one Waiting, so this runs to a fixed point.
     * [exportAllowed] says whether a plugin's export is switched on (an
     * update's new export waits for the user).
     */
    fun resolve(records: List<PluginRecord>, exportAllowed: (pluginId: String, export: ExportedApi) -> Boolean = { _, _ -> true }): ApiResolution {
        var active = records.filter { it.runnable() }
        var providers: Map<String, List<ApiProvider>> = emptyMap()
        val waitingAll = linkedMapOf<String, List<RequiredApi>>()
        while (true) {
            providers = active
                .flatMap { rec -> rec.manifest.v2.exports.filter { exportAllowed(rec.manifest.id, it) }.map { ApiProvider(rec, it) } }
                .sortedBy { it.plugin.manifest.id }
                .groupBy { it.export.api }
            val current = providers
            val waiting = buildMap {
                for (rec in active) {
                    val missing = rec.manifest.v2.requires.filter { req ->
                        !req.optional && current[req.api].orEmpty().none { it.plugin.manifest.id != rec.manifest.id && satisfies(it.export, req) }
                    }
                    if (missing.isNotEmpty()) put(rec.manifest.id, missing)
                }
            }
            waitingAll.putAll(waiting)
            val next = active.filter { it.manifest.id !in waiting }
            if (next.size == active.size) break
            active = next
        }
        return ApiResolution(providers, waitingAll)
    }

    /**
     * The provider that serves [required] for a caller: the user's choice
     * ([chosen], a plugin id) when it qualifies, otherwise the first
     * qualifying provider. A provider whose level is below the caller's
     * `minLevel` is not offered to that caller.
     */
    fun providerFor(resolution: ApiResolution, callerId: String, required: RequiredApi, chosen: String? = null): ApiProvider? {
        val candidates = leastFirst(
            resolution.providers[required.api].orEmpty().filter { it.plugin.manifest.id != callerId && satisfies(it.export, required) },
        )
        return candidates.firstOrNull { it.plugin.manifest.id == chosen } ?: candidates.firstOrNull()
    }

    /** The provider of [api] for a plain availability question (`plugins.available`), honouring the user's choice. */
    fun providerOf(resolution: ApiResolution, api: String, minLevel: String? = null, chosen: String? = null): ApiProvider? {
        val candidates = leastFirst(
            resolution.providers[api].orEmpty().filter { minLevel == null || satisfies(it.export, RequiredApi(api, it.export.version, minLevel = minLevel)) },
        )
        return candidates.firstOrNull { it.plugin.manifest.id == chosen } ?: candidates.firstOrNull()
    }

    /**
     * Least privilege first: when a provider exports one API at two levels, a caller that asked for no level, or for
     * `adb`, is served by the `adb` export and carries the `priv.shell.adb` grant, never the root one (docs/plugin-api.md
     * 2.7). Stable, so the plugin order and the user's choice are otherwise unchanged.
     */
    private fun leastFirst(candidates: List<ApiProvider>): List<ApiProvider> = candidates.sortedBy { LEVELS.indexOf(it.export.level) }

    private val lock = Any()
    private var cachedEpoch = -1
    private var cached: ApiResolution? = null

    /**
     * The resolution for the installed plugins, recomputed only when
     * [PluginEpoch] says a record changed (an install, approve, enable,
     * crash, uninstall) and never per call.
     */
    fun current(context: Context): ApiResolution = synchronized(lock) {
        val epoch = PluginEpoch.current()
        cached?.takeIf { cachedEpoch == epoch }?.let { return@synchronized it }
        val grants = PluginGrants.forContext(context)
        val levels = ProviderLevels.forContext(context)
        val resolved = resolve(PluginStore.installed(context)) { pluginId, export ->
            PluginGrants.exportState(grants.read(pluginId), export.grantKey) == GrantState.GRANTED &&
                // A root-level export is offered only while its provider reports it holds root (docs/plugin-api.md 2.7).
                (export.level != "root" || levels.held(pluginId, export.api) == "root")
        }
        cached = resolved
        cachedEpoch = epoch
        resolved
    }
}

/** A counter every write of a plugin record or grant bumps, so cached views know when to recompute. */
object PluginEpoch {
    @Volatile private var value = 0

    fun current(): Int = value

    @Synchronized fun bump() {
        value++
    }
}

/**
 * The "Provided by" choice per standard interface (docs/plugin-api.md 2.3):
 * which plugin serves an API when several do. Never ranked automatically
 * beyond the first default.
 */
class PluginProviderChoices(private val file: File) {
    fun all(): Map<String, String> = synchronized(LOCK) {
        runCatching {
            val json = JSONObject(file.readText())
            buildMap { json.keys().forEach { put(it, json.optString(it)) } }
        }.getOrDefault(emptyMap())
    }

    fun chosen(api: String): String? = all()[api]

    fun choose(api: String, pluginId: String) = synchronized(LOCK) {
        val json = JSONObject(all() as Map<*, *>)
        json.put(api, pluginId)
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.toString())
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        PluginEpoch.bump()
    }

    companion object {
        private val LOCK = Any()

        fun forContext(context: Context): PluginProviderChoices = PluginProviderChoices(File(context.filesDir, "plugin-provider-choices.json"))
    }
}
