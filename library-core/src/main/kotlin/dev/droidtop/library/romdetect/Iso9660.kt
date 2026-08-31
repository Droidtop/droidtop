package dev.droidtop.library.romdetect

import java.io.File
import java.io.RandomAccessFile

/**
 * Just enough ISO9660 to read one file out of a disc image's root
 * directory.
 *
 * This exists because scanning a disc image for a marker string cannot
 * work, which was established the expensive way. droidtop reported every
 * PS2 disc as PS1, and the obvious fix -- search the first N bytes for
 * the `BOOT2` line that distinguishes a PS2 disc from a PS1 one -- failed
 * on real discs no matter how N was chosen:
 *
 * - `SYSTEM.CNF` sat at byte 552,960 on one disc and at **3,923,748,864**
 *   on another. There is no window that catches the second and is not
 *   absurd.
 * - Two discs had `SYSTEM.CNF` at the identical offset, yet only one was
 *   detected -- a scan reading fixed-size windows off an SD card does not
 *   reliably see a match that a short read happens to straddle.
 * - One disc writes `BOOT2=` with no spaces, so even being in range is
 *   not enough if the matcher assumes spacing.
 *
 * Reading the filesystem instead is both correct and cheaper: the volume
 * descriptor says where the root directory is, the root directory says
 * where the file is -- a handful of small seeks rather than megabytes of
 * scanning.
 *
 * **Sector layouts.** A `.iso` stores bare 2048-byte data sectors, but a
 * raw `.bin` -- the most common PS1 dump format -- stores full 2352-byte
 * sectors with the data at an offset inside each: 16 for Mode 1, 24 for
 * Mode 2 Form 1 (CD-XA, which PS1 discs actually are; the vendored
 * scanner's own PSX magic offset 0x9320 is exactly sector 16 at
 * 2352-byte pitch plus that 24-byte header plus the identifier field).
 * The first version of this reader only understood 2048 and silently
 * returned null for every raw image; [probeLayout] now tries all three.
 * File content in a raw image is NOT contiguous, so reads go sector by
 * sector -- which is also why [readRootFile] is the public API rather
 * than a byte offset a caller would be tempted to read directly.
 *
 * Deliberately minimal beyond that -- root directory only, no
 * subdirectories, no Joliet or Rock Ridge. Everything droidtop needs to
 * identify a disc (`SYSTEM.CNF`) lives in the root by the PlayStation's
 * own boot convention, and a fuller ISO9660 reader would be code with no
 * caller.
 */
object Iso9660 {

    private const val DATA_SIZE = 2048

    /** The primary volume descriptor always sits at sector 16. */
    private const val PVD_SECTOR = 16L

    /** Offset of the root directory record within the PVD. */
    private const val ROOT_RECORD_OFFSET = 156

    /** Offset of the name length within a directory record. */
    private const val NAME_LENGTH_OFFSET = 32

    /** Guards against a malformed image claiming an enormous root directory. */
    private const val MAX_ROOT_DIRECTORY_BYTES = 1 shl 20

    /** How the 2048 data bytes of each sector sit inside the image. */
    private data class SectorLayout(val sectorSize: Long, val dataOffset: Long)

    private val LAYOUTS = listOf(
        SectorLayout(2048, 0), // plain .iso
        SectorLayout(2352, 24), // raw Mode 2 Form 1 (CD-XA) -- what PS1 discs are
        SectorLayout(2352, 16), // raw Mode 1
    )

    /**
     * The first [maxBytes] of [name]'s content from the image's root
     * directory, or null when this is not a readable ISO9660 image or the
     * file is not there. [name] matches case-insensitively and ignores
     * the `;1` version suffix ISO9660 appends.
     */
    fun readRootFile(file: File, name: String, maxBytes: Int): ByteArray? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val layout = probeLayout(raf) ?: return@runCatching null
            val pvd = readSectors(raf, layout, PVD_SECTOR, DATA_SIZE) ?: return@runCatching null

            val rootLba = pvd.leUInt(ROOT_RECORD_OFFSET + 2) ?: return@runCatching null
            val rootLength = pvd.leUInt(ROOT_RECORD_OFFSET + 10) ?: return@runCatching null
            if (rootLength <= 0L || rootLength > MAX_ROOT_DIRECTORY_BYTES) return@runCatching null

            val root = readSectors(raf, layout, rootLba, rootLength.toInt()) ?: return@runCatching null
            val record = findRecord(root, name) ?: return@runCatching null

