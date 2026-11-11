package dev.droidtop.app.apps

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApkInstallerDexMetadataTest {
    private fun apk(entries: Map<String, ByteArray>): File {
        val file = File.createTempFile("test", ".apk").apply { deleteOnExit() }
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }

    private fun read(dm: ByteArray): Map<String, ByteArray> {
        val out = linkedMapOf<String, ByteArray>()
        ZipInputStream(dm.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                out[entry.name] = zip.readBytes()
            }
        }
        return out
    }

    @Test
    fun `the APK's baseline profile becomes primary prof and profm`() {
        val prof = byteArrayOf(0x70, 0x72, 0x6f, 0x00, 1, 2, 3)
        val profm = byteArrayOf(0x70, 0x72, 0x6d, 0x00, 4, 5)
        val dm = ApkInstaller.dexMetadata(
            apk(mapOf("classes.dex" to byteArrayOf(1), "assets/dexopt/baseline.prof" to prof, "assets/dexopt/baseline.profm" to profm)),
        )!!
        val entries = read(dm)
        assertEquals(listOf("primary.prof", "primary.profm"), entries.keys.toList())
        assertArrayEquals(prof, entries["primary.prof"])
        assertArrayEquals(profm, entries["primary.profm"])
    }

    @Test
    fun `an APK without a profile gets none, and the install goes on without it`() {
        assertNull(ApkInstaller.dexMetadata(apk(mapOf("classes.dex" to byteArrayOf(1)))))
    }

    @Test
    fun `a file that is not a zip gets none`() {
        val notZip = File.createTempFile("broken", ".apk").apply { deleteOnExit(); writeText("not a zip") }
        assertNull(ApkInstaller.dexMetadata(notZip))
    }
}
