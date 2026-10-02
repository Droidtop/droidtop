package dev.droidtop.runtime.windows

import android.content.Context
import app.gamenative.utils.X86_64GuestLibs
import com.winlator.container.Container
import com.winlator.xenvironment.ImageFs
import dev.droidtop.library.LaunchDisplay
import dev.droidtop.library.PcLaunchResult
import java.io.File
import kotlinx.coroutines.Dispatchers
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
 * seam stays because the execution *mechanism* varies per prefix (an
 * x86_64 Wine under Box64, an arm64ec Wine with FEXCore or Box64 as its
 * emulator DLL, an x86_64 Wine run directly on an x86_64 device) -- but
 * every variant runs as the app's own uid.
 */
sealed interface WineEngine {

    /** Whether this engine could run something right now, and why not when it cannot. */
    fun readiness(prefix: Container): WineEngineReadiness

    /**
     * Starts [target] under Wine in [prefix], with [workingDir] as the
     * process working directory.
     *
     * [target] is whatever `wine` itself should be handed: the Unix path
     * of an executable on a mapped drive, or the Windows path a
     * `.desktop` shortcut already stores. Both are things Wine resolves;
     * neither is something this engine should try to convert.
     * [arguments] follow [target] on Wine's command line, escaped the way
     * [target] is ([WineLaunchPlan.guestExecutable]); an installer opened
     * with droidtop passes `start /unix <path>` this way (docs/SPEC.md 4b).
     *
     * Returns once the game has been HANDED OFF, not once it has exited.
     * The result answers "did this launch start", which is the question
     * every caller actually asks (each one `check`s it and reports the
     * detail to the user); the running game's own lifetime belongs to
     * the Activity that presents it, the same way every other droidtop
     * launch works.
     */
    suspend fun launch(prefix: Container, target: String, workingDir: File, arguments: List<String> = emptyList()): PcLaunchResult
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
 * with a plain `ProcessHelper` exec against the [ImageFs] root, loaded
 * through `/system/bin/linker64` -- no proot, and no Linux container.
 * proot is the *glibc* variant's mechanism, and upstream deleted the
 * arm64 `libproot.so`, so there is no arm64 proot to port to; that whole
 * line of enquiry is a dead end, and the no-root path does not need one.
 *
 * Everything below this seam is gamenative's own machinery, deliberately
 * used rather than re-derived. This class now owns only the two things
 * that are genuinely droidtop's: whether an environment exists to launch
 * into, and where the picture goes. The environment itself -- X server,
 * audio server, GPU renderer component, guest launcher -- is
 * [WineXSession], and the picture is [WineGameActivity], which is started
 * through [LaunchDisplay] so a handheld launch lands on the configured
 * launch-target display like every other launch droidtop makes.
 */
class BionicWineEngine(private val context: Context) : WineEngine {

    override fun readiness(prefix: Container): WineEngineReadiness {
        val imageFs = ImageFs.find(context)
        if (!imageFs.isValid) {
            return WineEngineReadiness.Missing(
                "the Windows system files are not installed yet -- run \"Set up Windows games\" in Settings",
            )
        }
        val wine = WineComponents.wineBinary(context, prefix.wineVersion)
        if (!wine.isFile) {
            return WineEngineReadiness.Missing(
                "no wine binary at ${wine.absolutePath} for ${prefix.wineVersion} " +
                    "-- run \"Set up Windows games\" in Settings",
            )
        }
        if (X86_64GuestLibs.isX86_64Host() && !X86_64GuestLibs.isInstalled(context)) {
            return WineEngineReadiness.Missing(
                "the x86_64 Windows libraries are not installed yet -- run \"Set up Windows games\" in Settings",
            )
        }
        return WineEngineReadiness.Ready
    }

    override suspend fun launch(
        prefix: Container,
        target: String,
        workingDir: File,
        arguments: List<String>,
    ): PcLaunchResult {
        // Whatever this prefix's settings name and the device lacks (a Wine
        // build, a DXVK or FEXCore version, a graphics driver chosen in its
        // Wine settings) is fetched first, the same step setup and the
        // settings screen's Download row run.
        runCatching { WineComponents.ensure(context, prefix) {} }
            .onFailure { return PcLaunchResult(false, "couldn't download what this game's Wine settings need: ${it.message ?: it}") }
        // Readiness reads the contents store off disk, so it does not run
        // on whatever thread the caller happens to be on.
        val missing = withContext(Dispatchers.IO) { readiness(prefix) as? WineEngineReadiness.Missing }
        if (missing != null) return PcLaunchResult(false, missing.reason)
        // Dispatched on the caller's own context, exactly like every
        // other provider's launch (EngineHost, Kirikiroid2, the console
        // players): LaunchDisplay is the one place that decides which
        // screen a launch lands on, and it is the same call for all of
        // them.
        return runCatching {
            LaunchDisplay.start(context, WineGameActivity.intent(context, prefix, target, workingDir, arguments))
        }.fold(
            onSuccess = { PcLaunchResult(true, "ok") },
            onFailure = { PcLaunchResult(false, it.message ?: "couldn't start the Windows game screen") },
        )
    }

}
