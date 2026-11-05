package dev.droidtop.runtime.windows

import android.content.Context
import android.os.BatteryManager
import dev.droidtop.library.PcGameRuntimeRegistry
import dev.droidtop.pluginhost.PluginJobsCenter
import java.io.File

/**
 * "Prepare for first launch" as a job (docs/SPEC.md 9; Droidtop/tracker#866): one entry in "Downloads and installs"
 * with progress and Cancel, started only from the game's own row ([WineOptions.PREPARE]), never by itself.
 *
 * The owner's rule (relayed 2026-11-04): opt-in, "never automatic on battery". A run started without [ARG_ON_BATTERY]
 * refuses unless the device is charging; the row offers the battery run as its own, explicit choice. Not pausable:
 * the run is minutes long and has no checkpoint, so Cancel stops it and the cache keeps what it gained.
 */
object PrepareFirstLaunchJob {
    const val KIND = "wine_prepare_first_launch"
    private const val ARG_ENTRY = "entry"
    private const val ARG_ROOT = "root"
    private const val ARG_ON_BATTERY = "on_battery"

    /** Registers the runner with the one jobs registry. Called once at process start. */
    fun register(context: Context) {
        val app = context.applicationContext
        PluginJobsCenter.registerNative(KIND) { args, _, report -> execute(app, args) { percent, line -> report(percent, line, null) } }
    }

    /** Starts the run for [entryId] (or finds the one already running). Null when the runner is not registered. */
    fun start(context: Context, entryId: String, title: String, gameRoot: String, onBattery: Boolean): String? =
        PluginJobsCenter.startNative(
            context = context,
            kind = KIND,
            title = "Preparing $title for its first start",
            args = buildMap {
                put(ARG_ENTRY, entryId)
                put(ARG_ROOT, gameRoot)
                if (onBattery) put(ARG_ON_BATTERY, "1")
            },
            pausable = false,
        )

    private suspend fun execute(context: Context, args: Map<String, String>, report: (Int, String) -> Unit): String {
        val entryId = args[ARG_ENTRY] ?: error("This job names no game.")
        val root = args[ARG_ROOT]?.let(::File) ?: error("This job names no game folder.")
        check(args[ARG_ON_BATTERY] == "1" || charging(context)) {
            "Plug in to prepare this game, or choose \"Prepare now, on battery\" on its Wine settings."
        }
        val runtime = PcGameRuntimeRegistry.runtime as? DroidtopPcGameRuntime ?: error("Windows games are not available here.")
        return runtime.prepareFirstLaunch(entryId, root, report)
    }

    private fun charging(context: Context): Boolean =
        (context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager)?.isCharging == true
}
