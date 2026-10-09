package dev.droidtop.runtime.systemstatus

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** One battery broadcast: level, whether it charges, and the current, charge, voltage and heat when reported. */
data class BatteryReading(
    val timeMs: Long,
    val percent: Int,
    val charging: Boolean,
    /** Current magnitude in microamps; the sign is not standardized across vendors, so it is never a direction. */
    val microAmps: Long?,
    /** Remaining charge in microamp-hours (`BATTERY_PROPERTY_CHARGE_COUNTER`). */
    val chargeMicroAh: Long?,
    val milliVolts: Int?,
    val tempTenthC: Int?,
) {
    /** Watts drawn or taken, from current and voltage; null when either is unknown. */
    val watts: Double? get() = if (microAmps != null && milliVolts != null) microAmps * milliVolts / 1e9 else null
}

/** Time to empty, or to full while charging, in whole minutes. */
data class BatteryEstimate(val minutes: Int, val toFull: Boolean) {
    /** "3 h 10 min left", "45 min to full". */
    val label: String get() {
        val time = if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
        return if (toFull) "$time to full" else "$time left"
    }
}

/**
 * Time to empty or full from battery broadcasts (docs/SPEC.md "The companion's tabs", Droidtop/tracker#414). Pure,
 * so the rules are tested: only readings since the charger was last plugged in or out count; nothing is said before
 * [MIN_SAMPLES] readings spanning [MIN_SPAN_MS]; the current is smoothed ([ALPHA], an exponential average) so a
 * menu's spike does not swing the estimate; without a current, the level's own slope stands in.
 */
object BatteryEstimator {
    const val MIN_SAMPLES = 3
    const val MIN_SPAN_MS = 45_000L
    const val ALPHA = 0.3
    private const val MAX_MINUTES = 48 * 60

    fun smooth(previous: Double?, value: Double): Double = previous?.let { it + ALPHA * (value - it) } ?: value

    fun estimate(readings: List<BatteryReading>): BatteryEstimate? {
        val latest = readings.lastOrNull() ?: return null
        val run = readings.takeLastWhile { it.charging == latest.charging }
        if (run.size < MIN_SAMPLES || latest.timeMs - run.first().timeMs < MIN_SPAN_MS) return null
        var current: Double? = null
        run.forEach { r -> r.microAmps?.takeIf { it > 0 }?.let { current = smooth(current, it.toDouble()) } }
        val charge = latest.chargeMicroAh?.takeIf { it > 0 }
        val minutes: Double? = when {
            current != null && charge != null -> {
                val target = if (latest.charging) charge * 100.0 / latest.percent.coerceAtLeast(1) - charge else charge.toDouble()
                target / current!! * 60.0
            }
            else -> {
                // The level's slope, percent per minute, over the run.
                val spanMin = (latest.timeMs - run.first().timeMs) / 60_000.0
                val moved = (latest.percent - run.first().percent).toDouble()
                val perMinute = if (latest.charging) moved / spanMin else -moved / spanMin
                if (perMinute <= 0.0) null else (if (latest.charging) 100 - latest.percent else latest.percent) / perMinute
            }
        }
        val whole = minutes?.takeIf { it.isFinite() && it >= 0 }?.toInt()?.coerceAtMost(MAX_MINUTES) ?: return null
        return BatteryEstimate(whole, toFull = latest.charging)
    }
}

/**
 * The battery readings behind the estimate: a `BATTERY_CHANGED` receiver while some surface holds it ([acquire] and
 * [release]), and the `BatteryManager` current and charge read on each broadcast. No timer, no polling while asleep,
 * no wakelock: Android sends the broadcast when the level, heat or voltage changes.
 */
object BatterySampler {
    private const val KEEP = 30

    private val readings = ArrayDeque<BatteryReading>()
    private val latestFlow = MutableStateFlow<BatteryReading?>(null)
    private val estimateFlow = MutableStateFlow<BatteryEstimate?>(null)
    val latest: StateFlow<BatteryReading?> = latestFlow
    val estimate: StateFlow<BatteryEstimate?> = estimateFlow

    private var holders = 0
    private var receiver: BroadcastReceiver? = null

    @Synchronized
    fun acquire(context: Context) {
        holders++
        if (receiver != null) return
        val app = context.applicationContext
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) = add(read(app, intent))
        }
        receiver = r
        // Sticky: the current state arrives at once.
        runCatching { app.registerReceiver(r, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }
    }

    @Synchronized
    fun release(context: Context) {
        holders = (holders - 1).coerceAtLeast(0)
        if (holders > 0) return
        receiver?.let { runCatching { context.applicationContext.unregisterReceiver(it) } }
        receiver = null
    }

    @Synchronized
    private fun add(reading: BatteryReading) {
        readings.addLast(reading)
        while (readings.size > KEEP) readings.removeFirst()
        latestFlow.value = reading
        estimateFlow.value = BatteryEstimator.estimate(readings.toList())
    }

    private fun read(context: Context, intent: Intent): BatteryReading {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val bm = context.getSystemService(BatteryManager::class.java)
        fun property(id: Int): Long? = runCatching { bm?.getLongProperty(id) }.getOrNull()
            ?.takeIf { it != Long.MIN_VALUE && it != 0L }
        return BatteryReading(
            timeMs = android.os.SystemClock.elapsedRealtime(),
            percent = if (level >= 0) level * 100 / scale else 0,
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
            microAmps = property(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)?.let { kotlin.math.abs(it) },
            chargeMicroAh = property(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER),
            milliVolts = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1).takeIf { it > 0 },
            tempTenthC = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE },
        )
    }
}
