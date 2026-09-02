package dev.droidtop.runtime.windows

import android.content.Context
import com.winlator.container.Container
import com.winlator.contents.ContentsManager
import com.winlator.core.Callback
import com.winlator.core.ProcessHelper
import com.winlator.core.WineInfo
import com.winlator.core.envvars.EnvVars
import com.winlator.xconnector.UnixSocketConfig
import com.winlator.xenvironment.ImageFs
import com.winlator.xenvironment.XEnvironment
import com.winlator.xenvironment.components.BionicProgramLauncherComponent
import com.winlator.xenvironment.components.NetworkInfoUpdateComponent
import com.winlator.xenvironment.components.SysVSharedMemoryComponent
import com.winlator.xenvironment.components.XServerComponent
import com.winlator.xserver.ScreenInfo
import com.winlator.xserver.XServer
import dev.droidtop.library.PcLaunchResult
import java.io.File
import java.util.ArrayDeque
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Runs a Windows executable in a Wine prefix, as the app's own uid and
 * NEVER with root.
 *
 * SECURITY BOUNDARY (docs/SPEC.md 5b, stated 2026-09-02): Wine exists
 * to execute arbitrary third-party Windows binaries -- code the user
 * downloaded from somewhere. A guest that wants to be malicious does
 * not need an exploit if the process it runs in has root; it just
 * needs to be run. So the Wine guest process must never execute in a
 * root-capable context: not on a rooted device, not in desktop mode,
 * not as an optimisation. That is why this is a SEALED interface that
 * deliberately cannot be handed a `ContainerRuntime`: the runtime
 * selection (`ContainerRuntimeFactory.select`) yields the root-backed
 * droidspaces runtime whenever root is present, and an engine built on
 * it would silently run guests as root on exactly the devices where it
 * matters. An implementation outside this module cannot exist, and one
 * inside it that touches `RootProcess` or `ContainerRuntime` is a
 * boundary violation, whatever it is called.
 *
 * History, so the pre-boundary shape does not return: both launch
 * sites used to demand a live
 * `dev.droidtop.runtime.PrimaryContainerSession` -- a droidspaces
 * container, root-only -- and executed Wine through
 * `ContainerRuntime.exec`. That made Windows games root-only by
 * accident (the no-root backend's `exec` was a `TODO()`), and had it
 * ever worked, it would have run downloaded binaries as root. The
 * seam stays because execution *mechanism* can still vary (box64
 * today, arm64ec/FEX deliberately later) -- but every variant runs as
 * the app's own uid.
 */
sealed interface WineEngine {

    /** Whether this engine could run something right now, and why not when it cannot. */
    fun readiness(prefix: Container): WineEngineReadiness

    /**
     * Runs [target] under Wine in [prefix], with [workingDir] as the
     * process working directory, and suspends until it exits.
     *
     * [target] is whatever `wine` itself should be handed: the Unix path
     * of an executable on a mapped drive, or the Windows path a
     * `.desktop` shortcut already stores. Both are things Wine resolves;
     * neither is something this engine should try to convert.
     */
    suspend fun launch(prefix: Container, target: String, workingDir: File): PcLaunchResult
}

/** Either ready, or the specific missing piece a user can act on. */
sealed interface WineEngineReadiness {
    data object Ready : WineEngineReadiness
    data class Missing(val reason: String) : WineEngineReadiness
}

/**
 * The no-root Wine engine: gamenative's own bionic execution model,
 * which is the one that works on this target.
 *
 * `:runtime-windows` compiles the vendored tree with
 * `MODERN_ANDROID = true`, because Android refuses to `exec()` extracted
 * binaries above `targetSdk 28`. On that path gamenative runs the guest
 * with a plain [ProcessHelper] exec against the [ImageFs] root, loaded
 * through `/system/bin/linker64` -- no proot, and no Linux container.
 * proot is the *glibc* variant's mechanism, and upstream deleted the
 * arm64 `libproot.so`, so there is no arm64 proot to port to; that whole
 * line of enquiry is a dead end, and the no-root path does not need one.
 *
 * Everything below this seam is gamenative's own machinery, deliberately
 * used rather than re-derived: [BionicProgramLauncherComponent] builds
 * the real guest environment (the box64/FEXCore sets, the `LD_PRELOAD`
 * of the sysvshm and W^X redirect shims, the wine PATH), extracts the
 * translator payload for the box64 version the prefix is configured for,
 * and starts the process. droidtop supplies only what is genuinely its
 * own: which prefix, which executable, and where the output goes.
 *
 * Display: the X server components below are real and accept Wine's
 * connection, but nothing presents their output yet -- droidtop composes
 * through `:host-bridge`, not through gamenative's Android SurfaceView
 * renderer. A game therefore reaches Wine and runs without a picture.
 * Wiring a renderer to droidtop's own surface is separate, named work
 * that this seam cannot decide on its own.
 */
class BionicWineEngine(private val context: Context) : WineEngine {

    override fun readiness(prefix: Container): WineEngineReadiness {
        val imageFs = ImageFs.find(context)
        if (!imageFs.isValid) {
            return WineEngineReadiness.Missing(
                "the Windows system files are not installed yet -- run \"Set up Windows games\" in Settings",
            )
        }
        val wine = wineBinary(prefix, imageFs)
        if (!wine.isFile) {
            return WineEngineReadiness.Missing(
                "no wine binary at ${wine.absolutePath} for ${prefix.wineVersion} " +
                    "-- run \"Set up Windows games\" in Settings",
            )
        }
        return WineEngineReadiness.Ready
    }

    override suspend fun launch(
        prefix: Container,
        target: String,
        workingDir: File,
    ): PcLaunchResult = withContext(Dispatchers.IO) {
        (readiness(prefix) as? WineEngineReadiness.Missing)?.let {
            return@withContext PcLaunchResult(false, it.reason)
        }

        val imageFs = ImageFs.find(context)
        val contentsManager = ContentsManager(context).apply { syncContents() }
        val wineInfo = WineInfo.fromIdentifier(context, contentsManager, prefix.wineVersion)
        // getWinePath() defaults to <rootfs>/opt/wine; a proton build
        // lives at opt/<version>, so point ImageFs at the one this prefix
        // is configured for before anything reads it (upstream does
        // exactly this in its own pre-launch phase).
        wineInfo.path?.takeIf { it.isNotEmpty() }?.let { imageFs.setWinePath(it) }

        val environment = XEnvironment(context, imageFs)
        val rootPath = imageFs.rootDir.path
        // A real X server, because Wine's X11 driver has to connect to
        // something for the process to get past window-system init. It is
        // headless on purpose (see this class' own doc comment): no
        // renderer is attached, so nothing is presented yet.
        val xServer = XServer(ScreenInfo(prefix.screenSize), false)
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

        val launcher = BionicProgramLauncherComponent(
            contentsManager,
            contentsManager.getProfileByEntryName(prefix.wineVersion),
        ).apply {
            setContainer(prefix)
            setWineInfo(wineInfo)
            isWoW64Mode = prefix.isWoW64Mode
            box64Preset = prefix.box64Preset
            // Every mapped drive, so a game on an SD card is reachable
            // from inside the prefix rather than only from Android.
            bindingPaths = prefix.drivesIterator().map { it[1] }.toTypedArray()
            envVars = EnvVars().apply {
                putAll(prefix.envVars)
                // droidtop owns where the prefix lives: this container's
                // own directory, not whatever `home/xuser` happens to
                // point at, so the same game keeps the same prefix no
                // matter which container was activated last.
                put("WINEPREFIX", File(prefix.rootDir, ".wine").absolutePath)
                put("WINEDEBUG", "-all")
            }
            setWorkingDir(workingDir.takeIf { it.isDirectory } ?: imageFs.rootDir)
            // Spaces are escaped rather than quoted: ProcessHelper's own
            // splitCommand keeps the quote characters inside the argument
            // it produces, which would hand Wine a path that does not
            // exist, but it treats a backslash-space pair as a literal
            // space.
            guestExecutable = "wine " + target.replace(" ", "\\ ")
        }
        environment.addComponent(launcher)

        // The tail of whatever Wine and box64 printed. A launch that
        // fails is only useful if the caller can say what it said.
        val tail = ArrayDeque<String>()
        val collector = Callback<String> { line ->
            synchronized(tail) {
                tail.addLast(line)
                while (tail.size > OUTPUT_TAIL_LINES) tail.removeFirst()
            }
        }
        ProcessHelper.addDebugCallback(collector)

        // Set only when the environment itself refused to start, so the
        // failure names that rather than an exit code that never happened.
        var startFailure: String? = null

        try {
            val status = suspendCancellableCoroutine { continuation ->
                launcher.setTerminationCallback { code -> continuation.resume(code ?: EXEC_FAILED) }
                continuation.invokeOnCancellation { runCatching { environment.stopEnvironmentComponents() } }
                val started = runCatching { environment.startEnvironmentComponents() }
                if (started.isFailure && continuation.isActive) {
                    startFailure = started.exceptionOrNull()?.message ?: "the Wine environment failed to start"
                    continuation.resume(EXEC_FAILED)
                }
            }
            val output = synchronized(tail) { tail.joinToString("\n") }
            PcLaunchResult(
                succeeded = status == 0,
                detail = when {
                    status == 0 -> "ok"
                    startFailure != null -> startFailure
                    output.isBlank() -> "wine exited $status with no output"
                    else -> "wine exited $status: $output"
                }.toString(),
            )
        } finally {
            ProcessHelper.removeDebugCallback(collector)
            runCatching { environment.stopEnvironmentComponents() }
        }
    }

    /**
     * Where this prefix's wine binary should be. A proton build resolves
     * to `opt/<version>` (a symlink into the shared proton store, so two
     * containers on the same build share one copy); the plain default
     * resolves to `opt/wine`.
     */
    private fun wineBinary(prefix: Container, imageFs: ImageFs): File {
        val contentsManager = ContentsManager(context).apply { syncContents() }
        val info = runCatching { WineInfo.fromIdentifier(context, contentsManager, prefix.wineVersion) }.getOrNull()
        val root = info?.path?.takeIf { it.isNotEmpty() }
            ?: return File(imageFs.rootDir, "opt/wine/bin/wine")
        return File(root, "bin/wine")
    }

    private companion object {
        const val OUTPUT_TAIL_LINES = 40
        const val EXEC_FAILED = -1
    }
}
