package dev.droidtop.library.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure half of mode gating and of the Handheld -> Gaming migration. */
class ModesTest {

    @Test
    fun `a disabled mode is not in the enabled set`() {
        assertEquals(
            setOf(Mode.LAUNCHER),
            ModeGate.enabledModes(launcherIsDroidtopHome = true, gamingEnabled = false, desktopEnabled = false),
        )
        assertEquals(
            setOf(Mode.GAMING),
            ModeGate.enabledModes(launcherIsDroidtopHome = false, gamingEnabled = true, desktopEnabled = false),
        )
        assertTrue(
            ModeGate.enabledModes(launcherIsDroidtopHome = false, gamingEnabled = false, desktopEnabled = false)
                .isEmpty(),
        )
    }

    @Test
    fun `an explicit mode wins, but only while it is enabled`() {
        val both = setOf(Mode.GAMING, Mode.DESKTOP)
        assertEquals(Mode.DESKTOP, ModeGate.resolveAppMode("desktop", "gaming", "gaming", both))
        assertEquals(
            Mode.GAMING,
            ModeGate.resolveAppMode("desktop", "gaming", "gaming", setOf(Mode.GAMING)),
        )
    }

    /**
     * Locks in what dq-modefix-01/dq-uxreview's "Gaming never appears"
     * finding turned out NOT to be: [ModeGate.resolveAppMode] already
     * picks GAMING correctly here, on BlueStacks (Android 9) exactly as
     * on Android 14 -- the real defect was GamepadShell's own Quick Menu
     * Compose state surviving the mode round trip and re-rendering on top
     * of the (correctly resolved) Gaming shell (see GamepadShell.kt's
     * quickMenuOpen reset). This test exists so a future regression in
     * THIS function, rather than that one, is what breaks it.
     */
    @Test
    fun `switching to Gaming after Android was last resolves to Gaming, not Android`() {
        assertEquals(
            Mode.GAMING,
            ModeGate.resolveAppMode(explicitId = "gaming", defaultId = null, lastId = "standard", enabled = setOf(Mode.GAMING)),
        )
    }

    @Test
    fun `a disabled default or last mode never resolves`() {
        assertEquals(
            Mode.GAMING,
            ModeGate.resolveAppMode(null, "desktop", "desktop", setOf(Mode.GAMING)),
        )
    }

    @Test
    fun `no app-hosted mode enabled resolves to nothing`() {
        assertNull(ModeGate.resolveAppMode("gaming", "gaming", "gaming", setOf(Mode.LAUNCHER)))
        assertNull(ModeGate.resolveAppMode(null, null, null, emptySet()))
    }

    @Test
    fun `launcher is never an app-hosted mode`() {
        assertNull(ModeGate.resolveAppMode("standard", null, null, setOf(Mode.LAUNCHER)))
    }

    @Test
    fun `the switcher always offers Android, and Desktop or Gaming only while enabled`() {
        assertEquals(listOf(Mode.LAUNCHER), ModeGate.switcherModes(emptySet()))
        assertEquals(listOf(Mode.LAUNCHER), ModeGate.switcherModes(setOf(Mode.LAUNCHER)))
        assertEquals(
            listOf(Mode.LAUNCHER, Mode.DESKTOP),
            ModeGate.switcherModes(setOf(Mode.LAUNCHER, Mode.DESKTOP)),
        )
        assertEquals(
            listOf(Mode.LAUNCHER, Mode.GAMING),
            ModeGate.switcherModes(setOf(Mode.GAMING)),
        )
        // Desktop before Gaming regardless of set iteration order -- the
        // switcher's row order must not depend on Set's own ordering.
        assertEquals(
            listOf(Mode.LAUNCHER, Mode.DESKTOP, Mode.GAMING),
            ModeGate.switcherModes(setOf(Mode.GAMING, Mode.DESKTOP, Mode.LAUNCHER)),
        )
    }

    @Test
    fun `a disabled mode contributes no piece`() {
        val launcherOnly = ModeGate.piecesToStart(setOf(Mode.LAUNCHER))
        assertEquals(
            setOf(ModePiece.LAUNCHER_SYSTEM_COMPONENTS, ModePiece.LAUNCHER_SECOND_SCREEN),
            launcherOnly,
        )
        assertTrue(ModeGate.piecesToStart(emptySet()).isEmpty())
    }

    @Test
    fun `every piece of an enabled mode starts`() {
        val gaming = ModeGate.piecesToStart(setOf(Mode.GAMING))
        assertEquals(
            ModePiece.entries.filter { Mode.GAMING in it.owners }.toSet(),
            gaming,
        )
        assertTrue(ModePiece.GAMING_NOTIFICATION_LISTENER in gaming)
        assertTrue(ModePiece.GAMING_PLATFORMS_DATABASE in gaming)
        assertFalse(ModePiece.DESKTOP_SECOND_SCREEN in gaming)
    }

