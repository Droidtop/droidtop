package dev.droidtop.runtime.systemstatus

/**
 * How much the in-game performance overlay shows (docs/SPEC.md, "Performance overlay"). Steam Deck's graduated
 * levels: the user picks how much, and a higher level is more obtrusive.
 */
enum class OverlayLevel(val key: String, val label: String) {
    OFF("off", "Off"),
    FPS("fps", "FPS"),
    BASIC("basic", "Basic"),
    FULL("full", "Full");

    /** The level a press cycles to; Full wraps to Off. */
    fun next(): OverlayLevel = values()[(ordinal + 1) % values().size]

    companion object {
        fun fromKey(key: String?): OverlayLevel = values().firstOrNull { it.key == key } ?: OFF
    }
}

/** Frames presented by a game's surface over the last second. */
data class FrameRate(val fps: Int, val avgFrameMs: Double, val worstFrameMs: Double)

/** What a `priv.shell` provider could read in one pass; every field is null when it could not be read. */
data class ShellReadings(
    val frameRate: FrameRate? = null,
    val cpuTimes: CpuTimes? = null,
    val gpuPercent: Int? = null,
    val cpuTempTenthC: Int? = null,
    val gpuTempTenthC: Int? = null,
)

/** Everything the overlay can draw. A null is a reading nobody could take, drawn as "--", never as a guess. */
data class OverlayReadings(
    val frameRate: FrameRate? = null,
    val cpuPercent: Int? = null,
    val gpuPercent: Int? = null,
    val batteryPercent: Int? = null,
    val charging: Boolean = false,
    val memUsedMb: Int? = null,
    val memTotalMb: Int? = null,
    val cpuTempTenthC: Int? = null,
    val gpuTempTenthC: Int? = null,
    val batteryTempTenthC: Int? = null,
)

/**
 * The pure parts of the overlay: reading the shell probe's output, picking the game's SurfaceFlinger layer, turning
 * `dumpsys SurfaceFlinger --latency` into a frame rate, and the lines each level draws. Unit tested.
 */
object PerformanceOverlayModel {
    /**
     * One command run through the `priv.shell` provider per second, so the provider is called once and not once
     * per reading. `$1` is the SurfaceFlinger layer (empty for none), `$2` the level key. Shell builtins only for
     * the sysfs reads, so a pass forks next to nothing. Sections start with a `#name` line.
     */
    const val PROBE_SCRIPT: String =
        "if [ -n \"\$1\" ]; then echo '#lat'; dumpsys SurfaceFlinger --latency \"\$1\"; fi\n" +
            "if [ \"\$2\" != fps ]; then\n" +
            "  read l < /proc/stat; echo '#cpu'; echo \"\$l\"\n" +
            "  echo '#gpu'; read g < /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage && echo \"\$g\"\n" +
            "fi\n" +
            "if [ \"\$2\" = full ]; then\n" +
            "  echo '#temp'\n" +
            "  for z in /sys/class/thermal/thermal_zone*; do read t < \"\$z/type\"; read v < \"\$z/temp\"; echo \"\$t \$v\"; done\n" +
            "fi\n"

    fun probeArgv(layer: String?, level: OverlayLevel): List<String> =
        listOf("sh", "-c", PROBE_SCRIPT, "sh", layer.orEmpty(), level.key)

    /** Splits the probe output at its `#name` lines. */
    fun sections(output: String?): Map<String, List<String>> {
        val out = LinkedHashMap<String, MutableList<String>>()
        var current: MutableList<String>? = null
        output?.lineSequence()?.forEach { raw ->
            val line = raw.trim()
            if (line.startsWith("#")) {
                current = out.getOrPut(line.substring(1)) { mutableListOf() }
            } else if (line.isNotEmpty()) {
                current?.add(line)
            }
        }
        return out
    }

    /** Reads one probe pass. [nowNs] is `System.nanoTime()` when the pass returned, the clock SurfaceFlinger stamps frames with. */
    fun parseProbe(output: String?, nowNs: Long): ShellReadings {
        val parts = sections(output)
        val temps = parts["temp"].orEmpty().mapNotNull(::parseZone)
        return ShellReadings(
            frameRate = parts["lat"]?.let { parseLatency(it, nowNs) },
            cpuTimes = PerformanceMonitor.parseCpuStat(parts["cpu"]?.firstOrNull()),
            gpuPercent = parts["gpu"]?.firstOrNull()?.let(::leadingInt)?.coerceIn(0, 100),
            cpuTempTenthC = hottest(temps, "cpu"),
            gpuTempTenthC = hottest(temps, "gpu"),
        )
    }

