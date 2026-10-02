package dev.droidtop.library

/**
 * The words and numbers that say a folder is one PART of a game rather
 * than a game: `book3`, `Week 1`, `Chap3+`, `Act II`, `Episode Two`.
 *
 * One definition, read by every walk and by [GameNaming], so the scan's
 * depth rule, the naming rule and the grouping cannot disagree about what a
 * part is (docs/SPEC.md 7m, 7n). Nothing here touches the filesystem.
 */
object PartMarkers {

    /**
     * The words that name a part when they begin a folder's WHOLE name and
     * a number follows. `book` is here since 2026-10-01: a parent folder
     * with `book1`, `book2`, `book3` below it is one game whose title the
     * parent gives (Droidtop/tracker#264). The words a games library
     * actually uses, not every word that could count a part.
     */
    private const val LEAF_WORDS = "chap(?:ter)?|part|week|act|episode|ep|season|vol(?:ume)?|day|disc|disk|book|bk|pt"

    /** A leaf's part word, with the dot and spaces after it. */
    val LEAF_PREFIX = Regex("""^($LEAF_WORDS)\.?\s*""", RegexOption.IGNORE_CASE)

    /**
     * The part words where they END a name that has a title in front of it
     * (`ThiefofHeartsPart3`). `book` is deliberately NOT here: a flat
     * `name_book1` beside `name_book2` is a numbered title (docs/SPEC.md
     * 7m), while `book3` alone as a folder under a titled parent is
     * unambiguous.
     */
    val TRAILING = Regex(
        """[-_ .]?(chap(?:ter)?|part|week|act|episode|ep|season|vol(?:ume)?|day|disc|disk)\.?\s*(\d+)$""",
        RegexOption.IGNORE_CASE,
    )

    // A roman numeral is only read when it is written in capitals, so a
    // name like "Part i" or a word that happens to begin with i/v/x is not
    // taken for a number. Both forms need a part word in front of them.
    // The number must END the name (bar separators): `Day One Something`
    // is a title, `Act II` is a part.
    private val ROMAN = Regex("""^((?i:$LEAF_WORDS)\.?\s*)([IVX]{1,4})\b(?=[\s\-_+.]*$)""")
    private val WORD_NUMBER = Regex("""^((?i:$LEAF_WORDS)\.?\s*)((?i:one|two|three|four|five|six|seven|eight|nine|ten))\b(?=[\s\-_+.]*$)""")
    private val ROMAN_VALUES = listOf("I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI", "XII", "XIII", "XIV", "XV")
    private val NUMBER_WORDS = listOf("one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten")

    /**
     * [name] with a roman numeral or a spelled-out number straight after its
     * part word written as digits: `Act II` is `Act 2`, `Book Three` is
     * `Book 3`. Every other name comes back unchanged, so the digit-based
     * rules downstream need only one form.
     */
    fun withArabic(name: String): String {
        val roman = ROMAN.find(name)
        if (roman != null) {
            val value = ROMAN_VALUES.indexOf(roman.groupValues[2]) + 1
            if (value > 0) return replaceNumber(name, roman, value)
        }
        val word = WORD_NUMBER.find(name)
        if (word != null) {
            val value = NUMBER_WORDS.indexOf(word.groupValues[2].lowercase()) + 1
            if (value > 0) return replaceNumber(name, word, value)
        }
        return name
    }

    private fun replaceNumber(name: String, match: MatchResult, value: Int): String {
        val range = match.groups[2]?.range ?: return name
        return name.substring(0, range.first) + value + name.substring(range.last + 1)
    }
}

/**
 * Folder names that are an ENGINE'S OWN LAYOUT and never a game's name
 * (docs/SPEC.md 7n; Droidtop/tracker#264): `game` is where Ren'Py keeps a
 * game's scripts, `www` is RPG Maker MV's web root, `Contents` is a macOS
 * app bundle's. Such a folder is part of the game it sits in. The title is
 * taken from that game; a stand-alone one has no title at all, and the
 * library shows it as an unidentified folder with its path instead of
 * calling it `game`.
 *
 * Exact names only, compared whole and case-insensitively, so a game
 * called `Game of Something` or `Data Wing` is untouched. Platform folders
 * (`win64`, `linux`) are here because a build is filed under them. Names
 * a person files games under (`games`, `adult`) are NOT: those are the
 * library's levels, and [GameNaming.relativeTo] keeps the games root and
 * everything above it out of a title.
 */
