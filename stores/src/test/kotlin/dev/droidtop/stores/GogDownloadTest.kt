package dev.droidtop.stores

import dev.droidtop.stores.gog.GOGDownloadManager
import dev.droidtop.stores.gog.api.DepotManifest
import dev.droidtop.stores.gog.api.FileChunk
import dev.droidtop.stores.gog.api.GOGManifestParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPOutputStream
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * GOG's download path off the network (Droidtop/tracker#732): a Gen 2 depot manifest read into files and their chunks,
 * the chunk plan and its CDN paths, chunks unpacked into their place in a file and checked by MD5, and a resume that
 * keeps a file only when its MD5 is the manifest's. The manifest is written here in GOG's Gen 2 shape, not taken from a
 * store.
 */
class GogDownloadTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun md5(bytes: ByteArray) = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun zlib(bytes: ByteArray) = ByteArrayOutputStream().also { out -> DeflaterOutputStream(out).use { it.write(bytes) } }.toByteArray()

    private val depot = """{"version":2,"depot":{"smallFilesContainer":null,"items":[
      {"type":"DepotFile","path":"bin\\Game.exe","md5":"aaa","chunks":[
        {"compressedMd5":"c1c1c1","md5":"m1","size":100,"compressedSize":60},
        {"compressedMd5":"c2c2c2","md5":"m2","size":50,"compressedSize":40}]},
      {"type":"DepotFile","path":"data/levels.pak","md5":null,"flags":["support"],"productId":"222","chunks":[
        {"compressedMd5":"c1c1c1","md5":"m1","size":100,"compressedSize":60},
        {"compressedMd5":"c3c3c3","md5":"m3","size":10}]},
      {"type":"DepotDirectory","path":"saves\\"},
      {"type":"DepotLink","path":"game","target":"bin/Game.exe"}
    ]}}"""

    @Test
    fun `a Gen 2 depot manifest gives its files, chunks, folders and links`() {
        val manifest = GOGManifestParser().parseDepotManifest(depot)
        assertEquals(listOf("bin/Game.exe", "data/levels.pak"), manifest.files.map { it.path })
        assertEquals("aaa", manifest.files[0].md5)
        assertNull(manifest.files[1].md5)
        assertTrue(manifest.files[1].isSupportFile())
        assertEquals("222", manifest.files[1].productId)
        assertEquals(listOf(60L, 40L), manifest.files[0].chunks.map { it.compressedSize })
        assertNull("a chunk with no compressed size is stored as is", manifest.files[1].chunks[1].compressedSize)
        assertEquals(listOf("saves"), manifest.directories.map { it.path })
        assertEquals("bin/Game.exe", manifest.links.single().target)
        assertEquals(DepotManifest(emptyList(), emptyList(), emptyList()), DepotManifest.fromJson(JSONObject("{}")))
    }

    @Test
    fun `the chunk plan fetches each chunk once, in file order, from each CDN in turn`() {
        val parser = GOGManifestParser()
        val files = parser.parseDepotManifest(depot).files
        assertEquals(listOf("c1c1c1", "c2c2c2", "c3c3c3"), parser.extractChunkHashes(files))
        assertEquals(60L + 40 + 60 + 10, parser.calculateTotalSize(files))
        assertEquals(100L + 50 + 100 + 10, parser.calculateUncompressedSize(files))
        val urls = parser.buildChunkUrlCandidates(listOf("c1c1c1"), listOf("https://cdn-a/path?token=1", "https://cdn-b/path"))
        assertEquals(listOf("https://cdn-a/path/c1/c1/c1c1c1?token=1", "https://cdn-b/path/c1/c1/c1c1c1"), urls.getValue("c1c1c1"))
        assertEquals(emptyMap<String, List<String>>(), parser.buildChunkUrlCandidates(listOf("c1c1c1"), emptyList()))
    }

    @Test
    fun `a manifest arrives zlib-packed, gzipped or plain and reads the same`() {
        val parser = GOGManifestParser()
        val gz = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(depot.toByteArray()) } }.toByteArray()
        assertEquals(depot, parser.decompressManifest(zlib(depot.toByteArray())))
        assertEquals(depot, parser.decompressManifest(gz))
        assertEquals(depot, parser.decompressManifest(depot.toByteArray()))
    }

    @Test
    fun `chunks are unpacked into their place in the file and checked by MD5`() {
        val first = ByteArray(5000) { (it % 13).toByte() }
        val second = ByteArray(3000) { (it % 7).toByte() }
        val c1 = File(temp.root, "c1").apply { writeBytes(zlib(first)) }
        val c2 = File(temp.root, "c2").apply { writeBytes(second) }
        val out = File(temp.root, "Game.exe")
        val one = FileChunk("x1", md5(first), first.size.toLong(), c1.length())
        val two = FileChunk("x2", md5(second), second.size.toLong(), null)
        // Written out of order: each chunk lands at its own offset.
        assertTrue(GOGDownloadManager.decompressChunkToFile(c2, two, out, first.size.toLong()).isSuccess)
        assertTrue(GOGDownloadManager.decompressChunkToFile(c1, one, out, 0L).isSuccess)
        assertArrayEquals(first + second, out.readBytes())
    }

    @Test
    fun `a chunk with the wrong MD5 or size is refused`() {
        val data = ByteArray(2000) { 1 }
        val chunkFile = File(temp.root, "c").apply { writeBytes(zlib(data)) }
        val wrongMd5 = GOGDownloadManager.decompressChunkToFile(chunkFile, FileChunk("x", md5(byteArrayOf(9)), data.size.toLong(), chunkFile.length()), File(temp.root, "a"), 0L)
        assertTrue(wrongMd5.isFailure)
        assertTrue(wrongMd5.exceptionOrNull()!!.message!!.contains("MD5"))
        val wrongSize = GOGDownloadManager.decompressChunkToFile(chunkFile, FileChunk("x", md5(data), data.size + 1L, chunkFile.length()), File(temp.root, "b"), 0L)
        assertTrue(wrongSize.isFailure)
        assertTrue(wrongSize.exceptionOrNull()!!.message!!.contains("size"))
    }

    @Test
    fun `a resume keeps a file only when its size and MD5 are the manifest's`() {
        val bytes = "game data".toByteArray()
        val file = temp.newFile("Game.exe").apply { writeBytes(bytes) }
        assertTrue(GOGDownloadManager.fileExistsWithCorrectSize(file, bytes.size.toLong(), md5(bytes)))
        assertTrue("MD5 in capitals is the same MD5", GOGDownloadManager.fileExistsWithCorrectSize(file, bytes.size.toLong(), md5(bytes).uppercase()))
        assertFalse("another size", GOGDownloadManager.fileExistsWithCorrectSize(file, bytes.size + 1L, md5(bytes)))
        assertFalse("another MD5", GOGDownloadManager.fileExistsWithCorrectSize(file, bytes.size.toLong(), md5(byteArrayOf(1))))
        assertFalse("a manifest with no MD5 cannot vouch for a file of the right size", GOGDownloadManager.fileExistsWithCorrectSize(file, bytes.size.toLong(), null))
        assertFalse("missing", GOGDownloadManager.fileExistsWithCorrectSize(File(temp.root, "none"), 1L, "x"))
        assertEquals(md5(bytes), GOGDownloadManager.calculateMd5File(file))
    }
}
