package dev.droidtop.library.integrations

import android.content.Context
import dev.droidtop.pluginhost.PluginCapability
import dev.droidtop.pluginhost.PluginCrashPolicy
import dev.droidtop.pluginhost.PluginEvent
import dev.droidtop.pluginhost.PluginJobsCenter
import dev.droidtop.pluginhost.PluginStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * droidtop's side of [PluginEvent] (docs/SPEC.md 12a "Event hooks"): the
 * one place that decides WHEN a droidtop state change becomes a plugin
 * notification. Lives in `library-core` (not `plugin-host`, which knows
 * nothing about console systems or players) so it can be called directly
 * from the settings write path that changes the state in question --
 * today, [dev.droidtop.library.consoles.PlayerOverridePrefs.set]'s own
 * call site.
 */
object PluginEventBus {
    /**
     * Fire-and-forget wrapper around [notifyDefaultPlayerChanged] for a
     * non-suspend call site -- [dev.droidtop.library.settings.ChoiceItem.onSelect]
     * (the player-choice row's own write path) is plain `(Context,
     * String) -> Unit`, and this event notification is a background side
     * effect of that write, not something the row's own commit should
     * block on (the row's write to [dev.droidtop.library.consoles.PlayerOverridePrefs]
     * already completed by the time this runs).
     */
    fun notifyDefaultPlayerChangedAsync(
        context: Context,
        systemId: String,
        systemName: String,
        playerId: String,
        playerName: String,
        playerPackage: String?,
        core: String?,
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            notifyDefaultPlayerChanged(context.applicationContext, systemId, systemName, playerId, playerName, playerPackage, core)
        }
    }

    /**
     * Fires [PluginEvent.DEFAULT_PLAYER_CHANGED] at every approved,
     * enabled plugin that both declares [PluginCapability.APP_STATUS]
     * (the capability [PluginEvent.DEFAULT_PLAYER_CHANGED]'s own doc
     * comment ties job-reactions to) AND subscribed to that event id --
     * [PluginCrashPolicy.notifyEvent] itself is the cheap early-out for
     * everything else, so this is safe to call on every player change
     * regardless of how many plugins are installed.
     *
     * A plugin's answer may ask droidtop to start a job in reaction
     * (`values["startJob"]` names a [PluginCapability.id], `values["job"]`
     * names the job, every OTHER returned value becomes that job's own
     * args) -- read [PluginEvent.DEFAULT_PLAYER_CHANGED]'s doc comment
     * for the exact shape. That job is tracked the normal way, through
     * [PluginJobsCenter], so it shows up on the shared Jobs screen and
     * any inline progress row exactly like a job the user started by
     * hand.
     */
    suspend fun notifyDefaultPlayerChanged(
        context: Context,
        systemId: String,
        systemName: String,
        playerId: String,
        playerName: String,
        playerPackage: String?,
        core: String?,
    ) {
        val args = defaultPlayerChangedArgs(systemId, systemName, playerId, playerName, playerPackage, core)
        val candidates = PluginStore.runnableFor(context, PluginCapability.APP_STATUS)
            .filter { it.manifest.subscribesTo(PluginEvent.DEFAULT_PLAYER_CHANGED) }
        if (candidates.isEmpty()) return
        val policy = PluginCrashPolicy(context.applicationContext)
        try {
            for (record in candidates) {
                val result = policy.notifyEvent(record, PluginEvent.DEFAULT_PLAYER_CHANGED, args) ?: continue
                if (!result.ok) continue
                val jobCapabilityId = result.values["startJob"] ?: continue
                val jobCapability = PluginCapability.fromId(jobCapabilityId) ?: continue
                val jobArgs = result.values - "startJob"
                // No plugin-label prefix here either -- see PluginAppStatus's
                // own note on the same fix (PluginJobsScreen adds it).
                val jobTitle = result.values["job"] ?: "event reaction"
                PluginJobsCenter.start(
                    context = context,
                    record = record,
                    capability = jobCapability,
                    args = jobArgs,
                    title = jobTitle,
                )
            }
        } finally {
            policy.shutdown()
        }
    }
}

/**
 * Fires [PluginEvent.DEVICES_CHANGED] (docs/plugin-api.md D21) at every runnable plugin that subscribed to it and holds
 * `devices.paired`: a device was paired or forgotten, or what one announced changed. Set as
 * [dev.droidtop.net.peer.PairedDevices.onChanged] at start; called on a background thread.
 */
