package dev.droidtop.library.scraper

import android.content.Context
import dev.droidtop.library.settings.LAUNCHER_PREFS_FILE_NAME
import org.json.JSONObject

/**
 * What ProtonDB last answered about a game the USER asked about, kept per
 * game (docs/SPEC.md 7i's ProtonDB rule: "asked for rather than fetched").
 *
 * The ask itself never moves: a lookup still runs only when the person
 * selects the row, exactly as 7e3 decided. What changes is that the answer
 * is no longer lost the moment the dialog that asked closes -- the PC
 * library's focused-game panel and its ProtonDB-tier filter draw real
 * content only for games that HAVE an answer, and an answer the person
 * already asked for is a fact worth remembering. Nothing here ever asks on
 * its own, and a remembered answer is always dated so no reader can
 * mistake it for a live report.
 *
 * Keyed by [dev.droidtop.library.LibraryEntry.id], like play history and
 * favourites, in the launcher's own preferences file.
 */
object ProtonDbMemory {
    /**
     * The remembered outcome of one ask. [tier] null means the answer was
     * not a summary -- ProtonDB had no reports for the game, or refused or
     * could not be reached -- and [line] carries the sentence that
     * outcome produced, so a remembered refusal reads as the refusal it
     * was, never as silence.
     */
    data class Answer(
        val appId: Long,
        val tier: String?,
        val total: Int,
        val confidence: String?,
        val trendingTier: String?,
        val askedAtEpochMs: Long,
        val line: String? = null,
    ) {
        /** The one line a list or panel draws for this answer, ProtonDB's own word first. */
        fun summaryLine(): String = when {
            tier != null -> buildString {
                append("ProtonDB: $tier")
                if (total > 0) append(" - $total report").append(if (total == 1) "" else "s")
            }
            else -> "ProtonDB: ${line ?: "no answer"}"
        }
    }

    private const val KEY_PREFIX = "droidtop_protondb_"

    fun get(context: Context, entryId: String): Answer? {
        val raw = context.getSharedPreferences(LAUNCHER_PREFS_FILE_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PREFIX + entryId, null) ?: return null
        return decode(raw)
    }

    fun remember(context: Context, entryId: String, answer: Answer) {
        context.getSharedPreferences(LAUNCHER_PREFS_FILE_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_PREFIX + entryId, encode(answer)).apply()
    }

    /** Pure, for the JVM tests: one answer as the prefs store it. */
    internal fun encode(answer: Answer): String = JSONObject()
        .put("appId", answer.appId)
        .put("tier", answer.tier ?: JSONObject.NULL)
        .put("total", answer.total)
        .put("confidence", answer.confidence ?: JSONObject.NULL)
        .put("trendingTier", answer.trendingTier ?: JSONObject.NULL)
        .put("askedAt", answer.askedAtEpochMs)
        .put("line", answer.line ?: JSONObject.NULL)
        .toString()

    /** Pure, for the JVM tests: null when the stored value is not one of ours. */
    internal fun decode(raw: String?): Answer? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val json = JSONObject(raw)
            Answer(
                appId = json.getLong("appId"),
                tier = json.optString("tier").ifBlank { null },
                total = json.optInt("total", 0),
                confidence = json.optString("confidence").ifBlank { null },
                trendingTier = json.optString("trendingTier").ifBlank { null },
                askedAtEpochMs = json.getLong("askedAt"),
                line = json.optString("line").ifBlank { null },
            )
        }.getOrNull()
    }
}
