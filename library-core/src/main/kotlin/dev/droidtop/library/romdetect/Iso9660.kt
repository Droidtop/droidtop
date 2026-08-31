package dev.droidtop.library.romdetect

import java.io.File
import java.io.RandomAccessFile

/**
 * Just enough ISO9660 to find a file in a disc image's root directory.
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
 * where the file is, and that is two small seeks rather than megabytes of
 * scanning.
 *
 * Deliberately minimal -- root directory only, no subdirectories, no
 * Joliet or Rock Ridge. Everything droidtop needs to identify a disc
 * (`SYSTEM.CNF`) lives in the root by the PlayStation's own boot
 * convention, and a fuller ISO9660 reader would be code with no caller.
 */
object Iso9660 {

    private const val SECTOR = 2048L

    /** The primary volume descriptor always sits at sector 16. */
    private const val PVD_SECTOR = 16L

    /** Offset of the root directory record within the PVD. */
    private const val ROOT_RECORD_OFFSET = 156

    /** Offset of the name length within a directory record. */
    private const val NAME_LENGTH_OFFSET = 32

    /** Guards against a malformed image claiming an enormous root directory. */
    private const val MAX_ROOT_DIRECTORY_BYTES = 1 shl 20

    data class Located(val offset: Long, val length: Int)

    /**
     * Where [name] lives in the image's root directory, or null if this
     * is not an ISO9660 image or the file is not there.
     *
     * [name] is matched case-insensitively and ignores the `;1` version
     * suffix ISO9660 appends, since callers think in terms of
     * "SYSTEM.CNF", not "SYSTEM.CNF;1".
     */
    fun findInRootDirectory(file: File, name: String): Located? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val pvd = raf.readAt(PVD_SECTOR * SECTOR, SECTOR.toInt()) ?: return@runCatching null
            // "CD001" at offset 1 is what makes this an ISO9660 volume
            // descriptor at all; without it there is nothing to walk and
            // the caller should fall back.
            if (!pvd.matchesAscii(1, "CD001")) return@runCatching null

            val rootLba = pvd.leUInt(ROOT_RECORD_OFFSET + 2) ?: return@runCatching null
            val rootLength = pvd.leUInt(ROOT_RECORD_OFFSET + 10) ?: return@runCatching null
            if (rootLength <= 0L || rootLength > MAX_ROOT_DIRECTORY_BYTES) return@runCatching null

            val root = raf.readAt(rootLba * SECTOR, rootLength.toInt()) ?: return@runCatching null
            findRecord(root, name)
        }
    }.getOrNull()

    private fun findRecord(root: ByteArray, name: String): Located? {
        val wanted = name.uppercase()
        val sector = SECTOR.toInt()
        var pos = 0
        while (pos < root.size) {
            val recordLength = root[pos].toInt() and 0xFF
            if (recordLength == 0) {
                // Zero length means padding to the end of this sector;
                // records never straddle a sector boundary.
                pos = ((pos / sector) + 1) * sector
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
                    return Located(lba * SECTOR, length.toInt())
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
     * sector count -- a disc past 2 GB overflows a signed Int, and two of
     * the real discs this was built against are 1.5 GB and 3.7 GB.
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
     * which case the caller should fall back to [SerialScanner] rather
     * than assume either.
     */
    fun detect(file: File): SystemID? {
        val located = Iso9660.findInRootDirectory(file, SYSTEM_CNF) ?: return null
        val text = runCatching {
            RandomAccessFile(file, "r").use { raf ->
                if (located.offset < 0 || located.offset >= raf.length()) return@runCatching null
                val size = located.length.coerceIn(0, MAX_CNF_BYTES)
                if (size == 0) return@runCatching null
                raf.seek(located.offset)
                val buffer = ByteArray(size)
                var read = 0
                while (read < size) {
                    val n = raf.read(buffer, read, size - read)
                    if (n <= 0) break
                    read += n
                }
                if (read == 0) null else String(buffer, 0, read, Charsets.US_ASCII)
            }
        }.getOrNull() ?: return null

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
