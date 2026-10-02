package dev.droidtop.pluginhost

import android.content.Context
import dev.droidtop.runtime.tasks.BackendState
import dev.droidtop.runtime.tasks.ElevatedBackend
import dev.droidtop.runtime.tasks.ForceStopResult
import dev.droidtop.runtime.tasks.ShellOutput
import dev.droidtop.runtime.tasks.TaskPrivileges
import org.json.JSONArray
import org.json.JSONObject

/**
 * The plugin backend of the privileged shell (the other is [SystemShizukuOps]), served by whichever provider
 * plugin is running: `priv.packages` for force-stop and `priv.shell` for reading the system's task list
 * (docs/plugin-api.md 2.7). Shizuku, or a root provider, plug in here without the task manager knowing which;
 * with none, [available] says so and the task manager says what to enable. Calls block on the provider's process.
 */
class PluginPrivilegedOps(private val context: Context) : ElevatedBackend {
    // Built on first use: the broker environment is not something application start should pay for.
    private val caller by lazy { PluginBrokers.hostCaller(context.applicationContext) }

    override fun available(): TaskPrivileges =
        TaskPrivileges(forceStop = caller.hasProvider("priv.packages", 1), shell = caller.hasProvider("priv.shell", 1))

    /** Ready while a running provider plugin serves either interface; the plugin itself says why a call fails. */
    override fun state(): BackendState =
        if (available().let { it.forceStop || it.shell }) BackendState.READY else BackendState.ABSENT

    override fun forceStop(packageName: String): ForceStopResult =
        when (val result = ForceStop.request(caller, packageName)) {
            ForceStop.Result.Stopped -> ForceStopResult.Stopped
            ForceStop.Result.NoProvider -> ForceStopResult.NoProvider
            is ForceStop.Result.Failed -> ForceStopResult.Failed(result.message)
        }

    override fun exec(argv: List<String>): ShellOutput? {
        if (!caller.hasProvider("priv.shell", 1)) return null
        val reply = caller.call("priv.shell", 1, "exec", JSONObject().put("argv", JSONArray(argv)))
        if (!reply.ok) return null
        return ShellOutput(reply.data.optInt("exit", -1), reply.data.optString("stdout"), reply.data.optString("stderr"))
    }
}