object EngineFolderNames {
    private val NAMES = setOf(
        "game", "data", "www", "resources", "resource", "res", "lib", "lib64", "libs", "renpy", "contents",
        "assets", "bin", "binaries", "content", "engine", "runtime", "app", "src", "js",
        "win", "win32", "win64", "x86", "x64", "windows", "linux", "mac", "macos", "pc",
    )

    /** Whether [name], a whole folder name, is one of them. */
    fun matches(name: String): Boolean = name.trim().lowercase() in NAMES
}

/**
 * What one folder or file name says about the game in it, with the name as
 * found kept beside what was made of it ([raw] is never altered).
 *
 * [title] is the drawn form: separators turned into spaces the way
 * [GameNaming.displayName] does it, bracket tags, platform and release
 * tags, versions and scene suffixes taken out, a subtitle and a numbered
 * title (`Far Cry 5`, `Name 2`) left in. [part] is set when the name is a
 * part of a game (`book3` under a parent folder, `Part3` at the end of a
 * name); the title then comes from the parent.
 */
data class ParsedTitle(
    val raw: String,
    val title: String,
    val version: String,
    /** `Final`, `Pre Alpha`, `Demo`, `GOG`: what the distribution says about the build. Null when it says nothing. */
    val releaseTag: String?,
    /** A code from [GameNaming] (`en`, `ja`, `zh`, `ko`, `mtl`); null when the name says none. */
    val language: String?,
    /** `windows`, `linux`, `mac`, `android` or `pc`; null when the name says none. */
    val platform: String?,
    val part: GameNaming.Segment?,
    /** What follows `Name: ` or `Name - ` in the title; null when the title has none. It stays in [title] too. */
    val subtitle: String?,
    /**
     * The title without its sequel number (`Far Cry`), when it ends in a one
     * or two digit number; null otherwise. Two numbered titles with the same
     * [seriesTitle] are separate games that may be GROUPED as a series, never
     * merged (docs/SPEC.md 7n). A year is not a number (`Name 2016`).
     */
    val seriesTitle: String?,
    /** The sequel number [seriesTitle] was cut at (`5` of `Far Cry 5`). */
    val number: Int?,
    /** Words left over after everything above was taken out: a mod name, a scene group. Never part of the title. */
    val extras: List<String>,
    /** An engine-standard folder (`game`, `data`) with no game above it: [title] is [GameNaming.UNIDENTIFIED] and the raw name says what it is. */
    val unidentified: Boolean = false,
)

/**
 * ONE deterministic parser from a folder or file name to a [ParsedTitle]
 * (docs/SPEC.md 7n; Droidtop/tracker#264). Pure: it reads names only, so a
 * table of names tests it and no list ever pays for it.
 *
 * It stands on [GameNaming.derive], which already reads a name's version,
 * language, mods and part (and is what groups folders into games), and adds
 * what a games folder carries beyond that: bracketed tags, dotted scene
 * names, trailing platform and release tags. [GameNaming.derive] calls the
 * two cleaning steps below ([stripNoise], [trimTrailing]) itself, so a
 * game's grouping name and the title shown for it are the same answer.
 *
 * What is NOT done, on purpose: no guessing at a title. A bare trailing
 * number is part of the title (`Far Cry 5`), a word like `Alpha` after a
 * plain space is part of the title (`Project Alpha`), and anything that is
 * not provably a tag stays.
 */
object GameTitleParser {

