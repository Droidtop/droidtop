package dev.droidtop.pluginhost

import org.json.JSONObject

/**
 * Background services (`jobs.service@1`, docs/plugin-api.md 3 E8) and scheduled tasks (`jobs.schedule@1`, E9). Both
 * are `provides` entries, so the approval list, the Permissions screen and the boundary check
 * ([PluginGrants.pointRefusal]) cover them like any point; there is no separate permission. Each entry also has its own
 * switch on the plugin's page ([entryKey], on unless switched off). Pure: the shells decide when to start, this decides
 * what an entry means.
 *
 * A service is a job droidtop starts (`run {serviceId}`) and keeps going while its switch is on, under droidtop's one
 * foreground notification; when it ends with a failure it is started again after [restartDelayMs] unless it said
 * `"restart": "never"`. A schedule (`"every": "6h"`, at least [MIN_EVERY_MS]; `"charging"` and `"unmetered"`
 * constraints) is a job droidtop starts (`run {scheduleId}`) when it is due.
 */
object BackgroundProtocol {
    const val SERVICE_POINT = "jobs.service"
    const val SCHEDULE_POINT = "jobs.schedule"

    /** The grant-file key prefix of one entry's own switch. */
    const val ENTRY_PREFIX = "entry:"

    const val MIN_EVERY_MS = 15 * 60 * 1000L
    const val MAX_EVERY_MS = 30L * 24 * 60 * 60 * 1000

    /** Restarts after a failure, in order; a service that fails once more is left stopped with its reason. */
    val RESTART_DELAYS_MS = listOf(30_000L, 2 * 60_000L, 10 * 60_000L, 30 * 60_000L)

    private fun extraOf(entry: ProvidedPoint): JSONObject = runCatching { JSONObject(entry.extra) }.getOrDefault(JSONObject())

    fun entryId(entry: ProvidedPoint): String = entry.id ?: "default"

    fun entryKey(entry: ProvidedPoint): String = "$ENTRY_PREFIX${entry.point}/${entryId(entry)}"

    /** The entry's own switch: on unless the person turned it off. */
    fun entryOn(snapshot: PluginGrants.Snapshot, entry: ProvidedPoint): Boolean = snapshot.states[entryKey(entry)] != GrantState.DENIED

    /** `"every"`: a number and `m`, `h` or `d` (`"30m"`, `"6h"`, `"1d"`). Null when missing, malformed or outside 15 minutes to 30 days. */
    fun everyMs(entry: ProvidedPoint): Long? = parseEvery(extraOf(entry).optString("every"))

    fun parseEvery(text: String?): Long? {
        val match = Regex("^\\s*(\\d{1,5})\\s*([mhd])\\s*$").matchEntire(text.orEmpty().lowercase()) ?: return null
        val n = match.groupValues[1].toLong()
        val unit = when (match.groupValues[2]) {
            "m" -> 60_000L
            "h" -> 60 * 60_000L
            else -> 24 * 60 * 60_000L
        }
        return (n * unit).takeIf { it in MIN_EVERY_MS..MAX_EVERY_MS }
    }

    data class Constraints(val charging: Boolean, val unmetered: Boolean)

    fun constraints(entry: ProvidedPoint): Constraints = extraOf(entry).let { Constraints(it.optBoolean("charging", false), it.optBoolean("unmetered", false)) }

    /** True when a schedule last run at [lastRunMs] (null: never) is due at [nowMs]. */
    fun due(lastRunMs: Long?, everyMs: Long, nowMs: Long): Boolean = lastRunMs == null || nowMs - lastRunMs >= everyMs

    /** True when the device state allows the run. */
    fun allowedNow(constraints: Constraints, charging: Boolean, unmetered: Boolean): Boolean =
        (!constraints.charging || charging) && (!constraints.unmetered || unmetered)

    /** Whether a service that failed is started again: `"restart": "never"` turns it off. */
    fun restartsOnFailure(entry: ProvidedPoint): Boolean = extraOf(entry).optString("restart") != "never"

    /** How long to wait before the [failuresInRow]th restart (1 for the first), or null when droidtop gives up. */
    fun restartDelayMs(failuresInRow: Int): Long? = RESTART_DELAYS_MS.getOrNull(failuresInRow - 1)
}
