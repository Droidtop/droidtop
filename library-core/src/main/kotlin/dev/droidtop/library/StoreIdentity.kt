package dev.droidtop.library

import java.text.Normalizer

/**
 * Which store rows are one game (docs/SPEC.md 7m, "One game across stores").
 *
 * The same game owned on Steam, GOG, Epic, Amazon and itch.io arrives as
 * one row per store, each with its own id and the store's own spelling of
 * the title. [group] says which of those rows are one game, in this
 * order of evidence:
 *
 * 1. **An install directory two rows name.** Two stores that put a game
 *    in the same folder are, by that fact, one game. This is the only
 *    cross-reference the stores' own rows carry: none of Steam's, GOG's,
 *    Epic's, Amazon's or itch.io's rows names another store's id.
 * 2. **The user's own word**: a [LibraryEntry.gameName] the person set
 *    ("The same game as...") groups the row under that name.
 * 3. **The same title once it is spelled the same way** ([titleKey]): case,
 *    punctuation, trademark symbols and a trailing edition label
 *    ("Deluxe Edition", "GOTY") do not make a different game.
 *
 * The title key is an EXACT comparison, never a similarity: `Doom` and
 * `Doom 3`, `Fallout` and `Fallout 3`, `Doom` and `Doom (2016)` stay
 * apart, because a number or a year in a title says which game it is.
 * Similar names are only ever suggested to a person ([SimilarGames]).
 *
 * Linear in the number of rows: each row is looked up in two hash maps.
 */
object StoreIdentity {

    /** A trailing edition label's own words, longest phrase first so "game of the year" is not eaten word by word. */
    private val EDITION_MODIFIERS: List<List<String>> = listOf(
        "game of the year", "digital deluxe", "collectors", "collector", "directors cut", "deluxe", "definitive",
        "complete", "ultimate", "gold", "standard", "special", "enhanced", "anniversary", "legendary", "premium",
        "digital", "goty", "limited", "founders", "launch", "platinum", "royal",
    ).map { it.split(' ') }.sortedByDescending { it.size }

    /** Labels that mean "this edition" without the word edition: removed only at the very end. */
    private val BARE_LABELS: List<List<String>> =
        listOf("game of the year", "goty", "directors cut").map { it.split(' ') }

    /** A word a title may begin with that no store spells consistently. */
    private const val ARTICLE = "the"

    private val TRADEMARKS = Regex("[\\u2122\\u00AE\\u00A9\\u2120]")
    private val APOSTROPHES = Regex("['\\u2018\\u2019\\u02BC`]")
    private val MARKS = Regex("\\p{M}+")
    private val NOT_ALNUM = Regex("[^a-z0-9]+")

    /**
     * The key two spellings of one title share, or "" when nothing is left
     * of it. Folds case, diacritics, trademark symbols, apostrophes and
     * punctuation; reads "&" as "and"; drops a leading "The" and any
     * trailing edition labels. Keeps every number.
     */
    fun titleKey(title: String): String {
        val folded = Normalizer.normalize(title.replace(TRADEMARKS, ""), Normalizer.Form.NFKD)
            .replace(MARKS, "")
            .lowercase()
            .replace(APOSTROPHES, "")
            .replace("&", " and ")
            .replace(NOT_ALNUM, " ")
            .trim()
        if (folded.isEmpty()) return ""
        var tokens = folded.split(' ').filter { it.isNotEmpty() }
        if (tokens.size > 1 && tokens.first() == ARTICLE) tokens = tokens.drop(1)
        tokens = stripEditions(tokens)
        return tokens.joinToString("")
    }

    private fun stripEditions(all: List<String>): List<String> {
        var tokens = all
        while (true) {
            val stripped = stripOne(tokens)
            // A title that is nothing but an edition label is its own name.
            if (stripped == null || stripped.isEmpty()) return tokens
            tokens = stripped
        }
    }

    private fun stripOne(tokens: List<String>): List<String>? {
        if (tokens.size > 1 && tokens.last() == "edition") {
            var rest = tokens.dropLast(1)
            while (true) {
                val phrase = EDITION_MODIFIERS.firstOrNull { rest.endsWithPhrase(it) } ?: break
                rest = rest.dropLast(phrase.size)
            }
            // "Gold Edition" on its own is a name, not a label on one.
            return rest.takeIf { it.isNotEmpty() }
        }
        val bare = BARE_LABELS.firstOrNull { tokens.endsWithPhrase(it) && tokens.size > it.size }
        return bare?.let { tokens.dropLast(it.size) }
    }

    private fun List<String>.endsWithPhrase(phrase: List<String>): Boolean =
        size >= phrase.size && subList(size - phrase.size, size) == phrase

    /** The stores' order everywhere a game shows more than one ([OWNERSHIP_STORES]); anything else after. */
    private fun storeRank(entry: LibraryEntry): Int {
        val index = OWNERSHIP_STORES.indexOf(entry.ownership()?.store)
        return if (index < 0) OWNERSHIP_STORES.size else index
    }

    /**
     * One game's worth of store rows and the name the game shows under,
     * ordered by store (Steam, GOG, Epic, Amazon, itch.io) and then by id,
     * so the order is the same on every scan.
     */
    data class Merged(val name: String, val entries: List<LibraryEntry>)

    /** Folds [stores] (rows whose [ownership] is a store) into games. */
    fun group(stores: List<LibraryEntry>): List<Merged> {
        val rows = stores.map { storeRank(it) to it }
            .sortedWith(compareBy({ it.first }, { it.second.id }))
            .map { it.second }
        val parent = IntArray(rows.size) { it }
        fun find(i: Int): Int {
            var x = i
            while (parent[x] != x) {
                parent[x] = parent[parent[x]]
                x = parent[x]
            }
            return x
        }
        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[maxOf(ra, rb)] = minOf(ra, rb)
        }
        val byDir = HashMap<String, Int>()
        // A directory joins rows only when an installed row stands on it: an
        // uninstalled store row's path is the store's would-be folder, which
        // can be one shared value for the whole catalogue (docs/SPEC.md 7m).
        val installedDirs = rows.mapNotNullTo(HashSet()) { row -> row.pcInfo?.takeIf { it.installed }?.installPath?.asInstallKey() }
        val byTitle = HashMap<String, Int>()
        rows.forEachIndexed { index, row ->
            row.pcInfo?.installPath?.asInstallKey()?.takeIf { it in installedDirs }?.let { dir -> union(index, byDir.getOrPut(dir) { index }) }
            titleKey(row.gameName ?: row.title).takeIf { it.isNotEmpty() }
                ?.let { key -> union(index, byTitle.getOrPut(key) { index }) }
        }
        val components = LinkedHashMap<Int, MutableList<LibraryEntry>>()
        rows.forEachIndexed { index, row -> components.getOrPut(find(index)) { mutableListOf() } += row }
        return components.values.map { members ->
            val said = members.firstNotNullOfOrNull { it.gameName?.takeIf(String::isNotBlank) }
            // The plainest spelling: the shortest title, which is the one
            // without an edition label or a trademark symbol after it.
            val name = said ?: members.minWith(compareBy<LibraryEntry> { it.title.length }).title
            Merged(name, members)
        }
    }
}
