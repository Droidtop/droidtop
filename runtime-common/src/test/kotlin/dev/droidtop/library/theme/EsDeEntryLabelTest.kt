package dev.droidtop.library.theme

import org.junit.Assert.assertEquals
import org.junit.Test

/** See [esDeEntryLabel] for the real source of every rule here. */
class EsDeEntryLabelTest {
    @Test
    fun `a plain entry takes the element's own letterCase`() {
        assertEquals(
            "SONIC",
            esDeEntryLabel("Sonic", letterCase = EsDeLetterCase.UPPERCASE),
        )
    }

    @Test
    fun `an auto-collection entry prefers letterCaseAutoCollections`() {
        assertEquals(
            "favorites",
            esDeEntryLabel(
                "Favorites",
                letterCase = EsDeLetterCase.UPPERCASE,
                collectionKind = EsDeCollectionKind.AUTO,
                letterCaseAutoCollections = EsDeLetterCase.LOWERCASE,
            ),
        )
    }

    @Test
    fun `UNDEFINED falls back to letterCase, which is why it is not NONE`() {
        assertEquals(
            "FAVORITES",
            esDeEntryLabel(
                "Favorites",
                letterCase = EsDeLetterCase.UPPERCASE,
                collectionKind = EsDeCollectionKind.AUTO,
                letterCaseAutoCollections = EsDeLetterCase.UNDEFINED,
            ),
        )
        // NONE is a real choice a theme can make, and it must NOT fall back.
        assertEquals(
            "Favorites",
            esDeEntryLabel(
                "Favorites",
                letterCase = EsDeLetterCase.UPPERCASE,
                collectionKind = EsDeCollectionKind.AUTO,
                letterCaseAutoCollections = EsDeLetterCase.NONE,
            ),
        )
    }

    @Test
    fun `a custom collection uses its own property`() {
        assertEquals(
            "Shmups",
            esDeEntryLabel(
                "shmups",
                letterCase = EsDeLetterCase.UPPERCASE,
                collectionKind = EsDeCollectionKind.CUSTOM,
                letterCaseCustomCollections = EsDeLetterCase.CAPITALIZE,
            ),
        )
    }

    @Test
    fun `the system name suffix is cased separately from the name`() {
        assertEquals(
            "sonic [MEGADRIVE]",
            esDeEntryLabel(
                "Sonic",
                letterCase = EsDeLetterCase.LOWERCASE,
                sourceSystemName = "megadrive",
            ),
        )
    }

    @Test
    fun `systemNameSuffix false drops the suffix entirely`() {
        assertEquals(
            "Sonic",
            esDeEntryLabel(
                "Sonic",
                letterCase = EsDeLetterCase.NONE,
                systemNameSuffix = false,
                sourceSystemName = "megadrive",
            ),
        )
    }

    @Test
    fun `an entry outside a collection never takes a suffix`() {
        assertEquals("Sonic", esDeEntryLabel("Sonic", letterCase = EsDeLetterCase.NONE))
    }

    @Test
    fun `lowercase is not one of the suffix branches, so the name is left as written`() {
        assertEquals(
            "Sonic [MegaDrive]",
            esDeEntryLabel(
                "Sonic",
                letterCase = EsDeLetterCase.NONE,
                letterCaseSystemNameSuffix = EsDeLetterCase.LOWERCASE,
                sourceSystemName = "MegaDrive",
            ),
        )
    }
}
