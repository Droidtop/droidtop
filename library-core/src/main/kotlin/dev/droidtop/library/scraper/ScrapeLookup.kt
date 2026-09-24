package dev.droidtop.library.scraper

import java.net.HttpURLConnection

/**
 * The three real, distinct outcomes of asking ANY scraper source about one
 * game (docs/SPEC.md section 7h).
 *
 * This type exists because of a specific lie. ScreenScraper's lookup used to
 * return a nullable result, so a server that refused to talk to droidtop at
 * all and a game ScreenScraper has genuinely never heard of arrived at the
 * caller as the same `null`. On 2026-09-01 an overnight pass over 46 ROMs
 * across 11 systems got HTTP 403 on all 46 requests, and the user was told
 * "no match for 46, 0 failed" -- a statement about their library when the
 * truth was a total API outage. TheGamesDB, Lutris and IGDB had the same
 * shape (a non-200 became an empty list or a generic failure), so the rule
 * is settled once, here, for every source: a refusal is not a miss, and
 * every caller is forced to handle the two separately.
 *
 * A fourth outcome, a transport failure (no route, DNS, timeout, TLS), is
 * deliberately NOT modelled here. Those still throw out of the clients,
 * and every scrape loop counts thrown exceptions as "failed", which is a
 * third honest bucket rather than a silent one.
 */
sealed interface ScrapeLookup<out T> {

    /** The server answered, and had this game. */
    data class Found<out T>(val value: T) : ScrapeLookup<T>

    /**
     * The server answered normally and its response carried no game: the
     * source really does not have it. This is the only outcome that says
     * anything at all about the user's library.
     */
    data object NoMatch : ScrapeLookup<Nothing>

    /**
     * The server refused the request outright -- credentials rejected,
     * application not authorised, client version obsolete, quota exhausted,
     * maintenance. [reason] is the server's own explanation, read from the
     * error body of the non-200 response, credential-redacted and bounded
     * (see [ScrapeRefusals.summarizeErrorBody]); it is null only when the
     * server sent no body at all. [source] is the name the user knows the
     * source by, because the summary that reports this has to say who
     * refused.
     *
     * Nothing about a refusal is evidence about the game that was asked for.
     */
    data class Refused(val source: String, val httpStatus: Int, val reason: String?) : ScrapeLookup<Nothing>
}

/** The value of a [ScrapeLookup.Found], or null for either other outcome. */
val <T> ScrapeLookup<T>.foundOrNull: T?
    get() = (this as? ScrapeLookup.Found<T>)?.value

/**
 * Reading a refusal: the one place every source's non-200 goes through, so
 * the bound, the redaction and the log line are the same for all of them.
 */
object ScrapeRefusals {

    /**
     * How much of a non-200 body is read at all. Real refusal bodies are one
     * short sentence; anything longer is either an HTML error page from an
     * intermediary or something that has no business being pulled into a
     * phone's memory, so the stream is read to this bound and then abandoned
     * rather than consumed whole.
     */
    private const val ERROR_BODY_MAX_CHARS = 512

    /**
     * Builds the [ScrapeLookup.Refused] for a connection that answered
     * [status] (anything but 200), logging it under `droidtop.Scraper` with
     * the server's reason. [secrets] is every credential the request
     * carried: each is redacted out of the body before anything else reads
     * it. [subject] names what was asked for, for the log line only.
     */
    fun refused(
        source: String,
        connection: HttpURLConnection,
        status: Int,
        secrets: List<String>,
        subject: String,
    ): ScrapeLookup.Refused {
        val reason = summarizeErrorBody(readBoundedErrorBody(connection), secrets)
        android.util.Log.w(
            "droidtop.Scraper",
            "$source refused $subject: HTTP $status" +
                (reason?.let { " -- server said: $it" } ?: " (server sent no error body)"),
        )
        return ScrapeLookup.Refused(source, status, reason)
    }

    private fun readBoundedErrorBody(connection: HttpURLConnection): String = try {
        connection.errorStream?.bufferedReader()?.use { reader ->
            val buffer = CharArray(ERROR_BODY_MAX_CHARS)
            var filled = 0
            while (filled < ERROR_BODY_MAX_CHARS) {
                val read = reader.read(buffer, filled, ERROR_BODY_MAX_CHARS - filled)
                if (read < 0) break
                filled += read
            }
            String(buffer, 0, filled)
        }.orEmpty()
    } catch (t: Throwable) {
        ""
    }

    /**
     * Turns a raw refusal body into one loggable line: every non-blank
     * credential the request carried blanked out FIRST, then any markup an
     * intermediary wrapped it in stripped, then collapsed to a single
     * bounded line. Nothing here can echo a password into logcat, however
     * the server chooses to phrase its complaint.
     *
     * Pure, so the refusal path is testable without a network.
     */
    internal fun summarizeErrorBody(raw: String, secrets: List<String>): String? {
        if (raw.isBlank()) return null
        var text = raw
        // Longest first, so a credential that contains another as a
        // substring still redacts fully.
        secrets.filter { it.isNotBlank() }.sortedByDescending { it.length }.forEach {
            text = text.replace(it, "[redacted]")
        }
        text = text.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim()
        if (text.isBlank()) return null
        return if (text.length > 200) text.take(200).trimEnd() + "..." else text
    }
}

/**
 * How many refusals in a row end a pass, for every scrape loop. One refusal
 * can be about one request; several in a row cannot be, and every further
 * request is just more time spent to be told the same thing.
 */
internal const val REFUSAL_ABORT_THRESHOLD = 5

/**
 * The lead sentence for a pass that was refused everything it asked for,
 * or null when it was not. Such a pass is an outage, not a result, and
 * reading it as one is the whole bug; both summaries (ROM and PC) say so
 * first and say nothing else.
 */
internal fun totalRefusalSummary(
    subject: String,
    attempted: Int,
    found: Int,
    refused: Int,
    lastRefusal: ScrapeLookup.Refused?,
): String? {
    if (refused == 0 || found != 0 || refused != attempted) return null
    val who = lastRefusal?.source ?: "The server"
    return "$subject: $who refused every request" +
        describeRefusal(refused, attempted, lastRefusal) +
        " Nothing was scraped, and this says nothing about whether your games are in the database."
}

/**
 * The server's own explanation, surfaced on screen rather than left in
 * logcat -- it is the only thing that tells a user whether to fix their
 * credentials, wait for an application approval, or come back tomorrow.
 */
internal fun describeRefusal(refused: Int, attempted: Int, lastRefusal: ScrapeLookup.Refused?): String {
    if (lastRefusal == null) return ""
    val reason = lastRefusal.reason
        ?: "the server sent no explanation with it -- check logcat, tag droidtop.Scraper, for the full request context"
    return " ($refused of $attempted refused; ${lastRefusal.source} HTTP ${lastRefusal.httpStatus}: $reason)."
}
