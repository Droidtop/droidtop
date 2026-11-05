package dev.droidtop.runtime.windows

import android.content.Context
import com.winlator.core.ProcessHelper
import com.winlator.core.envvars.EnvVars
import com.winlator.xenvironment.EnvironmentComponent
import com.winlator.xenvironment.XEnvironment
import java.io.File
import timber.log.Timber

/**
 * Video in Wine games through droidtop's sandboxed decode server (docs/SPEC.md 9, "Video in Wine games";
 * Droidtop/tracker#601, the enginehost-ffmpeg handoff the owner chose on #906).
 *
 * Two pieces from the APK, both built in runtime-windows/native/mediadecode:
 * - `libdroidtopmediad.so`, the server, started per session by [MediaDecodeServerComponent]. It forks one process per
 *   stream, so a crash or kill ends only that stream.
 * - `libgstdroidtopff.so`, a GStreamer plugin the image's own GStreamer loads in Wine's process. Its element claims a
 *   game's media container above every demuxer, has the server decode it, and hands Wine raw video and audio.
 *
 * The plugin is copied into a directory of its own, [pluginDir], which goes first on GST_PLUGIN_PATH only while the
 * server is listening; nativeLibraryDir itself can't go on that path, since GStreamer would try every library in it.
 * Without a listening server nothing changes, and the media converter's blank placeholder (#897) still lets a game
 * go on.
 *
 * Slice 1: the server's decoder is a stub that returns that same blank, and the element claims ASF only. Slice 2
 * brings the enginehost-ffmpeg library and the other containers.
 */
object WineMediaDecode {
    const val SERVER = "libdroidtopmediad.so"
    const val PLUGIN = "libgstdroidtopff.so"
    const val SOCKET_VAR = "DROIDTOP_MEDIA_SOCKET"

    /** DROIDTOP_MEDIA_TRACE=1 (from droidtop's own environment) turns on the element's and the server's step lines. */
    const val TRACE_VAR = "DROIDTOP_MEDIA_TRACE"
    @Volatile var trace: Boolean = true // DIAGNOSTIC BUILD (#897), not to land
    fun traceEnv(): Array<String> = if (trace) arrayOf("$TRACE_VAR=1") else emptyArray()

    fun server(context: Context) = File(context.applicationInfo.nativeLibraryDir, SERVER)
    fun socket(context: Context) = File(XEnvironment.getTmpDir(context), ".droidtop-media")
    fun pluginDir(context: Context) = File(context.filesDir, "gst-droidtop")

    /** [current] with [dir] first, each entry once. Pure. */
    fun withPluginDir(current: String?, dir: String): String =
        (listOf(dir) + current.orEmpty().split(':').filter { it.isNotEmpty() && it != dir }).joinToString(":")

    /** Copies the plugin into [pluginDir] when it is missing or differs in size from the APK's. Disk work. */
    fun preparePlugin(context: Context): Boolean {
        val source = File(context.applicationInfo.nativeLibraryDir, PLUGIN)
        if (!source.isFile) return false
        val dir = pluginDir(context).apply { mkdirs() }
        val target = File(dir, PLUGIN)
        if (!target.isFile || target.length() != source.length()) source.copyTo(target, overwrite = true)
        return true
    }

    /**
     * Puts the plugin's directory first on GST_PLUGIN_PATH and names the server's socket, when this session's server is
     * listening. Run after the launcher sets GST_PLUGIN_PATH to the image's own plugins.
     */
    @JvmStatic
    fun applyLaunchEnv(context: Context, envVars: EnvVars): Boolean {
        val socket = socket(context)
        val plugin = File(pluginDir(context), PLUGIN)
        if (!socket.exists() || !plugin.isFile) return false
        envVars.put("GST_PLUGIN_PATH", withPluginDir(envVars.get("GST_PLUGIN_PATH"), pluginDir(context).path))
        envVars.put(SOCKET_VAR, socket.path)
        if (trace) envVars.put(TRACE_VAR, "1")
        return true
    }
}

/** The decode server of a Windows session, started before the guest and stopped with it, as the audio servers are. */
class MediaDecodeServerComponent(private val socket: File) : EnvironmentComponent() {
    @Volatile private var pid = -1

    override fun start() {
        val context = environment.context
        val server = WineMediaDecode.server(context)
        if (!server.isFile || !runCatching { WineMediaDecode.preparePlugin(context) }.getOrDefault(false)) {
            Timber.w("No media decode server or plugin in the APK; Wine games keep the blank video")
            return
        }
        socket.delete()
        pid = ProcessHelper.exec(
            "${server.path} --socket ${socket.path}",
            arrayOf("LD_LIBRARY_PATH=/system/lib64") + WineMediaDecode.traceEnv(),
            context.filesDir,
        ) { status -> Timber.i("media decode server exited with %d", status) }
        var waited = 0
        while (!socket.exists() && waited < 2000 && pid > 0) {
            Thread.sleep(50)
            waited += 50
        }
        Timber.i("media decode server pid %d, socket %s after %d ms", pid, if (socket.exists()) "up" else "missing", waited)
    }

    override fun stop() {
        if (pid > 0) ProcessHelper.terminateProcess(pid)
        pid = -1
        socket.delete()
    }
}
