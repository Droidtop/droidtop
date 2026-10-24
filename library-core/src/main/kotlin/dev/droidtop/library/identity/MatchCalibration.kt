package dev.droidtop.library.identity

import dev.droidtop.library.GameTitleParser
import org.json.JSONArray
import org.json.JSONObject

/**
 * The calibration set for [MatchScore] (Droidtop/tracker#318 §3.3): games with the right ids and what every source
 * answered when asked, so the scorer is graded offline with no network and the same answer every run. [MatchRecorder]
 * writes the lines during a scrape; a reviewing agent fills `truth` and `review` from the facts and answers recorded,
 * and a person only overrides (owner, 2026-10-24: "it shouldn't require my manual intervention for every game, unless
 * I'm doing manual fixes"). One JSON object per line:
 *
 * ```
 * {"id":"f001","kind":"folder","system":"pc","local":"SevenDayWYandereGirlV0.80","path":"Games/SevenDayWYandereGirlV0.80",
 *  "size":123456,"md5":null,"year":null,
 *  "facts":{"title":"Seven Day W Yandere Girl","version":"0.80","files":"game, lib, renpy, SevenDay.exe"},
 *  "truth":{"igdb":"12345"},
 *  "review":{"igdb":{"by":"agent","sure":true,"reason":"same title and developer; the only 2021 release"}},
 *  "answers":{"igdb":{"by":"name","results":[{"id":"12345","name":"Seven Day W Yandere Girl","alt":[],"year":2021,"system":"pc",
 *    "details":{"developer":"...","release_date":"2021-03-02"}}]}}}
 * ```
 *
 * `facts` and a result's `details` are everything droidtop knew when it asked, for the reviewer; the grade never reads
 * them. `review` says who settled each source's truth: `agent` or `person` (a person's word stands over an agent's),
 * whether they were sure, and why. An answer whose review is not sure is left out of the grade, as is one still `"?"`.
 *
 * `truth` names the right id per source; a source missing from it, or given null, means the game is not in that
 * source and taking any of its answers is wrong. `"?"` means nobody has checked it yet ([MatchRecorder] writes that),
 * and the answer is left out of the grade. `by` is how the source was asked: `name` answers are scored, `hash`
 * and `id` answers are certain (tier A) and `alias` ones come from a pick the person made (tier A-).
 */
object MatchCalibration {
    val KINDS = listOf("rom", "store", "folder", "engine", "thread")

    /** [details] is keyed by candidate id: what the source said about it beyond what is scored. */
    data class Answers(val by: String, val results: List<MatchScore.Candidate>, val details: Map<String, Map<String, String>> = emptyMap())

    /** Who settled a source's truth ([by] `agent` or `person`), whether they were [sure], and why. */
    data class Review(val by: String, val sure: Boolean, val reason: String)

    data class Fixture(
        val id: String,
        val kind: String,
        val system: String?,
        val local: String,
        val path: String?,
        val size: Long?,
        val md5: String?,
        val year: Int?,
        val truth: Map<String, String?>,
        val answers: Map<String, Answers>,
        val facts: Map<String, String> = emptyMap(),
        val review: Map<String, Review> = emptyMap(),
    ) {
        /** Whether [source]'s answer is graded: its truth is filled in and whoever filled it was sure. */
        fun settled(source: String): Boolean = truth[source] != MatchRecorder.UNCHECKED && review[source]?.sure != false
    }

    /** One source's answer for one game, graded. [picked] is what droidtop would take, null when it asks. */
    data class Graded(val fixture: String, val kind: String, val source: String, val tier: MatchScore.Tier, val picked: String?, val right: String?) {
        val wrong: Boolean get() = picked != null && picked != right
    }

    data class Report(val graded: List<Graded>, val kinds: Map<String, Int>, val unchecked: Int = 0) {
        val takenAtB get() = graded.count { it.tier == MatchScore.Tier.B }
        val wrongAtB get() = graded.count { it.tier == MatchScore.Tier.B && it.wrong }
        val wrongTaken get() = graded.count { it.wrong }

        /** Share answered without asking (A, A-, B). */
        val auto get() = if (graded.isEmpty()) 0.0 else graded.count { it.tier.taken }.toDouble() / graded.size

        /** Whether the set is large and broad enough to judge by (§3.3: 200 games, 40 of each of rom, store, folder, engine). */
        val enough get() = kinds.values.sum() >= 200 && listOf("rom", "store", "folder", "engine").all { (kinds[it] ?: 0) >= 40 }

        /** §3.3's bars: at most 1 wrong in 100 at B, and at least 70% answered without asking. */
        val passes get() = wrongAtB * 100 <= takenAtB && auto >= 0.70

        fun summary(): String = buildString {
            append("${kinds.values.sum()} games (").append(kinds.entries.joinToString { "${it.key} ${it.value}" }).append("), ${graded.size} answers")
            append(if (unchecked > 0) "; $unchecked not settled yet, left out\n" else "\n")
            MatchScore.Tier.entries.forEach { tier -> append("  ${tier.name}: ${graded.count { it.tier == tier }}\n") }
            append("  wrong at B: $wrongAtB of $takenAtB; wrong taken in all: $wrongTaken\n")
            append("  answered without asking: ${"%.1f".format(auto * 100)}%\n")
            graded.filter { it.wrong }.forEach { append("  WRONG ${it.fixture} ${it.source} ${it.tier}: took ${it.picked}, right ${it.right}\n") }
        }
    }

