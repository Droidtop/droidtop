package dev.droidtop.library

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * An F95zone thread, from whatever a person pastes (docs/SPEC.md 7g,
 * "Where an update comes from"): the thread's link as the browser shows
 * it (`https://f95zone.to/threads/<slug>.<id>/`), the short form
 * (`f95zone.to/threads/<id>`), or the bare number.
 */
object F95Thread {
    private val LINK = Regex("""f95zone\.to/threads/(?:[^/?#\s]*\.)?(\d+)""", RegexOption.IGNORE_CASE)

    /** The thread id in [text], or null when it names none. */
    fun parse(text: String): Long? {
        val trimmed = text.trim()
        val id = trimmed.toLongOrNull() ?: LINK.find(trimmed)?.groupValues?.get(1)?.toLongOrNull()
        return id?.takeIf { it > 0 }
    }

    /** The thread's own page. */
    fun url(thread: Long): String = "https://f95zone.to/threads/$thread/"
}

/** What the update source last said about one thread, kept so a round asks only about what changed. */
data class F95ThreadCheck(
    val thread: Long,
    /** The source's own "last changed" stamp for the thread, from its fast check. */
    val lastChanged: Long,
    /** The thread's version as the source wrote it; null for a thread that is gone. */
    val version: String?,
    val checkedAtEpochMs: Long,
    /** The source said the thread is private, moved or deleted. */
    val gone: Boolean = false,
)

/**
 * F95Checker's public index, `api.f95checker.dev`: the source droidtop
 * asks. It is the index F95Checker itself reads (its `modules/api.py`,
 * `fast_check`/`full_check`), ported through the user's own Pythia
 * (`f95_update_check.py`), and it needs no F95zone account: neither call
 * sends a cookie. It is an interface so a round can be tested without a
 * network.
 */
interface F95CheckerApi {
    /**
     * Thread -> the index's last-changed stamp, for every thread the index
     * knows. Null when the index refused the whole request, which it does
     * when any one id in it is not a thread it can index. Throws
     * [IOException] when the index cannot be asked.
     */
    fun fastCheck(threads: List<Long>): Map<Long, Long>?

    /** The thread's current version (null when it gives none), or [FullAnswer.Gone]. */
    fun fullCheck(thread: Long, lastChanged: Long): FullAnswer

    sealed interface FullAnswer {
        data class Found(val version: String?) : FullAnswer
        data object Gone : FullAnswer
    }
}

/** The real index, over plain HTTPS. */
object HttpF95CheckerApi : F95CheckerApi {
    private const val HOST = "https://api.f95checker.dev"

    /** The most ids one fast check carries; F95Checker's own client uses the same limit. */
    const val FAST_CHECK_MAX_IDS = 10

    private const val TIMEOUT_MS = 30_000
    private const val USER_AGENT = "droidtop (github.com/Droidtop/droidtop)"

    // The index's own downtime pages, which come back as HTML with a 200
    // from the proxy in front of it (Pythia's `_raise_api_error`).
    private val DOWN_MARKERS = listOf("502: Bad gateway", "521: Web server is down")

    private val json = Json { ignoreUnknownKeys = true }

    override fun fastCheck(threads: List<Long>): Map<Long, Long>? {
        if (threads.isEmpty()) return emptyMap()
        val body = get("$HOST/fast?ids=${threads.joinToString(",")}").second
        // A refusal is a bare string ("Invalid thread IDs"), not an object.
        val parsed = parse(body) as? JsonObject ?: return null
        indexError(parsed)?.let { throw IOException("F95Checker's index said: $it") }
        return threads.mapNotNull { thread ->
            val stamp = (parsed[thread.toString()] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }
            stamp?.takeIf { it > 0 }?.let { thread to it }
        }.toMap()
    }

