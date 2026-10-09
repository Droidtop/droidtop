package dev.droidtop.runtime.systemstatus

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File

/**
 * One reading of the device's health. Every field a normal app may not read is null and a surface says
 * so rather than drawing a guess (docs/SPEC.md, "The companion's tabs").
 */
data class PerfSample(
    val timeMs: Long,
    /** Whole-device load 0..100 from /proc/stat; null on the Android versions that hide it from apps. */
    val deviceCpuPercent: Int?,
    /** droidtop's own share of all cores 0..100, always readable. */
    val ownCpuPercent: Int?,
    /** Highest current core frequency in MHz, when sysfs lets this app read it. */
    val cpuMhz: Int?,
    val memUsedPercent: Int,
    val memAvailMb: Int,
    val memTotalMb: Int,
    val lowMemory: Boolean,
    val batteryPercent: Int?,
    val charging: Boolean,
    /** Tenths of a degree Celsius. */
    val batteryTempTenthC: Int?,
    /** Magnitude in milliamps; the sign is not standardized across vendors, so it is never a direction. */
    val batteryMilliamps: Int?,
    /** [PowerManager] `THERMAL_STATUS_*`, API 29 and later. */
    val thermalStatus: Int?,
)

/** A fixed-capacity history: the oldest value falls off when a new one is added. */
class RingBuffer<T>(private val capacity: Int) {
    private val items = ArrayDeque<T>(capacity)

    @Synchronized
    fun add(item: T) {
        if (items.size == capacity) items.removeFirst()
        items.addLast(item)
    }

    @Synchronized
    fun toList(): List<T> = items.toList()
}

/** Jiffies from the aggregate `cpu` line of /proc/stat. */
data class CpuTimes(val total: Long, val idle: Long)

/** One row of `dumpsys cpuinfo`: only readable with a shell provider, so only shown then. */
data class AppCpu(val packageName: String, val percent: Double)

/**
 * The shared performance data source: the companion's Performance tab and the Quick Menu's performance
 * section read [history], so there is one sampler and one buffer (docs/SPEC.md, "The companion's tabs").
 *
 * Nothing runs by itself. [watch] is the loop a surface runs in the scope that owns its visibility, so a
 * hidden tab polls nothing; leaving composition ends it. Two surfaces watching at once do not double the
 * work: a sample younger than half the interval is not taken again. A reading is a few small file reads
 * and one battery sticky broadcast, done on `Dispatchers.IO`.
 */
object PerformanceMonitor {
    const val INTERVAL_MS = 2_000L

    /** Three minutes of history at [INTERVAL_MS]. */
    const val CAPACITY = 90

    private val ring = RingBuffer<PerfSample>(CAPACITY)
    private val flow = MutableStateFlow<List<PerfSample>>(emptyList())

    /** The last [CAPACITY] samples, oldest first; empty until a surface has watched. */
    val history: StateFlow<List<PerfSample>> = flow

    private val lock = Any()
    private var lastCpu: CpuTimes? = null
    private var lastOwnCpuMs: Long = -1
    private var lastOwnWallMs: Long = -1
    private var lastSampleMs: Long = 0

    suspend fun watch(context: Context, intervalMs: Long = INTERVAL_MS) {
        watchWith(intervalMs) { sampleOnce(context.applicationContext) }
    }

    private val watching = MutableStateFlow(0)

    /**
     * How many surfaces are watching right now. A reader lives only as long as the tab or section that shows it
     * (docs/SPEC.md "The companion's tabs"), so this is 0 whenever none of them is on screen.
     */
    val subscribers: StateFlow<Int> = watching

