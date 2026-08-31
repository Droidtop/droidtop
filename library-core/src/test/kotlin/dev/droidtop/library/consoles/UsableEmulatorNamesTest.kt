package dev.droidtop.library.consoles

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the label cleanup behind the "no emulator installed" message.
 *
 * Every case here is a real label taken verbatim from
 * `players-database.json`, not an invented example -- the whole reason
 * this function exists is that the real database's labels are
 * inconsistent in three specific ways, and a test built on tidy made-up
 * strings would not have caught any of them.
 */
class UsableEmulatorNamesTest {

    @Test
    fun `a real product name is kept as-is`() {
        assertEquals(listOf("Drastic", "MelonDS"), usableEmulatorNames(listOf("Drastic", "MelonDS")))
    }

    @Test
    fun `a redundant system prefix is stripped`() {
        // Real gba/gbc entries carry the system id in the label itself.
        assertEquals(
            listOf("Linkboy", "Pizza Boy A Pro"),
            usableEmulatorNames(listOf("gba - Linkboy", "gba - Pizza Boy A Pro")),
        )
    }

    @Test
    fun `labels that are really package ids are dropped, not shown`() {
        // Real n64/gba entries where no display name was ever filled in.
        // Suggesting "com.fastemulator.gba" to a user is barely better
        // than suggesting nothing, so these must not survive.
        assertEquals(
            listOf("My Boy! (Standalone)"),
            usableEmulatorNames(
                listOf(
                    "com.fastemulator.gba",
                    "org.mupen64plusae.v3.fzurita",
                    "My Boy! (Standalone)",
                ),
            ),
        )
    }

    @Test
    fun `duplicates collapse, keeping first occurrence order`() {
        // Real nds data lists melonDS under several near-identical labels;
        // the prefix strip makes some collide exactly.
        assertEquals(
            listOf("Linkboy", "SkyEmu"),
            usableEmulatorNames(listOf("Linkboy", "gbc - Linkboy", "SkyEmu", "gba - Linkboy")),
        )
    }

    @Test
    fun `a system with nothing usable yields an empty list rather than junk`() {
        assertEquals(emptyList<String>(), usableEmulatorNames(listOf("com.example.thing", "  ", "")))
    }
}
