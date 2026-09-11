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
