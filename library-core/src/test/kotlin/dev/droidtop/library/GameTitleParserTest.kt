package dev.droidtop.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GameTitleParser] over a table of folder and file names (docs/SPEC.md 7n).
 * Every name here is a made-up placeholder: the shapes are real, the games
 * are not, because this repository is public.
 */
class GameTitleParserTest {

    private fun parse(name: String) = GameTitleParser.parseName(name)

    /** One row of the table: the name, and what it must say. */
    private data class Row(
        val name: String,
        val title: String,
        val version: String = "",
        val releaseTag: String? = null,
        val language: String? = null,
        val platform: String? = null,
    )

    private val table = listOf(
        // Plain titles are left alone.
        Row("Some Game", "Some Game"),
        Row("Some Game Deluxe Edition", "Some Game Deluxe Edition"),
        Row("Part Time Job", "Part Time Job"),
        // Slugs are drawn as words, runs of separators collapse.
        Row("Some_Game", "Some Game"),
        Row("Game___Name", "Game Name"),
        Row("Game  Name -- Edition", "Game Name - Edition"),
        // Versions, platform and release tags, in the shapes a download takes.
        Row("Some_Game_v1.2_win64", "Some Game", version = "1.2", platform = "windows"),
        Row("Sample-0.9.5-pc", "Sample", version = "0.9.5", platform = "pc"),
        Row("Sample-v1.51-pc", "Sample", version = "1.51", platform = "pc"),
        Row("Sample-1.0-linux", "Sample", version = "1.0", platform = "linux"),
        Row("Game_Name-win64-Final", "Game Name", releaseTag = "Final", platform = "windows"),
        Row("Game_Name-Win64", "Game Name", platform = "windows"),
        Row("Game Name x64", "Game Name"),
        // Bracketed tags anywhere in the name.
        Row("Night Road [1.0] (Final)", "Night Road", version = "1.0", releaseTag = "Final"),
        Row("Some Game [GOG] (Final)", "Some Game", releaseTag = "GOG Final"),
        Row("Some Game [English] [v0.5]", "Some Game", version = "0.5", language = "en"),
        Row("Some Game (Win64)", "Some Game", platform = "windows"),
        Row("[Group] Some Game", "Some Game"),
        // A bracketed copy number is a number; a year in parentheses says which game it is.
        Row("Some Game (2)", "Some Game 2"),
        Row("Some Game (2016)", "Some Game (2016)"),
        // Scene names: dots are spaces, everything after the version is the build's.
        Row("Game.Name.v1.2.3-GROUP", "Game Name", version = "1.2.3"),
        // Language tokens.
        Row("SampleVN-1.0-eng", "SampleVN", version = "1.0", language = "en"),
        Row("SampleVN-1.0-jpn", "SampleVN", version = "1.0", language = "ja"),
        Row("SampleVN-1.0-mtl", "SampleVN", version = "1.0", language = "mtl"),
        // Numbered titles keep their number; a bare number is never a version.
        Row("Far Cry 5", "Far Cry 5"),
        Row("Cyberpunk 2077", "Cyberpunk 2077"),
        Row("Half-Life-2", "Half Life 2"),
        // A word that is only a tag after a hard separator is cut; after a plain space it is a title word.
        Row("Project Alpha", "Project Alpha"),
        Row("Sample-Demo", "Sample", releaseTag = "Demo"),
        Row("2064", "2064"),
    )

    @Test
    fun `every row of the table parses to what it says`() {
        for (row in table) {
            val parsed = parse(row.name)
            assertEquals(row.name, row.name, parsed.raw)
            assertEquals("title of ${row.name}", row.title, parsed.title)
            assertEquals("version of ${row.name}", row.version, parsed.version)
            assertEquals("release tag of ${row.name}", row.releaseTag, parsed.releaseTag)
            assertEquals("language of ${row.name}", row.language, parsed.language)
            assertEquals("platform of ${row.name}", row.platform, parsed.platform)
        }
    }

