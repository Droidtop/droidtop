package dev.droidtop.library.consoles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit cover for the `core` half of the `default_player_changed`
 * payload (docs/SPEC.md 12a): the event must name the core the CHOSEN
 * player will actually launch with, or a manager plugin ensures the
 * wrong core. The real case is psx: the system's configured core is
 * `mednafen_psx` (platforms-database.json), but the players database
 * ships six RetroArch entries that each name their own core in the
 * template -- choosing "Retroarch - beetle psx hw" launches
 * `mednafen_psx_hw`, and reporting the system core there would make
 * droidtop-plugin-retroarch download `mednafen_psx` instead. The
 * templates below are the real shipped ones, verbatim, not stand-ins.
 */
class LibretroCoreIdTest {
    // players-database.json's real psx entry, verbatim (pinned
    // droidtop-platforms c74eba36).
    private val beetlePsxHw = Player.AmStart(
        id = "psx-com-retroarch-retroarch-beetle-psx-hw",
        name = "Retroarch - beetle psx hw",
        argumentsTemplate = "-n com.retroarch/com.retroarch.browser.retroactivity.RetroActivityFuture " +
            "-e ROM {file.path} " +
            "-e LIBRETRO /data/data/com.retroarch/cores/mednafen_psx_hw_libretro_android.so " +
            "-e CONFIGFILE /storage/emulated/0/Android/data/com.retroarch/files/retroarch.cfg " +
            "-e QUITFOCUS --activity-clear-task --activity-clear-top",
        packageName = "com.retroarch",
        killPackageProcesses = true,
    )

    // DefaultPlayers.retroArch's real generated psx entry, same shape it
    // writes for every system with a configured core.
    private val defaultRetroArch = Player.AmStart(
        id = "retroarch-psx",
        name = "RetroArch",
        argumentsTemplate = "-n com.retroarch/com.retroarch.browser.retroactivity.RetroActivityFuture " +
            "--es ROM {file.path} " +
            "--es LIBRETRO /data/user/0/com.retroarch/cores/mednafen_psx_android.so",
        packageName = "com.retroarch",
    )

    @Test
    fun `a players-database entry names its own core, not the system's`() {
        // The exact rig-shaped case: psx's system core is mednafen_psx,
        // the chosen "beetle psx hw" entry launches mednafen_psx_hw.
        assertEquals("mednafen_psx_hw", libretroCoreId(beetlePsxHw, systemConfiguredCore = "mednafen_psx"))
    }

    @Test
    fun `an entry naming no core falls back to the system's configured core`() {
        assertEquals(
            "mednafen_psx",
            libretroCoreId(
                Player.AmStart(
                    id = "fpse",
                    name = "FPse",
                    argumentsTemplate = "-n com.emulator.fpse/.Main --es ROM {file.path}",
                    packageName = "com.emulator.fpse",
                ),
                systemConfiguredCore = "mednafen_psx",
            ),
        )
    }

    @Test
    fun `the generated default entry's template core wins over a stale fallback`() {
        // Template first even for the generated entry: the value in its
        // LIBRETRO extra is what will launch, whatever the fallback says.
        assertEquals("mednafen_psx", libretroCoreId(defaultRetroArch, systemConfiguredCore = "something_else"))
        assertEquals("mednafen_psx", libretroCoreId(defaultRetroArch, systemConfiguredCore = null))
    }

    @Test
    fun `the old wiki-shape --es template resolves the same way`() {
        // Verbatim from AmStartTokenizeTest (the real Daijishō-wiki shape
        // the players database generator once wrote): --es, and the old
        // public-storage cores dir -- still one core id.
        val oldWikiShape = Player.AmStart(
            id = "retroarch-n64",
            name = "RetroArch",
            argumentsTemplate = "-n com.retroarch.aarch64/.browser.retroactivity.RetroActivityFuture " +
                "--es ROM {file.path} " +
                "--es LIBRETRO /storage/emulated/0/Android/data/com.retroarch.aarch64/files/cores/mupen64plus_next_android.so",
            packageName = "com.retroarch.aarch64",
        )
        assertEquals("mupen64plus_next", libretroCoreId(oldWikiShape, systemConfiguredCore = null))
    }

    @Test
    fun `a non-AmStart player has no template, so the system core is the answer`() {
        assertEquals("mednafen_psx", libretroCoreId(Player.WinePrefixLauncher(), systemConfiguredCore = "mednafen_psx"))
        assertNull(libretroCoreId(Player.WinePrefixLauncher(), systemConfiguredCore = null))
    }

    @Test
    fun `a LIBRETRO extra that is not a core so falls back rather than guessing`() {
        val notACore = Player.AmStart(
            id = "custom",
            name = "Custom",
            argumentsTemplate = "-n com.retroarch/com.retroarch.browser.retroactivity.RetroActivityFuture --es LIBRETRO /no/so/here",
            packageName = "com.retroarch",
        )
        assertEquals("mednafen_psx", libretroCoreId(notACore, systemConfiguredCore = "mednafen_psx"))
    }

    @Test
    fun `a LIBRETRO that is another extra's value is not mistaken for the core extra`() {
        // "note LIBRETRO" has a token after it, so only the key/value
        // grammar -- LIBRETRO must follow -e/--es -- keeps it from being
        // read as the core.
        val decoy = Player.AmStart(
            id = "custom",
            name = "Custom",
            argumentsTemplate = "-n com.example/.Main --es note LIBRETRO --es other val",
            packageName = "com.example",
        )
        assertEquals("mednafen_psx", libretroCoreId(decoy, systemConfiguredCore = "mednafen_psx"))
    }

    @Test
    fun `a malformed template degrades to the fallback instead of breaking the write path`() {
        // The player-choice write path must survive any template a user or
        // a bad database download produced: tokenizer errors (unterminated
        // quote, {file.inject} with no game) fall back, never throw.
        val unterminatedQuote = Player.AmStart(
            id = "custom",
            name = "Custom",
            argumentsTemplate = "-n com.example/.Main --es LIBRETRO \"/unterminated",
            packageName = "com.example",
        )
        val injectWithoutGame = Player.AmStart(
            id = "custom",
            name = "Custom",
            argumentsTemplate = "--es LIBRETRO {file.inject:core.txt}",
            packageName = "com.example",
        )
        assertEquals("mednafen_psx", libretroCoreId(unterminatedQuote, systemConfiguredCore = "mednafen_psx"))
        assertEquals("mednafen_psx", libretroCoreId(injectWithoutGame, systemConfiguredCore = "mednafen_psx"))
    }

    @Test
    fun `either real core so suffix shape reduces to the same id`() {
        // buildbot / players-database write <core>_libretro_android.so;
        // DefaultPlayers writes <core>_android.so. The more specific
        // suffix must match first, or the id would keep a stray
        // _libretro tail.
        val both = Player.AmStart(
            id = "custom",
            name = "Custom",
            argumentsTemplate = "--es LIBRETRO /data/user/0/com.retroarch/cores/mednafen_psx_hw_libretro_android.so",
            packageName = "com.retroarch",
        )
        assertEquals("mednafen_psx_hw", libretroCoreId(both, systemConfiguredCore = null))
    }
}
