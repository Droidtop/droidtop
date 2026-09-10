package dev.droidtop.runtime.windows

import android.content.Context
import com.winlator.alsaserver.ALSAClient
import com.winlator.container.Container
import com.winlator.contents.ContentsManager
import com.winlator.core.Callback
import com.winlator.core.KeyValueSet
import com.winlator.core.ProcessHelper
import com.winlator.core.WineInfo
import com.winlator.core.envvars.EnvVars
import com.winlator.xconnector.UnixSocketConfig
import com.winlator.xenvironment.ImageFs
import com.winlator.xenvironment.XEnvironment
import com.winlator.xenvironment.components.ALSAServerComponent
import com.winlator.xenvironment.components.BionicProgramLauncherComponent
import com.winlator.xenvironment.components.NetworkInfoUpdateComponent
import com.winlator.xenvironment.components.PulseAudioComponent
import com.winlator.xenvironment.components.SysVSharedMemoryComponent
import com.winlator.xenvironment.components.VirGLRendererComponent
import com.winlator.xenvironment.components.VortekRendererComponent
import com.winlator.xenvironment.components.XServerComponent
import com.winlator.xserver.XServer
import java.io.File
import java.util.ArrayDeque

/**
 * The Wine guest and everything it draws and listens through, as one
 * object with a lifetime.
 *
 * This is the whole of what used to live inside `BionicWineEngine.launch`
 * plus the pieces that launch never had: the audio server, the GPU
 * renderer component, and an [XServer] whose renderer is a real Android
 * surface. The reason those were missing is the reason a Windows game
 * used to start and show nothing -- Wine connected to a headless X
 * server, drew into it, and no one was looking.
 *
 * Nothing here is a new renderer. Every component is gamenative's own,
 * constructed in the same order and from the same container fields its
 * `XServerScreen` uses; droidtop supplies which prefix, which
 * executable, and which surface. The two deliberate omissions from that
 * component set, both Steam-only:
 *
 *  - `SteamClientComponent`, which serves the Steam pipe. droidtop is
 *    not a Steam client on this path.
 *  - `WineRequestComponent`, which opens a listening socket to hand
 *    guest URL requests to `EpicOAuthActivity` -- an activity droidtop
 *    deliberately keeps out of its merged manifest, so starting the
 *    component would only produce an unresolvable intent.
 *
 * The session does NOT own the [XServer] or the surface: the Activity
 * hosting the picture creates both, because they have to exist before
 * anything can be presented into them, and hands them here.
 */
