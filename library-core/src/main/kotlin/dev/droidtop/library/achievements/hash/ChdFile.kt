package dev.droidtop.library.achievements.hash

import org.tukaani.xz.LZMAInputStream
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.Inflater

/** One CD track of a CHD, from its CHT2 / CHTR / CHGD metadata. [startFrame] counts the 4-frame padding of earlier tracks. */
internal data class ChdTrack(val number: Int, val type: String, val frames: Int, val startFrame: Int) {
    /** Bytes of the frame before the 2048 user bytes of a data sector; null for audio and types we do not read. */
    val userOffset: Int?
        get() = when (type) {
            "MODE1", "MODE2_FORM1" -> 0
            "MODE1_RAW" -> 16
            "MODE2_RAW" -> 24
            "MODE2", "MODE2_FORM_MIX" -> 8
            else -> null
        }
}

/**
 * A read-only reader for CHD version 5 files (MAME's compressed hunks of data), enough to read the sectors
 * RetroAchievements hashing looks at (docs/SPEC.md 7h, "RetroAchievements"). Written from libchdr's public source
 * (BSD-3-Clause): the header, the Huffman-coded hunk map, and the hunk codecs a CD or DVD image is written with
 * by default, zlib, LZMA and the CD wrappers of both (cdzl, cdlz). A hunk in another codec (FLAC, Huffman, zstd),
 * or any CHD before version 5, reads as unavailable and the caller says the file cannot be hashed.
 *
 * Nothing here is called on the main thread: the file is read at random as sectors are asked for, one hunk is
 * cached.
 */
