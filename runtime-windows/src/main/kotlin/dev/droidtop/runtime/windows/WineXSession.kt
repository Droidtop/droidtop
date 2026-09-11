package dev.droidtop.runtime.windows

import android.content.Context
import com.winlator.alsaserver.ALSAClient
import com.winlator.container.Container
import com.winlator.contents.ContentsManager
import com.winlator.core.Callback
import com.winlator.core.FileUtils
import com.winlator.core.KeyValueSet
import com.winlator.core.ProcessHelper
import com.winlator.core.WineInfo
import com.winlator.core.envvars.EnvVars
import com.winlator.winhandler.WinHandler.PreferredInputApi
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
import kotlinx.coroutines.runBlocking

/**
 * The Wine guest and everything it draws and listens through, as one
 * object with a lifetime.
 *
 * This is the whole of what used to live inside `BionicWineEngine.launch`
 * plus the pieces that launch never had: the prefix preparation
 * ([WinePrefixPreparation]), the audio server, the GPU renderer
 * component, and an [XServer] whose renderer is a real Android surface.
 * The reason those were missing is the reason a Windows game used to
 * start and show nothing -- Wine connected to a headless X server, drew
 * into it, and no one was looking.
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
     * Prepares the prefix, builds the environment and starts the guest.
     * [onTerminated] fires with the guest's exit status when it ends, on
     * whatever thread gamenative's launcher reports on.
     *
     * Throws if the preparation or the environment itself refuses to
     * start; the caller reports that rather than an exit code that never
     * happened.
     */
    fun start(onTerminated: (Int) -> Unit) {
        // A wineserver left behind by a previous launch owns the prefix
        // and will refuse this one's; killing stale guests first is what
        // gamenative does before every launch, for the same reason.
        runCatching { ProcessHelper.hardKillStaleWineProcesses() }

        val imageFs = ImageFs.find(context)
        val contentsManager = ContentsManager(context).apply { syncContents() }
        val wineInfo = WineInfo.fromIdentifier(context, contentsManager, prefix.wineVersion)

        // Drive symlinks, DX wrapper DLLs, the Vulkan driver, the wine
        // audio-driver registry value: everything the guest needs to
        // already be true of the prefix. It also hands back the
        // environment variables those steps communicate through, which is
        // why its result IS the base env rather than something merged
        // into one.
        val envVars: EnvVars = runBlocking { WinePrefixPreparation.prepare(context, prefix, xServer.screenInfo) }

        val rootPath = imageFs.rootDir.path
        // Wine's own temp directory, from a previous run. gamenative
        // clears it at the same point; a half-written shader cache or
        // installer payload left there is read back as if it were this
        // launch's.
        runCatching { FileUtils.clear(imageFs.tmpDir) }

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

        envVars.apply {
            // The prefix's own locale. A Japanese-locale game reads
            // cp932 paths and text through it, so leaving it out is not
            // neutral -- it is choosing C.
            put("LC_ALL", prefix.lC_ALL)
            put("MESA_DEBUG", "silent")
            put("MESA_NO_ERROR", "1")
            // droidtop owns where the prefix lives: this container's own
            // directory, not whatever `home/xuser` happens to point at,
            // so the same game keeps the same prefix no matter which
            // container was activated last.
            put("WINEPREFIX", File(prefix.rootDir, ".wine").absolutePath)
            put("WINEDEBUG", "-all")
            // EnvVars parses a string or copies another EnvVars; there is
            // no map overload, so the plan's pairs go in one at a time.
            WineLaunchPlan.audioEnvVars(prefix.audioDriver, rootPath).forEach { (name, value) -> put(name, value) }
            WineLaunchPlan.controllerEnvVars(prefix.isSdlControllerAPI, prefix.inputType)
                .forEach { (name, value) -> put(name, value) }
            // The prefix's own variables last, so a person who set one by
            // hand wins over every default above.
            putAll(prefix.envVars)
            // Frame-rate caps belong to gamenative's own in-game limiter
            // UI, which droidtop does not present; a stale value from the
            // prefix would silently cap a game nobody asked to cap.
            remove("DXVK_FRAME_RATE")
            remove("VKD3D_FRAME_RATE")
            if (!has("WINEESYNC")) put("WINEESYNC", "1")
            WineLaunchPlan.turnipDebug(KeyValueSet(prefix.graphicsDriverConfig).get("version"), get("TU_DEBUG"))
                ?.let { put("TU_DEBUG", it) }
        }

        // Audio, wired the way gamenative wires it and driven by the same
        // container field. Its own default is pulseaudio; alsa is the
        // other value a prefix can carry, and a prefix carrying neither
        // gets no audio server rather than a crash. Which backend WINE
        // hands audio to is the same field, written into the prefix's
        // registry by the preparation above.
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
            box86Version = prefix.box86Version
            box86Preset = prefix.box86Preset
            setFEXCorePreset(prefix.fexCorePreset)
            // Every mapped drive, so a game on an SD card is reachable
            // from inside the prefix rather than only from Android.
            bindingPaths = prefix.drivesIterator().map { it[1] }.toTypedArray()
            this.envVars = envVars
            setWorkingDir(workingDir.takeIf { it.isDirectory } ?: imageFs.rootDir)
            guestExecutable = WineLaunchPlan.guestExecutable(xServer.screenInfo.toString(), target, prefix.execArgs)
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
     * What a guest built against SDL needs told about controllers, when
     * the prefix says its games read the pad through SDL rather than
     * through wine's own xinput.
     *
     * SDL picks its own joystick backend from these, and which backend
     * is right is the prefix's `inputType` -- the same field WinHandler
     * is configured from on the Android side, so the two halves of one
     * decision cannot disagree. A prefix that does not claim the SDL API
     * gets nothing, exactly as upstream.
     */
    fun controllerEnvVars(sdlControllerApi: Boolean, inputType: Int): Map<String, String> {
        if (!sdlControllerApi) return emptyMap()
        val xinput = inputType == PreferredInputApi.XINPUT.ordinal ||
            inputType == PreferredInputApi.AUTO.ordinal ||
            inputType == PreferredInputApi.BOTH.ordinal
        val dinput = inputType == PreferredInputApi.DINPUT.ordinal ||
            inputType == PreferredInputApi.BOTH.ordinal
        return mapOf(
            "SDL_XINPUT_ENABLED" to if (xinput) "1" else "0",
            "SDL_DIRECTINPUT_ENABLED" to if (dinput) "1" else "0",
            "SDL_JOYSTICK_HIDAPI" to if (xinput) "1" else "0",
            "SDL_JOYSTICK_WGI" to "0",
            "SDL_JOYSTICK_RAWINPUT" to "0",
            "SDL_JOYSTICK_ALLOW_BACKGROUND_EVENTS" to "1",
            "SDL_HINT_FORCE_RAISEWINDOW" to "0",
            "SDL_ALLOW_TOPMOST" to "0",
            "SDL_MOUSE_FOCUS_CLICKTHROUGH" to "1",
        )
    }

    /**
     * Turnip's `nolrz` workaround, added for the gen8 driver versions
     * that need it and for no others -- returns null when the current
     * value already stands, so the caller never writes a variable it did
     * not change.
     */
    fun turnipDebug(graphicsDriverVersion: String, currentValue: String): String? {
        if (!graphicsDriverVersion.lowercase().contains("gen8")) return null
        if (currentValue.contains("nolrz")) return null
        return if (currentValue.isEmpty()) "nolrz" else "$currentValue,nolrz"
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
     * backslash-space pair as a literal space. [execArgs] is the prefix's
     * own argument string and goes through unescaped, the way a person
     * typed it.
     */
    fun guestExecutable(screenInfo: String, target: String, execArgs: String = ""): String {
        val command = "wine explorer /desktop=shell,$screenInfo " + target.replace(" ", "\\ ")
        return if (execArgs.isBlank()) command else "$command ${execArgs.trim()}"
    }
}