    override fun fullCheck(thread: Long, lastChanged: Long): F95CheckerApi.FullAnswer {
        val (code, body) = get("$HOST/full/$thread?ts=$lastChanged")
        // 400 is the index's "Invalid thread ID"; 403 and 404 are the
        // cases F95Checker itself reads as a thread that is gone.
        if (code == 400 || code == 403 || code == 404) return F95CheckerApi.FullAnswer.Gone
        val parsed = parse(body) as? JsonObject ?: throw IOException("F95Checker's index answered thread $thread with something that is not a thread")
        indexError(parsed)?.let { throw IOException("F95Checker's index said: $it") }
        val version = (parsed["version"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        return F95CheckerApi.FullAnswer.Found(version)
    }

    private fun parse(body: String): JsonElement {
        if (DOWN_MARKERS.any { body.contains(it) }) throw IOException("F95Checker's index is down; it is asked again later")
        return try {
            json.parseToJsonElement(body)
        } catch (e: Exception) {
            throw IOException("F95Checker's index answered with something that is not JSON", e)
        }
    }

    private fun indexError(obj: JsonObject): String? =
        (obj["INDEX_ERROR"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun get(url: String): Pair<Int, String> {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("User-Agent", USER_AGENT)
            val code = connection.responseCode
            val stream = if (code >= 400) connection.errorStream else connection.inputStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code >= 500) throw IOException("F95Checker's index answered HTTP $code")
            return code to body
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * One round of update checks over the threads the user has linked
 * (docs/SPEC.md 7g, "Where an update comes from"). Pythia's
 * `run_update_check`: a fast check of every thread tells which changed
 * since the last full check, and only those get a full check.
 *
 * What keeps it light, all of it here and none of it in a caller:
 *
 * - A thread is asked about at most once per [CHECK_INTERVAL_MS]; a
 *   round started sooner skips it. Linking a thread, or "Check now",
 *   asks about that one thread at once, but not twice within
 *   [RECHECK_MIN_MS].
 * - Fast checks go [HttpF95CheckerApi.FAST_CHECK_MAX_IDS] threads at a
 *   time, and every request waits [REQUEST_SPACING_MS] after the last.
 * - One round at a time: a round asked for while one runs is not queued.
 * - A failure stops the round and changes nothing it did not finish, so
 *   the next round asks again.
 */
class F95UpdateCheck(
    private val store: GameLinksStore,
    private val api: F95CheckerApi,
    private val now: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    /** What a round did: how many threads it asked about, which entries' facts changed, and why it stopped early if it did. */
    data class Outcome(val asked: Int, val changedIds: Set<String>, val error: String? = null)

    private val running = Mutex()

    /**
     * A round over every linked thread that is due, or over [only] alone
     * when a person asked about that one. Null when a round was already
     * running.
     */
    suspend fun round(only: Long? = null): Outcome? {
        if (!running.tryLock()) return null
        try {
            return runRound(only)
        } finally {
            running.unlock()
        }
    }

    private suspend fun runRound(only: Long?): Outcome {
        val linked = store.linkedThreads()
        val at = now()
        val due = linked.filter { (thread, last) ->
            when {
                only != null -> thread == only && (last == null || at - last.checkedAtEpochMs >= RECHECK_MIN_MS)
                else -> last == null || at - last.checkedAtEpochMs >= CHECK_INTERVAL_MS
            }
        }
        if (due.isEmpty()) return Outcome(0, emptySet())

        val changed = mutableSetOf<String>()
        var requests = 0
        suspend fun spaced() {
            if (requests++ > 0) pause(REQUEST_SPACING_MS)
        }
        try {
            val stamps = HashMap<Long, Long>()
            for (batch in due.keys.chunked(HttpF95CheckerApi.FAST_CHECK_MAX_IDS)) {
                spaced()
                val answered = api.fastCheck(batch)
                if (answered != null) {
                    stamps.putAll(answered)
                } else if (batch.size > 1) {
                    // One id the index refuses makes it refuse the whole
                    // batch, so a refused batch is asked again one thread
                    // at a time: the others still deserve an answer.
                    for (thread in batch) {
                        spaced()
                        api.fastCheck(listOf(thread))?.let { stamps.putAll(it) }
                    }
                }
            }
            for ((thread, last) in due) {
                val stamp = stamps[thread]
                val next = when {
                    // The index does not know the thread: gone, as far as
                    // anything droidtop can ask is concerned.
                    stamp == null -> F95ThreadCheck(thread, last?.lastChanged ?: 0L, null, now(), gone = true)
                    // Nothing changed since the last full check.
                    last != null && !last.gone && last.version != null && stamp <= last.lastChanged ->
                        last.copy(checkedAtEpochMs = now())
                    else -> {
                        spaced()
                        when (val answer = api.fullCheck(thread, stamp)) {
                            F95CheckerApi.FullAnswer.Gone -> F95ThreadCheck(thread, stamp, null, now(), gone = true)
                            is F95CheckerApi.FullAnswer.Found -> F95ThreadCheck(thread, stamp, answer.version, now())
                        }
                    }
                }
                store.saveCheck(next)
                if (last == null || last.version != next.version || last.gone != next.gone) {
                    changed += store.idsLinkedTo(thread)
                }
            }
        } catch (e: IOException) {
            val reason = e.message ?: e.javaClass.simpleName
            ScanLog.write("updates: stopped after ${changed.size} changed entries: $reason")
            return Outcome(due.size, changed, reason)
        }
        ScanLog.write("updates: asked F95Checker's index about ${due.size} thread(s); ${changed.size} entries changed")
        return Outcome(due.size, changed)
    }

    companion object {
        /** How long an answer stands before a round asks again. */
        const val CHECK_INTERVAL_MS = 6 * 60 * 60_000L

        /** The least time between two asks about one thread, however they were asked for. */
        const val RECHECK_MIN_MS = 60_000L

        /** The pause between two requests of one round. */
        const val REQUEST_SPACING_MS = 1_000L
    }
}
