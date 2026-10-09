package dev.droidtop.runtime.systemstatus

import android.content.Context
import android.provider.Settings
import dev.droidtop.runtime.keyboard.AccessibilityKeyboard
import dev.droidtop.runtime.tasks.TaskManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The in-game performance overlay's state and sampler (docs/SPEC.md, "Performance overlay"): the chosen
 * [OverlayLevel] (kept across restarts), whether droidtop may draw it, and the [readings] the window shows.
 * The Quick Menu's Performance section sets the level; `:app`'s overlay window draws [readings] while the level is
 * not Off and runs [watch] only then, so with the overlay off nothing is sampled.
 */
object PerformanceOverlay {
    private const val PREFS = "performance_overlay"
    private const val KEY_LEVEL = "level"
    private const val TICK_MS = 1_000L
    private const val LIST_EVERY = 5

    private val levelFlow = MutableStateFlow(OverlayLevel.OFF)
    private val readingsFlow = MutableStateFlow(OverlayReadings())
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val level: StateFlow<OverlayLevel> = levelFlow

    /** The newest figures; the window redraws when they change. */
    val readings: StateFlow<OverlayReadings> = readingsFlow

    /** Reads the stored level. Touches the preferences file: not for the main thread. */
    fun load(context: Context) {
        levelFlow.value = OverlayLevel.fromKey(prefs(context).getString(KEY_LEVEL, null))
    }

    /** Takes effect at once; the write happens off the calling thread. */
    fun setLevel(context: Context, level: OverlayLevel) {
        levelFlow.value = level
        val app = context.applicationContext
        io.launch { prefs(app).edit().putString(KEY_LEVEL, level.key).apply() }
    }

    /**
     * Whether droidtop can put a window over a game: its accessibility overlay needs no grant (the service must be
     * on), an app overlay needs "Display over other apps".
     */
    fun canDraw(context: Context): Boolean = AccessibilityKeyboard.connected || Settings.canDrawOverlays(context)

    /** Whether a `priv.shell` provider is running, which FPS, CPU and GPU need. Cheap: no provider call. */
    fun hasShell(): Boolean = TaskManager.shell.capabilities().shellCommand

    /**
     * Samples until cancelled: once a second, one pass of the shell provider (when there is one) and, from Basic up,
     * the shared sampler's battery and memory. [packageName] is the game in front, asked each pass; its layer is
     * looked up once and kept until the game changes or the layer stops answering.
     */
    suspend fun watch(context: Context, level: OverlayLevel, packageName: () -> String?) {
        val app = context.applicationContext
        var previousCpu: CpuTimes? = null
        var layer: String? = null
        var layerFor: String? = null
        var passesSinceList = 0
        coroutineScope {
            if (level != OverlayLevel.FPS) launch { PerformanceMonitor.watch(app) }
            while (true) {
                val pkg = packageName()
                val shell = TaskManager.shell.takeIf { it.capabilities().shellCommand }
                var probe: ShellReadings? = null
                if (shell != null) {
                    withContext(Dispatchers.IO) {
                        // A game that has no layer yet is looked for again every fifth pass, not every second.
                        if (pkg != layerFor || (layer == null && ++passesSinceList >= LIST_EVERY)) {
                            passesSinceList = 0
                            layerFor = pkg
                            layer = pkg?.let {
                                PerformanceOverlayModel.pickLayer(
                                    shell.exec(listOf("dumpsys", "SurfaceFlinger", "--list"))?.takeIf { out -> out.exit == 0 }?.stdout,
                                    it,
                                )
                            }
                        }
                        val out = runCatching { shell.exec(PerformanceOverlayModel.probeArgv(layer, level)) }.getOrNull()
                        probe = PerformanceOverlayModel.parseProbe(out?.takeIf { it.exit == 0 }?.stdout, System.nanoTime())
                        // A layer that answered with no frames is a stale name (the game restarted): look again.
                        if (probe?.frameRate == null) layer = null
                    }
                }
                val sample = PerformanceMonitor.history.value.lastOrNull()
                readingsFlow.value = PerformanceOverlayModel.readings(sample, probe, previousCpu)
                probe?.cpuTimes?.let { previousCpu = it }
                delay(TICK_MS)
            }
        }
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
