package dev.droidtop.samples.statustile

import dev.droidtop.pluginhost.DroidtopPlugin
import dev.droidtop.pluginhost.PluginArgs
import dev.droidtop.pluginhost.PluginCapability
import dev.droidtop.pluginhost.PluginContext
import dev.droidtop.pluginhost.PluginResult

/**
 * The sample plugin queued for the rig check (dq-plugins-01): a harmless
 * `status_tile` that exercises the plugin API end to end -- install,
 * approve, a real invoke() round-trip through the isolated :pluginhost
 * process and back, and (via [FORCE_CRASH_QUERY]) a deliberate crash so
 * PluginCrashPolicy's disable path can be watched actually firing.
 *
 * It declares no root and needs none: it reads nothing real off the
 * device, matching the owner's "root is an enhancement, never a
 * requirement" direction (2026-09-25) by simply not asking for it at
 * all, which is the normal case a plugin like this should be in.
 *
 * Ships as samples/plugin-sample-statustile rather than in droidtop's
 * own build (settings.gradle.kts deliberately does not include this
 * folder): a plugin bundle is built and signed OUTSIDE droidtop's own
 * Gradle graph, the same way a real third-party plugin would be, and
 * loaded only through PluginBundleInstaller like any other bundle. See
 * this folder's README for exactly how the tar.xz is produced and signed.
 */
class StatusTilePlugin : DroidtopPlugin {
    private var loadCount = 0

    override fun onLoad(context: PluginContext) {
        loadCount += 1
    }

    override fun invoke(capability: PluginCapability, args: PluginArgs): PluginResult {
        if (capability != PluginCapability.STATUS_TILE) {
            return PluginResult.failure("StatusTilePlugin only implements status_tile")
        }
        // The one deliberate way to test the crash-containment path on
        // the rig (dq-plugins-01, step "force a crash"): the query arg
        // is never present in droidtop's own status-tile call, only in
        // the rig instructions.
        if (args.string("query") == FORCE_CRASH_QUERY) {
            throw IllegalStateException("forced crash for dq-plugins-01")
        }
        return PluginResult.success(
            mapOf(
                "label" to "Sample tile",
                "value" to "loaded $loadCount time(s), called OK",
            ),
        )
    }

    companion object {
        const val FORCE_CRASH_QUERY = "force-crash"
    }
}
