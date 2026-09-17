package dev.droidtop.library

/**
 * What a game's folder name says about it: its name, its version, the mods
 * and language a distribution tacked on, and whether the folder is one
 * SEGMENT of a game (a week, a chapter, a part) rather than the game.
 *
 * The logic is ported from the user's own Pythia project
 * (`G:\Support\GameManagement\RenPyPatch\pythia`, `pythia/onboarding.py`
 * and `pythia/datadir.py`), which derives the same facts from the same
 * kind of folder names and has been corrected against a real library
 * several times. It is a rewrite, not a copy: Pythia is GPL-3 and the user
 * owns it, and what is reused is the reasoning, restated in Kotlin against
 * droidtop's own model, with this module's tests over the same real corpus
 * (`coordination/research/game-naming/adult-folder-names-2026-09-16.txt`).
 *
 * Nothing here touches the filesystem. A caller hands in a path and this
 * answers from the names in it, which is what makes it testable over a
 * list of folder names a rig produced.
 */
object GameNaming {

    /**
     * A bare trailing number only counts as a version when it is marked as
     * one (a `v` prefix) or dotted (`0.8.3`, `1_3_3`).
     *
     * Pythia's own finding, from a real library on 2026-08-02: the `5` in
     * `Far Cry 5` and the `2077` in `Cyberpunk 2077` are title numbers, and
     * reading them as versions silently merged unrelated games. It does not
     * catch every real case either -- a bare build number like
     * `Bunnycop-049` stays part of the name -- and that is the right way
     * round, since a missed version costs one extra entry and a wrong one
     * merges two games.
     */
    private val NAME_VERSION = Regex("""^(.*?)[-_ ](?:v(\d+(?:[._]\d+)*[a-zA-Z]*)|(\d+[._]\d+(?:[._]\d+)*[a-zA-Z]*))(.*)$""")

    /**
     * The words a folder uses when it is a part of a game rather than a
     * game: `Week 1`, `Part1`, `Chap3+`, `Episode 2`. Pythia's list, from
     * the real cases that fragmented into bogus separate games
     * (`Fetish Locator/Week 1|2|3`, `My New Family/Part 1`,
     * `Thief of Hearts/Part1`, `BeingADik/Chap3+`), plus `day`, `disc` and
     * `disk`, which droidtop's own episode-title rule already carried
     * ([qualifiedFolderTitle]) -- one list, because the two rules read the
     * same folder names and must not disagree about them.
     */
    private val GENERIC_PART_PREFIX =
        Regex("""^(chap(?:ter)?|part|week|act|episode|ep|season|vol(?:ume)?|day|disc|disk)\.?\s*""", RegexOption.IGNORE_CASE)

    /** The same words where they end a name that has a title in front of it -- see [trailingSegment]. */
    private val TRAILING_PART =
        Regex("""[-_ .]?(chap(?:ter)?|part|week|act|episode|ep|season|vol(?:ume)?|day|disc|disk)\.?\s*(\d+)$""", RegexOption.IGNORE_CASE)

    /** A leaf that is a dotted version and nothing else: `10.0-sancho`. */
    private val BARE_VERSION_LEAF = Regex("""^\d+\.\d""")

    /** What must follow the part word for the leaf to be a part marker: a number. */
    private val NUMBER_AFTER_PART_WORD = Regex("""^\W*\d""")

    /** The first version-shaped run of digits inside a part/version leaf. */
    private val VERSION_IN_LEAF = Regex("""(\d+(?:[._]\d+)*[a-zA-Z]*)""")

    /**
     * The language tokens a distribution appends, mapped to the code
     * droidtop keeps. Pythia's fixed set, unchanged: naming can only ever
     * be evidence for the languages people actually label.
     */
    private val LANGUAGE_TOKENS = mapOf(
        "eng" to "en", "en" to "en", "english" to "en",
        "jp" to "ja", "jpn" to "ja", "japanese" to "ja",
        "cn" to "zh", "chs" to "zh", "cht" to "zh", "chinese" to "zh",
        "kr" to "ko", "kor" to "ko", "korean" to "ko",
        "mtl" to "mtl", "machine-translated" to "mtl", "machinetranslated" to "mtl", "translated" to "mtl",
    )