    private val BRACKET = Regex("""[\[({]([^\[\]{}()]*)[\])}]""")
    private val SHORT_NUMBER = Regex("""\d{1,3}""")
    private val YEAR = Regex("""(?:19|20)\d\d""")
    private val SUBTITLE = Regex("""^(.+?)(?:\s+-\s+|:\s+)(.+)$""")
    private val SERIES_NUMBER = Regex("""^(.*\S)\s+(\d{1,2})$""")
    private val REPEATED = Regex("""([-_,])\1+""")
    private val VERSION_IN_BRACKET = Regex("""[vV]?\d+(?:[._]\d+)+[a-zA-Z]?""")
    private val DOTTED_WORD = Regex("""(?<=[A-Za-z])\.(?=[A-Za-z])""")
    private val SPACES = Regex("""\s+""")
    private val TRAILING_TOKEN = Regex("""([-_ .]+)([A-Za-z0-9]+)$""")
    private val MULTI = Regex("""multi\d*""", RegexOption.IGNORE_CASE)

    /** Platform and build-architecture tokens; the value is the platform they name, null for an architecture alone. */
    private val PLATFORMS: Map<String, String?> = mapOf(
        "pc" to "pc",
        "win" to "windows", "win32" to "windows", "win64" to "windows", "windows" to "windows",
        "lin" to "linux", "lin64" to "linux", "linux" to "linux",
        "mac" to "mac", "osx" to "mac", "macos" to "mac",
        "android" to "android", "apk" to "android",
        "x86" to null, "x64" to null, "32bit" to null, "64bit" to null,
    )

    private val RELEASE_TAGS = setOf(
        "final", "alpha", "beta", "demo", "repack", "gog", "steam", "epic", "itch", "prototype", "preview", "patched", "pre",
    )

    private fun isReleaseTag(lower: String): Boolean = lower in RELEASE_TAGS || MULTI.matches(lower)

    /**
     * [name] without its bracketed groups (`[GOG]`, `(Final)`, `{x64}`) and,
     * for a name with no spaces at all, with the dots between words read as
     * spaces (`Game.Name.v1.2.3-GROUP` is `Game Name v1.2.3-GROUP`). A
     * bracket holding only a short number is a copy or part number and keeps
     * it (`Game (2)` is `Game 2`). A name this changes nothing in comes back
     * as it is.
     */
    fun stripNoise(name: String): String {
        var s = name
        val hadSpace = name.contains(' ')
        if (s.indexOf('[') >= 0 || s.indexOf('(') >= 0 || s.indexOf('{') >= 0) {
            s = BRACKET.replace(s) { match ->
                val inner = match.groupValues[1].trim()
                when {
                    SHORT_NUMBER.matches(inner) -> " $inner "
                    // A version the distribution bracketed is still the version.
                    VERSION_IN_BRACKET.matches(inner) -> " v${inner.removePrefix("v").removePrefix("V")} "
                    // `Name (2016)`: the year says which game it is.
                    match.value.startsWith("(") && YEAR.matches(inner) -> " ($inner) "
                    else -> " "
                }
            }
        }
        if (!hadSpace) s = DOTTED_WORD.replace(s, " ")
        if (s == name) return name
        return SPACES.replace(s, " ").trim(' ', '-', '_', '.').ifEmpty { name }
    }

    /** A name with its trailing noise cut off, and what was cut ([tokens], in name order). */
    internal class Trimmed(val name: String, val platform: String?, val tokens: List<String>)

    /**
     * Cuts platform tokens (`pc`, `win64`, `linux`) off the end of [name],
     * after any separator, and release tags (`Final`, `Beta`, `GOG`) after a
     * dash, underscore or dot only: `Game_Name-win64-Final` is `Game_Name`,
     * while `Project Alpha` keeps its last word. The first word is never cut.
     */
    internal fun trimTrailing(name: String): Trimmed {
        var s = name
        var platform: String? = null
        val cut = ArrayList<String>()
        while (true) {
            val match = TRAILING_TOKEN.find(s) ?: break
            val separator = match.groupValues[1]
            val token = match.groupValues[2]
            val lower = token.lowercase()
            val head = s.substring(0, match.range.first)
            if (head.isBlank()) break
            val platformToken = PLATFORMS.containsKey(lower)
            val hardSeparator = separator.any { it == '-' || it == '_' || it == '.' }
            if (platformToken || (hardSeparator && isReleaseTag(lower))) {
                if (platformToken && platform == null) platform = PLATFORMS[lower]
                cut.add(0, token)
                s = head
            } else {
                break
            }
        }
        return Trimmed(s, platform, cut)
    }

