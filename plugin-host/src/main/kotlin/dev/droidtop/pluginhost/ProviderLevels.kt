package dev.droidtop.pluginhost

import android.content.Context
import java.io.File
import org.json.JSONObject

/**
 * The privilege level each provider says it holds right now, per exported API (docs/plugin-api.md 2.7,
 * `plugins.report_level`): the Shizuku plugin reports "root" while Shizuku's server runs as uid 0 (Shizuku started as
 * root, or Sui) and "adb" otherwise. A root-level export is offered to callers only while its provider reports "root", so
 * a manifest can never claim root by itself. Kept in one small file so the answer outlives a restart; a stale "root" only
 * means a call is refused by the provider, which checks again before every command. A change bumps [PluginEpoch] so the
 * resolution recomputes.
 */
class ProviderLevels private constructor(private val file: File) {
    private val lock = Any()

    fun held(pluginId: String, api: String): String? = synchronized(lock) {
        read().optJSONObject(pluginId)?.optString(api)?.ifBlank { null }
    }

    fun set(pluginId: String, api: String, level: String) {
        synchronized(lock) {
            val all = read()
            val mine = all.optJSONObject(pluginId) ?: JSONObject()
            if (mine.optString(api) == level) return
            all.put(pluginId, mine.put(api, level))
            write(all)
        }
        PluginEpoch.bump()
    }

    /** Forgets [pluginId]'s levels (uninstall). */
    fun forget(pluginId: String) {
        synchronized(lock) {
            val all = read()
            if (all.remove(pluginId) == null) return
            write(all)
        }
        PluginEpoch.bump()
    }

    private fun read(): JSONObject = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())

    private fun write(all: JSONObject) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(all.toString())
        if (!tmp.renameTo(file)) {
            file.writeText(all.toString())
            tmp.delete()
        }
    }

    companion object {
        @Volatile private var instance: ProviderLevels? = null

        fun forContext(context: Context): ProviderLevels = instance ?: synchronized(this) {
            instance ?: ProviderLevels(File(PluginStore.root(context.applicationContext), "provider-levels.json")).also { instance = it }
        }
    }
}
