package dev.droidtop.library.identity

import android.content.Context
import dev.droidtop.library.GameEngine
import dev.droidtop.library.GameTitleParser
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.integrations.ManualDownloads
import dev.droidtop.library.scraper.PcGameIds
import dev.droidtop.library.scraper.PcMatch
import dev.droidtop.library.settings.CatalogPrefs
import dev.droidtop.library.toLibraryEntryKind
import java.io.File

/**
 * Records what each source answered during a scrape as [MatchCalibration] lines (Droidtop/tracker#318 §3.3), with
 * everything droidtop knew about the game and about every answer, so a reviewing agent can settle each line's truth
 * without a person (owner, 2026-10-24: "Provide all the information we have about the game, possible matches, and
 * etc. Have an agent review it and fix"). Off unless turned on in the Developer menu; the lines go to droidtop's
 * folder in Download, one game per line, appended across scrapes.
 *
 * Covers PC games (every candidate the selected source returned, or the store's own record by id) and ROMs the
 * selected source matched by hash. A ROM matched by name is not recorded: today's ROM sources hand back one picked
 * answer, not the list, and a list is what the grade needs.
 */
object MatchRecorder {
    const val FILE_NAME = "match-answers.jsonl"
    private const val KEY_ON = "droidtop_record_match_answers"

    /** What a recorded line has in `truth` until a reviewer settles it; [MatchCalibration.grade] leaves such lines out. */
    const val UNCHECKED = "?"

    /** The most file names listed from a game's folder, and the most of a description kept: enough to tell games apart. */
    private const val MAX_FILES = 40
    private const val MAX_TEXT = 600

    private val ENGINE_KINDS by lazy { GameEngine.entries.map { it.toLibraryEntryKind() }.toSet() }

    fun on(context: Context): Boolean = CatalogPrefs.prefs(context).getBoolean(KEY_ON, false)

    fun set(context: Context, on: Boolean) {
        CatalogPrefs.prefs(context).edit().putBoolean(KEY_ON, on).apply()
    }

    fun file(): File = File(ManualDownloads.savedFolder(), FILE_NAME)

    /**
     * A PC game's answers from [source]: [by] is `name` for a search for [searched], `id` for a store record asked by
     * its id. [decision] is what droidtop does with them today (`taken`, `asked` or `none`), for the reviewer to compare.
     */
    fun pc(context: Context, entry: LibraryEntry, source: String, by: String, searched: String, decision: String, matches: List<PcMatch>) {
        if (!on(context)) return
        val folder = File(entry.id).takeIf { it.isDirectory }
        val local = folder?.name ?: entry.title
        val own = PcGameIds.of(entry)
        val kind = when {
            entry.kind in ENGINE_KINDS -> "engine"
            own.steamAppId != null || own.gogId != null -> "store"
            else -> "folder"
        }
        val key = sourceKey(source)
        val results = matches.map { MatchScore.Candidate(candidateId(it), it.name, year = it.year) }
        val verdict = MatchScore.verdict(MatchScore.Local(GameTitleParser.parseName(local).title, local), results)
        val facts = buildMap {
            putAll(parsedFacts(local))
            put("library_title", entry.title)
            put("library_kind", entry.kind.name)
            entry.systemId?.let { put("system_id", it) }
            entry.pcInfo?.storeId?.let { put("store_id", it) }
            entry.pcInfo?.installPath?.let { put("install_path", it) }
            entry.pcInfo?.installedVersion?.let { put("installed_version", it) }
            entry.pcInfo?.externalIds?.takeIf { it.isNotEmpty() }?.let { ids -> put("external_ids", ids.entries.joinToString { "${it.key}:${it.value}" }) }
            entry.releaseDate?.let { put("release_date", it) }
            entry.developer?.let { put("developer", it) }
            folder?.parentFile?.name?.let { put("parent_folder", it) }
            folder?.let { put("files", listing(it)) }
            put("searched", searched)
            put("source", source)
            put("decision_today", decision)
            put("score_tier", verdict.tier.name)
            verdict.ranked.firstOrNull()?.let { put("score_best", "${it.candidate.id} ${it.score}") }
        }
        val details = matches.associate { candidateId(it) to candidateDetails(it) }
        append(fixture(entry.id, kind, "pc", local, folder?.path ?: entry.id, entry.pcInfo?.sizeBytes?.takeIf { it > 0 }, null, key, by, results, details, facts))
    }

    /** A ROM [source] matched by [md5]: its answer is certain, so the line counts toward what is answered without asking. */
    fun romByHash(context: Context, path: String, system: String, size: Long, md5: String, source: String, name: String) {
        if (!on(context)) return
        val file = File(path)
        val facts = parsedFacts(file.nameWithoutExtension) + mapOf("file" to file.name, "system_id" to system, "source" to source, "decision_today" to "taken")
        append(fixture(path, "rom", system, file.name, path, size, md5, sourceKey(source), "hash", listOf(MatchScore.Candidate(name, name)), emptyMap(), facts))
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

    internal fun candidateDetails(match: PcMatch): Map<String, String> = buildMap {
        put("source", match.sourceLabel)
        match.ids.igdbId?.let { put("igdb", it.toString()) }
        match.ids.steamGridDbId?.let { put("sgdb", it.toString()) }
        match.ids.steamAppId?.let { put("steam", it.toString()) }
        match.ids.gogId?.let { put("gog", it) }
        match.ids.lutrisSlug?.let { put("lutris", it) }
        match.releaseDate?.let { put("release_date", it) }
        match.developer?.let { put("developer", it) }
        match.publisher?.let { put("publisher", it) }
        match.genre?.let { put("genre", it) }
        match.series?.let { put("series", it) }
        match.description?.let { put("description", it.take(MAX_TEXT)) }
        match.coverUrl?.let { put("cover_url", it) }
    }

    private fun parsedFacts(name: String): Map<String, String> {
        val parsed = GameTitleParser.parseName(name)
        return buildMap {
            put("title", parsed.title)
            parsed.version.takeIf { it.isNotBlank() }?.let { put("version", it) }
            parsed.releaseTag?.let { put("release_tag", it) }
            parsed.language?.let { put("language", it) }
            parsed.platform?.let { put("platform", it) }
            parsed.subtitle?.let { put("subtitle", it) }
        }
    }

    private fun listing(folder: File): String =
        folder.list()?.sorted()?.take(MAX_FILES)?.joinToString(", ").orEmpty()

    private fun sourceKey(source: String) = source.lowercase().filter { it.isLetterOrDigit() }

    internal fun fixture(
        id: String, kind: String, system: String?, local: String, path: String?, size: Long?, md5: String?,
        source: String, by: String, results: List<MatchScore.Candidate>,
        details: Map<String, Map<String, String>> = emptyMap(), facts: Map<String, String> = emptyMap(),
    ) = MatchCalibration.Fixture(
        id = id, kind = kind, system = system, local = local, path = path, size = size, md5 = md5, year = null,
        truth = mapOf(source to UNCHECKED),
        answers = mapOf(source to MatchCalibration.Answers(by, results, details)),
        facts = facts,
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
