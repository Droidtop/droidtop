package dev.droidtop.runtime

import dev.droidtop.shared.containers.ContainerArch
import dev.droidtop.shared.containers.ContainerPlatform
import dev.droidtop.shared.containers.Translator
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinuxGamesTest {
    // The shape of FEX's RootFS_links.json (rootfs.fex-emu.gg, read 2026-11-22), cut to three entries.
    private val list = """
        {"v1": {
          "Ubuntu 24.04 (EroFS)": {"DistroMatch": "ubuntu", "DistroVersion": "24.04",
            "URL": "https://rootfs.fex-emu.gg/Ubuntu_24_04/2026-08-11/Ubuntu_24_04.ero", "Hash": "52d6c0dab39efaed", "Type": "erofs"},
          "Ubuntu 24.04 (SquashFS)": {"DistroMatch": "ubuntu", "DistroVersion": "24.04",
            "URL": "https://rootfs.fex-emu.gg/Ubuntu_24_04/2026-08-11/Ubuntu_24_04.sqsh", "Hash": "0a1b2c3d4e5f6071", "Type": "squashfs"},
          "Fedora 44 (SquashFS)": {"DistroMatch": "fedora", "DistroVersion": "44",
            "URL": "https://rootfs.fex-emu.gg/Fedora_44/2026-08-11/Fedora_44.sqsh", "Hash": "fda4aaeab0fa69ba", "Type": "squashfs"}
        }}
    """.trimIndent()

    @Test
    fun theUbuntuSquashfsImageIsPicked() {
        assertEquals(
            "https://rootfs.fex-emu.gg/Ubuntu_24_04/2026-08-11/Ubuntu_24_04.sqsh" to "0a1b2c3d4e5f6071",
            LinuxGames.rootfsFromList(list),
        )
    }

    @Test
    fun aListWithoutItHasNoImage() {
        assertNull(LinuxGames.rootfsFromList("""{"v1": {}}"""))
        assertNull(LinuxGames.rootfsFromList("""{"v1": {"x": {"DistroMatch": "ubuntu", "DistroVersion": "24.04", "URL": "http://plain", "Hash": "1", "Type": "squashfs"}}}"""))
    }

    @Test
    fun containersAndLaunchersAreRecordedPerGameOrShared() {
        val dir = Files.createTempDirectory("linux-games").toFile()
        try {
            val shared = LinuxGames.key(null, own = false)
            val own = LinuxGames.key("steam:2173800", own = true)
            assertEquals(shared, LinuxGames.key("steam:2173800", own = false))
            assertFalse(LinuxGames.translationReady(dir, shared))

            LinuxGames.recordContainer(dir, shared, "droidtop-sibling-1")
            LinuxGames.recordContainer(dir, own, "droidtop-sibling-2")
            LinuxGames.recordLauncher(dir, "droidtop-sibling-1", "FEX")
            assertEquals(setOf("droidtop-sibling-1", "droidtop-sibling-2"), LinuxGames.recordedIds(dir))
            assertEquals("FEX", LinuxGames.recordedLauncher(dir, "droidtop-sibling-1"))
            assertNull(LinuxGames.recordedLauncher(dir, "droidtop-sibling-2"))

            // Ready once the rootfs is unpacked too: its mark alone, on a partial tree, is not enough.
            assertFalse(LinuxGames.translationReady(dir, shared))
            File(LinuxGames.rootfsDir(dir), ".droidtop-ready").also { it.parentFile.mkdirs() }.writeText("")
            assertFalse(LinuxGames.translationReady(dir, shared))
            File(LinuxGames.rootfsDir(dir), LinuxGames.ROOTFS_LOADER).also { it.parentFile.mkdirs() }.writeText("")
            assertTrue(LinuxGames.translationReady(dir, shared))
            assertFalse(LinuxGames.translationReady(dir, own))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aContainerWithAnotherFexBuildIsSetUpAgain() {
        val dir = Files.createTempDirectory("linux-games").toFile()
        try {
            // A container set up with FEX's own packages: a launcher, no build recorded.
            File(dir, "linux-game-containers.properties").writeText("fex.droidtop-sibling-1=FEX\n")
            assertNull(LinuxGames.recordedLauncher(dir, "droidtop-sibling-1"))
            LinuxGames.recordLauncher(dir, "droidtop-sibling-1", "FEX")
            assertEquals("FEX", LinuxGames.recordedLauncher(dir, "droidtop-sibling-1"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun theInstallScriptChecksDroidtopsFexBuild() {
        val script = LinuxGames.installScript
        assertFalse(script.contains("@SHA"))
        assertFalse(script.contains("ppa.launchpadcontent.net"))
        assertTrue(script.contains("https://github.com/mytop-project/FEX/releases/download/${LinuxGames.FEX_BUILD}/"))
        for (level in listOf("armv8.0", "armv8.2", "armv8.4")) {
            assertTrue(level, Regex("$level\\) want=[0-9a-f]{64} ;;").containsMatchIn(script))
        }
        assertTrue(script.contains("sha256sum -c"))
    }

    @Test
    fun theUnpackScriptChecksTheHashWithoutLeadingZeros() {
        val script = LinuxGames.unpackScript("0A1B2C3D4E5F6071")
        assertTrue(script.contains("want=\"a1b2c3d4e5f6071\""))
        assertTrue(script.contains("unsquashfs"))
        assertTrue(script.contains(LinuxGames.rootfsInContainer))
        // Each run unpacks into its own folder and checks the loader before marking the rootfs ready.
        assertTrue(script.contains(".unpacking.$$"))
        assertTrue(script.contains(LinuxGames.ROOTFS_LOADER))
    }

    @Test
    fun theHashIsReadFromEitherXxhsumStyle() {
        val sh = File("/bin/sh")
        org.junit.Assume.assumeTrue(sh.canExecute())
        fun parse(line: String): String {
            val process = ProcessBuilder(sh.path, "-c", "sed -E '${LinuxGames.XXH3_PARSE}'").start()
            process.outputStream.use { it.write((line + "\n").toByteArray()) }
            return process.inputStream.bufferedReader().readText().trim().also { process.waitFor() }
        }
        // xxhsum 0.8.2's own line for FEX's Ubuntu 24.04 image (console, 2026-11-22), and the older GNU style.
        assertEquals("3517e0e5ea25a473", parse("XXH3 (/run/droidtop-app-storage/fex-rootfs/Ubuntu_24_04.sqsh) = 3517e0e5ea25a473"))
        assertEquals("3517e0e5ea25a473", parse("XXH3_3517e0e5ea25a473  Ubuntu_24_04.sqsh"))
        assertEquals("a1b2", parse("XXH3 (x) = 0000a1b2"))
    }

    @Test
    fun aContainersPlatformSurvivesTheRecord() {
        val dir = Files.createTempDirectory("linux-games-platform").toFile()
        try {
            val arm = ContainerArch.ARM64
            assertEquals(ContainerPlatform.native(arm), LinuxGames.platform(dir, "shared", arm))
            val chosen = ContainerPlatform(ContainerArch.X86_64, Translator.BOX64)
            LinuxGames.recordPlatform(dir, "game.a", chosen, arm)
            assertEquals(chosen, LinuxGames.platform(dir, "game.a", arm))
            assertEquals(ContainerPlatform.native(arm), LinuxGames.platform(dir, "shared", arm))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun box64IsOnlyOfferedWhereItCoversTheProgramsAndAStaleChoiceFallsBack() {
        val arm = ContainerArch.ARM64
        assertTrue(Translator.BOX64 in LinuxGames.translatorChoices(ContainerArch.X86_64, arm))
        assertFalse(Translator.BOX64 in LinuxGames.translatorChoices(ContainerArch.X86, arm))
        assertEquals(listOf(Translator.FEX), LinuxGames.translatorChoices(ContainerArch.X86, arm))
        assertTrue(LinuxGames.translatorChoices(ContainerArch.X86_64, ContainerArch.X86_64).isEmpty())
        assertTrue(LinuxGames.architectureChoices(ContainerArch.X86_64).isEmpty())
        val dir = Files.createTempDirectory("linux-games-platform").toFile()
        try {
            LinuxGames.recordPlatform(dir, "shared", ContainerPlatform(ContainerArch.X86, Translator.BOX64), arm)
            assertEquals(Translator.FEX, LinuxGames.platform(dir, "shared", arm).translator)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun theLauncherFollowsTheTranslator() {
        assertEquals("FEX", LinuxGames.launcherFor(Translator.FEX, "FEX"))
        assertNull(LinuxGames.launcherFor(Translator.FEX, null))
        assertEquals("box64", LinuxGames.launcherFor(Translator.BOX64, "FEX"))
    }
}