class WineXSession(
    private val context: Context,
    private val prefix: Container,
    private val target: String,
    private val workingDir: File,
    private val xServer: XServer,
) {

    private var environment: XEnvironment? = null
    private val tail = ArrayDeque<String>()

    private val outputCollector = Callback<String> { line ->
        synchronized(tail) {
            tail.addLast(line)
            while (tail.size > OUTPUT_TAIL_LINES) tail.removeFirst()
        }
    }

    /** The tail of whatever Wine and box64 printed, for a failure the user has to be told about. */
    fun output(): String = synchronized(tail) { tail.joinToString("\n") }

    /**
     * Builds the environment and starts the guest. [onTerminated] fires
     * with the guest's exit status when it ends, on whatever thread
     * gamenative's launcher reports on.
     *
     * Throws if the environment itself refuses to start; the caller
     * reports that rather than an exit code that never happened.
     */
    fun start(onTerminated: (Int) -> Unit) {
        val imageFs = ImageFs.find(context)
        val contentsManager = ContentsManager(context).apply { syncContents() }
        val wineInfo = WineInfo.fromIdentifier(context, contentsManager, prefix.wineVersion)
        // getWinePath() defaults to <rootfs>/opt/wine; a proton build
        // lives at opt/<version>, so point ImageFs at the one this prefix
        // is configured for before anything reads it (upstream does
        // exactly this in its own pre-launch phase).
        wineInfo.path?.takeIf { it.isNotEmpty() }?.let { imageFs.setWinePath(it) }

        val rootPath = imageFs.rootDir.path
        val environment = XEnvironment(context, imageFs)
        this.environment = environment

        environment.addComponent(
            SysVSharedMemoryComponent(
                xServer,
                UnixSocketConfig.createSocket(rootPath, UnixSocketConfig.SYSVSHM_SERVER_PATH),
            ),
        )
        environment.addComponent(
            XServerComponent(xServer, UnixSocketConfig.createSocket(rootPath, UnixSocketConfig.XSERVER_PATH)),
        )
        environment.addComponent(NetworkInfoUpdateComponent())

        val envVars = EnvVars().apply {
            putAll(prefix.envVars)
            // droidtop owns where the prefix lives: this container's own
            // directory, not whatever `home/xuser` happens to point at,
            // so the same game keeps the same prefix no matter which
            // container was activated last.
            put("WINEPREFIX", File(prefix.rootDir, ".wine").absolutePath)
            put("WINEDEBUG", "-all")
            // EnvVars parses a string or copies another EnvVars; there is
            // no map overload, so the plan's pairs go in one at a time.
            WineLaunchPlan.audioEnvVars(prefix.audioDriver, rootPath).forEach { (name, value) -> put(name, value) }
        }

        // Audio, wired the way gamenative wires it and driven by the same
        // container field. Its own default is pulseaudio; alsa is the
        // other value a prefix can carry, and a prefix carrying neither
        // gets no audio server rather than a crash.
        when (WineLaunchPlan.audioDriverOf(prefix.audioDriver)) {
            WineLaunchPlan.AudioDriver.PULSEAUDIO -> environment.addComponent(
                PulseAudioComponent(
                    UnixSocketConfig.createSocket(rootPath, UnixSocketConfig.PULSE_SERVER_PATH),
                    prefix.pulseaudioLowLatency,
                ),
            )
            WineLaunchPlan.AudioDriver.ALSA -> environment.addComponent(
                ALSAServerComponent(
                    UnixSocketConfig.createSocket(rootPath, UnixSocketConfig.ALSA_SERVER_PATH),
                    ALSAClient.Options.fromKeyValueSet(null),
                ),
            )
            WineLaunchPlan.AudioDriver.NONE -> Unit
        }

        // The GPU side. Vortek is the container default and is backed by
        // libvortekrenderer.so, which this module already packages; virgl
        // is the OpenGL passthrough path and needs the GL view, which the
        // host Activity picks by the same field.
        when {
            prefix.graphicsDriver == "virgl" -> environment.addComponent(
                VirGLRendererComponent(
                    xServer,
                    UnixSocketConfig.createSocket(rootPath, UnixSocketConfig.VIRGL_SERVER_PATH),
                ),
            )
            prefix.graphicsDriver in VULKAN_DRIVERS -> {
                val config = KeyValueSet(prefix.graphicsDriverConfig)
                if (prefix.graphicsDriver != "vortek") {
                    // adreno / sd-8-elite mean "vortek, but through the
                    // Adrenotools-loaded driver" -- upstream writes the
                    // choice back into the container's own config so the
                    // component reads one field, not two.
                    config.put("adrenotoolsDriver", "vulkan.adreno.so")
                    prefix.graphicsDriverConfig = config.toString()
                }
                environment.addComponent(
                    VortekRendererComponent(
                        xServer,
                        UnixSocketConfig.createSocket(rootPath, UnixSocketConfig.VORTEK_SERVER_PATH),
                        VortekRendererComponent.Options.fromKeyValueSet(context, config),
                        context,
                    ),
                )
            }
        }

        val launcher = BionicProgramLauncherComponent(
            contentsManager,
            contentsManager.getProfileByEntryName(prefix.wineVersion),
        ).apply {
            setContainer(prefix)
            setWineInfo(wineInfo)
            isWoW64Mode = prefix.isWoW64Mode
            box64Version = prefix.box64Version
            box64Preset = prefix.box64Preset
            setFEXCorePreset(prefix.fexCorePreset)
            // Every mapped drive, so a game on an SD card is reachable
            // from inside the prefix rather than only from Android.
            bindingPaths = prefix.drivesIterator().map { it[1] }.toTypedArray()
            this.envVars = envVars
            setWorkingDir(workingDir.takeIf { it.isDirectory } ?: imageFs.rootDir)
            guestExecutable = WineLaunchPlan.guestExecutable(xServer.screenInfo.toString(), target)
            setTerminationCallback { code -> onTerminated(code ?: EXEC_FAILED) }
        }
        environment.addComponent(launcher)

        ProcessHelper.addDebugCallback(outputCollector)
        try {
            environment.startEnvironmentComponents()
        } catch (e: Throwable) {
            ProcessHelper.removeDebugCallback(outputCollector)
            runCatching { environment.stopEnvironmentComponents() }
            this.environment = null
            throw e
        }

        // WinHandler is what carries controller state into the guest's
        // xinput bridge; it does network setup on start, so it is not
        // started on the UI thread. The Activity owns the handler itself.
        xServer.winHandler?.start()
    }

    fun onPause() {
        environment?.onPause()
    }

    fun onResume() {
        environment?.onResume()
    }

    fun stop() {
        val environment = this.environment ?: return
        this.environment = null
        runCatching { xServer.winHandler?.stop() }
        ProcessHelper.removeDebugCallback(outputCollector)
        runCatching { environment.stopEnvironmentComponents() }
    }

    private companion object {
        const val OUTPUT_TAIL_LINES = 40
        const val EXEC_FAILED = -1

        /** Container graphics-driver values that all resolve to the Vortek Vulkan renderer. */
        val VULKAN_DRIVERS = setOf("vortek", "adreno", "sd-8-elite")
    }
}

