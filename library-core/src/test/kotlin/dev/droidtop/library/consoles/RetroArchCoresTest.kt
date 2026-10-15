package dev.droidtop.library.consoles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure parts of getting a RetroArch core in (Droidtop/tracker#271). */
class RetroArchCoresTest {
    private fun elf(is64: Boolean, machine: Int): ByteArray = ByteArray(64).also {
        it[0] = 0x7F.toByte(); it[1] = 'E'.code.toByte(); it[2] = 'L'.code.toByte(); it[3] = 'F'.code.toByte()
        it[4] = (if (is64) 2 else 1).toByte()
        it[18] = (machine and 0xFF).toByte(); it[19] = (machine shr 8).toByte()
    }

    @Test
    fun `the generated launch names a core droidtop can install`() {
        val need = RetroArchCores.needFor("com.retroarch.aarch64", DefaultPlayers.retroArchArguments("com.retroarch.aarch64", "mupen64plus_next_gles3"))
        assertEquals("mupen64plus_next_gles3", need?.core)
        assertEquals("/data/user/0/com.retroarch.aarch64/cores/mupen64plus_next_gles3_libretro_android.so", need?.corePath)
    }

    @Test
    fun `a launch intent's LIBRETRO extra names the same core`() {
        val need = RetroArchCores.needForPath("com.retroarch.aarch64", "/data/user/0/com.retroarch.aarch64/cores/gambatte_libretro_android.so")
        assertEquals("gambatte", need?.core)
        assertNull(RetroArchCores.needForPath("com.retroarch.aarch64", "/storage/emulated/0/cores/gambatte_libretro_android.so"))
        assertNull(RetroArchCores.needForPath("org.ppsspp.ppsspp", "/data/user/0/org.ppsspp.ppsspp/cores/gambatte_libretro_android.so"))
    }

    @Test
    fun `a players-database row under data-data maps to the same cores folder`() {
        val need = RetroArchCores.needFor(
            "com.retroarch",
            "-n com.retroarch/com.retroarch.browser.retroactivity.RetroActivityFuture -e ROM {file.path} " +
                "-e LIBRETRO /data/data/com.retroarch/cores/mednafen_psx_hw_libretro_android.so",
        )
        assertEquals("/data/user/0/com.retroarch/cores/mednafen_psx_hw_libretro_android.so", need?.corePath)
    }

    @Test
    fun `other apps, other folders and other names are not droidtop's to install`() {
        assertNull(RetroArchCores.needFor("org.ppsspp.ppsspp", "-n org.ppsspp.ppsspp/.PpssppActivity --es LIBRETRO /data/user/0/org.ppsspp.ppsspp/cores/x_libretro_android.so"))
        assertNull(RetroArchCores.needFor("com.retroarch", "-n com.retroarch/.X --es LIBRETRO /sdcard/cores/mgba_libretro_android.so"))
        assertNull(RetroArchCores.needFor("com.retroarch", "-n com.retroarch/.X --es LIBRETRO /data/user/0/com.retroarch/cores/mgba_android.so"))
        assertNull(RetroArchCores.needFor("com.retroarch", "-n com.retroarch/.X --es ROM {file.path}"))
    }

    @Test
    fun `a core whose section table ends past the end of the file is incomplete`() {
        // ELF64: e_shoff 0x1000, e_shentsize 64, e_shnum 10, so the table ends at 0x1000 + 640.
        val header = elf(true, 183).also {
            it[0x29] = 0x10
            it[0x3A] = 64
            it[0x3C] = 10
        }
        assertTrue(RetroArchCores.elfWhole(header, 0x1000L + 640))
        assertFalse(RetroArchCores.elfWhole(header, 0x1000L))
        assertFalse(RetroArchCores.elfWhole("PK".toByteArray() + ByteArray(62), 1_000_000))
        assertTrue(RetroArchCores.missingMessage(RetroArchCores.Need("com.retroarch.aarch64", "gambatte", "/x"), RetroArchCores.State.PARTIAL).contains("incomplete"))
    }


    @Test
    fun `the warning names the core, the RetroArch package and the file it checked`() {
        val need = RetroArchCores.Need("com.retroarch.aarch64", "mupen64plus_next_gles3", "/data/user/0/com.retroarch.aarch64/cores/mupen64plus_next_gles3_libretro_android.so")
        val line = RetroArchCores.missingMessage(need, RetroArchCores.State.MISSING)
        assertTrue(line, line.contains("mupen64plus_next_gles3 in com.retroarch.aarch64, /data/user/0/com.retroarch.aarch64/cores/mupen64plus_next_gles3_libretro_android.so"))
        assertTrue(RetroArchCores.troubleHint(need).contains("com.retroarch.aarch64"))
    }

    @Test
    fun `only a legacy-storage RetroArch with storage access can sideload a core droidtop leaves in Download`() {
        // The buildbot builds target SDK 28; the Play Store builds target 35 (pkg/android/phoenix/build.gradle, 1.22.0).
        assertTrue(RetroArchCores.canSideload(targetSdk = 28, storageGranted = true))
        assertFalse(RetroArchCores.canSideload(targetSdk = 28, storageGranted = false))
        assertFalse(RetroArchCores.canSideload(targetSdk = 35, storageGranted = true))
        val need = RetroArchCores.Need("com.retroarch.aarch64", "mgba", "/data/user/0/com.retroarch.aarch64/cores/mgba_libretro_android.so")
        // RetroArch's sideload keeps the file's own name in its cores folder, so the copy carries the name a launch loads.
        assertEquals(
            "/storage/emulated/0/Download/droidtop-cores/mgba_libretro_android.so",
            RetroArchCores.sharedCorePath("/storage/emulated/0/Download", need),
        )
    }

    @Test
    fun `the manager's downloaded core is read only from its own data folder`() {
        val data = java.nio.file.Files.createTempDirectory("plugin-data").toFile()
        val core = java.io.File(data, "cores/arm64-v8a/mgba_libretro_android.so").apply { parentFile.mkdirs(); writeBytes(ByteArray(8)) }
        val outside = java.io.File(data.parentFile, "outside-${data.name}.so").apply { writeBytes(ByteArray(8)); deleteOnExit() }
        assertEquals(core.canonicalFile, RetroArchCores.managerFile(data, "cores/arm64-v8a/mgba_libretro_android.so"))
        assertNull(RetroArchCores.managerFile(data, "../${outside.name}"))
        assertNull(RetroArchCores.managerFile(data, outside.absolutePath))
        assertNull(RetroArchCores.managerFile(data, "cores/arm64-v8a/missing_libretro_android.so"))
        // One file and its folders, one level at a time.
        core.delete(); core.parentFile.delete(); core.parentFile.parentFile.delete(); data.delete()
    }
}
