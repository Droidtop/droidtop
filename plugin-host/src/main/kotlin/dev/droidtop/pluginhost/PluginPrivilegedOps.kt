package dev.droidtop.pluginhost

import android.content.Context
import android.util.Base64
import dev.droidtop.runtime.tasks.BackendState
import dev.droidtop.runtime.tasks.ElevatedBackend
import dev.droidtop.runtime.tasks.ElevatedFiles
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

    // Each capability is the op itself being exported, so a provider that predates an op is not offered for it.
    override fun available(): TaskPrivileges =
        TaskPrivileges(
            forceStop = caller.hasProvider("priv.packages", 1),
            shell = caller.hasProvider("priv.shell", 1),
            files = caller.hasOp("priv.shell", 1, "read_file") && caller.hasOp("priv.shell", 1, "write_file"),
        )

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

    /** `priv.shell` `read_file`, in [FILE_CHUNK] pieces (docs/plugin-api.md 2.7, "Ops for the emulator setup helper"). */
    override fun readFile(path: String): ByteArray? {
        if (!ElevatedFiles.allowed(path) || !caller.hasOp("priv.shell", 1, "read_file")) return null
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            val reply = caller.call("priv.shell", 1, "read_file", JSONObject().put("path", path).put("offset", out.size()).put("length", FILE_CHUNK))
            if (!reply.ok) return null
            val piece = Base64.decode(reply.data.optString("dataBase64"), Base64.NO_WRAP)
            out.write(piece)
            if (out.size() > ElevatedFiles.MAX_READ_BYTES) return null
            if (reply.data.optBoolean("eof", piece.isEmpty()) || piece.isEmpty()) return out.toByteArray()
        }
    }

    /** `priv.shell` `write_file`, in [FILE_CHUNK] pieces; the provider renames the file into place on the last one. */
    override fun writeFile(path: String, data: ByteArray): Boolean {
        if (!ElevatedFiles.allowed(path) || data.size > ElevatedFiles.MAX_WRITE_BYTES || !caller.hasOp("priv.shell", 1, "write_file")) return false
        var offset = 0
        do {
            val end = minOf(data.size, offset + FILE_CHUNK)
            val args = JSONObject()
                .put("path", path)
                .put("offset", offset)
                .put("dataBase64", Base64.encodeToString(data, offset, end - offset, Base64.NO_WRAP))
                .put("last", end == data.size)
            if (!caller.call("priv.shell", 1, "write_file", args).ok) return false
            offset = end
        } while (offset < data.size)
        return true
    }

    /**
     * A long-lived root process through the provider's stream session ([ProviderProcess], `exec_stream`), for the
     * rooted desktop stack (dev.droidtop.runtime.RootProcess, Droidtop/tracker#394). Only a provider that holds
     * `priv.shell` at root level answers: a Shizuku plugin running over ADB gives null here, so the rooted stack never
     * gets a shell-user process it would mistake for root.
     */
    override fun spawn(argv: List<String>): Process? = ProviderProcess.start(caller, argv, minLevel = ROOT_LEVEL)

    private companion object {
        /** Raw bytes per call: under a quarter of a binder transaction once base64-encoded. */
        const val FILE_CHUNK = 192 * 1024
        const val ROOT_LEVEL = "root"
    }
}
