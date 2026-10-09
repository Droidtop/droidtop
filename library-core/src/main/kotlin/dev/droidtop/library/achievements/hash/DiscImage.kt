package dev.droidtop.library.achievements.hash

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/**
 * The first data track of a disc image as 2048-byte sectors, however it is stored (an ISO, a raw or cooked BIN
 * behind a CUE, or a CHD). The reader RetroAchievements hashing needs (docs/SPEC.md 7h) and nothing more:
 * sector 0 is the first sector of the track.
 */
internal interface DiscTrack : Closeable {
    /** Up to [length] (at most 2048) user bytes of [sector], or null when it cannot be read. */
    fun readSector(sector: Int, length: Int = 2048): ByteArray?
}

internal object DiscImages {

    /** Opens the data track of [file] (.iso, .bin, .img, .cue or .chd), or null when it is none of those or unreadable. */
    fun open(file: File): DiscTrack? {
        val track = when (file.extension.lowercase()) {
            "chd" -> openChd(file)
            "cue" -> openCue(file)
            "iso", "bin", "img" -> BinTrack.open(file, 0L)
            else -> null
        } ?: return null
        return aligned(track)
    }

    /** An image whose track starts with the two-second lead-in (150 sectors) before sector 0 is read from there. */
    private fun aligned(track: DiscTrack): DiscTrack {
        if (hasVolume(track, 0)) return track
        if (hasVolume(track, PREGAP)) return Shifted(track, PREGAP)
        return track
    }

    private fun hasVolume(track: DiscTrack, shift: Int): Boolean {
        val sector = track.readSector(16 + shift, 8) ?: return false
        return sector.size >= 6 && String(sector, 1, 5, Charsets.ISO_8859_1) == "CD001"
    }

    private fun openChd(file: File): DiscTrack? {
        val chd = ChdFile.open(file) ?: return null
        val data = chd.tracks.firstOrNull { it.userOffset != null }
        if (data != null) return ChdCdTrack(chd, data)
        // A DVD image keeps no tracks: 2048-byte sectors straight through.
        if (chd.tracks.isEmpty() && chd.logicalBytes % 2048L == 0L) return ChdDvdTrack(chd)
        chd.close()
        return null
    }

    private fun openCue(file: File): DiscTrack? {
        val text = runCatching { file.readText(Charsets.ISO_8859_1) }.getOrNull() ?: return null
        var name: String? = null
        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("FILE", ignoreCase = true) && name == null) {
                name = Regex("\"([^\"]+)\"").find(trimmed)?.groupValues?.get(1)
                    ?: trimmed.substring(4).trim().substringBefore(' ')
            }
            if (trimmed.startsWith("TRACK", ignoreCase = true)) break
        }
        val bin = File(file.parentFile, name ?: return null)
        return BinTrack.open(bin, 0L)
    }

    private const val PREGAP = 150
}

private class Shifted(private val inner: DiscTrack, private val shift: Int) : DiscTrack {
    override fun readSector(sector: Int, length: Int): ByteArray? = inner.readSector(sector + shift, length)
    override fun close() = inner.close()
}

private class ChdCdTrack(private val chd: ChdFile, private val track: ChdTrack) : DiscTrack {
    override fun readSector(sector: Int, length: Int): ByteArray? =
        chd.readCdSector(track, sector)?.let { if (length >= it.size) it else it.copyOf(length) }

    override fun close() = chd.close()
}

private class ChdDvdTrack(private val chd: ChdFile) : DiscTrack {
    override fun readSector(sector: Int, length: Int): ByteArray? = chd.readLogical(sector.toLong() * 2048L, length)
    override fun close() = chd.close()
}

/**
 * A file of whole sectors. The layout is read off the image as RetroAchievements' own reader does (the CUE may
 * be wrong): a sync pattern at sector 16 means 2352-byte raw sectors, "CD001" at 2048 means cooked sectors, and
 * a file that shows neither is taken by its size.
 */
private class BinTrack private constructor(
    private val file: RandomAccessFile,
    private val base: Long,
    private val sectorSize: Int,
    private val headerSize: Int,
) : DiscTrack {

    override fun readSector(sector: Int, length: Int): ByteArray? {
        if (sector < 0) return null
        val at = base + sector.toLong() * sectorSize + headerSize
        return try {
            val size = minOf(length, 2048)
            if (at + size > file.length()) return null
            val out = ByteArray(size)
            file.seek(at)
            file.readFully(out)
            out
        } catch (e: java.io.IOException) {
            null
        }
    }

    override fun close() {
        runCatching { file.close() }
    }

    companion object {
        private val SYNC = byteArrayOf(0, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 0)

        fun open(source: File, base: Long): DiscTrack? {
            val raf = try {
                RandomAccessFile(source, "r")
            } catch (e: java.io.IOException) {
                return null
            }
            val layout = try {
                detect(raf, base)
            } catch (e: java.io.IOException) {
                null
            }
            if (layout == null) {
                raf.close()
                return null
            }
            return BinTrack(raf, base, layout.first, layout.second)
        }

        private fun detect(raf: RandomAccessFile, base: Long): Pair<Int, Int>? {
            val header = ByteArray(32)
            for (size in intArrayOf(2352, 2336)) {
                if (!readAt(raf, base + 16L * size, header)) continue
                if (header.copyOfRange(0, 12).contentEquals(SYNC)) {
                    val cooked = String(header, 25, 5, Charsets.ISO_8859_1) == "CD001"
                    return size to (if (cooked) 24 else 16)
                }
            }
            if (readAt(raf, base + 16L * 2048, header) && String(header, 1, 5, Charsets.ISO_8859_1) == "CD001") return 2048 to 0
            val length = raf.length() - base
            return when {
                length % 2352L == 0L -> 2352 to 24
                length % 2048L == 0L -> 2048 to 0
                length % 2336L == 0L -> 2336 to 8
                else -> null
            }
        }

        private fun readAt(raf: RandomAccessFile, at: Long, into: ByteArray): Boolean {
            if (at + into.size > raf.length()) return false
            raf.seek(at)
            raf.readFully(into)
            return true
        }
    }
}