/**
 * The decisions a Wine launch makes that are pure functions of the
 * prefix and the target -- kept out of [WineXSession] so they can be
 * tested without an Android environment, a container or a GPU.
 */
object WineLaunchPlan {

    enum class AudioDriver { PULSEAUDIO, ALSA, NONE }

    fun audioDriverOf(containerValue: String?): AudioDriver = when (containerValue?.lowercase()) {
        "pulseaudio" -> AudioDriver.PULSEAUDIO
        "alsa" -> AudioDriver.ALSA
        else -> AudioDriver.NONE
    }

    /**
     * What the guest needs in its environment to find the audio server
     * droidtop started for it. The paths are the socket paths the
     * matching component binds, so the two cannot drift apart.
     */
    fun audioEnvVars(containerValue: String?, rootPath: String): Map<String, String> =
        when (audioDriverOf(containerValue)) {
            AudioDriver.PULSEAUDIO -> mapOf(
                "PULSE_SERVER" to rootPath + UnixSocketConfig.PULSE_SERVER_PATH,
            )
            AudioDriver.ALSA -> mapOf(
                "ANDROID_ALSA_SERVER" to rootPath + UnixSocketConfig.ALSA_SERVER_PATH,
                "ANDROID_ASERVER_USE_SHM" to "true",
            )
            AudioDriver.NONE -> emptyMap()
        }

    /**
     * The command handed to the guest.
     *
     * `explorer /desktop=shell,<w>x<h>` is not decoration: it makes Wine
     * create one virtual-desktop window at the X screen's own size, which
     * is what gives a game a mapped, sized window to draw into instead of
     * whatever geometry its own code assumes about a real desktop. It is
     * what gamenative's own launch does, and running without it is why
     * `wine <target>` alone produced nothing worth presenting.
     *
     * Spaces are escaped rather than quoted: `ProcessHelper.splitCommand`
     * keeps quote characters inside the argument it produces, which would
     * hand Wine a path that does not exist, but it treats a
     * backslash-space pair as a literal space.
     */
    fun guestExecutable(screenInfo: String, target: String): String =
        "wine explorer /desktop=shell,$screenInfo " + target.replace(" ", "\\ ")
}