    @Test
    fun `the raw name is kept as it was, whatever the title became`() {
        assertEquals("Some_Game_v1.2_win64", parse("Some_Game_v1.2_win64").raw)
        assertEquals("book3", parse("/games/Some Game/book3").raw)
        assertEquals("Some Game [GOG] (Final)", parse("/x/Some Game [GOG] (Final)").raw)
    }

    @Test
    fun `what the parser cannot place is an extra, never part of the title`() {
        assertEquals(listOf("GROUP"), parse("Game.Name.v1.2.3-GROUP").extras)
        assertEquals(listOf("scrappy"), parse("Sample-0.8.3-scrappy").extras)
        assertEquals("Sample", parse("Sample-0.8.3-scrappy").title)
    }

    // --- numbering and subtitles ----------------------------------------

    @Test
    fun `a sequel number and a subtitle are read, and both stay in the title`() {
        val far = parse("Far Cry 5")
        assertEquals("Far Cry", far.seriesTitle)
        assertEquals(5, far.number)
        assertEquals("Far Cry 5", far.title)

        val subtitled = parse("Name 2: The Subtitle")
        assertEquals("Name 2: The Subtitle", subtitled.title)
        assertEquals("The Subtitle", subtitled.subtitle)
        assertEquals("Name", subtitled.seriesTitle)
        assertEquals(2, subtitled.number)

        assertEquals("Edition", parse("Game Name - Edition").subtitle)
    }

    @Test
    fun `a year or a name with no number has no series number`() {
        assertNull(parse("Cyberpunk 2077").number)
        assertNull(parse("Some Game").number)
        assertNull(parse("Some Game").seriesTitle)
        assertNull(parse("Some Game").subtitle)
        assertNull(parse("Some Game (2016)").number)
    }

    @Test
    fun `two numbered sequels share a series and stay two titles`() {
        val first = parse("Series Name")
        val second = parse("Series Name 2")
        val third = parse("Series Name 3 - Another Subtitle")
        assertEquals("Series Name", second.seriesTitle)
        assertEquals("Series Name", third.seriesTitle)
        assertEquals(listOf(2, 3), listOf(second.number, third.number))
        assertEquals(3, setOf(first.title, second.title, third.title).size)
    }

    // --- part folders: the title is the parent's -------------------------

    @Test
    fun `a part folder is a part of the game above it, never a title`() {
        val labels = listOf(
            "book1" to 1, "book3" to 3, "Book 2" to 2, "episode4" to 4, "Episode 4" to 4, "Chapter 5" to 5,
            "Part 6" to 6, "Volume 7" to 7, "Vol 2" to 2, "Season 3" to 3, "Act II" to 2, "Part IV" to 4,
            "Book Three" to 3, "Episode Two" to 2, "Week 1" to 1,
        )
        for ((leaf, order) in labels) {
            val parsed = parse("/games/Some Game/$leaf")
            assertEquals(leaf, "Some Game", parsed.title)
            assertEquals(leaf, leaf, parsed.part?.label)
            assertEquals(leaf, order, parsed.part?.order)
            assertEquals(leaf, leaf, parsed.raw)
        }
    }

    @Test
    fun `a name that merely contains a part word keeps its title`() {
        assertNull(parse("/games/Part Time Job").part)
        assertNull(parse("/games/Seasons").part)
        assertNull(parse("/games/Day One Something").part)
        assertEquals("Day One Something", parse("/games/Day One Something").title)
        // A roman numeral is only read in capitals after a part word.
        assertNull(parse("/games/Some Game/Part i").part)
    }

    @Test
    fun `a title that ends in a part marker is that part of the game`() {
        val third = parse("/games/Series/SeriesPart3-0.0.9-pc")
        assertEquals("Series", third.title)
        assertEquals("Part 3", third.part?.label)
        assertEquals("0.0.9", third.version)
    }

    @Test
    fun `book at the end of a name is a numbered title, not a part`() {
        // A flat name_book1 beside name_book2 stays two titles (docs/SPEC.md 7m);
        // only a folder that is JUST the marker takes the parent's title.
        assertNull(parse("/games/sample_book1").part)
        assertEquals("sample book1", parse("/games/sample_book1").title)
    }

