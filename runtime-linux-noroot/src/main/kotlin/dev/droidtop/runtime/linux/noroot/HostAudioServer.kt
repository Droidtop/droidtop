package dev.droidtop.runtime.linux.noroot

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import dev.droidtop.runtime.AudioHandOff
import io.airlift.compress.tar.TarInputStream
import io.airlift.compress.zstd.ZstdInputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Bridges host audio into proot containers, the missing half of
 * docs/SPEC.md's container Sockets row (Droidtop/tracker#95): proot has
 * no kernel namespace and no access to Android's audio HAL the way
 * runtime-linux-root's DroidSpacesRuntime does (its own doc comment:
 * "droidspaces already bridges Android's audio HAL to a single host-side
 * PulseAudio daemon"), so there is nothing for a proot container to bind
 * into the way DroidSpacesRuntime binds droidspaces' socket.
 *
 * Rather than build a second audio server for this, this reuses the ONE
 * PulseAudio build already in the tree for exactly the same underlying
 * problem: gamenative's Windows runtime (runtime-windows/WineXSession)
 * runs Wine/box64 with no root either, and gets audio out through a
 * PulseAudio server built against bionic with an AAudio sink instead of a
 * real ALSA/HAL backend (docs/SPEC.md 10b,
 * build-scripts/build-vendor-deps.sh's "PulseAudio 13.0" section) --
 * `libpulseaudio.so` packaged for both ABIs
 * (runtime-windows/src/main/jniLibs/<abi>), its modules and `pactl` in the
 * matching `pulseaudio-gamenative-*.tzst` asset (also runtime-windows,
 * merged into the one app-wide assets/nativeLibraryDir every module
 * shares -- this class needs no build dependency on that module for
 * either). gamenative's own [com.winlator.xenvironment.components.
 * PulseAudioComponent] starts one such server per Wine launch; this
 * starts exactly one for the whole desktop session instead, the same
 * "one long-lived thing the primary owns" shape as the compositor and
 * cupsd (docs/SPEC.md 3d), with its socket at
 * [dev.droidtop.runtime.ContainerLayout.AUDIO_SOCKET] under the socket
 * directory every container already binds -- so any process in any
 * container reaches it through `PULSE_SERVER`
 * ([dev.droidtop.runtime.ContainerLayout.clientEnvironment]) exactly the
 * way it would reach a real Linux desktop's PulseAudio.
 *
 * The microphone (Droidtop/tracker#80) rides the same server: with the
 * person's opt-in and the RECORD_AUDIO grant, [start] also loads PulseAudio's
 * stock `module-pipe-source` on a FIFO that [MicrophonePump] fills from
 * `AudioRecord`, and makes it the default source. Programs see a normal
 * PulseAudio microphone; nothing else changes for audio out.
 *
 * A daemon never holds up the desktop ([ContainerLayout.primaryInitScript]'s
 * own rule for in-container daemons, dq-desk2-01): [start] never throws,
 * it returns why it could not start, and a caller logs that and keeps
 * booting -- the primary container comes up silent rather than not at
 * all, same as a CUPS that failed to start.
 */
internal class HostAudioServer(private val context: Context) {
    private val workingDir = File(context.filesDir, "audio-bridge")
    private val modulesDir = File(workingDir, "modules")

    private val micPipe = File(workingDir, MIC_PIPE)
    private val micPump = MicrophonePump(micPipe)

    @Volatile private var process: Process? = null

    /**
     * Why the last [start] left the microphone out, or null when it is in
     * (or was not asked for). Soft, like the daemon rule above: the desktop
     * comes up with audio out either way.
     */
    @Volatile var microphoneNote: String? = null
        private set

    val running: Boolean get() = process?.isAlive == true

    /** The socket the running server listens on, for [handOff]. */
    @Volatile private var socketPath: String? = null

    /**
     * The Desktop session's part of the launch audio hand-off
     * ([AudioHandOff], Droidtop/tracker#160). The server runs with no
     * idle suspend (the packaged modules have no module-suspend-on-idle),
     * so its AAudio sink keeps a low-latency output stream open and
     * writing silence for the whole session, including while another app
     * is in front. Suspending every sink closes that stream
     * ([PulseSinkSuspend]); resuming opens it again. Container programs
     * stay connected and simply play into a suspended sink meanwhile.
     */
    private val handOff = object : AudioHandOff.Holder {
        override val name = "Desktop audio bridge"

        override suspend fun release(fade: Boolean): String? {
            val path = socketPath ?: return null
            if (!running) return null
            withContext(Dispatchers.IO) { PulseSinkSuspend.setAllSuspended(path, suspend = true) }
            return "PulseAudio sinks suspended, AAudio stream closed"
        }

        override suspend fun reopen() {
            val path = socketPath ?: return
            if (!running) return
            withContext(Dispatchers.IO) { PulseSinkSuspend.setAllSuspended(path, suspend = false) }
        }
    }

    /**
     * Starts the server listening on the Unix socket at [socketPath],
     * stopping any previous instance first. Returns null on success, or
     * why it could not start. [microphone] asks for the device microphone
     * too; whether it could be provided is in [microphoneNote].
     */
    fun start(socketPath: String, microphone: Boolean = false): String? {
        stop()
        val nativeLibraryDir = context.applicationInfo.nativeLibraryDir
        val binary = File(nativeLibraryDir, BINARY_NAME)
        if (!binary.isFile) return "$BINARY_NAME is not packaged for this device's ABI ($nativeLibraryDir)"
        val abi = Build.SUPPORTED_64_BIT_ABIS.firstOrNull()
            ?: return "no supported 64-bit ABI reported"

        try {
            extractModulesIfNeeded(abi)
        } catch (e: IOException) {
            return "could not extract the PulseAudio modules asset: ${e.message}"
        }

        workingDir.mkdirs()
        microphoneNote = if (microphone) microphoneProblem() else null
        val micPath = if (microphone && microphoneNote == null) {
            try {
                micPump.prepare()
                micPipe.absolutePath
            } catch (e: Exception) {
                microphoneNote = "could not create the microphone pipe: ${e.message}"
                null
            }
        } else {
            null
        }
        // A cookie from a previous run would be for a socket that no
        // longer exists.
        File(workingDir, ".config").deleteRecursively()
        File(socketPath).delete()
        File(workingDir, CONFIG_FILE).writeText(defaultPaConfig(socketPath, micPath))

        val builder = ProcessBuilder(
            binary.absolutePath,
            "--system=false",
            "--disable-shm=true",
            "--fail=false",
            "-n",
            "--file=$CONFIG_FILE",
            "--daemonize=false",
            "--use-pid-file=false",
            "--exit-idle-time=-1",
        ).directory(workingDir).redirectErrorStream(true)
        builder.environment().apply {
            clear()
            put("LD_LIBRARY_PATH", "/system/lib64:$nativeLibraryDir:${modulesDir.absolutePath}")
            put("HOME", workingDir.absolutePath)
            put("TMPDIR", context.cacheDir.absolutePath)
        }

        val started = try {
            builder.start()
        } catch (e: IOException) {
            return "could not start $BINARY_NAME: ${e.message}"
        }
        process = started
        this.socketPath = socketPath
        AudioHandOff.register(handOff)
        thread(name = "audio-bridge", isDaemon = true) {
            try {
                started.inputStream.bufferedReader().forEachLine { Log.i(TAG, it) }
            } catch (_: IOException) {
                // the process went away; the stream closing is the normal end
            }
        }
        if (micPath != null) micPump.start()
        return null
    }

    /** What stops the microphone being offered on this device right now, or null. */
    private fun microphoneProblem(): String? = when {
        context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ->
            "the microphone permission is not granted"
        !File(modulesDir, PIPE_SOURCE_MODULE).isFile ->
            "this build's PulseAudio modules have no $PIPE_SOURCE_MODULE"
        else -> null
    }

    /** Stops the server, if one is running. Never throws. */
    fun stop() {
        AudioHandOff.unregister(handOff)
        socketPath = null
        micPump.stop()
        val current = process ?: return
        process = null
        current.destroy()
        if (!current.waitFor(STOP_GRACE_S, TimeUnit.SECONDS)) current.destroyForcibly()
    }

    /** Unpacks the modules asset into [modulesDir] once; a no-op once they are there. */
    private fun extractModulesIfNeeded(abi: String) {
        // Re-extracted after an app update: the modules asset can gain modules
        // (the microphone's pipe source did), and an old extraction lacks them.
        val installed = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        }.getOrDefault("")
        val marker = File(modulesDir, EXTRACTED_MARKER)
        if (File(modulesDir, AAUDIO_SINK_MODULE).isFile && marker.isFile && marker.readText() == installed) return
        val assets = context.assets
        val assetName = assetNameFor(assets.list("").orEmpty().toList(), abi)
            ?: throw IOException("no PulseAudio modules asset packaged for $abi")
        modulesDir.mkdirs()
        assets.open(assetName).use { raw ->
            ZstdInputStream(raw).use { zstd ->
                TarInputStream(zstd).use { tar ->
                    var entry = tar.nextEntry
                    while (entry != null) {
                        if (!entry.name.endsWith("/")) {
                            val out = File(workingDir, entry.name)
                            out.parentFile?.mkdirs()
                            out.outputStream().use { tar.copyTo(it) }
                        }
                        entry = tar.nextEntry
                    }
                }
            }
        }
        marker.writeText(installed)
    }

    companion object {
        private const val TAG = "droidtop.audiobridge"
        private const val BINARY_NAME = "libpulseaudio.so"
        private const val CONFIG_FILE = "default.pa"
        private const val MIC_PIPE = "mic.pipe"
        private const val EXTRACTED_MARKER = ".extracted-for"
        private const val PIPE_SOURCE_MODULE = "module-pipe-source.so"
        private const val AAUDIO_SINK_MODULE = "module-aaudio-sink.so"
        private const val STOP_GRACE_S = 2L

        private val ARM64_ASSET = Regex("""pulseaudio-gamenative-\d+\.tzst""")
        private const val X86_64_ASSET = "pulseaudio-gamenative-x86_64.tzst"

        /**
         * Which packaged asset holds [abi]'s modules and pactl, picked
         * from the app's actual asset list rather than a hardcoded date:
         * the arm64 one is upstream gamenative's own file, named for the
         * day it was rebuilt there, and droidtop does not own that name.
         * The x86_64 one is droidtop's own build
         * (build-scripts/build-vendor-deps.sh), named without a date so
         * this lookup has one fixed name to check. Pure so it is testable
         * without an AssetManager.
         */
        internal fun assetNameFor(assetNames: List<String>, abi: String): String? = when (abi) {
            "x86_64" -> assetNames.firstOrNull { it == X86_64_ASSET }
            "arm64-v8a" -> assetNames.firstOrNull { ARM64_ASSET.matches(it) }
            else -> null
        }

        /**
         * The whole of the server's config: a Unix socket at [socketPath],
         * auth left open the way gamenative's own PulseAudioComponent
         * leaves it (this socket is already only reachable from inside a
         * container that has the shared socket directory bound, same
         * trust boundary as the Wayland and CUPS sockets beside it), and
         * the AAudio sink module module-aaudio-sink.c
         * (build-scripts/pulseaudio-patches) exists for. Pure so it is
         * testable without touching a filesystem.
         */
        internal fun defaultPaConfig(socketPath: String, micPipePath: String? = null): String =
            "load-module module-native-protocol-unix auth-anonymous=1 auth-cookie-enabled=false socket=\"$socketPath\"\n" +
                "load-module module-aaudio-sink\n" +
                (micPipePath?.let {
                    "load-module module-pipe-source source_name=droidtop_mic file=\"$it\" " +
                        "format=s16le rate=${MicrophonePump.RATE} channels=1\n" +
                        "set-default-source droidtop_mic\n"
                } ?: "")
    }
}
