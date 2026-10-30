package dev.droidtop.pluginhost

import kotlinx.coroutines.currentCoroutineContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/*
 * The one jobs and progress model (docs/SPEC.md "Jobs and their results", Droidtop/tracker#577): what a job is
 * doing, its numbers, what can be done to it, and what it did. Every job in [PluginJobsCenter] carries it, downloads
 * and the work that is not a download alike (a library scrape, a save sync, a rescan), so the Downloads place, the
 * rows on game pages and the widgets read one thing. The screens write every line from these values; a job sends
 * numbers and outcomes, not prose about them.
 */

/**
 * Where a job stands. Derived from [PluginJobsCenter.Entry] by [PluginJobsCenter.Entry.state]. A job that lost its
 * connection waits for it ([WAITING_FOR_NETWORK]) and carries on by itself when it is back; a lost connection is
 * never [FAILED] (Droidtop/tracker#577).
 */
enum class JobState { RUNNING, WAITING_FOR_NETWORK, WAITING_TO_RETRY, HELD, PAUSED, DONE, FAILED }

/** Why a running job is not moving right now, set and cleared by the job ([JobHandle.waiting]). */
enum class WaitReason { NETWORK, RETRY }

/** A running job's wait: why, and for a retry when it tries again (epoch ms, null when not known). */
data class JobWait(val reason: WaitReason, val untilMs: Long? = null)

/** A job's numbers, each -1 when the job does not know it. Bytes for a transfer, items for work counted in things. */
data class JobNumbers(
    val bytesDone: Long = -1,
    val bytesTotal: Long = -1,
    val downloadBps: Long = -1,
    val uploadBps: Long = -1,
    val itemsDone: Int = -1,
    val itemsTotal: Int = -1,
)

/** What "Open" on a job or a line of its report goes to. */
sealed interface OpenTarget {
    /** A game's page, by its library id. */
    data class Game(val entryId: String) : OpenTarget

    /** A folder on the device, shown in the file manager. */
    data class Folder(val path: String) : OpenTarget

    /** A file on the device, opened with what Android offers for it. */
    data class File(val path: String) : OpenTarget

    /** One of droidtop's places, by its id (dev.droidtop.library.settings.Place). */
    data class Place(val id: String) : OpenTarget

    /** A download site's sign-in, by its domain: a job its expired session stopped (docs/SPEC.md 12a "Download sites"). */
    data class SiteSignIn(val domain: String) : OpenTarget

    /** One plugin's page, by its id: where it is approved, turned on and allowed (Droidtop/tracker#759). */
    data class Plugin(val pluginId: String) : OpenTarget

    companion object {
        fun encode(target: OpenTarget?): String? = when (target) {
            null -> null
            is Game -> "game:${target.entryId}"
            is Folder -> "folder:${target.path}"
            is File -> "file:${target.path}"
            is Place -> "place:${target.id}"
            is SiteSignIn -> "signin:${target.domain}"
            is Plugin -> "plugin:${target.pluginId}"
        }

        fun decode(raw: String?): OpenTarget? {
            val text = raw?.takeIf { it.isNotEmpty() } ?: return null
            val value = text.substringAfter(':', "").takeIf { it.isNotEmpty() } ?: return null
            return when (text.substringBefore(':')) {
                "game" -> Game(value)
                "folder" -> Folder(value)
                "file" -> File(value)
                "place" -> Place(value)
                "signin" -> SiteSignIn(value)
                "plugin" -> Plugin(value)
                else -> null
            }
        }
    }
}

/** What happened to one thing a job worked on. */
enum class Outcome { DONE, SKIPPED, NEEDS_PICK, FAILED }

/** One line of a job's report: the thing ([title]), what happened to it, why or where ([detail]), and where Open goes. */
data class ReportItem(val title: String, val outcome: Outcome, val detail: String? = null, val open: OpenTarget? = null)

/** What a job did, kept after it ends: one line per thing it worked on. */
data class JobReport(val items: List<ReportItem> = emptyList()) {
    fun count(outcome: Outcome): Int = items.count { it.outcome == outcome }

    fun encode(): String = JSONArray().apply {
        items.forEach { item ->
            put(
                JSONObject().put("t", item.title).put("o", item.outcome.name)
                    .put("d", item.detail).put("open", OpenTarget.encode(item.open)),
            )
        }
    }.toString()

    companion object {
        /** At most this many lines are kept, so a pass over a large library stays a small record. */
        const val MAX_ITEMS = 2_000

        fun decode(raw: String?): JobReport? {
            val text = raw?.takeIf { it.isNotEmpty() } ?: return null
            return runCatching {
                val array = JSONArray(text)
                JobReport(
                    (0 until array.length()).mapNotNull { i ->
                        val o = array.optJSONObject(i) ?: return@mapNotNull null
                        val outcome = runCatching { Outcome.valueOf(o.optString("o")) }.getOrNull() ?: return@mapNotNull null
                        ReportItem(
                            title = o.optString("t"),
                            outcome = outcome,
                            detail = o.optString("d").takeIf { it.isNotEmpty() && it != "null" },
                            open = OpenTarget.decode(o.optString("open").takeIf { it != "null" }),
                        )
                    },
                )
            }.getOrNull()
        }
    }
}

/**
 * What a running native job can tell the jobs center besides its line and percent: its numbers, a line of its report,
 * and what Open goes to. Found by the job's code with [current] while it runs, so a job reports these without a
 * second runner signature; outside a job [current] is null and nothing is told.
 */
class JobHandle internal constructor(val jobId: String) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<JobHandle> {
        /** The job the calling code is running in, or null outside one. */
        suspend fun current(): JobHandle? = currentCoroutineContext()[Key]
    }

    fun numbers(numbers: JobNumbers) = PluginJobsCenter.setNumbers(jobId, numbers)

    /** The job's bar and line from inside a step its runner does not report from itself (an unpack, Droidtop/tracker#842). */
    fun progress(percent: Int, line: String) = PluginJobsCenter.setProgress(jobId, percent, line)

    fun item(item: ReportItem) = PluginJobsCenter.addReportItem(jobId, item)

    fun item(title: String, outcome: Outcome, detail: String? = null, open: OpenTarget? = null) =
        item(ReportItem(title, outcome, detail, open))

    fun open(target: OpenTarget?) = PluginJobsCenter.setOpen(jobId, target)

    /** The job is waiting ([wait]), or moving again (null). */
    fun waiting(wait: JobWait?) = PluginJobsCenter.setWait(jobId, wait)
}
