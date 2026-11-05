package dev.droidtop.stores

import dev.droidtop.stores.epic.EpicDownloadManager
import dev.droidtop.stores.epic.manifest.BinaryManifest
import dev.droidtop.stores.epic.manifest.ChunkDataList
import dev.droidtop.stores.epic.manifest.ChunkInfo
import dev.droidtop.stores.epic.manifest.ChunkPart
import dev.droidtop.stores.epic.manifest.EpicManifest
import dev.droidtop.stores.epic.manifest.FileManifest
import dev.droidtop.stores.epic.manifest.FileManifestList
import dev.droidtop.stores.epic.manifest.ManifestMeta
import dev.droidtop.stores.util.StreamingAssembly
import dev.droidtop.stores.util.newDownloadInfo
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.DeflaterOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Epic's download path off the network (Droidtop/tracker#732): a chunk file decoded and checked as the download does,
 * an install's files skipped on resume only when whole, which failures fetch fresh links, and a binary manifest read
 * back into the chunk plan. The chunks and manifests are written here in Epic's format (Legendary's layout), not taken
 * from a store.
 */
class EpicDownloadTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun sha1(bytes: ByteArray) = MessageDigest.getInstance("SHA-1").digest(bytes)

    /** A chunk file: header version 3 (66 bytes) or 2 (62), stored compressed or not. */
    private fun chunk(data: ByteArray, version: Int = 3, compressed: Boolean = true, magic: Int = 0xB1FE3AA2.toInt()): ByteArray {
        val payload = if (compressed) ByteArrayOutputStream().also { out -> DeflaterOutputStream(out).use { it.write(data) } }.toByteArray() else data
        val header = if (version >= 3) 66 else 62
        val buffer = ByteBuffer.allocate(header + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(magic)
        buffer.putInt(version)
        buffer.putInt(header)
        buffer.putInt(payload.size)
        repeat(4) { buffer.putInt(it + 1) }
        buffer.putLong(0L)
        buffer.put(if (compressed) 1.toByte() else 0.toByte())
        buffer.put(sha1(data))
        buffer.put(3.toByte())
        if (version >= 3) buffer.putInt(data.size)
        buffer.put(payload)
        return buffer.array()
    }

    private val data = ByteArray(200_000) { (it % 97).toByte() }

    private fun decode(bytes: ByteArray, out: File, size: Long = data.size.toLong(), hash: ByteArray = sha1(data)) =
        EpicDownloadManager.decompressStreamingChunkToFile(bytes.inputStream(), out, size, hash, newDownloadInfo())

    @Test
    fun `a compressed chunk is unpacked whole and checked`() {
        val out = File(temp.root, "c.bin")
        decode(chunk(data), out)
        assertArrayEquals(data, out.readBytes())
    }

    @Test
    fun `an older header and a chunk stored as is read the same`() {
        val out = File(temp.root, "v2.bin")
        decode(chunk(data, version = 2, compressed = false), out)
        assertArrayEquals(data, out.readBytes())
        val out2 = File(temp.root, "v2c.bin")
        decode(chunk(data, version = 2), out2)
        assertArrayEquals(data, out2.readBytes())
    }

    @Test
    fun `a chunk whose hash does not match is refused and nothing of it is kept`() {
        val out = File(temp.root, "bad.bin")
        val wrong = data.copyOf().also { it[0] = 99 }
        try {
            decode(chunk(wrong), out)
            fail("a chunk with another SHA-1 than the manifest's must not be accepted")
        } catch (e: Exception) {
            assertTrue(e.message!!.contains("hash"))
        }
        assertFalse(out.exists())
    }

    @Test
    fun `a chunk of the wrong size is refused and nothing of it is kept`() {
        val out = File(temp.root, "short.bin")
        try {
            decode(chunk(data), out, size = data.size + 10L)
            fail("a chunk shorter than the manifest says must not be accepted")
        } catch (e: Exception) {
            assertTrue(e.message!!.contains("size"))
        }
        assertFalse(out.exists())
    }

    @Test
    fun `a file that is not a chunk is refused`() {
        try {
            decode(chunk(data, magic = 0x12345678), File(temp.root, "x.bin"))
            fail("a file without Epic's chunk magic must not be accepted")
        } catch (e: Exception) {
            assertTrue(e.message!!.contains("magic"))
        }
    }

    @Test
    fun `a resume skips a file only when its size and SHA-1 are the manifest's`() {
        val file = temp.newFile("game.pak").apply { writeBytes(data) }
        assertTrue(EpicDownloadManager.fileExistsWithCorrectHash(file, data.size.toLong(), sha1(data)))
        assertFalse("another size", EpicDownloadManager.fileExistsWithCorrectHash(file, data.size + 1L, sha1(data)))
        assertFalse("another hash", EpicDownloadManager.fileExistsWithCorrectHash(file, data.size.toLong(), sha1(byteArrayOf(1))))
        assertFalse("a manifest with no hash cannot vouch for it", EpicDownloadManager.fileExistsWithCorrectHash(file, data.size.toLong(), ByteArray(20)))
        assertFalse("missing", EpicDownloadManager.fileExistsWithCorrectHash(File(temp.root, "none"), 1L, sha1(data)))
    }

    @Test
    fun `an expired, refused or failing CDN fetches fresh links, a bad request does not`() {
        listOf(401, 403, 404, 410, 429, 500, 502, 503, 504).forEach { assertTrue("$it", EpicDownloadManager.refreshesLinks(it)) }
        listOf(400, 405, 416, 200, 304).forEach { assertFalse("$it", EpicDownloadManager.refreshesLinks(it)) }
    }

    @Test
    fun `a binary manifest reads back into the chunk plan, each chunk once in the order files use them`() {
        val a = ChunkInfo(guid = intArrayOf(1, 2, 3, 4), hash = 11u, shaHash = sha1(byteArrayOf(1)), groupNum = 7, windowSize = 1 shl 20, fileSize = 900)
        val b = ChunkInfo(guid = intArrayOf(5, 6, 7, 8), hash = 22u, shaHash = sha1(byteArrayOf(2)), groupNum = 42, windowSize = 1 shl 20, fileSize = 800)
        val first = FileManifest(filename = "Game/Binaries/Game.exe", hash = sha1(byteArrayOf(3)), fileSize = 300).apply {
            chunkParts.add(ChunkPart(guid = a.guid, offset = 0, size = 200, fileOffset = 0))
            chunkParts.add(ChunkPart(guid = b.guid, offset = 0, size = 100, fileOffset = 200))
        }
        val second = FileManifest(filename = "Game/Content/data.pak", hash = sha1(byteArrayOf(4)), fileSize = 50).apply {
            chunkParts.add(ChunkPart(guid = a.guid, offset = 200, size = 50, fileOffset = 0))
        }
        val written = BinaryManifest().apply {
            meta = ManifestMeta(appName = "Fixture", buildVersion = "1.0.0")
            chunkDataList = ChunkDataList().also { it.elements.addAll(listOf(a, b)) }
            fileManifestList = FileManifestList().also { it.elements.addAll(listOf(first, second)) }
        }
        val read = EpicManifest.readAll(written.serialize())
        assertEquals("Fixture", read.meta?.appName)
        assertEquals("1.0.0", read.meta?.buildVersion)
        val files = read.fileManifestList!!.elements
        assertEquals(listOf("Game/Binaries/Game.exe", "Game/Content/data.pak"), files.map { it.filename })
        assertEquals(listOf(300L, 50L), files.map { it.fileSize })
        assertArrayEquals(sha1(byteArrayOf(3)), files[0].hash)
        assertEquals(listOf(0 to 200, 0 to 100), files[0].chunkParts.map { it.offset to it.size })
        val plan = StreamingAssembly.buildOrderedChunkQueue(files.map { file -> file.chunkParts.map { it.guidStr } })
        assertEquals(listOf(a.guidStr, b.guidStr), plan)
        val chunkB = read.chunkDataList!!.getChunkByGuid(b.guidStr)!!
        assertEquals(42, chunkB.groupNum)
        assertEquals(800L, chunkB.fileSize)
        // Where the CDN keeps it: the group folder and the hash and GUID in its name.
        assertEquals(b.getPath(), chunkB.getPath())
        assertTrue(chunkB.getPath().startsWith("ChunksV4/42/"))
    }
}