    /** A bare name; the same as [parse] on a one-component path. */
    fun parseName(name: String): ParsedTitle = parse(name)

    /**
     * What [path]'s last component says. The parent folders are read the way
     * [GameNaming.derive] reads them, so `/games/Some Game/book3` is the
     * title `Some Game` with part `book3`, never a title `book3`. [root] is
     * the games root the folder is under, when it is known: nothing at or
     * above it is ever read as a title ([GameNaming.relativeTo]).
     */
    fun parse(path: String, root: String? = null): ParsedTitle {
        val raw = path.split('/', '\\').lastOrNull { it.isNotEmpty() }.orEmpty()
        val derived = GameNaming.derive(GameNaming.relativeTo(root, path))
        var platform: String? = derived.platform
        var language: String? = derived.language
        var version: String = derived.version
        val tags = ArrayList<String>()
        val extras = ArrayList<String>()

        fun classify(token: String) {
            val key = token.lowercase()
            when {
                PLATFORMS.containsKey(key) -> if (platform == null) platform = PLATFORMS[key]
                isReleaseTag(key) -> tags.add(token)
                else -> extras.add(token)
            }
        }
        // What derive's cleaning took out of the leaf: its bracketed groups.
        for (group in BRACKET.findAll(raw)) {
            val inner = group.groupValues[1].trim()
            if (inner.isEmpty() || SHORT_NUMBER.matches(inner) || YEAR.matches(inner)) continue
            if (VERSION_IN_BRACKET.matches(inner)) {
                if (version.isEmpty()) version = inner.removePrefix("v").removePrefix("V").replace('_', '.')
                continue
            }
            val (rest, lang) = GameNaming.classifyVariantTokens(inner)
            if (language == null) language = lang
            for (token in rest) classify(token)
        }
        for (token in derived.tags) classify(token)
        for (token in derived.mods) classify(token)

        val title = tidy(GameNaming.displayName(derived.name)).ifEmpty { raw }
        val subtitle = SUBTITLE.find(title)?.groupValues?.get(2)?.trim()?.ifEmpty { null }
        val main = SUBTITLE.find(title)?.groupValues?.get(1)?.trim() ?: title
        val numbered = SERIES_NUMBER.find(main)
        return ParsedTitle(
            raw = raw,
            title = title,
            version = version,
            releaseTag = tags.joinToString(" ").ifEmpty { null },
            language = language,
            platform = platform,
            part = derived.segment,
            subtitle = subtitle,
            seriesTitle = numbered?.groupValues?.get(1)?.trim(),
            number = numbered?.groupValues?.get(2)?.toIntOrNull(),
            extras = extras,
            unidentified = derived.unidentified,
        )
    }

    /**
     * [parse] with the person's own title laid over it (docs/SPEC.md 7n):
     * a blank [override] changes nothing, anything else is the title and
     * the raw name stays what it was. The override is stored by the
     * library against the game's id (`GameLinksStore.gameName`), so it
     * survives every rescan; this is where it meets the parsed name.
     */
    fun withOverride(parsed: ParsedTitle, override: String?): ParsedTitle {
        val name = override?.trim().orEmpty()
        if (name.isEmpty()) return parsed
        val subtitle = SUBTITLE.find(name)?.groupValues?.get(2)?.trim()?.ifEmpty { null }
        val main = SUBTITLE.find(name)?.groupValues?.get(1)?.trim() ?: name
        val numbered = SERIES_NUMBER.find(main)
        return parsed.copy(
            title = name,
            subtitle = subtitle,
            seriesTitle = numbered?.groupValues?.get(1)?.trim(),
            number = numbered?.groupValues?.get(2)?.toIntOrNull(),
        )
    }

    /** Runs of spaces and of `-`, `_`, `,` made one, and separators hanging off either end cut. */
    private fun tidy(title: String): String =
        REPEATED.replace(SPACES.replace(title, " "), "$1").trim(' ', '-', '_', ',')
}
