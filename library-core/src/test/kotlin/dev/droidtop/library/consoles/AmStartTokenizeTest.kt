package dev.droidtop.library.consoles

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression cover for the space-in-ROM-path launch failure.
 *
 * The real device case, from the all-systems launch sweep: launching
 * `Glover (USA).z64` through RetroArch's `--es ROM {file.path}` threw
 * `UnsupportedArgumentException: Unsupported am start argument:
 * (USA).z64`, because the path was substituted into the template before
 * the template was split on whitespace. Four systems (N64, NDS, GBA,
 * GBC) failed this way and it would break any collection whose filenames
 * contain spaces.
 *
 * The paths and templates below are the real ones from the device and
 * from `DefaultPlayers.retroArch`, not simplified stand-ins.
 */
class AmStartTokenizeTest {

    private val retroArchTemplate =
        "-n com.retroarch.aarch64/.browser.retroactivity.RetroActivityFuture " +
            "--es ROM {file.path} " +
            "--es LIBRETRO /storage/emulated/0/Android/data/com.retroarch.aarch64/files/cores/mupen64plus_next_android.so"

    @Test
    fun `a ROM path containing spaces stays one token`() {
        val rom = "/storage/7EF7-E477/Roms/n64/Glover (USA).z64"
        val tokens = AmStartCommandToIntentConverter.tokenize(retroArchTemplate, rom, null)

        // The value following "--es ROM" must be the whole path.
        val romIndex = tokens.indexOf("ROM")
        assertEquals(rom, tokens[romIndex + 1])
        // And nothing may be left over as a stray token -- "(USA).z64"
        // reaching the parser as its own token is exactly the crash.
        assertEquals(false, tokens.contains("(USA).z64"))
    }

    @Test
    fun `a path with several spaces still stays one token`() {
        val rom = "/storage/7EF7-E477/Roms/nds/Pokemon - Black Version (USA, Europe) (NDSi Enhanced).nds"
        val tokens = AmStartCommandToIntentConverter.tokenize(retroArchTemplate, rom, null)
        assertEquals(rom, tokens[tokens.indexOf("ROM") + 1])
    }

    @Test
    fun `a path without spaces is unchanged, so nothing regressed`() {
        val rom = "/storage/7EF7-E477/Roms/n64/Glover.z64"
        val tokens = AmStartCommandToIntentConverter.tokenize(retroArchTemplate, rom, null)
        assertEquals(
            listOf(
                "-n", "com.retroarch.aarch64/.browser.retroactivity.RetroActivityFuture",
                "--es", "ROM", rom,
                "--es", "LIBRETRO",
                "/storage/emulated/0/Android/data/com.retroarch.aarch64/files/cores/mupen64plus_next_android.so",
            ),
            tokens,
        )
    }

    @Test
    fun `the file uri placeholder expands the same way`() {
        val uri = "content://dev.droidtop.app.fileprovider/roms/n64/Glover%20(USA).z64"
        val tokens = AmStartCommandToIntentConverter.tokenize(
            "-a android.intent.action.VIEW -d {file.uri} -t application/octet-stream",
            null,
            uri,
        )
        assertEquals(uri, tokens[tokens.indexOf("-d") + 1])
    }

    @Test
    fun `a template with no placeholders is untouched`() {
        val tokens = AmStartCommandToIntentConverter.tokenize(
            "-n com.example/.Main --activity-clear-task",
            null,
            null,
        )
        assertEquals(listOf("-n", "com.example/.Main", "--activity-clear-task"), tokens)
    }
}
