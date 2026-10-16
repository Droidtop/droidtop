package dev.droidtop.pluginhost

import java.util.concurrent.ConcurrentHashMap

/**
 * What the person declined for a plugin in this run of droidtop, and what the broker last refused it (Droidtop/tracker
 * #518). In memory only: droidtop's next start asks again.
 *
 * - Not now lasts the session: one page that calls a permission the person put off does not ask on every visit. The
 *   plugin's Permissions page still allows it, and any choice made there ([PluginGrants.set]) forgets the Not now.
 * - The last refusal per plugin lets a page tell which permission a plugin's failed call was about, when the plugin
 *   passed droidtop's refusal on without naming it: the page then degrades to that one permission, never to "not
 *   allowed to show this".
 */
object PluginRefusals {
    private val notNow: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val last = ConcurrentHashMap<String, Pair<String, Long>>()

    private fun key(pluginId: String, permission: String) = "$pluginId\u0000$permission"

    fun rememberNotNow(pluginId: String, permission: String) {
        notNow += key(pluginId, permission)
    }

    fun isNotNow(pluginId: String, permission: String): Boolean = key(pluginId, permission) in notNow

    /** The person chose for [permission] on the plugin's Permissions page: whatever they chose replaces the Not now. */
    fun forgetNotNow(pluginId: String, permission: String) {
        notNow -= key(pluginId, permission)
    }

    fun noteRefused(pluginId: String, permission: String, atMs: Long = System.currentTimeMillis()) {
        last[pluginId] = permission to atMs
    }

    /** The permission the broker refused [pluginId] at or after [sinceMs], or null. */
    fun refusedSince(pluginId: String, sinceMs: Long): String? = last[pluginId]?.takeIf { it.second >= sinceMs }?.first
}