    @Test
    fun `a piece two modes own starts for either and stops only when both are off`() {
        assertTrue(ModePiece.WINDOWS_BACKBONE in ModeGate.piecesToStart(setOf(Mode.GAMING)))
        assertTrue(ModePiece.WINDOWS_BACKBONE in ModeGate.piecesToStart(setOf(Mode.DESKTOP)))
        assertFalse(ModePiece.WINDOWS_BACKBONE in ModeGate.piecesToStart(setOf(Mode.LAUNCHER)))
    }

    @Test
    fun `home goes to the chosen default, not to the mode used last`() {
        val all = setOf(Mode.LAUNCHER, Mode.GAMING, Mode.DESKTOP)
        // "Opens into Android", then Gaming opened once (rig, dq-coordinator-24).
        assertEquals(Mode.LAUNCHER, ModeGate.homeTarget("standard", "gaming", all))
        assertEquals(Mode.GAMING, ModeGate.homeTarget("gaming", "standard", all))
    }

    @Test
    fun `home follows the last mode only when no default was chosen`() {
        val all = setOf(Mode.LAUNCHER, Mode.GAMING, Mode.DESKTOP)
        assertEquals(Mode.GAMING, ModeGate.homeTarget(null, "gaming", all))
        assertEquals(Mode.LAUNCHER, ModeGate.homeTarget(null, null, all))
    }

    @Test
    fun `home never forwards into a mode that is off`() {
        val launcherOnly = setOf(Mode.LAUNCHER)
        assertEquals(Mode.LAUNCHER, ModeGate.homeTarget("gaming", "desktop", launcherOnly))
        assertEquals(Mode.DESKTOP, ModeGate.homeTarget("gaming", "desktop", setOf(Mode.DESKTOP)))
    }

    @Test
    fun `every piece is owned by at least one mode`() {
        assertTrue(ModePiece.entries.all { it.owners.isNotEmpty() })
        // Nothing mode-specific is left running when every mode is off.
        assertTrue(ModeGate.piecesToStart(emptySet()).isEmpty())
        // With all three on, everything listed runs.
        assertEquals(
            ModePiece.entries.toSet(),
            ModeGate.piecesToStart(Mode.entries.toSet()),
        )
    }

    @Test
    fun `the migration renames handheld keys and rewrites stored mode ids`() {
        val migration = ModeRenameMigration.migrate(
            mapOf(
                "droidtop_last_mode" to "handheld",
                "droidtop_default_mode" to "handheld",
                "droidtop_mode_enabled_handheld" to false,
                "pref_handheld_default_section" to "Games",
                "pref_handheld_theme" to "decaffe-es-de",
                "pref_global_enable_handheld" to true,
                "droidtop_games_root_paths" to setOf("/sdcard/roms"),
            ),
        )
        assertEquals("gaming", migration.writes["droidtop_last_mode"])
        assertEquals("gaming", migration.writes["droidtop_default_mode"])
        assertEquals(false, migration.writes["droidtop_mode_enabled_gaming"])
        assertEquals("Games", migration.writes["pref_gaming_default_section"])
        assertEquals("decaffe-es-de", migration.writes["pref_gaming_theme"])
        assertEquals(true, migration.writes["pref_global_enable_gaming"])
        assertTrue("droidtop_mode_enabled_handheld" in migration.removals)
        assertTrue("pref_handheld_theme" in migration.removals)
        // Untouched keys are neither rewritten nor dropped.
        assertFalse("droidtop_games_root_paths" in migration.writes)
        assertFalse("droidtop_games_root_paths" in migration.removals)
    }

    @Test
    fun `the migration reads the old keys once -- a second pass has nothing to do`() {
        val before = mapOf(
            "droidtop_last_mode" to "handheld",
            "pref_handheld_show_hints" to true,
        )
        val first = ModeRenameMigration.migrate(before)
        assertFalse(first.isEmpty)

        // What storage looks like afterwards: the renamed keys, the old
        // ones gone. Feeding that back must be a no-op, which is what
        // "reads the old key once" means in practice.
        val after = (before - first.removals) + first.writes
        assertEquals("gaming", after["droidtop_last_mode"])
        assertEquals(true, after["pref_gaming_show_hints"])
        assertFalse("pref_handheld_show_hints" in after)
        assertTrue(ModeRenameMigration.migrate(after).isEmpty)
    }
}
