package dev.droidtop.library

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The one title fold (Droidtop/tracker#870): [TitleKeys.slug] is Lutris's `slugify`. Every expected value below is
 * what lutris/lutris `lutris/util/strings.py` `slugify` (master 4d9939e468de) returns for the same input.
 */
class TitleKeysTest {
    @Test
    fun `the slug is Lutris's, edge cases included`() {
        mapOf(
            "Being a DIK" to "being-a-dik",
            "BeingADIK" to "beingadik",
            "Assassin's Creed® II" to "assassins-creed-ii",
            "Pokémon: Red Version" to "pokemon-red-version",
            "foo_bar baz" to "foo_bar-baz",
            "  -- Hello --  World  " to "-hello-world",
            "Love & Sex: Second Base" to "love-sex-second-base",
            "Half-Life 2" to "half-life-2",
            "S.T.A.L.K.E.R." to "stalker",
            "café\tnoir" to "cafe-noir",
        ).forEach { (title, slug) -> assertEquals(title, slug, TitleKeys.slug(title)) }
    }

    @Test
    fun `a title with no Latin letters is the uuid5 Lutris gives it`() {
        assertEquals("eb63f7d1-d5d1-571f-8642-44e41fd745dc", TitleKeys.slug("ドキドキ文芸部"))
    }

    @Test
    fun `compact matches spaced and unspaced names in any script`() {
        assertEquals(TitleKeys.compact("SampleSE"), TitleKeys.compact("Sample SE"))
        assertEquals("pokemon", TitleKeys.compact("Pokémon"))
        assertEquals("ドキドキ文芸部", TitleKeys.compact("ドキドキ 文芸部!"))
    }

    @Test
    fun `the handheld's same game is the cross-device key's`() {
        // The bug: two names were one game here and two across devices. Now one game here is one key everywhere.
        val names = listOf("Being a DIK", "BeingADik", "being a dik!", "BeingADIK", "GoodbyeEternity", "Goodbye Eternity",
            "Anomalous_Coffee_Machine_2", "Anomalous Coffee Machine 2", "ThiefofHearts", "Thief of Hearts")
        for (a in names) for (b in names) {
            val keyed = dev.droidtop.library.computers.ComputerLibrary.titleKey(GameTitleParser.words(a)) ==
                dev.droidtop.library.computers.ComputerLibrary.titleKey(GameTitleParser.words(b))
            assertEquals("$a / $b", keyed, GameNaming.sameGame(a, b))
        }
        assertEquals(true, GameNaming.sameGame("Being a DIK", "being a dik!"))
        assertEquals(true, GameNaming.sameGame("BeingADik", "Being a DIK"))
    }
}
