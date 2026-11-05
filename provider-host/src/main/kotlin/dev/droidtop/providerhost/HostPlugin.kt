package dev.droidtop.providerhost

import android.content.Context
import dev.droidtop.pluginhost.DroidtopPlugin
import dev.droidtop.pluginhost.PluginArgs
import dev.droidtop.pluginhost.PluginCall
import dev.droidtop.pluginhost.PluginCapability
import dev.droidtop.pluginhost.PluginContext
import dev.droidtop.pluginhost.PluginErrorCode
import dev.droidtop.pluginhost.PluginReply
import dev.droidtop.pluginhost.PluginResult

/**
 * The host's own plugin, `dev.droidtop.providerhost` (its manifest is assets/droidtop-plugin.json): what the host answers
 * for itself over droidtop's broker. It holds nothing yet; the providers it carries come with their own exports.
 */
internal class HostPlugin(private val app: Context) : DroidtopPlugin {
    private lateinit var broker: PluginContext

    override fun onLoad(context: PluginContext) {
        broker = context
    }

    override fun invoke(capability: PluginCapability, args: PluginArgs): PluginResult =
        PluginResult.failure("the provider host has no ${capability.id}")

    override fun handle(call: PluginCall): PluginReply =
        PluginReply.error(PluginErrorCode.UNSUPPORTED, "the provider host has no ${call.point} ${call.op}")
}
