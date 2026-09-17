package dev.droidtop.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Folders becoming games: the version and segment model (docs/SPEC.md 7m)
 * and the corpus it has to survive.
 *
 * The corpus is the real list of game folders under the user's own
 * `Adult/<engine>/` roots, taken off the rig on 2026-09-16 and committed
 * beside this test so what the test asserts is what the library actually
 * holds. Every line of it goes through the grouping here and the whole
 * result is printed, because the failure mode this work has to avoid --
 * two different games merged because their names look alike -- is only
 * visible by reading the grouping, not by counting it.
 */
class GameGroupingTest {

    private val corpus: List<String> = javaClass.classLoader
        .getResourceAsStream("adult-folder-names-2026-09-16.txt")!!
        .bufferedReader()
        .readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    private fun found(vararg paths: String) = paths.map { GameGrouping.Found(path = it) }

    // --- the two normative cases ----------------------------------------

    @Test
    fun `Fetish Locator's three weeks are one game with three segments`() {
        val games = GameGrouping.group(
            found(
                "/games/adult/renpy/Fetish Locator/Week 1",
                "/games/adult/renpy/Fetish Locator/Week 2",
                "/games/adult/renpy/Fetish Locator/Week 3",
            ),
        )

        assertEquals(listOf("Fetish Locator"), games.map { it.name })
        val game = games.single()
        assertEquals(listOf("Week 1", "Week 2", "Week 3"), game.segments.map { it.label })
        // The detail opens on the first part, and Play starts it.
        assertEquals("Week 1", game.defaultSegment?.label)
        assertEquals("/games/adult/renpy/Fetish Locator/Week 1", game.defaultVersion?.playable?.path)
    }

    @Test
    fun `two folders of one game are one game with two versions, newest first`() {
        val games = GameGrouping.group(
            found(
                "/games/adult/renpy/GoodbyeEternity-0.8.1-pc-animated-unc",
                "/games/adult/renpy/Goodbye Eternity",
            ),
        )

        val game = games.single()
        assertEquals("Goodbye Eternity", game.name)
        assertEquals(listOf("0.8.1", ""), game.versions.map { it.version })
        assertEquals(
            "/games/adult/renpy/GoodbyeEternity-0.8.1-pc-animated-unc",
            game.defaultVersion?.playable?.path,
        )
        assertTrue(game.segments.isEmpty())
    }

    // --- the rules around them ------------------------------------------

    @Test
    fun `a version list is ordered by what the numbers mean, not by how they read`() {
        assertTrue(GameVersion.compareVersions("0.10", "0.9") > 0)
        assertTrue(GameVersion.compareVersions("1.2", "1.1.9") > 0)
        assertTrue(GameVersion.compareVersions("0.8.3b", "0.8.3") > 0)
        assertTrue(GameVersion.compareVersions("1.0", "") > 0)
        assertEquals(0, GameVersion.compareVersions("1.2", "1.2"))
    }

    @Test
    fun `re-scanning the same folder updates that copy instead of adding a second`() {
        val first = GameGrouping.group(found("/games/x/Eternum-0.9.5-pc")).single()
        val again = GameGrouping.group(
            listOf(
                GameGrouping.Found("/games/x/Eternum-0.9.5-pc", source = "Folder", installed = false),
                GameGrouping.Found("/games/x/Eternum-0.9.5-pc", source = "Folder", installed = true),
            ),
        ).single()

        assertEquals(1, first.versions.single().copies.size)
        assertEquals(1, again.versions.single().copies.size)
        assertEquals("Folder", again.versions.single().copies.single().source)
        assertTrue(again.installed)
    }

    @Test
    fun `an update is only claimed once a source has actually looked`() {
        val plain = GameGrouping.group(found("/games/x/Eternum-0.9.5-pc")).single()
        assertFalse(plain.updateAvailable)

        val checked = GameGrouping.group(
            listOf(GameGrouping.Found("/games/x/Eternum-0.9.5-pc", latestKnown = "0.9.6")),
        ).single()
        assertTrue(checked.updateAvailable)

        val current = GameGrouping.group(
            listOf(GameGrouping.Found("/games/x/Eternum-0.9.5-pc", latestKnown = "0.9.5")),
        ).single()
        assertFalse(current.updateAvailable)
    }

    @Test
    fun `the order folders arrive in does not change the grouping`() {
        val paths = listOf(
            "/g/adult/renpy/Thief of Hearts/Part2",
            "/g/adult/renpy/Thief of Hearts/ThiefofHeartsPart3-0.0.9-pc",
            "/g/adult/renpy/Thief of Hearts/Part1",
        )
        val forwards = GameGrouping.group(paths.map { GameGrouping.Found(it) })
        val backwards = GameGrouping.group(paths.reversed().map { GameGrouping.Found(it) })

        assertEquals(forwards, backwards)
        assertEquals(listOf("Part1", "Part2", "Part 3"), forwards.single().segments.map { it.label })
    }

    // --- the corpus -------------------------------------------------------

