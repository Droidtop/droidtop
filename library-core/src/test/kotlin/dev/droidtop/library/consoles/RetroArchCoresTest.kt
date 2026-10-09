package dev.droidtop.library.consoles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The pure parts of the touchless RetroArch core install (Droidtop/tracker#271). */
class RetroArchCoresTest {
    private fun elf(is64: Boolean, machine: Int): ByteArray = ByteArray(64).also {
        it[0] = 0x7F.toByte(); it[1] = 'E'.code.toByte(); it[2] = 'L'.code.toByte(); it[3] = 'F'.code.toByte()
        it[4] = (if (is64) 2 else 1).toByte()
        it[18] = (machine and 0xFF).toByte(); it[19] = (machine shr 8).toByte()
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): File =
        File.createTempFile("core", ".zip").also { file ->
            ZipOutputStream(file.outputStream()).use { out ->
                entries.forEach { (name, bytes) -> out.putNextEntry(ZipEntry(name)); out.write(bytes); out.closeEntry() }
            }
            file.deleteOnExit()
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
    fun `the download is the official buildbot over https`() {
        assertEquals(
            "https://buildbot.libretro.com/nightly/android/latest/arm64-v8a/mgba_libretro_android.so.zip",
            RetroArchCores.buildbotUrl("arm64-v8a", "mgba"),
        )
    }

    @Test
    fun `the ABI is the one Android runs RetroArch as`() {
        assertEquals("arm64-v8a", RetroArchCores.abiFor("/data/app/x/com.retroarch.aarch64-y/lib/arm64", listOf("x86_64")))
        assertEquals("armeabi-v7a", RetroArchCores.abiFor("/data/app/x/lib/arm", listOf("arm64-v8a")))
        assertEquals("arm64-v8a", RetroArchCores.abiFor(null, listOf("arm64-v8a", "armeabi-v7a")))
        assertNull(RetroArchCores.abiFor(null, listOf("mips")))
    }

    @Test
    fun `only an ELF for the right architecture passes`() {
        assertTrue(RetroArchCores.elfMatches(elf(true, 183), "arm64-v8a"))
        assertFalse(RetroArchCores.elfMatches(elf(true, 62), "arm64-v8a"))
        assertFalse(RetroArchCores.elfMatches(elf(false, 40), "arm64-v8a"))
        assertTrue(RetroArchCores.elfMatches(elf(false, 40), "armeabi-v7a"))
        assertFalse(RetroArchCores.elfMatches("PK".toByteArray() + ByteArray(30), "arm64-v8a"))
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
    fun `the archive must hold exactly the named core`() {
        val dest = File.createTempFile("core", ".so").also { it.deleteOnExit() }
        RetroArchCores.extractCore(zipOf("mgba_libretro_android.so" to elf(true, 183)), "mgba", "arm64-v8a", dest)
        assertTrue(dest.length() > 0)
        assertTrue(runCatching { RetroArchCores.extractCore(zipOf("other.so" to elf(true, 183)), "mgba", "arm64-v8a", dest) }.isFailure)
        assertTrue(runCatching { RetroArchCores.extractCore(zipOf("mgba_libretro_android.so" to elf(true, 62)), "mgba", "arm64-v8a", dest) }.isFailure)
        assertFalse(dest.exists())
    }

    @Test
    fun `placing never replaces a core and never leaves a half copy`() {
        val commands = RetroArchCores.placeCommands("/src/mgba.so", "/data/user/0/com.retroarch/cores/mgba_libretro_android.so", 10115, "u:object_r:app_data_file:s0:c115,c256,c512,c768")
        val staged = "/data/user/0/com.retroarch/cores/mgba_libretro_android.so.droidtop-new"
        assertEquals(listOf("cp", "/src/mgba.so", staged), commands.first())
        assertTrue(listOf("chown", "10115:10115", staged) in commands)
        assertTrue(listOf("mv", "-n", staged, "/data/user/0/com.retroarch/cores/mgba_libretro_android.so") in commands)
        assertEquals(listOf("rm", "-f", staged), commands.last())
    }
}