    /** What one folder name says. [segment] is null unless the folder is a part of a game. */
    data class Derived(
        val name: String,
        val version: String,
        val mods: List<String>,
        val language: String?,
        val segment: Segment? = null,
    )

    /** One part of a game: `Week 1` of `Fetish Locator`. [order] sorts them. */
    data class Segment(val label: String, val order: Int?)

    /** What a leaf folder name is, when it is not a title of its own. */
    private enum class LeafKind { TITLE, BARE_VERSION, PART }

    /**
     * Pythia's `_is_generic_part_leaf`, split into the two answers droidtop
     * needs from it: a leaf that is a bare dotted version
     * (`BeingADik/Chap3+/10.0-sancho`) is another VERSION of the game its
     * ancestor names, while a leaf that is a part word plus a number
     * (`Fetish Locator/Week 1`) is a SEGMENT of it. Pythia treats both as
     * "no title of its own", which is the half they share; the library
     * shows them differently, so they are told apart here.
     */
    /**
     * Whether a folder named [name] is the structure of ONE game rather
     * than a game or a library level of its own: a part marker
     * (`Chap3+`, `Week 2`) or a bare version (`12.0-scrappy`, `1.0`).
     * Both walks ask this to decide what a folder costs them in depth
     * (docs/SPEC.md 7m): such a folder costs nothing, because the game it
     * belongs to is the level, not the folder.
     */
    fun isStructuralFolderName(name: String): Boolean = leafKind(name) != LeafKind.TITLE

    private fun leafKind(name: String): LeafKind {
        if (BARE_VERSION_LEAF.containsMatchIn(name)) return LeafKind.BARE_VERSION
        val stripped = GENERIC_PART_PREFIX.replaceFirst(name, "")
        if (stripped == name) return LeafKind.TITLE
        return if (NUMBER_AFTER_PART_WORD.containsMatchIn(stripped)) LeafKind.PART else LeafKind.TITLE
    }

    /**
     * The nearest ancestor of [pathParts] that looks like a real title,
     * walking up past part-marker folders, with the last part-marker
     * folder passed on the way.
     *
     * Pythia bounds this to two levels so it can never walk up into an
     * engine or library root (`adult/renpy`), and so does this: it is only
     * ever consulted when the leaf itself is already known to have no
     * title of its own.
     *
     * The part marker passed on the way is droidtop's addition and it is
     * the real `BeingADik/Chap3+/10.0-sancho` case: Pythia derives "the
     * game is BeingADik, the version is 10.0" and stops, because it has
     * nowhere to put "and this is chapter 3". droidtop does, so the
     * chapter is kept rather than thrown away.
     */
    private data class Ancestor(val name: String, val segment: Segment?)

    private fun meaningfulAncestor(pathParts: List<String>, maxLevels: Int = 2): Ancestor? {
        var index = pathParts.size - 2
        var levels = 0
        var segment: Segment? = null
        while (index >= 0 && levels < maxLevels) {
            val name = pathParts[index]
            if (name.isNotEmpty()) {
                when (leafKind(name)) {
                    LeafKind.TITLE -> return Ancestor(name, segment)
                    LeafKind.PART -> if (segment == null) segment = segmentOf(name)
                    LeafKind.BARE_VERSION -> Unit
                }
            }
            index--
            levels++
        }
        return null
    }

    /** A part-marker folder name read as a segment: its own label, and the number in it. */
    private fun segmentOf(name: String): Segment =
        Segment(name.trim(), extractVersionOnly(name).first.substringBefore('.').toIntOrNull())

    /**
     * Pythia's `_extract_version_only`: the version/mods/language of a leaf
     * that is JUST a version or part marker, where the name comes from an
     * ancestor instead. The part word itself is stripped rather than kept
     * as a mod.
     */
    private fun extractVersionOnly(leaf: String): Triple<String, List<String>, String?> {
        val stripped = GENERIC_PART_PREFIX.replaceFirst(leaf, "")
        val match = VERSION_IN_LEAF.find(stripped)
            ?: return classifyVariantTokens(stripped).let { (mods, language) ->
                Triple(stripped.trim().ifEmpty { leaf }, mods, language)
            }
        val version = match.value.replace('_', '.')
        val rest = stripped.substring(0, match.range.first) + " " + stripped.substring(match.range.last + 1)
        val (mods, language) = classifyVariantTokens(rest)
        return Triple(version, mods, language)
    }

