package dev.droidtop.library.identity

import dev.droidtop.library.GameTitleParser
import org.json.JSONArray
import org.json.JSONObject

/**
 * The calibration set for [MatchScore] (Droidtop/tracker#318 §3.3): games checked by hand, each with the right ids and
 * what every source answered when asked, so the scorer is graded offline with no network and the same answer every
 * run. One JSON object per line:
 *
 * ```
 * {"id":"f001","kind":"folder","system":"pc","local":"SevenDayWYandereGirlV0.80","path":"Games/SevenDayWYandereGirlV0.80",
 *  "size":123456,"md5":null,"year":null,
 *  "truth":{"igdb":"12345"},
 *  "answers":{"igdb":{"by":"name","results":[{"id":"12345","name":"Seven Day W Yandere Girl","alt":[],"year":2021,"system":"pc"}]}}}
 * ```
 *
 * `truth` names the right id per source; a source missing from it, or given null, means the game is not in that
 * source and taking any of its answers is wrong. `by` is how the source was asked: `name` answers are scored, `hash`
 * and `id` answers are certain (tier A) and `alias` ones come from a pick the person made (tier A-).
 */
object MatchCalibration {
    val KINDS = listOf("rom", "store", "folder", "engine", "thread")

    data class Answers(val by: String, val results: List<MatchScore.Candidate>)

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
    )

    /** One source's answer for one game, graded. [picked] is what droidtop would take, null when it asks. */
    data class Graded(val fixture: String, val kind: String, val source: String, val tier: MatchScore.Tier, val picked: String?, val right: String?) {
        val wrong: Boolean get() = picked != null && picked != right
    }

    data class Report(val graded: List<Graded>, val kinds: Map<String, Int>) {
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
            append("${kinds.values.sum()} games (").append(kinds.entries.joinToString { "${it.key} ${it.value}" }).append("), ${graded.size} answers\n")
            MatchScore.Tier.entries.forEach { tier -> append("  ${tier.name}: ${graded.count { it.tier == tier }}\n") }
            append("  wrong at B: $wrongAtB of $takenAtB; wrong taken in all: $wrongTaken\n")
            append("  answered without asking: ${"%.1f".format(auto * 100)}%\n")
            graded.filter { it.wrong }.forEach { append("  WRONG ${it.fixture} ${it.source} ${it.tier}: took ${it.picked}, right ${it.right}\n") }
        }
    }

    fun grade(fixtures: List<Fixture>): Report {
        val graded = fixtures.flatMap { fixture ->
            fixture.answers.map { (source, answers) -> gradeOne(fixture, source, answers) }
        }
        return Report(graded, fixtures.groupingBy { it.kind }.eachCount())
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
                Answers(entry.optString("by", "name"), entry.optJSONArray("results").candidates())
            }.orEmpty(),
        )
    }

    /** [fixture] as one line, the form [read] takes and the recorder writes. */
    fun write(fixture: Fixture): String = JSONObject()
        .put("id", fixture.id).put("kind", fixture.kind).put("system", fixture.system ?: JSONObject.NULL)
        .put("local", fixture.local).put("path", fixture.path ?: JSONObject.NULL)
        .put("size", fixture.size ?: JSONObject.NULL).put("md5", fixture.md5 ?: JSONObject.NULL)
        .put("year", fixture.year ?: JSONObject.NULL)
        .put("truth", JSONObject().apply { fixture.truth.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) } })
        .put("answers", JSONObject().apply {
            fixture.answers.forEach { (source, answers) ->
                put(source, JSONObject().put("by", answers.by).put("results", JSONArray(answers.results.map(::candidateJson))))
            }
        })
        .toString()

    private fun candidateJson(c: MatchScore.Candidate) = JSONObject()
        .put("id", c.id).put("name", c.name).put("alt", JSONArray(c.alternates))
        .put("year", c.year ?: JSONObject.NULL).put("system", c.system ?: JSONObject.NULL)
        .apply { c.confidence?.let { put("confidence", it) } }

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
