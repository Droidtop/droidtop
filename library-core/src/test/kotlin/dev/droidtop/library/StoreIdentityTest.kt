package dev.droidtop.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which store rows are one library game (docs/SPEC.md 7m, "One game across stores"). */
class StoreIdentityTest {

    private fun row(
        id: String,
        title: String,
        installPath: String? = null,
        installed: Boolean = false,
        gameName: String? = null,
        externalIds: Map<String, String> = emptyMap(),
    ) =
        LibraryEntry(
            id = id,
            title = title,
            kind = LibraryEntryKind.WINE_PROFILE,
            gameName = gameName,
            pcInfo = PcInfo(
                storeId = id,
                installed = installed,
                installPath = installPath,
                externalIds = externalIds,
            ),
        )

    private fun same(a: String, b: String) = StoreIdentity.titleKey(a) == StoreIdentity.titleKey(b)

    @Test
    fun `case and punctuation do not make a different game`() {
        assertTrue(same("Half-Life 2", "HALF LIFE 2"))
        assertTrue(same("Baldur's Gate 3", "Baldurs Gate 3"))
        assertTrue(same("Baldur’s Gate 3", "Baldur's Gate 3"))
        assertTrue(same("Warhammer 40,000: Dawn of War", "Warhammer 40000 - Dawn of War"))
        assertTrue(same("Tom & Jerry", "Tom and Jerry"))
        assertTrue(same("The Witcher 3: Wild Hunt", "Witcher 3 Wild Hunt"))
    }

    @Test
    fun `trademark symbols and diacritics are not part of the name`() {
        assertTrue(same("DOOM™", "Doom"))
        assertTrue(same("Tomb Raider®", "Tomb Raider"))
        assertTrue(same("Pokémon Quest", "Pokemon Quest"))
    }

    @Test
    fun `a trailing edition label is not part of the name`() {
        assertTrue(same("Fallout 3: Game of the Year Edition", "Fallout 3"))
        assertTrue(same("Fallout 3 GOTY", "Fallout 3"))
        assertTrue(same("Cyberpunk 2077 (Deluxe Edition)", "Cyberpunk 2077"))
        assertTrue(same("Metro Exodus - Gold Edition", "Metro Exodus"))
        assertTrue(same("Divinity: Original Sin 2 - Definitive Edition", "Divinity Original Sin 2"))
        assertTrue(same("Skyrim Special Edition", "Skyrim"))
        assertTrue(same("Control Ultimate Edition", "Control"))
        assertTrue(same("Prey Digital Deluxe Edition", "Prey"))
        assertTrue(same("Hitman Game of the Year", "Hitman"))
    }

    @Test
    fun `an edition label that is the whole title or sits mid-title is kept`() {
        assertNotEquals("", StoreIdentity.titleKey("Deluxe Edition"))
        assertFalse(same("Gold Edition", "Gold"))
        assertFalse(same("Deluxe Edition Soundtrack", "Soundtrack"))
    }

    @Test
    fun `a different game with a similar name never merges`() {
        assertFalse(same("Doom", "Doom 3"))
        assertFalse(same("Doom", "Doom (2016)"))
        assertFalse(same("Doom 3", "Doom 3: Resurrection of Evil"))
        assertFalse(same("Fallout", "Fallout 3"))
        assertFalse(same("Fallout 3", "Fallout 4"))
        assertFalse(same("Half-Life", "Half-Life 2"))
        assertFalse(same("Portal", "Portal 2"))
        assertFalse(same("Mega Man X", "Mega Man 10"))
        assertFalse(same("Civilization V", "Civilization VI"))
        assertFalse(same("Tomb Raider I", "Tomb Raider II"))
        assertFalse(same("Cyberpunk 2077", "Cyberpunk 2077: Phantom Liberty"))
    }

    @Test
    fun `the same game on five stores is one game with every store kept`() {
        val rows = listOf(
            row("itch:9", "Cave Story+"),
            row("epic:cat", "CAVE STORY+"),
            row("steam:200900", "Cave Story™+ Deluxe Edition"),
            row("gog:1", "Cave Story+"),
            row("amazon:b0", "Cave Story+"),
        )

        val merged = StoreIdentity.group(rows).single()

        assertEquals(listOf("steam:200900", "gog:1", "epic:cat", "amazon:b0", "itch:9"), merged.entries.map { it.id })
        assertEquals("Cave Story+", merged.name)
    }