            val size = minOf(record.length, maxBytes.toLong()).toInt()
            if (size <= 0) return@runCatching null
            readSectors(raf, layout, record.lba, size)
        }
    }.getOrNull()

    private data class Record(val lba: Long, val length: Long)

    /**
     * Which sector layout this image uses, decided by where a valid
     * primary volume descriptor (type byte 1, magic "CD001") actually
     * is. Both checks, not just the magic: the probe looks at three
     * different offsets in arbitrary files, and five magic bytes alone
     * is a weaker accident-proofing than five plus the type.
     */
    private fun probeLayout(raf: RandomAccessFile): SectorLayout? =
        LAYOUTS.firstOrNull { layout ->
            val header = raf.readAt(PVD_SECTOR * layout.sectorSize + layout.dataOffset, 6)
            header != null && header.size == 6 &&
                header[0].toInt() == 1 && header.matchesAscii(1, "CD001")
        }

    /**
     * [count] bytes of DATA starting at [startLba], read sector by
     * sector -- in a raw image the data areas are not contiguous, so a
     * single long read would interleave sync headers and EDC trailers
     * into what the caller thinks is file content.
     */
    private fun readSectors(raf: RandomAccessFile, layout: SectorLayout, startLba: Long, count: Int): ByteArray? {
        val out = ByteArray(count)
        var produced = 0
        var lba = startLba
        while (produced < count) {
            val want = minOf(DATA_SIZE, count - produced)
            val chunk = raf.readAt(lba * layout.sectorSize + layout.dataOffset, want) ?: break
            chunk.copyInto(out, produced)
            produced += chunk.size
            if (chunk.size < want) break
            lba += 1
        }
        return when {
            produced == 0 -> null
            produced < count -> out.copyOf(produced)
            else -> out
        }
    }

    private fun findRecord(root: ByteArray, name: String): Record? {
        val wanted = name.uppercase()
        var pos = 0
        while (pos < root.size) {
            val recordLength = root[pos].toInt() and 0xFF
            if (recordLength == 0) {
                // Zero length means padding to the end of this sector;
                // records never straddle a sector boundary.
                pos = ((pos / DATA_SIZE) + 1) * DATA_SIZE
                continue
            }
            if (pos + recordLength > root.size || recordLength <= NAME_LENGTH_OFFSET) return null

            val nameLength = root[pos + NAME_LENGTH_OFFSET].toInt() and 0xFF
            val nameStart = pos + NAME_LENGTH_OFFSET + 1
            if (nameLength > 0 && nameStart + nameLength <= root.size) {
                val entry = String(root, nameStart, nameLength, Charsets.US_ASCII)
                    .substringBefore(';')
                    .trim()
                    .uppercase()
                if (entry == wanted) {
                    val lba = root.leUInt(pos + 2) ?: return null
                    val length = root.leUInt(pos + 10) ?: return null
                    return Record(lba, length)
                }
            }
            pos += recordLength
        }
        return null
    }

    /** Reads [length] bytes at [offset], or null if nothing could be read there. */
    private fun RandomAccessFile.readAt(offset: Long, length: Int): ByteArray? {
        if (offset < 0 || length <= 0 || offset >= this.length()) return null
        seek(offset)
        val buffer = ByteArray(length)
        var read = 0
        // Loops rather than trusting one read(): a single read off real
        // storage routinely returns short, and a half-filled buffer is
        // exactly what made the previous scanning approach behave
        // differently between runs on identical files.
        while (read < length) {
            val n = read(buffer, read, length - read)
            if (n <= 0) break
            read += n
        }
        return when {
            read == 0 -> null
            read < length -> buffer.copyOf(read)
            else -> buffer
        }
    }

    private fun ByteArray.matchesAscii(at: Int, text: String): Boolean =
        at >= 0 && at + text.length <= size && String(this, at, text.length, Charsets.US_ASCII) == text

    /**
     * ISO9660 stores these both-endian; droidtop reads the little-endian
     * half. Returned as a [Long] because the field is an unsigned 32-bit
     * value -- a disc past 2 GB overflows a signed Int, and two of the
     * real discs this was built against are 1.5 GB and 3.7 GB.
     */
    private fun ByteArray.leUInt(at: Int): Long? {
        if (at < 0 || at + 4 > size) return null
        return (this[at].toLong() and 0xFF) or
            ((this[at + 1].toLong() and 0xFF) shl 8) or
            ((this[at + 2].toLong() and 0xFF) shl 16) or
            ((this[at + 3].toLong() and 0xFF) shl 24)
    }
}

/**
 * Tells a PlayStation 2 disc from a PlayStation 1 one.
 *
 * Both carry the literal string "PLAYSTATION" as their ISO9660 volume
 * descriptor system identifier, so magic numbers cannot separate them --
 * which is why droidtop used to report every PS2 disc as PS1 and launch
 * those games through a PS1 emulator that cannot run them.
 *
 * `SYSTEM.CNF` is what actually differs: a PS1 disc boots through a
 * `BOOT` line, a PS2 disc through `BOOT2`. Verified against real discs,
 * whose SYSTEM.CNF read `BOOT2 = cdrom0:\SCUS_974.64;1` and, on one that
 * omits the spaces, `BOOT2=cdrom0:\SLUS_202.65;1` -- hence the
 * whitespace-insensitive check rather than matching a literal "BOOT2 =".
 */
object PlayStationDiscType {

    private const val SYSTEM_CNF = "SYSTEM.CNF"

    /** SYSTEM.CNF is a handful of short lines; this is generous. */
    private const val MAX_CNF_BYTES = 4096

    /**
     * [SystemID.PS2] or [SystemID.PSX] for a PlayStation disc image, or
     * null when the image is not one droidtop can read this way -- in
     * which case the caller should fall back rather than assume either.
     */
    fun detect(file: File): SystemID? {
        val bytes = Iso9660.readRootFile(file, SYSTEM_CNF, MAX_CNF_BYTES) ?: return null
        val text = String(bytes, Charsets.US_ASCII)

        // Whitespace-insensitive: real discs write both "BOOT2 =" and
        // "BOOT2=", and matching one spelling would miss the other.
        val compact = text.filterNot { it.isWhitespace() }.uppercase()
        return when {
            compact.contains("BOOT2=") -> SystemID.PS2
            compact.contains("BOOT=") -> SystemID.PSX
            else -> null
        }
    }
}
