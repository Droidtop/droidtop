package dev.droidtop.library.integrations

import dev.droidtop.pluginhost.JobState
import dev.droidtop.pluginhost.PluginJobsCenter
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.exp

/**
 * How fast a running job moves and roughly how long it has left, said calmly (docs/SPEC.md 7j "Places", Droidtop/tracker#643;
 * owner, 2026-10-24: "The current 'flickery numbers for download progress', for instance, is bad"). The rate is an
 * exponential moving average over about [TAU_MS], weighted by the time between reports so an uneven stream of them
 * cannot tip it; it measures bytes where the job counts them, else things, else its percent. The time left is said in
 * coarse words, only once the job has moved for [MIN_ELAPSED_MS], and a said time stands for [HOLD_MS] before it may
 * change. A pause, a wait, a step backwards or the end starts the measure again. In memory, for the screens that show
 * jobs; nothing polls.
 */
internal object JobPace {
    const val TAU_MS = 5_000.0
    const val MIN_ELAPSED_MS = 5_000L
    const val HOLD_MS = 5_000L

    /** What a job's progress is counted in, for one run: bytes, things, or its percent. */
    private enum class Unit { BYTES, ITEMS, PERCENT }

    private class Track(
        val unit: Unit,
        val startMs: Long,
        var atMs: Long,
        var done: Long,
        var reportedBps: Long,
        var rate: Double? = null,
        var bps: Double? = null,
        var said: String? = null,
        var saidAtMs: Long = 0L,
    )

    private val tracks = ConcurrentHashMap<String, Track>()

    /** "About 3 min left" for [entry] at [nowMs], or null while there is nothing honest to say. */
    fun line(entry: PluginJobsCenter.Entry, nowMs: Long): String? {
        val track = sample(entry, nowMs) ?: return null
        val total = totalOf(entry, track.unit) ?: return null
        val rate = track.rate?.takeIf { nowMs - track.startMs >= MIN_ELAPSED_MS }
        val fresh = rate?.let { remainingMs(total - track.done, it) }?.let(::words)
        return steady(track.said, track.saidAtMs, fresh, nowMs).also { kept ->
            if (kept != track.said) {
                track.said = kept
                track.saidAtMs = nowMs
            }
        }
    }

    /** The smoothed download speed of [entry] in bytes a second, or null when it reports none. */
    fun speed(entry: PluginJobsCenter.Entry, nowMs: Long): Long? = sample(entry, nowMs)?.bps?.toLong()?.takeIf { it > 0 }

    /** Forgets the jobs no longer listed. */
    fun forget(keep: Set<String>) {
        tracks.keys.retainAll(keep)
    }

    /** Takes in [entry]'s latest report when it says something new, and returns its track; null when it is not moving. */
    private fun sample(entry: PluginJobsCenter.Entry, nowMs: Long): Track? {
        val unit = unitOf(entry)
        if (unit == null || entry.done || entry.paused || entry.hold != null || entry.state != JobState.RUNNING) {
            tracks.remove(entry.jobId)
            return null
        }
        val done = doneOf(entry, unit)
        val reportedBps = entry.numbers?.downloadBps ?: -1L
        val track = tracks[entry.jobId]
        if (track == null || track.unit != unit || done < track.done) {
            return Track(unit, nowMs, nowMs, done, reportedBps, bps = reportedBps.takeIf { it > 0 }?.toDouble()).also { tracks[entry.jobId] = it }
        }
        // A screen drawn again between reports adds nothing: only a new report is a sample.
        if (done == track.done && reportedBps == track.reportedBps) return track
        val elapsed = nowMs - track.atMs
        if (elapsed <= 0) return track
        track.rate = smooth(track.rate, (done - track.done).toDouble() / elapsed, elapsed)
        if (reportedBps > 0) track.bps = smooth(track.bps, reportedBps.toDouble(), elapsed)
        track.atMs = nowMs
        track.done = done
        track.reportedBps = reportedBps
        return track
    }

    private fun unitOf(entry: PluginJobsCenter.Entry): Unit? {
        val numbers = entry.numbers
        return when {
            numbers != null && numbers.bytesTotal > 0 && numbers.bytesDone >= 0 -> Unit.BYTES
            numbers != null && numbers.itemsTotal > 0 && numbers.itemsDone >= 0 -> Unit.ITEMS
            entry.percent in 0..99 -> Unit.PERCENT
            else -> null
        }
    }

    private fun doneOf(entry: PluginJobsCenter.Entry, unit: Unit): Long = when (unit) {
        Unit.BYTES -> entry.numbers!!.bytesDone
        Unit.ITEMS -> entry.numbers!!.itemsDone.toLong()
        Unit.PERCENT -> entry.percent.toLong()
    }

    private fun totalOf(entry: PluginJobsCenter.Entry, unit: Unit): Long? = when (unit) {
        Unit.BYTES -> entry.numbers?.bytesTotal
        Unit.ITEMS -> entry.numbers?.itemsTotal?.toLong()
        Unit.PERCENT -> 100L
    }?.takeIf { it > 0 }

    /**
     * The moving average after a [sample] taken [elapsedMs] after the last one: a sample [TAU_MS] late counts for about
     * two thirds, one a moment later for almost nothing, so many quick reports weigh what one slow one does. Pure.
     */
    fun smooth(previous: Double?, sample: Double, elapsedMs: Long): Double {
        previous ?: return sample
        val weight = 1.0 - exp(-elapsedMs / TAU_MS)
        return previous + weight * (sample - previous)
    }

    /** The time [left] units take at [ratePerMs]; null when the job is not moving. Pure. */
    fun remainingMs(left: Long, ratePerMs: Double): Long? =
        if (ratePerMs <= 0.0 || left < 0) null else (left / ratePerMs).toLong()

    /** What to say now: the time said within the last [HOLD_MS] stands, else the fresh one. Pure. */
    fun steady(said: String?, saidAtMs: Long, fresh: String?, nowMs: Long): String? =
        if (said != null && nowMs - saidAtMs < HOLD_MS) said else fresh ?: said

    /** The time left in the coarsest words that are honest. Pure. */
    fun words(ms: Long): String {
        val minutes = (ms + 30_000L) / 60_000L
        return when {
            ms < 45_000L -> "Under a minute left"
            minutes < 60 -> "About ${minutes.coerceAtLeast(1)} min left"
            else -> "About ${(minutes + 30) / 60} h left"
        }
    }
}