internal class ChdFile private constructor(
    private val file: RandomAccessFile,
    val logicalBytes: Long,
    val hunkBytes: Int,
    private val codecs: IntArray,
    private val compressed: Boolean,
    private val kinds: ByteArray,
    private val lengths: IntArray,
    private val offsets: LongArray,
    val tracks: List<ChdTrack>,
) : Closeable {

    private var cachedIndex = -1
    private var cachedHunk: ByteArray? = null

    val hunkCount: Int get() = kinds.size

    /** The hunk [index], or null when it cannot be read or uses a codec this reader does not have. */
    fun readHunk(index: Int, depth: Int = 0): ByteArray? {
        if (index < 0 || index >= hunkCount || depth > 8) return null
        if (index == cachedIndex) return cachedHunk
        val hunk = decodeHunk(index, depth)
        cachedIndex = index
        cachedHunk = hunk
        return hunk
    }

    private fun decodeHunk(index: Int, depth: Int): ByteArray? {
        if (!compressed) {
            val offset = offsets[index]
            if (offset == 0L) return ByteArray(hunkBytes)
            return readAt(offset * hunkBytes, hunkBytes)
        }
        return when (val kind = kinds[index].toInt()) {
            in COMPRESSION_TYPE_0..COMPRESSION_TYPE_3 -> {
                val packed = readAt(offsets[index], lengths[index]) ?: return null
                decompress(codecs[kind], packed)
            }
            COMPRESSION_NONE -> readAt(offsets[index], hunkBytes)
            COMPRESSION_SELF -> readHunk(offsets[index].toInt(), depth + 1)?.copyOf()
            else -> null
        }
    }

    private fun readAt(position: Long, length: Int): ByteArray? {
        if (length < 0 || position < 0) return null
        val out = ByteArray(length)
        return try {
            file.seek(position)
            file.readFully(out)
            out
        } catch (e: java.io.IOException) {
            null
        }
    }

    private fun decompress(codec: Int, packed: ByteArray): ByteArray? = try {
        when (codec) {
            CODEC_ZLIB -> inflate(packed, 0, packed.size, hunkBytes)
            CODEC_LZMA -> lzma(packed, 0, packed.size, hunkBytes)
            CODEC_CD_ZLIB -> cdHunk(packed, lzma = false)
            CODEC_CD_LZMA -> cdHunk(packed, lzma = true)
            else -> null
        }
    } catch (e: java.io.IOException) {
        null
    } catch (e: java.util.zip.DataFormatException) {
        null
    }

    /**
     * cdzl and cdlz hunks: an ECC bitmap, the compressed length of the sector data (2 bytes), the sector data of
     * all frames (2352 bytes each) in zlib or LZMA, then the subcode in zlib. The sync header and ECC a frame
     * lost to compression are not rebuilt, the sectors are read by the track's type and never by the sync bytes;
     * the subcode is left zero.
     */
    private fun cdHunk(src: ByteArray, lzma: Boolean): ByteArray? {
        val frames = hunkBytes / CD_FRAME_SIZE
        val eccBytes = (frames + 7) / 8
        val lengthBytes = if (hunkBytes < 65536) 2 else 3
        val header = eccBytes + lengthBytes
        if (src.size < header) return null
        var baseLength = ((src[eccBytes].toInt() and 0xFF) shl 8) or (src[eccBytes + 1].toInt() and 0xFF)
        if (lengthBytes > 2) baseLength = (baseLength shl 8) or (src[eccBytes + 2].toInt() and 0xFF)
        if (src.size < header + baseLength) return null
        val sectors = frames * CD_SECTOR_BYTES
        val base = (if (lzma) lzma(src, header, baseLength, sectors) else inflate(src, header, baseLength, sectors)) ?: return null
        val out = ByteArray(hunkBytes)
        for (frame in 0 until frames) System.arraycopy(base, frame * CD_SECTOR_BYTES, out, frame * CD_FRAME_SIZE, CD_SECTOR_BYTES)
        return out
    }

    /** The 2048 user bytes of sector [lba] of [track], by the track's type; null when unreadable. */
    fun readCdSector(track: ChdTrack, lba: Int): ByteArray? {
        val userOffset = track.userOffset ?: return null
        if (lba < 0 || lba >= track.frames) return null
        val frame = track.startFrame + lba
        val framesPerHunk = hunkBytes / CD_FRAME_SIZE
        if (framesPerHunk == 0) return null
        val hunk = readHunk(frame / framesPerHunk) ?: return null
        val at = (frame % framesPerHunk) * CD_FRAME_SIZE + userOffset
        return if (at + 2048 <= hunk.size) hunk.copyOfRange(at, at + 2048) else null
    }

    /** Bytes of a DVD image (no CD tracks): 2048-byte sectors straight through the hunks. */
    fun readLogical(position: Long, length: Int): ByteArray? {
        if (position < 0 || position + length > logicalBytes) return null
        val out = ByteArray(length)
        var done = 0
        while (done < length) {
            val at = position + done
            val hunk = readHunk((at / hunkBytes).toInt()) ?: return null
            val inside = (at % hunkBytes).toInt()
            val take = minOf(length - done, hunkBytes - inside)
            System.arraycopy(hunk, inside, out, done, take)
            done += take
        }
        return out
    }

    override fun close() {
        runCatching { file.close() }
    }

    companion object {
        private const val CODEC_ZLIB = 0x7a6c6962 // 'zlib'
        private const val CODEC_LZMA = 0x6c7a6d61 // 'lzma'
        private const val CODEC_CD_ZLIB = 0x63647a6c // 'cdzl'
        private const val CODEC_CD_LZMA = 0x63646c7a // 'cdlz'

        private const val TAG_CHT2 = 0x43485432
        private const val TAG_CHTR = 0x43485452
        private const val TAG_CHGD = 0x43484744

        private const val COMPRESSION_TYPE_0 = 0
        private const val COMPRESSION_TYPE_3 = 3
        private const val COMPRESSION_NONE = 4
        private const val COMPRESSION_SELF = 5
        private const val COMPRESSION_PARENT = 6
        private const val COMPRESSION_RLE_SMALL = 7
        private const val COMPRESSION_RLE_LARGE = 8
        private const val COMPRESSION_SELF_0 = 9
        private const val COMPRESSION_SELF_1 = 10
        private const val COMPRESSION_PARENT_SELF = 11
        private const val COMPRESSION_PARENT_0 = 12
        private const val COMPRESSION_PARENT_1 = 13

        internal const val CD_SECTOR_BYTES = 2352
        internal const val CD_FRAME_SIZE = 2352 + 96
        private const val HEADER_SIZE = 124
        private const val MAX_METADATA = 4096

        /** Opens [source]; null for anything that is not a version 5 CHD this reader can map. */
        fun open(source: File): ChdFile? {
            val raf = try {
                RandomAccessFile(source, "r")
            } catch (e: java.io.IOException) {
                return null
            }
            return try {
                parse(raf) ?: run { raf.close(); null }
            } catch (e: Exception) {
                runCatching { raf.close() }
                null
            }
        }

        private fun parse(raf: RandomAccessFile): ChdFile? {
            if (raf.length() < HEADER_SIZE) return null
            val header = ByteArray(HEADER_SIZE)
            raf.seek(0)
            raf.readFully(header)
            if (String(header, 0, 8, Charsets.ISO_8859_1) != "MComprHD") return null
            if (u32(header, 8) != HEADER_SIZE.toLong() || u32(header, 12) != 5L) return null
            val codecs = IntArray(4) { u32(header, 16 + 4 * it).toInt() }
            val logical = u64(header, 32)
            val mapOffset = u64(header, 40)
            val metaOffset = u64(header, 48)
            val hunkBytes = u32(header, 56).toInt()
            if (hunkBytes <= 0 || logical <= 0L) return null
            val unitBytes = u32(header, 60).toInt()
            if (unitBytes <= 0) return null
            val hunkCountL = (logical + hunkBytes - 1) / hunkBytes
            if (hunkCountL <= 0 || hunkCountL > 8_000_000L) return null
            val hunkCount = hunkCountL.toInt()
            val compressed = codecs[0] != 0
            val kinds = ByteArray(hunkCount)
            val lengths = IntArray(hunkCount)
            val offsets = LongArray(hunkCount)
            if (compressed) {
                if (!readCompressedMap(raf, mapOffset, hunkCount, hunkBytes, unitBytes, kinds, lengths, offsets)) return null
            } else {
                val map = ByteArray(hunkCount * 4)
                raf.seek(mapOffset)
                raf.readFully(map)
                for (i in 0 until hunkCount) offsets[i] = u32(map, i * 4)
            }
            return ChdFile(raf, logical, hunkBytes, codecs, compressed, kinds, lengths, offsets, readTracks(raf, metaOffset))
        }

        private fun readCompressedMap(
            raf: RandomAccessFile,
            mapOffset: Long,
            hunkCount: Int,
            hunkBytes: Int,
            unitBytes: Int,
            kinds: ByteArray,
            lengths: IntArray,
            offsets: LongArray,
        ): Boolean {
            val head = ByteArray(16)
            raf.seek(mapOffset)
            raf.readFully(head)
            val mapBytes = u32(head, 0)
            val firstOffset = (u32(head, 4) shl 16) or ((head[8].toLong() and 0xFF) shl 8) or (head[9].toLong() and 0xFF)
            val lengthBits = head[12].toInt() and 0xFF
            val selfBits = head[13].toInt() and 0xFF
            val parentBits = head[14].toInt() and 0xFF
            if (lengthBits > 32 || selfBits > 32 || parentBits > 32) return false
            if (mapBytes <= 0 || mapOffset + 16 + mapBytes > raf.length()) return false
            val packed = ByteArray(mapBytes.toInt())
            raf.seek(mapOffset + 16)
            raf.readFully(packed)
            val bits = BitReader(packed)
            val huffman = Huffman(16, 8)
            if (!huffman.importRle(bits)) return false

            var repeat = 0
            var last = 0
            for (hunk in 0 until hunkCount) {
                if (repeat > 0) {
                    kinds[hunk] = last.toByte()
                    repeat--
                } else {
                    when (val value = huffman.decode(bits)) {
                        COMPRESSION_RLE_SMALL -> {
                            kinds[hunk] = last.toByte()
                            repeat = 2 + huffman.decode(bits)
                        }
                        COMPRESSION_RLE_LARGE -> {
                            kinds[hunk] = last.toByte()
                            repeat = 2 + 16 + (huffman.decode(bits) shl 4)
                            repeat += huffman.decode(bits)
                        }
                        else -> {
                            last = value
                            kinds[hunk] = value.toByte()
                        }
                    }
                }
            }

            var current = firstOffset
            var lastSelf = 0L
            var lastParent = 0L
            for (hunk in 0 until hunkCount) {
                var offset = current
                var length = 0
                when (kinds[hunk].toInt()) {
                    in COMPRESSION_TYPE_0..COMPRESSION_TYPE_3 -> {
                        length = bits.read(lengthBits).toInt()
                        current += length
                        bits.read(16)
                    }
                    COMPRESSION_NONE -> {
                        length = hunkBytes
                        current += length
                        bits.read(16)
                    }
                    COMPRESSION_SELF -> {
                        offset = bits.read(selfBits)
                        lastSelf = offset
                    }
                    COMPRESSION_PARENT -> {
                        offset = bits.read(parentBits)
                        lastParent = offset
                    }
                    COMPRESSION_SELF_1 -> {
                        lastSelf++
                        kinds[hunk] = COMPRESSION_SELF.toByte()
                        offset = lastSelf
                    }
                    COMPRESSION_SELF_0 -> {
                        kinds[hunk] = COMPRESSION_SELF.toByte()
                        offset = lastSelf
                    }
                    COMPRESSION_PARENT_SELF -> {
                        kinds[hunk] = COMPRESSION_PARENT.toByte()
                        offset = hunk.toLong() * hunkBytes / unitBytes
                        lastParent = offset
                    }
                    COMPRESSION_PARENT_1 -> {
                        lastParent += hunkBytes / unitBytes
                        kinds[hunk] = COMPRESSION_PARENT.toByte()
                        offset = lastParent
                    }
                    COMPRESSION_PARENT_0 -> {
                        kinds[hunk] = COMPRESSION_PARENT.toByte()
                        offset = lastParent
                    }
                    else -> return false
                }
                lengths[hunk] = length
                offsets[hunk] = offset
            }
            return true
        }

        private val TRACK_LINE = Regex("""TRACK:(\d+)\s+TYPE:(\S+)\s+SUBTYPE:(\S+)\s+FRAMES:(\d+)""")

        private fun readTracks(raf: RandomAccessFile, metaOffset: Long): List<ChdTrack> {
            val found = ArrayList<Triple<Int, String, Int>>()
            var at = metaOffset
            var guard = 0
            while (at != 0L && guard++ < MAX_METADATA) {
                val head = ByteArray(16)
                raf.seek(at)
                raf.readFully(head)
                val tag = u32(head, 0).toInt()
                val length = (u32(head, 4) and 0xFFFFFF).toInt()
                val next = u64(head, 8)
                if ((tag == TAG_CHT2 || tag == TAG_CHTR || tag == TAG_CHGD) && length in 1..4096) {
                    val text = ByteArray(length)
                    raf.readFully(text)
                    TRACK_LINE.find(String(text, Charsets.ISO_8859_1))?.let { m ->
                        found.add(Triple(m.groupValues[1].toInt(), m.groupValues[2], m.groupValues[4].toInt()))
                    }
                }
                at = next
            }
            var start = 0
            return found.sortedBy { it.first }.map { (number, type, frames) ->
                ChdTrack(number, type, frames, start).also { start += frames + (4 - frames % 4) % 4 }
            }
        }

        private fun u32(b: ByteArray, at: Int): Long =
            ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or
                ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)

        private fun u64(b: ByteArray, at: Int): Long = (u32(b, at) shl 32) or u32(b, at + 4)

        private fun inflate(src: ByteArray, offset: Int, length: Int, outLength: Int): ByteArray? {
            val inflater = Inflater(true)
            try {
                inflater.setInput(src, offset, length)
                val out = ByteArray(outLength)
                var done = 0
                while (done < outLength) {
                    val n = inflater.inflate(out, done, outLength - done)
                    if (n == 0 && (inflater.finished() || inflater.needsInput() || inflater.needsDictionary())) break
                    done += n
                }
                return if (done == outLength) out else null
            } finally {
                inflater.end()
            }
        }

        /** chdman's LZMA: lc 3, lp 0, pb 2 (properties byte 93), a dictionary at least the hunk long. */
        private fun lzma(src: ByteArray, offset: Int, length: Int, outLength: Int): ByteArray? {
            var dictionary = 4096
            while (dictionary < outLength && dictionary < (1 shl 26)) dictionary = dictionary shl 1
            val stream = LZMAInputStream(ByteArrayInputStream(src, offset, length), outLength.toLong(), 93.toByte(), dictionary)
            val out = ByteArray(outLength)
            var done = 0
            stream.use {
                while (done < outLength) {
                    val n = it.read(out, done, outLength - done)
                    if (n < 0) break
                    done += n
                }
            }
            return if (done == outLength) out else null
        }
    }
}