    @Test
    fun `a similarly named game on another store stays its own game`() {
        val merged = StoreIdentity.group(
            listOf(row("steam:1", "Doom"), row("gog:2", "Doom 3"), row("epic:3", "DOOM Deluxe Edition"), row("itch:4", "Doom II")),
        )

        assertEquals(3, merged.size)
        assertEquals(listOf("steam:1", "epic:3"), merged.first { it.name == "Doom" }.entries.map { it.id })
    }

    @Test
    fun `two stores that install into one folder are one game whatever they call it`() {
        val merged = StoreIdentity.group(
            listOf(
                row("steam:1", "Some Title", installPath = "/games/Some Title/", installed = true),
                row("gog:2", "Some Title Remaster Cut", installPath = "/games/Some Title"),
                row("epic:3", "Elsewhere", installPath = "/games/Elsewhere"),
            ),
        )

        assertEquals(2, merged.size)
        assertEquals(listOf("steam:1", "gog:2"), merged.first { it.entries.size == 2 }.entries.map { it.id })
    }

    @Test
    fun `a name the person set wins, and rows given it merge though their titles differ`() {
        val merged = StoreIdentity.group(
            listOf(row("steam:1", "Thing"), row("gog:2", "Entirely Other Title", gameName = "Thing")),
        ).single()

        assertEquals("Thing", merged.name)
        assertEquals(2, merged.entries.size)
    }

    @Test
    fun `the grouping makes one library row with a copy per store`() {
        val groups = LibraryGrouping.group(
            listOf(
                row("steam:1", "Cave Story+ Deluxe Edition"),
                row("gog:2", "Cave Story+", installPath = "/games/cs", installed = true),
                row("gog:3", "Another Game"),
            ),
        )

        assertEquals(2, groups.size)
        val cave = groups.first { it.game.name == "Cave Story+" }
        assertEquals(setOf("steam:1", "gog:2"), cave.entriesByPath.keys)
        assertTrue(cave.hasChoices)
        // Play starts the installed copy, not the first store's.
        assertEquals("gog:2", cave.displayEntry.id)
        assertEquals("Cave Story+", cave.displayEntry.title)
    }

    @Test
    fun `a store id another store's row names joins the two whatever their titles`() {
        val merged = StoreIdentity.group(
            listOf(
                row("steam:200900", "Cave Story+"),
                row("gog:1207658927", "Doukutsu Monogatari", externalIds = mapOf("steam" to "200900")),
                row("gog:3", "Another Game"),
            ),
        )

        assertEquals(2, merged.size)
        assertEquals(listOf("steam:200900", "gog:1207658927"), merged.first { it.entries.size == 2 }.entries.map { it.id })
    }

    @Test
    fun `two rows naming the same third store's id are one game though neither names the other`() {
        val merged = StoreIdentity.group(
            listOf(
                row("gog:1", "Title One", externalIds = mapOf("steam" to "440")),
                row("epic:abc", "Title Two", externalIds = mapOf("STEAM" to " 440 ")),
            ),
        )

        assertEquals(1, merged.size)
    }

    @Test
    fun `different named store ids keep games apart that share nothing else`() {
        val merged = StoreIdentity.group(
            listOf(
                row("gog:1", "Alpha", externalIds = mapOf("steam" to "1")),
                row("epic:2", "Beta", externalIds = mapOf("steam" to "2")),
            ),
        )

        assertEquals(2, merged.size)
    }

    @Test
    fun `a blank store id joins nothing`() {
        val merged = StoreIdentity.group(
            listOf(
                row("gog:1", "Alpha", externalIds = mapOf("steam" to "")),
                row("epic:2", "Beta", externalIds = mapOf("steam" to " ")),
            ),
        )

        assertEquals(2, merged.size)
    }

    @Test
    fun `a library of many rows merges in one pass`() {
        val rows = (0 until 20_000).flatMap { n -> listOf(row("steam:$n", "Game $n"), row("gog:$n", "GAME $n Deluxe Edition")) }

        assertEquals(20_000, StoreIdentity.group(rows).size)
    }
}