    @Test
    fun `every folder in the real corpus groups into a game, and no two games are merged`() {
        val games = GameGrouping.group(corpus.map { GameGrouping.Found("/games/adult/renpy/$it") })

        // Printed in full: this is the result a person has to read before
        // trusting it, and a count cannot show a wrong merge.
        println("corpus: ${corpus.size} folders => ${games.size} games")
        for (game in games) {
            println(
                "  " + game.name +
                    "  versions=" + game.versions.joinToString("/") { it.version.ifEmpty { "-" } } +
                    "  segments=" + game.segments.joinToString("/") { it.label }.ifEmpty { "-" },
            )
        }

        // Nothing is lost: every folder is a copy of exactly one game.
        val paths = games.flatMap { game -> game.allVersions.flatMap { it.copies } }.map { it.path }
        assertEquals(corpus.size, paths.size)
        assertEquals(corpus.size, paths.distinct().size)

        // The corpus is a listing of NAMES, one per line, exactly as the
        // rig printed them, and one of those lines
        // (`Anomalous_Coffee_Machine_2-1.0.00_deluxe_linux.x86_64`) is a
        // loose 2 GB file rather than a folder. Grouping is a rule about
        // names and never touches a filesystem, so it groups that line
        // like any other; the SCAN is what never yields an entry for a
        // file, which is why Anomalous Coffee Machine 2 shows one version
        // on the device and two here (docs/SPEC.md 7m, "A version is a
        // FOLDER").
        //
        // The only merges in this corpus, all of them right: the same
        // game found twice under two spellings of one name.
        val merged = games.filter { it.allVersions.sumOf { version -> version.copies.size } > 1 }
        assertEquals(
            listOf("Anomalous_Coffee_Machine_2", "Goodbye Eternity", "love_of_magic_book3"),
            merged.map { it.name }.sorted(),
        )
        // ...and each of those really is two VERSIONS of one game.
        assertEquals(listOf("1.2", "1.0.00"), games.single { it.name == "Anomalous_Coffee_Machine_2" }.versions.map { it.version })
        assertEquals(listOf("1.1.9f", "0.5.11c"), games.single { it.name == "love_of_magic_book3" }.versions.map { it.version })

        // The three Love of Magic books are three games, not one, although
        // their names are 0.95 alike -- the reason similarity suggests and
        // never merges. Same for Lust Academy and Lust Theory (0.61) and
        // for ARTEMIS and RTS (0.60).
        for (name in listOf("love_of_magic_book1", "love_of_magic_book2", "love_of_magic_book3", "Lust Academy", "Lust Theory", "ARTEMIS", "RTS")) {
            assertEquals(name, 1, games.count { it.name == name })
        }
    }

    @Test
    fun `similar names are offered as suggestions, never merged`() {
        val games = GameGrouping.group(corpus.map { GameGrouping.Found("/games/adult/renpy/$it") })
        val suggestions = GameGrouping.suggestions(games)

        assertTrue(
            suggestions.toString(),
            suggestions.any { it.name == "love_of_magic_book1" && it.other == "love_of_magic_book2" },
        )
        assertTrue(suggestions.all { it.score >= GameNaming.NAME_SIMILARITY_THRESHOLD })
        // Suggestions are strongest first, so a UI that shows three shows
        // the three worth asking about.
        assertEquals(suggestions.map { it.score }.sortedDescending(), suggestions.map { it.score })
    }

    // --- the library layer over it ----------------------------------------

    @Test
    fun `the library shows one entry per game and keeps the rest reachable`() {
        val entries = listOf("Week 1", "Week 2", "Week 3").map { week ->
            LibraryEntry(
                id = "/games/adult/renpy/Fetish Locator/$week",
                title = "Fetish Locator - $week",
                kind = LibraryEntryKind.RENPY,
            )
        } + LibraryEntry(id = "steam:440", title = "Team Fortress 2", kind = LibraryEntryKind.WINE_PROFILE)

        val groups = LibraryGrouping.group(entries)

        assertEquals(listOf("Fetish Locator", "Team Fortress 2"), groups.map { it.game.name })
        val locator = groups.first()
        assertEquals("Fetish Locator", locator.displayEntry.title)
        assertEquals("/games/adult/renpy/Fetish Locator/Week 1", locator.displayEntry.id)
        assertEquals(3, locator.folders)
        assertTrue(locator.hasChoices)
        // Every segment is still a real entry, so Play, artwork and the
        // runner model keep working on the one the user picks.
        for (segment in locator.game.segments) {
            val copy = segment.versions.single().copies.single()
            assertEquals(copy.path, locator.entryFor(copy)?.id)
        }
        // A store row has no folder name to read, so it is its own game
        // under the name the store gave it.
        assertFalse(groups.last().hasChoices)
    }

    @Test
    fun `an entry title still says which part it is, for the surfaces that list entries`() {
        assertEquals("Fetish Locator - Week 1", qualifiedFolderTitle(File("/games/renpy/Fetish Locator/Week 1")))
        assertEquals("Thief of Hearts - Part1", qualifiedFolderTitle(File("/games/renpy/Thief of Hearts/Part1")))
        assertEquals("ThiefofHeartsPart3-0.0.9-pc", qualifiedFolderTitle(File("/games/renpy/Thief of Hearts/ThiefofHeartsPart3-0.0.9-pc")))
        assertEquals("Part Time Job", qualifiedFolderTitle(File("/games/renpy/Part Time Job")))
    }
}