/** A file of an ISO 9660 volume: where it starts and how long it is. */
internal class DiscFile(val sector: Int, val size: Long)

/** The directory lookup RetroAchievements hashing uses: a backslash path from the root, case-insensitive, version suffix ignored. */
internal class Iso9660(private val track: DiscTrack) {

    fun find(path: String): DiscFile? {
        val clean = path.trimStart('\\')
        val slash = clean.lastIndexOf('\\')
        val directorySector: Int
        val directoryBytes: Long
        val name: String
        if (slash >= 0) {
            val directory = find(clean.substring(0, slash)) ?: return null
            directorySector = directory.sector
            directoryBytes = directory.size
            name = clean.substring(slash + 1)
        } else {
            val volume = track.readSector(16, 256) ?: return null
            if (volume.size < 170) return null
            directorySector = le24(volume, 156 + 2)
            directoryBytes = le32(volume, 156 + 10)
            name = clean
        }
        if (name.isEmpty()) return null
        val sectors = ((directoryBytes + 2047) / 2048).coerceIn(1, 64).toInt()
        for (offset in 0 until sectors) {
            val buffer = track.readSector(directorySector + offset) ?: return null
            var at = 0
            while (at + 33 <= buffer.size) {
                val recordLength = buffer[at].toInt() and 0xFF
                if (recordLength == 0) break
                val nameLength = buffer[at + 32].toInt() and 0xFF
                if (at + 33 + nameLength <= buffer.size) {
                    val entry = String(buffer, at + 33, nameLength, Charsets.ISO_8859_1)
                    if (entry.equals(name, ignoreCase = true) || entry.startsWith("$name;", ignoreCase = true)) {
                        return DiscFile(le24(buffer, at + 2), le32(buffer, at + 10))
                    }
                }
                at += recordLength
            }
        }
        return null
    }

    private fun le24(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or ((b[at + 2].toInt() and 0xFF) shl 16)

    private fun le32(b: ByteArray, at: Int): Long = le24(b, at).toLong() or ((b[at + 3].toLong() and 0xFF) shl 24)
}

/** The executable a PlayStation (BOOT) or PlayStation 2 (BOOT2) disc names in SYSTEM.CNF, found as rcheevos finds it. */
internal object PlayStationBoot {
    class Boot(val name: String, val file: DiscFile)

    fun find(track: DiscTrack, bootKey: String, prefix: String, psxFallback: Boolean): Boot? {
        val volume = Iso9660(track)
        val cnf = volume.find("SYSTEM.CNF")
        if (cnf != null) {
            val text = track.readSector(cnf.sector, 2047)?.let { String(it, Charsets.ISO_8859_1) }.orEmpty()
            for (line in text.lineSequence()) {
                if (!line.startsWith(bootKey)) continue
                var rest = line.substring(bootKey.length).trimStart()
                if (!rest.startsWith("=")) continue
                rest = rest.substring(1).trimStart()
                if (rest.startsWith(prefix)) rest = rest.substring(prefix.length)
                rest = rest.trimStart('\\')
                val name = rest.takeWhile { !it.isWhitespace() && it != ';' }.take(63)
                val file = volume.find(name)
                if (file != null) return Boot(name, file)
                break
            }
        }
        if (psxFallback) volume.find("PSX.EXE")?.let { return Boot("PSX.EXE", it) }
        return null
    }
}

/** A disc's serial as emulators' game databases write it (DuckStation: SLUS-01234), from the boot file's name. */
object DiscSerials {
    private val BOOT_NAME = Regex("([A-Z]{4})_(\\d{3})\\.(\\d{2})")

    fun playstation(file: File): String? = try {
        DiscImages.open(file)?.use { track ->
            PlayStationBoot.find(track, "BOOT", "cdrom:", psxFallback = false)?.name?.let { serial(it) }
        }
    } catch (e: Exception) {
        null
    }

    internal fun serial(bootName: String): String? =
        BOOT_NAME.matchEntire(bootName.substringAfterLast('\\'))?.let { "${it.groupValues[1]}-${it.groupValues[2]}${it.groupValues[3]}" }
}
