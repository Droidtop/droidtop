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

    suspend fun notifyDefaultPlayerChanged(
        context: Context,
        systemId: String,
        systemName: String,
        playerId: String,
        playerName: String,
        playerPackage: String?,
        core: String?,
    ) {
        val args = mapOf(
            "systemId" to systemId,
            "systemName" to systemName,
            "playerId" to playerId,
            "playerName" to playerName,
            "playerPackage" to (playerPackage ?: ""),
            "core" to (core ?: ""),
        )
        val candidates = PluginStore.runnableFor(context, PluginCapability.APP_STATUS)
            .filter { PluginEvent.DEFAULT_PLAYER_CHANGED.id in it.manifest.subscribedEvents }
        if (candidates.isEmpty()) return
        val policy = PluginCrashPolicy(context.applicationContext)
        try {
            for (record in candidates) {
                val result = policy.notifyEvent(record, PluginEvent.DEFAULT_PLAYER_CHANGED, args) ?: continue
                if (!result.ok) continue
                val jobCapabilityId = result.values["startJob"] ?: continue
                val jobCapability = PluginCapability.fromId(jobCapabilityId) ?: continue
                val jobArgs = result.values - "startJob"
                val jobTitle = result.values["job"]?.let { "${record.manifest.label}: $it" } ?: record.manifest.label
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