    private fun leadingInt(text: String): Int? = Regex("""^\s*(\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull()

    /** "cpuss-0-usr 45000" to a zone name and tenths of a degree; kernels report millidegrees. */
    private fun parseZone(line: String): Pair<String, Int>? {
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size < 2) return null
        val milli = parts.last().toLongOrNull() ?: return null
        if (milli <= 0 || milli > 200_000) return null
        return parts.dropLast(1).joinToString(" ").lowercase() to (milli / 100).toInt()
    }

    private fun hottest(zones: List<Pair<String, Int>>, word: String): Int? =
        zones.filter { it.first.contains(word) }.maxOfOrNull { it.second }

    /**
     * The layer of [packageName]'s game in `dumpsys SurfaceFlinger --list`: a SurfaceView layer first (that is where a
     * game draws), else the app's window layer; background and bounds helper layers never.
     */
    fun pickLayer(listing: String?, packageName: String): String? {
        val layers = listing?.lineSequence()?.map { it.trim() }?.filter { it.isNotEmpty() && packageName in it }
            ?.filterNot { it.startsWith("Background for") || it.startsWith("Bounds for") || it.startsWith("Dim Layer") }
            ?.toList().orEmpty()
        return layers.firstOrNull { it.startsWith("SurfaceView[") && "(BLAST)" in it }
            ?: layers.firstOrNull { it.startsWith("SurfaceView[") }
            ?: layers.firstOrNull { "(BLAST)" in it }
            ?: layers.firstOrNull()
    }

    private const val NOT_PRESENTED = Long.MAX_VALUE
    private const val WINDOW_NS = 1_000_000_000L
    private const val STALE_NS = 2_000_000_000L

    /**
     * `dumpsys SurfaceFlinger --latency <layer>`: the refresh period, then up to 127 rows of desired, actual and
     * ready times in nanoseconds. The rate is the frames actually presented in the last second before the newest;
     * when the newest is older than two seconds before [nowNs] the game presents nothing and the rate is zero.
     * Null when no row is a real frame (the layer name matched nothing, or the layer has not drawn).
     */
    fun parseLatency(lines: List<String>, nowNs: Long): FrameRate? {
        val presented = lines.drop(1).mapNotNull { row ->
            val cols = row.trim().split(Regex("\\s+"))
            cols.getOrNull(1)?.toLongOrNull()?.takeIf { cols.size >= 3 && it > 0 && it != NOT_PRESENTED }
        }.sorted()
        if (presented.size < 2) return null
        val newest = presented.last()
        if (nowNs - newest > STALE_NS) return FrameRate(0, 0.0, 0.0)
        val window = presented.filter { it > newest - WINDOW_NS }
        if (window.size < 2) return FrameRate(1, WINDOW_NS / 1e6, WINDOW_NS / 1e6)
        val gaps = window.zipWithNext { a, b -> (b - a) / 1e6 }
        val span = (window.last() - window.first()) / 1e9
        return FrameRate(Math.round((window.size - 1) / span).toInt(), gaps.average(), gaps.max())
    }

    /** Combines the unprivileged readings of the shared sampler with one shell pass into the overlay's figures. */
    fun readings(sample: PerfSample?, shell: ShellReadings?, previousCpu: CpuTimes?): OverlayReadings = OverlayReadings(
        frameRate = shell?.frameRate,
        cpuPercent = PerformanceMonitor.cpuPercent(previousCpu, shell?.cpuTimes),
        gpuPercent = shell?.gpuPercent,
        batteryPercent = sample?.batteryPercent,
        charging = sample?.charging ?: false,
        memUsedMb = sample?.let { it.memTotalMb - it.memAvailMb },
        memTotalMb = sample?.memTotalMb,
        cpuTempTenthC = shell?.cpuTempTenthC,
        gpuTempTenthC = shell?.gpuTempTenthC,
        batteryTempTenthC = sample?.batteryTempTenthC,
    )

    /** The lines [level] draws, top to bottom. Off draws nothing. */
    fun lines(level: OverlayLevel, r: OverlayReadings): List<String> {
        if (level == OverlayLevel.OFF) return emptyList()
        val fps = "FPS ${r.frameRate?.fps ?: "--"}"
        if (level == OverlayLevel.FPS) return listOf(fps)
        val battery = (r.batteryPercent?.let { "$it%" } ?: "--") + if (r.charging) " +" else ""
        val basic = listOf(fps, "CPU ${percent(r.cpuPercent)}", "GPU ${percent(r.gpuPercent)}", "BAT $battery")
        if (level == OverlayLevel.BASIC) return basic
        val frame = r.frameRate?.let { "Frame %.1f ms (worst %.1f)".format(java.util.Locale.ROOT, it.avgFrameMs, it.worstFrameMs) } ?: "Frame --"
        val ram = if (r.memUsedMb != null && r.memTotalMb != null) "RAM ${r.memUsedMb} / ${r.memTotalMb} MB" else "RAM --"
        return basic + listOf(
            frame,
            "CPU ${temp(r.cpuTempTenthC)}   GPU ${temp(r.gpuTempTenthC)}   BAT ${temp(r.batteryTempTenthC)}",
            ram,
        )
    }

    private fun percent(value: Int?) = value?.let { "$it%" } ?: "--"

    private fun temp(tenthC: Int?) = tenthC?.let { "%d.%d C".format(java.util.Locale.ROOT, it / 10, Math.abs(it % 10)) } ?: "--"
}
