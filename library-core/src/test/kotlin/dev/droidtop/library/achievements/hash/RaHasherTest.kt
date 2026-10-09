package dev.droidtop.library.achievements.hash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.Deflater

/** RetroAchievements' hash rules (rcheevos rc_hash_rom.c, rc_hash_disc.c) applied to small synthetic images. */
class RaHasherTest {
    @get:Rule val folder = TemporaryFolder()

    private fun md5(vararg parts: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5")
        parts.forEach { digest.update(it) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun pattern(size: Int, seed: Int = 1): ByteArray = ByteArray(size) { ((it * 31 + seed) and 0xFF).toByte() }

    private fun file(name: String, bytes: ByteArray): File = File(folder.root, name).also { it.writeBytes(bytes) }

    private fun hash(file: File, system: String): String? = RaHasher.hash(file, RaConsoles.forSystem(system)!!)

    @Test
    fun `a Mega Drive game is the md5 of the whole file`() {
        val rom = pattern(5000)
        assertEquals(md5(rom), hash(file("a.md", rom), "megadrive"))
    }

    @Test
    fun `NES skips the 16 byte iNES header and a headerless file is whole`() {
        val body = pattern(40000, 7)
        val header = byteArrayOf(0x4E, 0x45, 0x53, 0x1A, 2, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        assertEquals(md5(body), hash(file("a.nes", header + body), "nes"))
        assertEquals(md5(body), hash(file("b.nes", body), "nes"))
    }

    @Test
    fun `SNES skips a 512 byte copier header only when the size is 512 over a multiple of 8 KiB`() {
        val body = pattern(0x8000, 3)
        assertEquals(md5(body), hash(file("a.sfc", ByteArray(512) + body), "snes"))
        val odd = pattern(0x8000 + 100, 3)
        assertEquals(md5(odd), hash(file("b.sfc", odd), "snes"))
    }

    @Test
    fun `PC Engine skips 512 bytes when bit 9 of the size is set`() {
        val body = pattern(0x20000, 5)
        assertEquals(md5(body), hash(file("a.pce", ByteArray(512) + body), "pcengine"))
        assertEquals(md5(body), hash(file("b.pce", body), "pcengine"))
    }

    @Test
    fun `Lynx and Atari 7800 skip their headers`() {
        val body = pattern(3000, 9)
        val lynx = byteArrayOf('L'.code.toByte(), 'Y'.code.toByte(), 'N'.code.toByte(), 'X'.code.toByte(), 0) + ByteArray(59)
        assertEquals(md5(body), hash(file("a.lnx", lynx + body), "atarilynx"))
        val a78 = byteArrayOf(1) + "ATARI7800".toByteArray() + ByteArray(118)
        assertEquals(md5(body), hash(file("a.a78", a78 + body), "atari7800"))
    }

    @Test
    fun `an N64 game in any byte order hashes as the big endian file`() {
        val z64 = byteArrayOf(0x80.toByte(), 0x37, 0x12, 0x40) + pattern(1000, 2)
        val v64 = ByteArray(z64.size).also { for (i in z64.indices step 2) { it[i] = z64[i + 1]; it[i + 1] = z64[i] } }
        val n64 = ByteArray(z64.size).also { for (i in z64.indices step 4) { for (k in 0..3) it[i + k] = z64[i + 3 - k] } }
        val expected = md5(z64)
        assertEquals(expected, hash(file("a.z64", z64), "n64"))
        assertEquals(expected, hash(file("a.v64", v64), "n64"))
        assertEquals(expected, hash(file("a.n64", n64), "n64"))
        assertNull(hash(file("x.bin", pattern(100)), "n64"))
    }

    @Test
    fun `a single file zip is hashed as the file inside`() {
        val rom = pattern(4096, 4)
        val zip = File(folder.root, "a.zip")
        java.util.zip.ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(java.util.zip.ZipEntry("game.gb"))
            out.write(rom)
            out.closeEntry()
        }
        assertEquals(md5(rom), hash(zip, "gb"))
    }

    @Test
    fun `arcade is the hash of the file name without its extension`() {
        val f = file("galaga.zip", pattern(10))
        assertEquals(md5("galaga".toByteArray()), hash(f, "arcade"))
        val sub = File(folder.newFolder("nes"), "mario.zip").also { it.writeBytes(pattern(10)) }
        assertEquals(md5("nes_mario".toByteArray()), hash(sub, "fbneo"))
    }

    @Test
    fun `a console without an implemented rule is not hashed`() {
        assertNull(hash(file("a.gcm", pattern(100)), "gc"))
        assertNull(RaConsoles.forSystem("steam"))
    }

    // ----- discs ---------------------------------------------------------

    /** A cooked ISO 9660 image from backslash paths: root directory in sector 18, then one sector a folder, then the files. */
    private fun iso(entries: List<Pair<String, ByteArray>>): ByteArray {
        fun parentOf(path: String) = if ('\\' in path) path.substringBeforeLast('\\') else ""
        val dirs = linkedMapOf("" to 18)
        var next = 19
        for ((path, _) in entries) {
            var dir = ""
            for (part in path.split('\\').dropLast(1)) {
                dir = if (dir.isEmpty()) part else "$dir\\$part"
                if (dir !in dirs) dirs[dir] = next++
            }
        }
        val fileSector = HashMap<String, Int>()
        for ((path, bytes) in entries) {
            fileSector[path] = next
            next += maxOf(1, (bytes.size + 2047) / 2048)
        }
        val out = ByteArray(next * 2048)
        fun le32(at: Int, v: Int) { for (k in 0..3) out[at + k] = (v ushr (8 * k)).toByte() }
        val pvd = 16 * 2048
        out[pvd] = 1
        "CD001".toByteArray().copyInto(out, pvd + 1)
        out[pvd + 128] = 0; out[pvd + 129] = 8 // logical block size 2048
        val root = pvd + 156
        out[root] = 34
        le32(root + 2, 18); le32(root + 10, 2048)
        for ((dir, sector) in dirs) {
            var at = sector * 2048
            fun record(name: String, extent: Int, size: Int) {
                val id = name.toByteArray()
                val length = (33 + id.size + 1) and 1.inv()
                out[at] = length.toByte()
                le32(at + 2, extent); le32(at + 10, size)
                out[at + 32] = id.size.toByte()
                id.copyInto(out, at + 33)
                at += length
            }
            for ((child, childSector) in dirs) if (child.isNotEmpty() && parentOf(child) == dir) record(child.substringAfterLast('\\'), childSector, 2048)
            for ((path, bytes) in entries) if (parentOf(path) == dir) record(path.substringAfterLast('\\') + ";1", fileSector.getValue(path), bytes.size)
        }
        for ((path, bytes) in entries) bytes.copyInto(out, fileSector.getValue(path) * 2048)
        return out
    }

    private fun psxFiles(): Pair<List<Pair<String, ByteArray>>, ByteArray> {
        val exe = ByteArray(2048 + 3000).also {
            "PS-X EXE".toByteArray().copyInto(it)
            it[28] = (3000 and 0xFF).toByte(); it[29] = (3000 ushr 8).toByte()
            pattern(3000, 11).copyInto(it, 2048)
        }
        val cnf = "BOOT = cdrom:\\SLUS_000.01;1\r\nTCB = 4\r\n".toByteArray()
        return listOf("SYSTEM.CNF" to cnf, "SLUS_000.01" to exe) to exe
    }

    @Test
    fun `a PlayStation disc is the boot file name then the executable including its header`() {
        val (files, exe) = psxFiles()
        val image = iso(files)
        assertEquals(md5("SLUS_000.01".toByteArray(), exe), hash(file("a.iso", image), "psx"))
    }

    @Test
    fun `a PlayStation 2 disc names BOOT2 and hashes the file by its directory length`() {
        val elf = byteArrayOf(0x7f, 0x45, 0x4c, 0x46) + pattern(5000, 13)
        val cnf = "BOOT2 = cdrom0:\\SLES_123.45;1\r\nVER = 1.00\r\n".toByteArray()
        val image = iso(listOf("SYSTEM.CNF" to cnf, "SLES_123.45" to elf))
        assertEquals(md5("SLES_123.45".toByteArray(), elf), hash(file("a.iso", image), "ps2"))
    }

    @Test
    fun `a PSP disc is PARAM SFO then EBOOT BIN`() {
        val sfo = pattern(900, 21)
        val eboot = pattern(4100, 22)
        val image = iso(listOf("PSP_GAME\\PARAM.SFO" to sfo, "PSP_GAME\\SYSDIR\\EBOOT.BIN" to eboot))
        assertEquals(md5(sfo, eboot), hash(file("a.iso", image), "psp"))
        assertNull(hash(file("b.iso", iso(listOf("OTHER.BIN" to sfo))), "psp"))
    }

    @Test
    fun `a raw 2352 byte BIN behind a CUE hashes like the cooked ISO`() {
        val (files, exe) = psxFiles()
        val cooked = iso(files)
        val raw = ByteArrayOutputStream()
        val sync = byteArrayOf(0, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 0)
        for (s in 0 until cooked.size / 2048) {
            raw.write(sync)
            raw.write(byteArrayOf(0, 2, (s % 75).toByte(), 2)) // header, mode 2
            raw.write(ByteArray(8)) // subheader
            raw.write(cooked, s * 2048, 2048)
            raw.write(ByteArray(280))
        }
        file("game.bin", raw.toByteArray())
        val cue = file("game.cue", "FILE \"game.bin\" BINARY\r\n  TRACK 01 MODE2/2352\r\n    INDEX 01 00:00:00\r\n".toByteArray())
        assertEquals(md5("SLUS_000.01".toByteArray(), exe), hash(cue, "psx"))
    }

    @Test
    fun `a Saturn or Sega CD disc is the first 512 bytes when the header says so`() {
        val head = "SEGA SEGASATURN ".toByteArray() + pattern(496, 5)
        val image = head + ByteArray(2048 * 20 - 512)
        assertEquals(md5(head), hash(file("a.iso", image), "saturn"))
        assertNull(hash(file("b.iso", ByteArray(2048 * 20)), "saturn"))
    }

    // ----- CHD ---------------------------------------------------------

    private class Bits {
        val out = ByteArrayOutputStream()
        private var cur = 0
        private var n = 0
        fun write(value: Long, count: Int) {
            for (i in count - 1 downTo 0) {
                cur = (cur shl 1) or ((value ushr i) and 1L).toInt()
                if (++n == 8) { out.write(cur); cur = 0; n = 0 }
            }
        }
        fun finish(): ByteArray {
            while (n != 0) write(0, 1)
            return out.toByteArray()
        }
    }

    private fun be(value: Long, bytes: Int): ByteArray = ByteArray(bytes) { (value ushr (8 * (bytes - 1 - it))).toByte() }

    /** A version 5 CHD of 2048-byte cooked sectors in 2448-byte CD frames, eight frames a hunk; zlib-compressed or not. */
    private fun chd(cooked: ByteArray, compress: Boolean): ByteArray {
        val frames = (cooked.size / 2048 + 7) / 8 * 8
        val hunkBytes = 8 * 2448
        val hunks = frames / 8
        val hunkData = List(hunks) { h ->
            ByteArray(hunkBytes).also { hunk ->
                for (f in 0 until 8) {
                    val sector = h * 8 + f
                    if ((sector + 1) * 2048 <= cooked.size) System.arraycopy(cooked, sector * 2048, hunk, f * 2448, 2048)
                }
            }
        }
        val meta = "TRACK:1 TYPE:MODE1 SUBTYPE:NONE FRAMES:$frames PREGAP:0 PGTYPE:MODE1 PGSUB:NONE POSTGAP:0\u0000".toByteArray()
        val head = ByteArrayOutputStream()
        head.write("MComprHD".toByteArray())
        head.write(be(124, 4)); head.write(be(5, 4))
        head.write(be(if (compress) 0x7a6c6962L else 0L, 4)); head.write(be(0, 12))
        head.write(be(frames.toLong() * 2448, 8))
        val metaOffset = 124L
        val mapOffset = metaOffset + 16 + meta.size
        head.write(be(mapOffset, 8)); head.write(be(metaOffset, 8))
        head.write(be(hunkBytes.toLong(), 4)); head.write(be(2448, 4))
        head.write(ByteArray(60)) // SHA-1s
        check(head.size() == 124)
        val metaBlock = ByteArrayOutputStream().also {
            it.write("CHT2".toByteArray()); it.write(be(meta.size.toLong(), 4)); it.write(be(0, 8)); it.write(meta)
        }.toByteArray()
        val body = ByteArrayOutputStream()
        if (!compress) {
            val mapEnd = mapOffset + hunks * 4
            val firstHunkOffset = (mapEnd + hunkBytes - 1) / hunkBytes * hunkBytes
            val map = ByteArrayOutputStream()
            for (h in 0 until hunks) map.write(be(firstHunkOffset / hunkBytes + h, 4))
            body.write(map.toByteArray())
            body.write(ByteArray((firstHunkOffset - mapEnd).toInt()))
            hunkData.forEach { body.write(it) }
        } else {
            val packed = hunkData.map { raw ->
                val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
                deflater.setInput(raw); deflater.finish()
                val buf = ByteArray(raw.size + 1024)
                val n = deflater.deflate(buf)
                deflater.end()
                buf.copyOf(n)
            }
            // The map: a Huffman code with the one symbol 0 (compression type 0), one bit per hunk, then each hunk's length and crc.
            val bits = Bits()
            bits.write(1, 4); bits.write(1, 4) // node 0: code length 1
            bits.write(1, 4); bits.write(0, 4); bits.write(12, 4) // nodes 1..15: length 0 (15 = 12 + 3)
            repeat(hunks) { bits.write(0, 1) }
            packed.forEach { bits.write(it.size.toLong(), 24); bits.write(0, 16) }
            val coded = bits.finish()
            val firstOffset = mapOffset + 16 + coded.size
            body.write(be(coded.size.toLong(), 4)); body.write(be(firstOffset, 6)); body.write(be(0, 2))
            body.write(byteArrayOf(24, 0, 0, 0))
            body.write(coded)
            packed.forEach { body.write(it) }
        }
        return head.toByteArray() + metaBlock + body.toByteArray()
    }

    @Test
    fun `a PlayStation disc in an uncompressed CHD hashes like the ISO`() {
        val (files, exe) = psxFiles()
        val image = chd(iso(files), compress = false)
        assertEquals(md5("SLUS_000.01".toByteArray(), exe), hash(file("a.chd", image), "psx"))
    }

    @Test
    fun `a PlayStation disc in a zlib compressed CHD hashes like the ISO`() {
        val (files, exe) = psxFiles()
        val image = chd(iso(files), compress = true)
        assertEquals(md5("SLUS_000.01".toByteArray(), exe), hash(file("b.chd", image), "psx"))
    }

    @Test
    fun `a file that is not a version 5 CHD is not hashed`() {
        assertNull(hash(file("c.chd", pattern(4000)), "psx"))
    }
}