    /** Grades every settled answer ([Fixture.settled]); a game with none settled is counted, not graded. */
    fun grade(fixtures: List<Fixture>): Report {
        val checked = fixtures.filter { fixture -> fixture.answers.keys.any(fixture::settled) }
        val graded = checked.flatMap { fixture ->
            fixture.answers.filterKeys(fixture::settled).map { (source, answers) -> gradeOne(fixture, source, answers) }
        }
        return Report(graded, checked.groupingBy { it.kind }.eachCount(), unchecked = fixtures.size - checked.size)
    }

    private fun gradeOne(fixture: Fixture, source: String, answers: Answers): Graded {
        val right = fixture.truth[source]
        val (tier, picked) = when (answers.by) {
            "hash", "id" -> (if (answers.results.isEmpty()) MatchScore.Tier.E else MatchScore.Tier.A) to answers.results.firstOrNull()?.id
            "alias" -> (if (answers.results.isEmpty()) MatchScore.Tier.E else MatchScore.Tier.A_ALIAS) to answers.results.firstOrNull()?.id
            else -> {
                // A ROM is a file: its name is searched without the extension, as the scraper does (romFile.nameWithoutExtension).
                val name = if (fixture.kind == "rom") java.io.File(fixture.local).nameWithoutExtension else fixture.local
                val parsed = GameTitleParser.parseName(name)
                val verdict = MatchScore.verdict(MatchScore.Local(parsed.title, name, fixture.year, fixture.system), answers.results)
                verdict.tier to verdict.best?.id
            }
        }
        return Graded(fixture.id, fixture.kind, source, tier, picked.takeIf { tier.taken }, right)
    }

    /** The fixtures in [text], one JSON object per line; blank lines and lines starting with `#` are skipped. */
    fun read(text: String): List<Fixture> = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { fixture(JSONObject(it)) }
        .toList()

    fun fixture(json: JSONObject): Fixture {
        val truth = json.optJSONObject("truth")
        val answers = json.optJSONObject("answers")
        val review = json.optJSONObject("review")
        return Fixture(
            id = json.getString("id"),
            kind = json.getString("kind").also { require(it in KINDS) { "kind $it is not one of $KINDS" } },
            system = json.optStringOrNull("system"),
            local = json.getString("local"),
            path = json.optStringOrNull("path"),
            size = if (json.isNull("size")) null else json.optLong("size"),
            md5 = json.optStringOrNull("md5"),
            year = if (json.isNull("year")) null else json.optInt("year"),
            truth = truth?.keys()?.asSequence()?.associateWith { truth.optStringOrNull(it) }.orEmpty(),
            answers = answers?.keys()?.asSequence()?.associateWith { source ->
                val entry = answers.getJSONObject(source)
                val results = entry.optJSONArray("results")
                Answers(entry.optString("by", "name"), results.candidates(), results.details())
            }.orEmpty(),
            facts = json.optJSONObject("facts").strings(),
            review = review?.keys()?.asSequence()?.associateWith { source ->
                val r = review.getJSONObject(source)
                Review(r.optString("by", "agent"), r.optBoolean("sure", false), r.optString("reason"))
            }.orEmpty(),
        )
    }

    /** [fixture] as one line, the form [read] takes and the recorder writes. */
    fun write(fixture: Fixture): String = JSONObject()
        .put("id", fixture.id).put("kind", fixture.kind).put("system", fixture.system ?: JSONObject.NULL)
        .put("local", fixture.local).put("path", fixture.path ?: JSONObject.NULL)
        .put("size", fixture.size ?: JSONObject.NULL).put("md5", fixture.md5 ?: JSONObject.NULL)
        .put("year", fixture.year ?: JSONObject.NULL)
        .put("facts", JSONObject(fixture.facts))
        .put("truth", JSONObject().apply { fixture.truth.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) } })
        .put("review", JSONObject().apply {
            fixture.review.forEach { (k, r) -> put(k, JSONObject().put("by", r.by).put("sure", r.sure).put("reason", r.reason)) }
        })
        .put("answers", JSONObject().apply {
            fixture.answers.forEach { (source, answers) ->
                val results = answers.results.map { candidateJson(it, answers.details[it.id]) }
                put(source, JSONObject().put("by", answers.by).put("results", JSONArray(results)))
            }
        })
        .toString()

    private fun candidateJson(c: MatchScore.Candidate, details: Map<String, String>?) = JSONObject()
        .put("id", c.id).put("name", c.name).put("alt", JSONArray(c.alternates))
        .put("year", c.year ?: JSONObject.NULL).put("system", c.system ?: JSONObject.NULL)
        .apply { c.confidence?.let { put("confidence", it) } }
        .apply { if (!details.isNullOrEmpty()) put("details", JSONObject(details)) }

    private fun JSONArray?.details(): Map<String, Map<String, String>> {
        if (this == null) return emptyMap()
        return (0 until length()).mapNotNull { i ->
            val c = getJSONObject(i)
            c.optJSONObject("details")?.let { c.getString("id") to it.strings() }
        }.toMap()
    }

    private fun JSONObject?.strings(): Map<String, String> =
        this?.keys()?.asSequence()?.associateWith { optString(it) }.orEmpty()

    private fun JSONArray?.candidates(): List<MatchScore.Candidate> {
        if (this == null) return emptyList()
        return (0 until length()).map { i ->
            val c = getJSONObject(i)
            val alt = c.optJSONArray("alt")
            MatchScore.Candidate(
                id = c.getString("id"),
                name = c.getString("name"),
                alternates = if (alt == null) emptyList() else (0 until alt.length()).map { alt.getString(it) },
                year = if (c.isNull("year")) null else c.optInt("year"),
                system = c.optStringOrNull("system"),
                confidence = if (c.has("confidence") && !c.isNull("confidence")) c.getDouble("confidence") else null,
            )
        }
    }

    private fun JSONObject.optStringOrNull(key: String): String? = if (!has(key) || isNull(key)) null else getString(key)
}
