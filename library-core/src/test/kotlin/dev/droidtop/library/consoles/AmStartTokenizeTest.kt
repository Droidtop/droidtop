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
    fun `an integration's system folder keeps its spaces`() {
        // Integrations hit the same bug: their placeholders used to be
        // pasted into the template before the split, so a games folder
        // with a space was torn apart exactly like the ROM path was.
        val folder = "/storage/7EF7-E477/My Roms/psx"
        val tokens = AmStartCommandToIntentConverter.tokenize(
            "-a android.intent.action.VIEW -n com.example.dl/.MainActivity --es dest {system.folder}",
            null,
            null,
            mapOf("{system.folder}" to folder),
        )
        assertEquals(folder, tokens[tokens.indexOf("dest") + 1])
    }

    @Test
    fun `a multi-word search query stays one token`() {
        val tokens = AmStartCommandToIntentConverter.tokenize(
            "-n com.example.dl/.Search --es q {query}",
            null,
            null,
            mapOf("{query}" to "final fantasy vii"),
        )
        assertEquals("final fantasy vii", tokens[tokens.indexOf("q") + 1])
    }

    @Test
    fun `a double-quoted span keeps its spaces as one token`() {
        // The real shape of ES-DE's 78 MAME4droid commands, translated:
        // a multi-word cli_params string extra. The generator used to
        // SKIP every one of these because the tokenizer split them.
        val tokens = AmStartCommandToIntentConverter.tokenize(
            "-n com.seleuco.mame4d2024/.MAME4droid -a android.intent.action.VIEW " +
                "--es cli_params \"-rompath '{file.dir};{system.folder}' -cass1 '{file.path}'\"",
            "/storage/7EF7-E477/Roms/adam/Buck Rogers.ddp",
            null,
            mapOf(
                "{file.dir}" to "/storage/7EF7-E477/Roms/adam",
                "{system.folder}" to "/storage/7EF7-E477/Roms/adam",
            ),
        )
        assertEquals(
            "-rompath '/storage/7EF7-E477/Roms/adam;/storage/7EF7-E477/Roms/adam' " +
                "-cass1 '/storage/7EF7-E477/Roms/adam/Buck Rogers.ddp'",
            tokens[tokens.indexOf("cli_params") + 1],
        )
    }

    @Test
    fun `an escaped quote inside a span is a literal quote`() {
        // Verbatim from the dragon32 tape preset: MAME's autoboot
        // command contains \" pairs the emulator itself must receive.
        val tokens = AmStartCommandToIntentConverter.tokenize(
            """--es cli_params "-autoboot_command 'cloadm\"\"' -cass '{file.path}'"""",
            "/roms/dragon32/Game.cas",
            null,
        )
        assertEquals(
            """-autoboot_command 'cloadm""' -cass '/roms/dragon32/Game.cas'""",
            tokens[1],
        )
    }

    @Test
    fun `an empty quoted value is an empty token, not a missing one`() {
        val tokens = AmStartCommandToIntentConverter.tokenize("--es key \"\"", null, null)
        assertEquals(listOf("--es", "key", ""), tokens)
    }

    @Test
    fun `an unterminated quote is an error, not a silent guess`() {
        try {
            AmStartCommandToIntentConverter.tokenize("--es key \"unclosed", null, null)
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun `file inject substitutes the content of a sibling file`() {
        // ES-DE's %INJECT% mechanism: Vita3K launches by the title id
        // STORED IN <basename>.psvita -- the argument is not derivable
        // from any path, only from the bytes.
        val dir = java.nio.file.Files.createTempDirectory("inject").toFile()
        try {
            val game = java.io.File(dir, "Persona 4 Golden.psvita")
            game.writeText("PCSE00120\n")
            val tokens = AmStartCommandToIntentConverter.tokenize(
                "--esa AppStartParameters -r,{file.inject:{file.basename}.psvita}",
                game.absolutePath,
                null,
                mapOf("{file.basename}" to game.nameWithoutExtension),
            )
            // Content trimmed -- the trailing newline must not ride into
            // the extra -- and the surrounding literal kept.
            assertEquals("-r,PCSE00120", tokens[2])
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a missing inject file is an error by name, not a half-substituted launch`() {
        val dir = java.nio.file.Files.createTempDirectory("inject").toFile()
        try {
            val game = java.io.File(dir, "Game.psvita").apply { writeText("x") }
            AmStartCommandToIntentConverter.tokenize(
                "-e id {file.inject:absent.txt}",
                game.absolutePath,
                null,
            )
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            org.junit.Assert.assertTrue(expected.message!!.contains("absent.txt"))
        } finally {
            dir.deleteRecursively()
        }
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