    /** [watch]'s loop with the reading passed in, so the counting is tested without a device. */
    internal suspend fun watchWith(intervalMs: Long, sample: () -> Unit) {
        watching.update { it + 1 }
        try {
            while (true) {
                // One reading that fails (a vendor's odd battery property) must not end the loop, or the
                // history stops growing and every graph stays empty.
                try {
                    withContext(Dispatchers.IO) { sample() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // skipped: the next tick tries again
                }
                delay(intervalMs)
            }
        } finally {
            watching.update { it - 1 }
        }
    }

    private fun sampleOnce(context: Context) {
        synchronized(lock) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastSampleMs < INTERVAL_MS / 2) return
            lastSampleMs = now
            ring.add(read(context, now))
            flow.value = ring.toList()
        }
    }

    private fun read(context: Context, nowMs: Long): PerfSample {
        val cpu = readCpuTimes()
        val devicePercent = cpuPercent(lastCpu, cpu)
        if (cpu != null) lastCpu = cpu

        val ownMs = Process.getElapsedCpuTime()
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val ownPercent = if (lastOwnCpuMs >= 0 && nowMs > lastOwnWallMs) {
            ownCpuPercent(ownMs - lastOwnCpuMs, nowMs - lastOwnWallMs, cores)
        } else null
        lastOwnCpuMs = ownMs
        lastOwnWallMs = nowMs

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val totalMb = (mem.totalMem / MB).toInt()
        val availMb = (mem.availMem / MB).toInt()

        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val temp = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val microAmps = bm?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            ?.takeIf { it != Long.MIN_VALUE && it != 0L }

        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.currentThermalStatus
        } else null

        return PerfSample(
            timeMs = System.currentTimeMillis(),
            deviceCpuPercent = devicePercent,
            ownCpuPercent = ownPercent,
            cpuMhz = readMaxCpuMhz(),
            memUsedPercent = memUsedPercent(totalMb, availMb),
            memAvailMb = availMb,
            memTotalMb = totalMb,
            lowMemory = mem.lowMemory,
            batteryPercent = if (level >= 0 && scale > 0) level * 100 / scale else null,
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
            batteryTempTenthC = temp.takeIf { it != Int.MIN_VALUE },
            batteryMilliamps = microAmps?.let { (Math.abs(it) / 1000).toInt() },
            thermalStatus = thermal,
        )
    }

    private fun readCpuTimes(): CpuTimes? =
        runCatching { parseCpuStat(File("/proc/stat").bufferedReader().use { it.readLine() }) }.getOrNull()

    private fun readMaxCpuMhz(): Int? = runCatching {
        File("/sys/devices/system/cpu").listFiles { f -> f.name.matches(CPU_DIR) }
            ?.mapNotNull { File(it, "cpufreq/scaling_cur_freq").takeIf(File::canRead)?.readText()?.trim()?.toLongOrNull() }
            ?.maxOrNull()?.let { (it / 1000).toInt() }
    }.getOrNull()

    private val CPU_DIR = Regex("cpu\\d+")
    private const val MB = 1024L * 1024L

    // ---- Pure parts, unit tested -------------------------------------------------------------

    /** The aggregate "cpu  user nice system idle iowait irq softirq steal" line; null for anything else. */
    fun parseCpuStat(line: String?): CpuTimes? {
        val parts = line?.trim()?.split(Regex("\\s+")) ?: return null
        if (parts.firstOrNull() != "cpu" || parts.size < 5) return null
        val values = parts.drop(1).map { it.toLongOrNull() ?: return null }
        val idle = values[3] + values.getOrElse(4) { 0L }
        return CpuTimes(total = values.take(8).sum(), idle = idle)
    }

    /** Busy share between two readings, 0..100; null when there is nothing to compare or no time passed. */
    fun cpuPercent(previous: CpuTimes?, current: CpuTimes?): Int? {
        if (previous == null || current == null) return null
        val total = current.total - previous.total
        if (total <= 0) return null
        val idle = (current.idle - previous.idle).coerceIn(0, total)
        return (((total - idle) * 100) / total).toInt()
    }

    /** This process's CPU time over a wall interval, as a share of all [cores]. */
    fun ownCpuPercent(cpuMs: Long, wallMs: Long, cores: Int): Int? {
        if (wallMs <= 0 || cores <= 0 || cpuMs < 0) return null
        return ((cpuMs * 100) / (wallMs * cores)).toInt().coerceIn(0, 100)
    }

    fun memUsedPercent(totalMb: Int, availMb: Int): Int =
        if (totalMb <= 0) 0 else (((totalMb - availMb).coerceAtLeast(0)) * 100 / totalMb).coerceIn(0, 100)

    /** The busiest apps in `dumpsys cpuinfo` output, at most [limit]; lines that are not an app row are skipped. */
    fun parseTopApps(dump: String?, limit: Int = 5): List<AppCpu> {
        if (dump == null) return emptyList()
        return dump.lineSequence().mapNotNull { line ->
            val m = APP_ROW.matchEntire(line) ?: return@mapNotNull null
            AppCpu(m.groupValues[3], m.groupValues[1].toDoubleOrNull() ?: return@mapNotNull null)
        }.take(limit).toList()
    }

    private val APP_ROW = Regex("""^\s*([\d.]+)%\s+(\d+)/([^:\s]+):.*""")

    /** Android's own word for a `PowerManager.THERMAL_STATUS_*` value; null reads as "not reported". */
    fun thermalLabel(status: Int?): String = when (status) {
        null -> "Not reported"
        0 -> "Normal"
        1 -> "Light throttling"
        2 -> "Moderate throttling"
        3 -> "Severe throttling"
        4 -> "Critical"
        5 -> "Emergency"
        6 -> "Shutdown imminent"
        else -> "Unknown"
    }

    /** "36.5 C", or the honest placeholder. */
    fun tempText(tenthC: Int?): String = tenthC?.let { "%d.%d C".format(it / 10, Math.abs(it % 10)) } ?: "Not reported"
}
