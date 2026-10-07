package dev.droidtop.stores

import dev.droidtop.stores.amazon.AmazonManifest
import dev.droidtop.stores.amazon.AmazonStore
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Amazon's verify line and its manifest reader (docs/SPEC.md 7g, "Stores"). */
class AmazonStoreTest {

    @Test
    fun `a verify says what matched and what is missing or changed`() {
        assertEquals("All 12 files match", AmazonStore.verifyLine(12, missing = 0, changed = 0))
        assertEquals("2 missing of 12 files. Install again to repair them", AmazonStore.verifyLine(12, missing = 2, changed = 0))
        assertEquals("1 missing, 3 changed of 12 files. Install again to repair them", AmazonStore.verifyLine(12, missing = 1, changed = 3))
    }

    /** One uncompressed manifest: a header saying "no compression", a body with one package of one file. */
    @Test
    fun `an uncompressed manifest reads back its files and their total size`() {
        val hash = ByteArray(32) { it.toByte() }
        // File: path = 1, mode = 2, size = 3, hash = 5 (AmazonManifest's own field list).
        val fileMsg = message {
            string(1, "bin\\Game.exe")
            varint(2, 420)
            varint(3, 1234)
            bytes(5, message { varint(1, 0); bytes(2, hash) })
        }
        val packageMsg = message {
            string(1, "main")
            bytes(2, fileMsg)
        }
        val body = message { bytes(1, packageMsg) }
        val header = message { bytes(1, message { varint(1, 0) }) }
        val content = ByteBuffer.allocate(4 + header.size + body.size).putInt(header.size).put(header).put(body).array()

        val manifest = AmazonManifest.parse(content)
        assertEquals(1, manifest.allFiles.size)
        assertEquals("bin/Game.exe", manifest.allFiles.single().unixPath)
        assertEquals(1234L, manifest.totalInstallSize)
        assertTrue(manifest.allFiles.single().hashBytes.contentEquals(hash))
    }

    private class Proto {
        val out = ByteArrayOutputStream()
        fun varint(field: Int, value: Long) {
            tag(field, 0)
            raw(value)
        }
        fun bytes(field: Int, value: ByteArray) {
            tag(field, 2)
            raw(value.size.toLong())
            out.write(value)
        }
        fun string(field: Int, value: String) = bytes(field, value.toByteArray())
        private fun tag(field: Int, wire: Int) = raw(((field shl 3) or wire).toLong())
        private fun raw(value: Long) {
            var v = value
            while (true) {
                if (v and 0x7FL.inv() == 0L) {
                    out.write(v.toInt())
                    return
                }
                out.write(((v and 0x7F) or 0x80).toInt())
                v = v ushr 7
            }
        }
    }

    private fun message(build: Proto.() -> Unit): ByteArray = Proto().apply(build).out.toByteArray()
}
