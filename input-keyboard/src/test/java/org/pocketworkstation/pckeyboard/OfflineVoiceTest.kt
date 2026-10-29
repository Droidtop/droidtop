package org.pocketworkstation.pckeyboard

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Offline voice typing's download (Droidtop/tracker#343): which files of the engine's AAR and the model's archive are
 * kept, that nothing is kept unless the whole download matches its pinned SHA-256, and how heard words are typed.
 */
class OfflineVoiceTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun zip(vararg files: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            files.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
    }.toByteArray()

    private fun tarBz2(vararg files: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { out ->
        TarArchiveOutputStream(BZip2CompressorOutputStream(out)).use { tar ->
            files.forEach { (name, text) ->
                val bytes = text.toByteArray()
                tar.putArchiveEntry(TarArchiveEntry(name).apply { size = bytes.size.toLong() })
                tar.write(bytes)
                tar.closeArchiveEntry()
            }
        }
    }.toByteArray()

    private val aar = zip(
        "AndroidManifest.xml" to "<manifest/>",
        "classes.jar" to "jar",
        "jni/arm64-v8a/libonnxruntime.so" to "ort-arm64",
        "jni/arm64-v8a/libsherpa-onnx-c-api.so" to "c-api",
        "jni/arm64-v8a/libsherpa-onnx-jni.so" to "jni-arm64",
        "jni/x86_64/libonnxruntime.so" to "ort-x86_64",
        "jni/x86_64/libsherpa-onnx-jni.so" to "jni-x86_64",
        "jni/armeabi-v7a/libonnxruntime.so" to "ort-32",
    )

    @Test
    fun `only this ABI's two libraries are kept from the engine's AAR`() {
        assertEquals("libonnxruntime.so", OfflineVoice.engineEntry("jni/arm64-v8a/libonnxruntime.so", "arm64-v8a"))
        assertEquals("libsherpa-onnx-jni.so", OfflineVoice.engineEntry("jni/x86_64/libsherpa-onnx-jni.so", "x86_64"))
        assertNull(OfflineVoice.engineEntry("jni/x86_64/libonnxruntime.so", "arm64-v8a"))
        assertNull(OfflineVoice.engineEntry("jni/arm64-v8a/libsherpa-onnx-c-api.so", "arm64-v8a"))
        assertNull(OfflineVoice.engineEntry("classes.jar", "arm64-v8a"))
    }

    @Test
    fun `only the model's int8 files and tokens are kept from its archive`() {
        val top = OfflineVoice.MODEL_NAME
        OfflineVoice.MODEL_FILES.forEach { assertEquals(it, OfflineVoice.modelEntry("$top/$it")) }
        assertNull(OfflineVoice.modelEntry("$top/encoder-epoch-99-avg-1.onnx"))
        assertNull(OfflineVoice.modelEntry("$top/test_wavs/0.wav"))
        assertNull(OfflineVoice.modelEntry("other/tokens.txt"))
        assertNull(OfflineVoice.modelEntry("tokens.txt"))
    }

    @Test
    fun `a matching engine download is unpacked and marked complete, and its staging folder is gone`() {
        val dest = File(tmp.root, "engine")
        val error = OfflineVoice.unpackChecked(ByteArrayInputStream(aar), sha256(aar), dest, 2, {}) { s, out ->
            OfflineVoice.unpackEngine(s, out, "arm64-v8a")
        }
        assertNull(error)
        assertTrue(OfflineVoice.complete(dest))
        assertEquals("ort-arm64", File(dest, "libonnxruntime.so").readText())
        assertEquals("jni-arm64", File(dest, "libsherpa-onnx-jni.so").readText())
        assertFalse(File(dest, "libsherpa-onnx-c-api.so").exists())
        assertFalse(File(tmp.root, "engine.part").exists())
    }

    @Test
    fun `a download that does not match its checksum keeps nothing and leaves the old one in place`() {
        val dest = File(tmp.root, "engine")
        assertNull(OfflineVoice.unpackChecked(ByteArrayInputStream(aar), sha256(aar), dest, 2, {}) { s, out -> OfflineVoice.unpackEngine(s, out, "x86_64") })
        val other = zip("jni/x86_64/libonnxruntime.so" to "tampered", "jni/x86_64/libsherpa-onnx-jni.so" to "tampered")
        val error = OfflineVoice.unpackChecked(ByteArrayInputStream(other), sha256(aar), dest, 2, {}) { s, out ->
            OfflineVoice.unpackEngine(s, out, "x86_64")
        }
        assertNotNull(error)
        assertEquals("ort-x86_64", File(dest, "libonnxruntime.so").readText())
        assertFalse(File(tmp.root, "engine.part").exists())
    }

    @Test
    fun `a download missing a file is not kept`() {
        val dest = File(tmp.root, "engine")
        val partial = zip("jni/arm64-v8a/libonnxruntime.so" to "ort-arm64")
        val error = OfflineVoice.unpackChecked(ByteArrayInputStream(partial), sha256(partial), dest, 2, {}) { s, out ->
            OfflineVoice.unpackEngine(s, out, "arm64-v8a")
        }
        assertNotNull(error)
        assertFalse(OfflineVoice.complete(dest))
    }

    @Test
    fun `the whole file is hashed, not only the part the unpacker read`() {
        val top = OfflineVoice.MODEL_NAME
        val model = tarBz2(
            "$top/tokens.txt" to "tokens",
            "$top/encoder-epoch-99-avg-1.int8.onnx" to "encoder",
            "$top/decoder-epoch-99-avg-1.int8.onnx" to "decoder",
            "$top/joiner-epoch-99-avg-1.int8.onnx" to "joiner",
            "$top/encoder-epoch-99-avg-1.onnx" to "fp32",
            "$top/test_wavs/0.wav" to "wav",
        )
        val withTail = model + ByteArray(4096) { 7 }
        val dest = File(tmp.root, top)
        val error = OfflineVoice.unpackChecked(ByteArrayInputStream(withTail), sha256(withTail), dest, 4, {}) { s, out ->
            OfflineVoice.unpackModel(s, out)
        }
        assertNull(error)
        assertEquals(setOf(".complete") + OfflineVoice.MODEL_FILES, dest.list()!!.toSet())
        assertEquals("joiner", File(dest, OfflineVoice.JOINER).readText())
    }

    @Test
    fun `heard words are typed in lower case, with I and the start of a sentence capitalised`() {
        assertEquals("Hello world", OfflineVoiceTyping.typed("HELLO WORLD", sentenceStart = true))
        assertEquals("and then I left", OfflineVoiceTyping.typed(" AND  THEN I LEFT ", sentenceStart = false))
        assertEquals("I'm here", OfflineVoiceTyping.typed("I'M HERE", sentenceStart = false))
        assertEquals("", OfflineVoiceTyping.typed("   ", sentenceStart = true))
    }

    @Test
    fun `a sentence starts at the start of the field or after its end, and words get a space only when they need one`() {
        assertTrue(OfflineVoiceTyping.sentenceStart(null))
        assertTrue(OfflineVoiceTyping.sentenceStart(""))
        assertTrue(OfflineVoiceTyping.sentenceStart("Done. "))
        assertTrue(OfflineVoiceTyping.sentenceStart("Really?"))
        assertFalse(OfflineVoiceTyping.sentenceStart("and then "))
        assertEquals("", OfflineVoiceTyping.leadingSpace(""))
        assertEquals("", OfflineVoiceTyping.leadingSpace("word "))
        assertEquals("", OfflineVoiceTyping.leadingSpace("line\n"))
        assertEquals(" ", OfflineVoiceTyping.leadingSpace("word"))
    }
}
