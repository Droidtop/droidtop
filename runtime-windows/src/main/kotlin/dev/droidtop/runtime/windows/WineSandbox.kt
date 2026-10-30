package dev.droidtop.runtime.windows

import android.content.Context
import android.os.Looper
import com.winlator.core.envvars.EnvVars
import dev.droidtop.library.settings.CatalogPrefs
import dev.droidtop.library.settings.DeveloperMenu
import dev.droidtop.sandboxhost.SandboxHost
import java.io.File
import timber.log.Timber

/**
 * Wine's process tree under the sandbox broker (docs/SPEC.md 5b "Containment and copy-on-write layers for Wine",
 * piece 0; Droidtop/tracker#584). Piece 0 is learning only: behind a Developer menu switch, off by default, a launch
 * starts a learning listener ([SandboxHost.startLearning]) and every process of the game's tree attaches to it, so
 * what Wine really reaches is written to a learn log before any rule is tightened (SPEC 5b: "What Wine really uses is
 * found with the library's learning mode, not by tightening first"). A learning run gives NO protection, and the
 * switch says so.
 */
object WineSandbox {
    private const val TAG = "droidtop.WineSandbox"
    private const val KEY_LEARNING = "droidtop_wine_sandbox_learning"
    private const val LOGS_KEPT = 5

    /**
     * The GPU nodes a driver opens itself (Turnip and the wrapper open the Adreno node, Mesa a DRM render node), so the
     * descriptor is Wine's own and not one the broker opened in droidtop's process.
     */
    val GPU_NODES = listOf("/dev/kgsl-3d0", "/dev/dri/renderD128", "/dev/dri/card0", "/dev/mali0")

    /** On only while the Developer menu is: turning the menu off turns learning off with it. */
    fun learning(context: Context): Boolean =
        DeveloperMenu.on(context) && CatalogPrefs.prefs(context).getBoolean(KEY_LEARNING, false)

    fun setLearning(context: Context, on: Boolean) {
        CatalogPrefs.prefs(context).edit().putBoolean(KEY_LEARNING, on).apply()
    }

    /** Where the learn logs are, newest last by name. */
    fun logDir(context: Context): File = File(context.filesDir, "wine-sandbox")

    @Volatile private var session: SandboxHost.Session? = null

    /**
     * The launch hook (BionicProgramLauncherComponent, after the other droidtop hooks, since the session's environment
     * is laid over the launcher's own): a new launch stops the last listener (its attached processes are still served
     * until they end), and while learning is on starts one for this tree and puts the attach environment and
     * libsbx_child.so first on LD_PRELOAD. Disk work: never on the main thread.
     */
    @JvmStatic
    fun applyLaunchEnv(context: Context, envVars: EnvVars) {
        session?.close()
        session = null
        if (!learning(context)) return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Timber.tag(TAG).w("not learning: the launch ran on the main thread")
            return
        }
        val dir = logDir(context)
        dir.mkdirs()
        prune(dir)
        val log = File(dir, "learn-${System.currentTimeMillis()}.log")
        val started = SandboxHost.startLearning(File(context.filesDir, "sbx"), log)
        if (started == null) {
            Timber.tag(TAG).w("not learning: the broker could not listen (logcat droidtop.sandbox says why)")
            return
        }
        session = started
        val lib = File(context.applicationInfo.nativeLibraryDir)
        started.environment(lib, GPU_NODES).forEach { (name, value) -> envVars.put(name, value) }
        envVars.put("LD_PRELOAD", SandboxHost.withChildPreload(envVars.get("LD_PRELOAD"), lib))
        Timber.tag(TAG).w("Wine runs under the sandbox broker in LEARNING mode, with no protection; records in %s", log.path)
    }

    /** Keeps the newest [LOGS_KEPT] - 1 logs, so with the one about to be written there are [LOGS_KEPT]. */
    private fun prune(dir: File) {
        val logs = dir.listFiles { f -> f.isFile && f.name.startsWith("learn-") && f.name.endsWith(".log") }.orEmpty()
        logs.sortedBy { it.name.removePrefix("learn-").removeSuffix(".log").toLongOrNull() ?: 0L }
            .dropLast(LOGS_KEPT - 1)
            .forEach { it.delete() }
    }
}