    // --- engine-standard folders -----------------------------------------

    @Test
    fun `an engine folder takes the title of the game it sits in`() {
        assertEquals("Some Game", parse("Some Game/game").title)
        assertEquals("Some Game", parse("Some Game/www").title)
        assertEquals("Some Game", parse("Some Game/data/www").title)
        assertEquals("Some Game", parse("Some Game/game/lib").title)
        assertFalse(parse("Some Game/game").unidentified)
    }

    @Test
    fun `an engine folder with no game above it is unidentified, and its raw name stays`() {
        for (name in listOf("game", "Game", "data", "www", "resources", "lib", "renpy", "Contents")) {
            val parsed = parse(name)
            assertEquals(name, GameNaming.UNIDENTIFIED, parsed.title)
            assertTrue(name, parsed.unidentified)
            assertEquals(name, parsed.raw)
        }
    }

    @Test
    fun `the games root and what is above it are never a title`() {
        assertEquals("games", GameTitleParser.parse("/games/game").title)
        val parsed = GameTitleParser.parse("/games/game", root = "/games")
        assertEquals(GameNaming.UNIDENTIFIED, parsed.title)
        assertTrue(parsed.unidentified)
        assertEquals("Some Game", GameTitleParser.parse("/games/Some Game/game", root = "/games").title)
        assertEquals("Some Game", GameTitleParser.parse("/games/Some Game/book2", root = "/games").title)
        // A part folder straight under a root has no parent game to name it, so it keeps its own name.
        assertEquals("book2", GameTitleParser.parse("/games/book2", root = "/games").title)
    }

    @Test
    fun `a title that only contains an engine word is a title`() {
        assertEquals("Game of Something", parse("Game of Something").title)
        assertEquals("Data Wing", parse("Data Wing").title)
        assertFalse(parse("Data Wing").unidentified)
        assertTrue(EngineFolderNames.matches("Game"))
        assertFalse(EngineFolderNames.matches("Game of Something"))
    }

    // --- the person's own title ------------------------------------------

    @Test
    fun `an override replaces the title and nothing else, and a blank one changes nothing`() {
        val parsed = parse("Some_Game_v1.2_win64")
        val renamed = GameTitleParser.withOverride(parsed, "  My Own: Title ")
        assertEquals("My Own: Title", renamed.title)
        assertEquals("Title", renamed.subtitle)
        assertEquals("Some_Game_v1.2_win64", renamed.raw)
        assertEquals("1.2", renamed.version)
        assertEquals(parsed, GameTitleParser.withOverride(parsed, "   "))
        assertEquals(parsed, GameTitleParser.withOverride(parsed, null))
    }

    // --- the helpers the table rests on ----------------------------------

    @Test
    fun `stripNoise takes out bracketed groups and scene dots and nothing else`() {
        assertEquals("Name", GameTitleParser.stripNoise("Name [GOG]"))
        assertEquals("Name 2", GameTitleParser.stripNoise("Name (2)"))
        assertEquals("Name v1.0", GameTitleParser.stripNoise("Name [1.0]"))
        assertEquals("Game Name v1.2.3-GROUP", GameTitleParser.stripNoise("Game.Name.v1.2.3-GROUP"))
        assertEquals("Plain Name", GameTitleParser.stripNoise("Plain Name"))
        // A name that is nothing but a tag keeps itself rather than becoming empty.
        assertEquals("[GOG]", GameTitleParser.stripNoise("[GOG]"))
    }

    @Test
    fun `roman numerals and number words become digits after a part word only`() {
        assertEquals("Act 2", PartMarkers.withArabic("Act II"))
        assertEquals("Part 4", PartMarkers.withArabic("Part IV"))
        assertEquals("Book 3", PartMarkers.withArabic("Book Three"))
        assertEquals("book3", PartMarkers.withArabic("book3"))
        assertEquals("Part i", PartMarkers.withArabic("Part i"))
        assertEquals("Day One Something", PartMarkers.withArabic("Day One Something"))
        assertEquals("Some Game II", PartMarkers.withArabic("Some Game II"))
    }
}