    /**
     * Pythia's `classify_variant_tokens`: what is left after the name and
     * version have been taken out is a language token or a mod name.
     * Naming alone can never tell a mod's version, so a mod is just a name.
     */
    fun classifyVariantTokens(rest: String): Pair<List<String>, String?> {
        val mods = mutableListOf<String>()
        var language: String? = null
        for (token in rest.trim(' ', '-', '_').split(Regex("""[-_ ]+"""))) {
            if (token.isEmpty() || token.none { it.isLetterOrDigit() }) continue
            val canonical = LANGUAGE_TOKENS[token.lowercase()]
            if (canonical != null && language == null) language = canonical else mods += token
        }
        return mods to language
    }

    /**
     * Everything one folder says about the game in it. [path] is the
     * folder's path, `/`-separated; only the last three components are ever
     * looked at ([meaningfulAncestor]'s bound).
     *
     * Three shapes, in order:
     *
     * 1. The leaf is a part marker (`Fetish Locator/Week 1`) -- the name
     *    comes from the nearest titled ancestor and the leaf becomes a
     *    SEGMENT.
     * 2. The leaf is a bare version (`BeingADik/Chap3+/10.0-sancho`) -- the
     *    name comes from the ancestor, the leaf becomes a VERSION, and a
     *    part-marker folder passed on the way up is its SEGMENT.
     * 3. The leaf is a title, possibly with a version, mods and a language
     *    in it, and possibly ending in a part marker of its own
     *    (`ThiefofHeartsPart3-0.0.9-pc`) -- see [trailingSegment].
     */
    fun derive(path: String): Derived {
        val parts = path.split('/', '\\').filter { it.isNotEmpty() }
        val leaf = parts.lastOrNull().orEmpty().ifEmpty { return Derived(path, "", emptyList(), null) }

        when (leafKind(leaf)) {
            LeafKind.PART -> {
                val ancestor = meaningfulAncestor(parts)
                if (ancestor != null) {
                    val (_, mods, language) = extractVersionOnly(leaf)
                    // The number a part leaf yields IS its part number, so
                    // it names the segment and is not also a version.
                    return Derived(ancestor.name, "", mods, language, segmentOf(leaf))
                }
            }
            LeafKind.BARE_VERSION -> {
                val ancestor = meaningfulAncestor(parts)
                if (ancestor != null) {
                    val (version, mods, language) = extractVersionOnly(leaf)
                    return Derived(ancestor.name, version, mods, language, ancestor.segment)
                }
            }
            LeafKind.TITLE -> Unit
        }

        val match = NAME_VERSION.find(leaf)
            ?: return trailingSegment(Derived(leaf, "", emptyList(), null))
        val name = match.groupValues[1].trim(' ', '-', '_').ifEmpty { leaf }
        val version = (match.groupValues[2].ifEmpty { match.groupValues[3] }).replace('_', '.')
        val (mods, language) = classifyVariantTokens(match.groupValues[4])
        return trailingSegment(Derived(name, version, mods, language))
    }

    /**
     * A title that ENDS in a part marker is that part of the game the rest
     * of it names: `ThiefofHeartsPart3-0.0.9-pc` is part 3 of Thief of
     * Hearts, which on the rig sits beside `Part1` and `Part2` -- two
     * segments of one game and a third game with a name of its own.
     *
     * droidtop's own extension of Pythia's rule, which only covers a leaf
     * that is a marker and NOTHING else. The word list is the same one, so
     * the numbered titles it must not touch are the same too:
     * `Anomalous_Coffee_Machine_2` (no part word) and
     * `love_of_magic_book1` (`book` is not one of these words) keep their
     * numbers and stay separate games.
     */
    private fun trailingSegment(derived: Derived): Derived {
        if (derived.segment != null) return derived
        val match = TRAILING_PART.find(derived.name) ?: return derived
        val title = derived.name.substring(0, match.range.first).trim(' ', '-', '_', '.')
        if (title.isEmpty()) return derived
        val label = match.groupValues[1].replaceFirstChar { it.uppercase() } + " " + match.groupValues[2]
        return derived.copy(name = title, segment = Segment(label, match.groupValues[2].toIntOrNull()))
    }