fun notifyDevicesChanged(context: Context) {
    CoroutineScope(Dispatchers.IO).launch {
        val grants = dev.droidtop.pluginhost.PluginGrants.forContext(context)
        val candidates = PluginStore.installed(context)
            .filter { it.runnable() && it.manifest.subscribesTo(PluginEvent.DEVICES_CHANGED) }
            .filter { dev.droidtop.pluginhost.PluginGrants.stateOf(it, grants.read(it.manifest.id), "devices.paired") == dev.droidtop.pluginhost.GrantState.GRANTED }
        if (candidates.isEmpty()) return@launch
        val policy = PluginCrashPolicy(context.applicationContext)
        try {
            candidates.forEach { runCatching { policy.notifyEvent(it, PluginEvent.DEVICES_CHANGED, emptyMap()) } }
        } finally {
            policy.shutdown()
        }
    }
}

/**
 * Fires [PluginEvent.THEME_CHANGED] (Droidtop/tracker#559) at every runnable plugin that subscribed to it: the role
 * tokens changed. Set as [dev.droidtop.library.theme.RoleTokens.onChanged] at start.
 */
fun notifyThemeChanged(context: Context, light: Boolean) {
    CoroutineScope(Dispatchers.IO).launch {
        val candidates = PluginStore.installed(context).filter { it.runnable() && it.manifest.subscribesTo(PluginEvent.THEME_CHANGED) }
        if (candidates.isEmpty()) return@launch
        val policy = PluginCrashPolicy(context.applicationContext)
        try {
            val args = mapOf("theme" to if (light) "light" else "dark")
            candidates.forEach { runCatching { policy.notifyEvent(it, PluginEvent.THEME_CHANGED, args) } }
        } finally {
            policy.shutdown()
        }
    }
}

/**
 * Fires [PluginEvent.WEB_SIGNED_IN] at [record] when it subscribed: the person signed in to its site through droidtop
 * (docs/SPEC.md 12a "A plugin's site account"). Returns at once; the plugin is told off the caller's thread.
 */
fun notifyWebSignedIn(context: Context, record: dev.droidtop.pluginhost.PluginRecord) {
    if (!record.runnable() || !record.manifest.subscribesTo(PluginEvent.WEB_SIGNED_IN)) return
    val app = context.applicationContext
    CoroutineScope(Dispatchers.IO).launch {
        val policy = PluginCrashPolicy(app)
        try {
            runCatching { policy.notifyEvent(record, PluginEvent.WEB_SIGNED_IN, emptyMap()) }
        } finally {
            policy.shutdown()
        }
    }
}

/**
 * The exact args map [PluginEventBus.notifyDefaultPlayerChanged] delivers
 * as [PluginEvent.DEFAULT_PLAYER_CHANGED]'s payload -- pulled out of the
 * suspend dispatch (which needs a real [Context], the plugin store and a
 * bound runtime) purely so the payload contract itself is unit-testable,
 * the same way [dev.droidtop.library.consoles.usableEmulatorNames] is.
 * All plain strings: a plugin reads them through PluginArgs, which has no
 * null string, so an absent fact arrives as "" (no package, no core).
 */
internal fun defaultPlayerChangedArgs(
    systemId: String,
    systemName: String,
    playerId: String,
    playerName: String,
    playerPackage: String?,
    core: String?,
): Map<String, String> = mapOf(
    "systemId" to systemId,
    "systemName" to systemName,
    "playerId" to playerId,
    "playerName" to playerName,
    "playerPackage" to (playerPackage ?: ""),
    "core" to (core ?: ""),
)

/**
 * Fires [PluginEvent.CONTEXT_RECEIVED] (docs/plugin-api.md F8, Droidtop/tracker#794) at the plugin that owns
 * [contextId], when it subscribed: a merged copy of that context was made current here. Set as
 * [dev.droidtop.pluginhost.PluginContexts.onReceived] at start; returns at once.
 */
fun notifyContextReceived(context: Context, pluginId: String, contextId: String, generation: Long) {
    CoroutineScope(Dispatchers.IO).launch {
        val record = PluginStore.installed(context).firstOrNull { it.manifest.id == pluginId } ?: return@launch
        if (!record.runnable() || !record.manifest.subscribesTo(PluginEvent.CONTEXT_RECEIVED)) return@launch
        val policy = PluginCrashPolicy(context.applicationContext)
        try {
            runCatching { policy.notifyEvent(record, PluginEvent.CONTEXT_RECEIVED, mapOf("context" to contextId, "generation" to generation.toString())) }
        } finally {
            policy.shutdown()
        }
    }
}
