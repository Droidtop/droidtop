package dev.droidtop.library.identity

import android.content.Context
import dev.droidtop.library.integrations.ManualDownloads
import dev.droidtop.library.scraper.PcMatch
import dev.droidtop.library.settings.CatalogPrefs
import java.io.File

/**
 * Records what each source answered during a scrape as [MatchCalibration] lines (Droidtop/tracker#318 §3.3), so the
 * calibration set is built from real answers and whoever checks it only fills in `truth`. Off unless turned on in the
 * Developer menu; the lines go to droidtop's folder in Download, one game per line, appended across scrapes.
 *
 * Covers PC games (every candidate the selected source returned, or the store's own record by id) and ROMs the
 * selected source matched by hash. A ROM matched by name is not recorded: today's ROM sources hand back one picked
 * answer, not the list, and a list is what the grade needs.
 */
object MatchRecorder {
    const val FILE_NAME = "match-answers.jsonl"
    private const val KEY_ON = "droidtop_record_match_answers"

    /** What a recorded line has in `truth` until a person checks it; [MatchCalibration.grade] leaves such lines out. */
    const val UNCHECKED = "?"

    fun on(context: Context): Boolean = CatalogPrefs.prefs(context).getBoolean(KEY_ON, false)

    fun set(context: Context, on: Boolean) {
        CatalogPrefs.prefs(context).edit().putBoolean(KEY_ON, on).apply()
    }

    fun file(): File = File(ManualDownloads.savedFolder(), FILE_NAME)

    /** A PC game's answers from [source]: [by] is `name` for a search, `id` for a store record asked by its id. */
    fun pc(context: Context, entryId: String, kind: String, local: String, size: Long?, source: String, by: String, matches: List<PcMatch>) {
        if (!on(context)) return
        val key = source.lowercase().filter { it.isLetterOrDigit() }
        val results = matches.map { MatchScore.Candidate(candidateId(it), it.name, year = it.year) }
        append(fixture(entryId, kind, "pc", local, entryId, size, null, key, by, results))
    }

    /** A ROM [source] matched by [md5]: its answer is certain, so the line counts toward what is answered without asking. */
    fun romByHash(context: Context, path: String, system: String, size: Long, md5: String, source: String, name: String) {
        if (!on(context)) return
        val key = source.lowercase().filter { it.isLetterOrDigit() }
        append(fixture(path, "rom", system, File(path).name, path, size, md5, key, "hash", listOf(MatchScore.Candidate(name, name))))
    }

    /** The id a PC candidate is written under: the first id it carries, named by its kind, else its name. */
    internal fun candidateId(match: PcMatch): String = with(match.ids) {
        igdbId?.let { "igdb:$it" }
            ?: steamGridDbId?.let { "sgdb:$it" }
            ?: steamAppId?.let { "steam:$it" }
            ?: gogId?.let { "gog:$it" }
            ?: lutrisSlug?.let { "lutris:$it" }
            ?: match.name
    }

    internal fun fixture(
        id: String, kind: String, system: String?, local: String, path: String?, size: Long?, md5: String?,
        source: String, by: String, results: List<MatchScore.Candidate>,
    ) = MatchCalibration.Fixture(
        id = id, kind = kind, system = system, local = local, path = path, size = size, md5 = md5, year = null,
        truth = mapOf(source to UNCHECKED),
        answers = mapOf(source to MatchCalibration.Answers(by, results)),
    )

    @Synchronized
    private fun append(fixture: MatchCalibration.Fixture) {
        // A recording that cannot be written must never stop the scrape it rides on.
        runCatching {
            val out = file()
            out.parentFile?.mkdirs()
            out.appendText(MatchCalibration.write(fixture) + "\n")
        }
    }
}