/** The most significant bit first reader the map is coded in. */
private class BitReader(private val data: ByteArray) {
    private var position = 0
    private var buffer = 0L
    private var held = 0

    fun peek(count: Int): Long {
        if (count == 0) return 0L
        while (held < count) {
            val next = if (position < data.size) data[position].toLong() and 0xFF else 0L
            position++
            buffer = (buffer shl 8) or next
            held += 8
        }
        return (buffer ushr (held - count)) and ((1L shl count) - 1)
    }

    fun remove(count: Int) {
        held -= count
        buffer = if (held <= 0) 0L else buffer and ((1L shl held) - 1)
    }

    fun read(count: Int): Long {
        val value = peek(count)
        remove(count)
        return value
    }

    /** True once more bits were consumed than the data held. */
    fun overflowed(): Boolean = position - held / 8 > data.size
}

/** The canonical Huffman code the hunk map's compression types are coded with. */
private class Huffman(private val numCodes: Int, private val maxBits: Int) {
    private val numBits = IntArray(numCodes)
    private val codes = IntArray(numCodes)
    private var lookup = IntArray(0)

    fun importRle(reader: BitReader): Boolean {
        val width = if (maxBits >= 16) 5 else if (maxBits >= 8) 4 else 3
        var node = 0
        while (node < numCodes) {
            var value = reader.read(width).toInt()
            if (value != 1) {
                numBits[node++] = value
            } else {
                value = reader.read(width).toInt()
                if (value == 1) {
                    numBits[node++] = value
                } else {
                    var repeat = reader.read(width).toInt() + 3
                    if (repeat + node > numCodes) return false
                    while (repeat-- > 0) numBits[node++] = value
                }
            }
        }
        return assign() && build() && !reader.overflowed()
    }

    private fun assign(): Boolean {
        val histogram = LongArray(33)
        for (i in 0 until numCodes) {
            if (numBits[i] > maxBits) return false
            histogram[numBits[i]]++
        }
        var start = 0L
        for (length in 32 downTo 1) {
            val next = (start + histogram[length]) shr 1
            if (length != 1 && next * 2 != start + histogram[length]) return false
            histogram[length] = start
            start = next
        }
        for (i in 0 until numCodes) {
            if (numBits[i] > 0) codes[i] = (histogram[numBits[i]]++).toInt()
        }
        return true
    }

    private fun build(): Boolean {
        lookup = IntArray(1 shl maxBits)
        for (i in 0 until numCodes) {
            val length = numBits[i]
            if (length <= 0) continue
            val shift = maxBits - length
            val first = codes[i] shl shift
            val end = ((codes[i] + 1) shl shift) - 1
            if (first < 0 || end >= lookup.size) return false
            for (at in first..end) lookup[at] = (i shl 5) or length
        }
        return true
    }

    fun decode(reader: BitReader): Int {
        val entry = lookup[reader.peek(maxBits).toInt()]
        reader.remove(entry and 0x1f)
        return entry ushr 5
    }
}