    /**
     * Two derived names for the same game, decided by the names alone:
     * everything that is not a letter or a digit is noise a distribution
     * added, so `GoodbyeEternity` and `Goodbye Eternity` are one game and
     * one library entry with two versions.
     */
    fun sameGame(a: String, b: String): Boolean = nameKey(a) == nameKey(b) && nameKey(a).isNotEmpty()

    /** [sameGame]'s comparison key. */
    fun nameKey(name: String): String = name.lowercase().filter { it.isLetterOrDigit() }

    /**
     * Pythia's `NAME_SIMILARITY_THRESHOLD`, and what it is for.
     *
     * In Pythia this decides which already-tracked games are SUGGESTED to
     * the person onboarding a folder (`find_candidates` returns
     * `{"certain", "suggested"}`; only an exact path, a sync marker or a
     * store id is ever `certain`). droidtop's scan has no person in it, so
     * similarity may not merge on its own, and the corpus says why plainly:
     * `love_of_magic_book1`, `book2` and `book3` score 0.94 against each
     * other and are three different games, as do `Lust Academy` and
     * `Lust Theory` at 0.75. Equal names merge ([sameGame]); similar names
     * become [GameGrouping.Suggestion]s a person can accept.
     */
    const val NAME_SIMILARITY_THRESHOLD = 0.6

    /**
     * Python's `difflib.SequenceMatcher(None, a, b).ratio()`, which is the
     * measure Pythia's threshold was chosen against, so it is the measure
     * ported rather than a Levenshtein ratio that would put the same
     * threshold in a different place.
     *
     * `2 * M / T`, where `M` is the total length of the matching blocks
     * found by difflib's recursive longest-matching-block search and `T` is
     * the two lengths added. No junk heuristic is ported: `isjunk` is None
     * at Pythia's call site and difflib's `autojunk` only engages at 200
     * elements, which a folder name never reaches.
     */
    fun similarity(a: String, b: String): Double {
        val total = a.length + b.length
        if (total == 0) return 1.0
        return 2.0 * matchingBlockTotal(a, b) / total
    }

    private fun matchingBlockTotal(a: String, b: String): Int {
        val b2j = HashMap<Char, MutableList<Int>>()
        for ((index, ch) in b.withIndex()) b2j.getOrPut(ch) { mutableListOf() }.add(index)

        var matched = 0
        val queue = ArrayDeque<IntArray>()
        queue.add(intArrayOf(0, a.length, 0, b.length))
        while (queue.isNotEmpty()) {
            val (alo, ahi, blo, bhi) = queue.removeFirst()
            val match = longestMatch(a, b2j, alo, ahi, blo, bhi)
            val (i, j, size) = match
            if (size == 0) continue
            matched += size
            if (alo < i && blo < j) queue.add(intArrayOf(alo, i, blo, j))
            if (i + size < ahi && j + size < bhi) queue.add(intArrayOf(i + size, ahi, j + size, bhi))
        }
        return matched
    }

    /** difflib's `find_longest_match`: the earliest longest matching block in the two windows. */
    private fun longestMatch(
        a: String,
        b2j: Map<Char, List<Int>>,
        alo: Int,
        ahi: Int,
        blo: Int,
        bhi: Int,
    ): Triple<Int, Int, Int> {
        var besti = alo
        var bestj = blo
        var bestsize = 0
        var j2len = HashMap<Int, Int>()
        for (i in alo until ahi) {
            val newj2len = HashMap<Int, Int>()
            for (j in b2j[a[i]].orEmpty()) {
                if (j < blo) continue
                if (j >= bhi) break
                val k = (j2len[j - 1] ?: 0) + 1
                newj2len[j] = k
                if (k > bestsize) {
                    besti = i - k + 1
                    bestj = j - k + 1
                    bestsize = k
                }
            }
            j2len = newj2len
        }
        return Triple(besti, bestj, bestsize)
    }

    private operator fun IntArray.component1(): Int = this[0]
    private operator fun IntArray.component2(): Int = this[1]
    private operator fun IntArray.component3(): Int = this[2]
    private operator fun IntArray.component4(): Int = this[3]
}
