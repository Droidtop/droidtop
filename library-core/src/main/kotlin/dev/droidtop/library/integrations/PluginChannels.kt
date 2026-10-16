package dev.droidtop.library.integrations

import android.content.Context

/**
 * A plugin's release lines (docs/SPEC.md 12a, "Channels", Droidtop/tracker#179). The owner, 2026-10-16: "Yeah, plugins
 * are channeled, too". A channel takes its own line and every steadier one ([streams]); Stable is the default.
 */
enum class PluginChannel(val id: String, val label: String, val streams: Set<String>) {
    STABLE("stable", "Stable", setOf("stable")),
    TESTING("testing", "Testing", setOf("stable", "testing")),
    UNSTABLE("unstable", "Unstable", setOf("stable", "testing", "unstable"));

    companion object {
        fun of(id: String?): PluginChannel? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Which channel each catalog, and each plugin that overrides it, is on. A plugin's own choice wins over its catalog's;
 * a catalog with no choice is on [PluginChannel.STABLE]. Kept in droidtop's preferences, never in a plugin's data.
 */
class PluginChannels private constructor(private val prefs: android.content.SharedPreferences) {
    fun catalog(sourceId: String): PluginChannel = PluginChannel.of(prefs.getString(CATALOG + sourceId, null)) ?: PluginChannel.STABLE

    fun setCatalog(sourceId: String, channel: PluginChannel) {
        prefs.edit().putString(CATALOG + sourceId, channel.id).apply()
    }

    /** The plugin's own choice, or null when it follows its catalog. */
    fun plugin(pluginId: String): PluginChannel? = PluginChannel.of(prefs.getString(PLUGIN + pluginId, null))

    fun setPlugin(pluginId: String, channel: PluginChannel?) {
        prefs.edit().apply { if (channel == null) remove(PLUGIN + pluginId) else putString(PLUGIN + pluginId, channel.id) }.apply()
    }

    /** The channel [pluginId] is offered on from the catalog [sourceId] (null: whichever lists it). */
    fun effective(sourceId: String?, pluginId: String): PluginChannel =
        resolve(plugin(pluginId), sourceId?.let { catalog(it) })

    companion object {
        private const val FILE = "plugin_channels"
        private const val CATALOG = "catalog:"
        private const val PLUGIN = "plugin:"

        fun forContext(context: Context): PluginChannels =
            PluginChannels(context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE))

        /** The one rule, pure: a plugin's own choice, else its catalog's, else Stable. */
        fun resolve(pluginChoice: PluginChannel?, catalogChoice: PluginChannel?): PluginChannel =
            pluginChoice ?: catalogChoice ?: PluginChannel.STABLE

        /**
         * What the catalog's pure functions ask when no channel is passed: Stable until [install] points it at the
         * person's choices (droidtop's start), so a test or a caller without a context sees Stable.
         */
        @Volatile
        internal var resolver: (sourceId: String?, pluginId: String) -> PluginChannel = { _, _ -> PluginChannel.STABLE }

        fun install(context: Context) {
            val store = forContext(context)
            resolver = store::effective
        }
    }
}
