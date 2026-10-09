package dev.droidtop.library.achievements.hash

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.ZipFile

/** How RetroAchievements hashes a game of a console (rcheevos' rc_hash, one function per family). */
enum class RaHashKind {
    /** The whole file (up to 64 MiB). */
    WHOLE,
    NES,
    SNES,
    PCE,
    LYNX,
    A7800,
    N64,
    NDS,
    ARCADE,
    PSX,
    PS2,
    PSP,
    SEGA_DISC,
    PCE_CD,

    /** A console RetroAchievements has, whose rule droidtop does not implement: only the name can match. */
    NONE,
}

/** A RetroAchievements console id and how a game on it is hashed. */
data class RaConsole(val id: Int, val kind: RaHashKind)

/**
 * ES-DE system ids (droidtop's own, `PlatformsDatabase`) to RetroAchievements consoles. Ids are rcheevos'
 * `rc_consoles.h`.
 */
object RaConsoles {
    private val table: Map<String, RaConsole> = buildMap {
        fun add(console: RaConsole, vararg systems: String) = systems.forEach { put(it, console) }
        add(RaConsole(1, RaHashKind.WHOLE), "megadrive", "megadrivejp", "genesis", "mark3")
        add(RaConsole(2, RaHashKind.N64), "n64")
        add(RaConsole(3, RaHashKind.SNES), "snes", "snesna", "sfc", "satellaview", "sufami")
        add(RaConsole(4, RaHashKind.WHOLE), "gb", "sgb")
        add(RaConsole(5, RaHashKind.WHOLE), "gba")
        add(RaConsole(6, RaHashKind.WHOLE), "gbc")
        add(RaConsole(7, RaHashKind.NES), "nes", "famicom")
        add(RaConsole(8, RaHashKind.PCE), "pcengine", "tg16", "supergrafx")
        add(RaConsole(9, RaHashKind.SEGA_DISC), "segacd", "megacd", "megacdjp")
        add(RaConsole(10, RaHashKind.WHOLE), "sega32x", "sega32xjp", "sega32xna")
        add(RaConsole(11, RaHashKind.WHOLE), "mastersystem")
        add(RaConsole(12, RaHashKind.PSX), "psx")
        add(RaConsole(13, RaHashKind.LYNX), "atarilynx")
        add(RaConsole(14, RaHashKind.WHOLE), "ngp", "ngpc")
        add(RaConsole(15, RaHashKind.WHOLE), "gamegear")
        add(RaConsole(16, RaHashKind.NONE), "gc")
        add(RaConsole(17, RaHashKind.WHOLE), "atarijaguar")
        add(RaConsole(18, RaHashKind.NDS), "nds")
        add(RaConsole(19, RaHashKind.NONE), "wii")
        add(RaConsole(21, RaHashKind.PS2), "ps2")
        add(RaConsole(23, RaHashKind.WHOLE), "odyssey2", "videopac")
        add(RaConsole(24, RaHashKind.WHOLE), "pokemini")
        add(RaConsole(25, RaHashKind.WHOLE), "atari2600")
        add(RaConsole(27, RaHashKind.ARCADE), "arcade", "mame", "fbneo", "fba", "neogeo", "cps", "cps1", "cps2", "cps3")
        add(RaConsole(28, RaHashKind.WHOLE), "virtualboy")
        add(RaConsole(33, RaHashKind.WHOLE), "sg-1000")
        add(RaConsole(39, RaHashKind.SEGA_DISC), "saturn", "saturnjp")
        add(RaConsole(40, RaHashKind.NONE), "dreamcast")
        add(RaConsole(41, RaHashKind.PSP), "psp")
        add(RaConsole(43, RaHashKind.NONE), "3do")
        add(RaConsole(44, RaHashKind.WHOLE), "colecovision")
        add(RaConsole(45, RaHashKind.WHOLE), "intellivision")
        add(RaConsole(46, RaHashKind.WHOLE), "vectrex")
        add(RaConsole(49, RaHashKind.NONE), "pcfx")
        add(RaConsole(50, RaHashKind.WHOLE), "atari5200")
        add(RaConsole(51, RaHashKind.A7800), "atari7800")
        add(RaConsole(53, RaHashKind.WHOLE), "wonderswan", "wonderswancolor")
        add(RaConsole(56, RaHashKind.NONE), "neogeocd", "neogeocdjp")
        add(RaConsole(57, RaHashKind.WHOLE), "channelf")
        add(RaConsole(63, RaHashKind.WHOLE), "supervision")
        add(RaConsole(69, RaHashKind.WHOLE), "megaduck")
        add(RaConsole(76, RaHashKind.PCE_CD), "pcenginecd", "tg-cd")
        add(RaConsole(77, RaHashKind.NONE), "atarijaguarcd")
        add(RaConsole(81, RaHashKind.NES), "fds")
    }

    /** The RetroAchievements console of an ES-DE system id, or null when RetroAchievements has none for it. */
    fun forSystem(systemId: String?): RaConsole? = systemId?.let { table[it] }
}

/**
 * RetroAchievements' game hash of a file (docs/SPEC.md 7h, "RetroAchievements"), computed by the rules of
 * rcheevos' `rc_hash` (src/hash/rc_hash_rom.c, rc_hash_disc.c): the same bytes into MD5, header stripping, N64
 * byte order, and for the disc consoles the boot executable or header the rule names, read from an ISO, a
 * BIN behind a CUE or a CHD. Anything the rules cannot be applied to is null, never a guess. Reads the file: never
 * on the main thread.
 */
object RaHasher {

    private const val MAX = 64L * 1024 * 1024

    /** The lowercase hex hash of [file] on [console], or null when it cannot be hashed (kind NONE, unreadable, not that console). */
    fun hash(file: File, console: RaConsole): String? = try {
        when (console.kind) {
            RaHashKind.NONE -> null
            RaHashKind.ARCADE -> arcade(file)
            RaHashKind.PSX, RaHashKind.PS2, RaHashKind.PSP, RaHashKind.SEGA_DISC, RaHashKind.PCE_CD -> disc(file, console.kind)
            else -> cartridge(file, console.kind)
        }
    } catch (e: Exception) {
        null
    }

    // ----- cartridges ---------------------------------------------------

    private interface Source : Closeable {
        val size: Long
        fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int
    }

    private class FileSource(private val raf: RandomAccessFile) : Source {
        override val size: Long = raf.length()
        override fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int {
            raf.seek(position)
            return raf.read(into, offset, length)
        }
        override fun close() = raf.close()
    }

    private class BytesSource(private val bytes: ByteArray) : Source {
        override val size: Long = bytes.size.toLong()
        override fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int {
            if (position >= bytes.size) return -1
            val n = minOf(length.toLong(), bytes.size - position).toInt()
            System.arraycopy(bytes, position.toInt(), into, offset, n)
            return n
        }
        override fun close() = Unit
    }

    /** A file, or the one file inside a zip (an emulator is handed the extracted ROM, so that is what is hashed). */
    private fun open(file: File): Source? {
        if (!file.isFile) return null
        if (file.extension.equals("zip", ignoreCase = true)) {
            ZipFile(file).use { zip ->
                val entries = zip.entries().asSequence().filter { !it.isDirectory }.toList()
                if (entries.size != 1) return null
                val out = ByteArrayOutputStream()
                zip.getInputStream(entries[0]).use { input ->
                    val buffer = ByteArray(1 shl 16)
                    while (out.size() < MAX) {
                        val n = input.read(buffer, 0, minOf(buffer.size.toLong(), MAX - out.size()).toInt())
                        if (n < 0) break
                        out.write(buffer, 0, n)
                    }
                }
                return BytesSource(out.toByteArray())
            }
        }
        return FileSource(RandomAccessFile(file, "r"))
    }

    private fun cartridge(file: File, kind: RaHashKind): String? {
        val source = open(file) ?: return null
        source.use { return hashCartridge(it, kind) }
    }

    private fun hashCartridge(source: Source, kind: RaHashKind): String? {
        val size = minOf(source.size, MAX)
        if (size <= 0L) return null
        val head = ByteArray(minOf(size, 16L).toInt())
        readFully(source, 0L, head)
        var skip = 0L
        var swap = 0
        when (kind) {
            RaHashKind.NES -> if (size > 16 && (startsWith(head, "NES\u001a") || startsWith(head, "FDS\u001a"))) skip = 16
            RaHashKind.SNES -> if (size - (size / 0x2000) * 0x2000 == 512L) skip = 512
            RaHashKind.PCE -> if (size and 512L != 0L) skip = 512
            RaHashKind.LYNX -> if (size > 64 && startsWith(head, "LYNX") && head.size > 4 && head[4].toInt() == 0) skip = 64
            RaHashKind.A7800 -> if (size > 128 && head.size > 9 && String(head, 1, 9, Charsets.ISO_8859_1) == "ATARI7800") skip = 128
            RaHashKind.N64 -> when (head[0].toInt() and 0xFF) {
                0x80, 0xE8, 0x22 -> Unit
                0x37 -> swap = 2
                0x40 -> swap = 4
                else -> return null
            }
            RaHashKind.NDS -> return nintendoDs(source)
            else -> Unit
        }
        val digest = MessageDigest.getInstance("MD5")
        val buffer = ByteArray(1 shl 16)
        var at = skip
        while (at < size) {
            val want = minOf(buffer.size.toLong(), size - at).toInt()
            val n = source.read(at, buffer, 0, want)
            if (n <= 0) break
            if (swap == 2) swap16(buffer, n) else if (swap == 4) swap32(buffer, n)
            digest.update(buffer, 0, n)
            at += n
        }
        return hex(digest.digest())
    }

    private fun swap16(b: ByteArray, n: Int) {
        var i = 0
        while (i + 1 < n) {
            val t = b[i]; b[i] = b[i + 1]; b[i + 1] = t
            i += 2
        }
    }

    private fun swap32(b: ByteArray, n: Int) {
        var i = 0
        while (i + 3 < n) {
            val a = b[i]; val c = b[i + 1]
            b[i] = b[i + 3]; b[i + 1] = b[i + 2]; b[i + 2] = c; b[i + 3] = a
            i += 4
        }
    }

    /** rc_hash_nintendo_ds: the 352-byte header, the ARM9 and ARM7 code, the 2560 bytes of icon and title. */
    private fun nintendoDs(source: Source): String? {
        var offset = 0L
        var header = ByteArray(512)
        if (!readFully(source, 0L, header)) return null
        if ((header[0].toInt() and 0xFF) == 0x2E && header[1].toInt() == 0 && header[2].toInt() == 0 &&
            (header[3].toInt() and 0xFF) == 0xEA && (header[0xB0].toInt() and 0xFF) == 0x44 &&
            (header[0xB1].toInt() and 0xFF) == 0x46 && (header[0xB2].toInt() and 0xFF) == 0x96 && header[0xB3].toInt() == 0
        ) {
            offset = 512
            header = ByteArray(512)
            if (!readFully(source, offset, header)) return null
        }
        val arm9Address = le32(header, 0x20)
        val arm9Size = le32(header, 0x2C)
        val arm7Address = le32(header, 0x30)
        val arm7Size = le32(header, 0x3C)
        val iconAddress = le32(header, 0x68)
        if (arm9Size + arm7Size > 16L * 1024 * 1024) return null
        val digest = MessageDigest.getInstance("MD5")
        digest.update(header, 0, 0x160)
        val arm9 = ByteArray(arm9Size.toInt())
        if (!readFully(source, arm9Address + offset, arm9)) return null
        digest.update(arm9)
        val arm7 = ByteArray(arm7Size.toInt())
        if (!readFully(source, arm7Address + offset, arm7)) return null
        digest.update(arm7)
        val icon = ByteArray(0xA00)
        // Some homebrew has no full icon block: the rest is zeros, as the rule says.
        readUpTo(source, iconAddress + offset, icon)
        digest.update(icon)
        return hex(digest.digest())
    }

    /** rc_hash_arcade: the hash of the file name without its extension, with a console folder in front for FBNeo's subsystems. */
    private fun arcade(file: File): String {
        val name = file.name
        val dot = name.lastIndexOf('.')
        val stem = if (dot <= 0) name else name.substring(0, dot)
        val parent = file.parentFile?.name?.lowercase().orEmpty()
        val text = if (dot > 0 && parent in ARCADE_SUBSYSTEM_FOLDERS) "${parent}_$stem" else stem
        return hex(MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.ISO_8859_1)))
    }

    private val ARCADE_SUBSYSTEM_FOLDERS = setOf(
        "nes", "fds", "sms", "msx", "ngp", "pce", "chf", "sgx", "tg16", "msx1", "neocd", "coleco", "sg1000",
        "genesis", "gamegear", "megadriv", "pcengine", "channelf", "spectrum", "megadrive", "supergrafx",
        "zxspectrum", "mastersystem", "colecovision",
    )

    // ----- discs ----------------------------------------------------------

    private fun disc(file: File, kind: RaHashKind): String? {
        if (kind == RaHashKind.PSP && file.extension.equals("pbp", ignoreCase = true)) return wholeFile(file)
        val track = DiscImages.open(file) ?: return null
        track.use {
            return when (kind) {
                RaHashKind.PSX -> playstation(it, "BOOT", "cdrom:", psx = true)
                RaHashKind.PS2 -> playstation(it, "BOOT2", "cdrom0:", psx = false)
                RaHashKind.PSP -> psp(it)
                RaHashKind.SEGA_DISC -> segaDisc(it)
                RaHashKind.PCE_CD -> pceCd(it)
                else -> null
            }
        }
    }

    private fun wholeFile(file: File): String? {
        val source = open(file) ?: return null
        source.use { return hashCartridge(it, RaHashKind.WHOLE) }
    }

    /** rc_hash_psx and rc_hash_ps2: the boot file's name, then its bytes (a PS-X EXE by its own header's length plus 2048). */
    private fun playstation(track: DiscTrack, bootKey: String, prefix: String, psx: Boolean): String? {
        val boot = PlayStationBoot.find(track, bootKey, prefix, psxFallback = psx) ?: return null
        val file = boot.file
        var size = file.size
        if (psx) {
            val head = track.readSector(file.sector, 32) ?: return null
            if (head.size < 32) return null
            if (String(head, 0, 7, Charsets.ISO_8859_1) == "PS-X EX") {
                size = (le32(head, 28) + 2048) and 0xFFFFFFFFL
            }
        } else if ((track.readSector(file.sector, 4)?.size ?: 0) < 4) {
            return null
        }
        val digest = MessageDigest.getInstance("MD5")
        digest.update(boot.name.toByteArray(Charsets.ISO_8859_1))
        return if (hashDiscFile(digest, track, file.sector, size)) hex(digest.digest()) else null
    }

    /** rc_hash_psp: PARAM.SFO then EBOOT.BIN. */
    private fun psp(track: DiscTrack): String? {
        val volume = Iso9660(track)
        val digest = MessageDigest.getInstance("MD5")
        for (path in arrayOf("PSP_GAME\\PARAM.SFO", "PSP_GAME\\SYSDIR\\EBOOT.BIN")) {
            val file = volume.find(path) ?: return null
            if (!hashDiscFile(digest, track, file.sector, file.size)) return null
        }
        return hex(digest.digest())
    }

    /** rc_hash_sega_cd: the first 512 bytes of the disc, which must be a Sega CD or Saturn header. */
    private fun segaDisc(track: DiscTrack): String? {
        val head = track.readSector(0, 512) ?: return null
        if (head.size < 512) return null
        val tag = String(head, 0, 16, Charsets.ISO_8859_1)
        if (tag != "SEGADISCSYSTEM  " && tag != "SEGA SEGASATURN ") return null
        return hex(MessageDigest.getInstance("MD5").digest(head))
    }

    /** rc_hash_pce_track for an ordinary PC Engine CD: the title, then the program sectors the header names. */
    private fun pceCd(track: DiscTrack): String? {
        val head = track.readSector(1, 128) ?: return null
        if (head.size < 128 || String(head, 32, 23, Charsets.ISO_8859_1) != "PC Engine CD-ROM SYSTEM") return null
        val digest = MessageDigest.getInstance("MD5")
        digest.update(head, 106, 22)
        var sector = ((head[0].toInt() and 0xFF) shl 16) + ((head[1].toInt() and 0xFF) shl 8) + (head[2].toInt() and 0xFF)
        var count = head[3].toInt() and 0xFF
        while (count > 0) {
            val data = track.readSector(sector, 2048) ?: return null
            digest.update(data)
            sector++
            count--
        }
        return hex(digest.digest())
    }

    /** rc_hash_cd_file: [size] bytes (at most 64 MiB) of the file from [sector] on, sector by sector. */
    private fun hashDiscFile(digest: MessageDigest, track: DiscTrack, firstSector: Int, size: Long): Boolean {
        var remaining = minOf(size, MAX)
        var sector = firstSector
        while (remaining > 0) {
            val want = minOf(remaining, 2048L).toInt()
            val data = track.readSector(sector, want) ?: return false
            if (data.size < want) return false
            digest.update(data, 0, want)
            remaining -= want
            sector++
        }
        return true
    }

    // ----- helpers ----------------------------------------------------------

    private fun startsWith(bytes: ByteArray, text: String): Boolean {
        if (bytes.size < text.length) return false
        for (i in text.indices) if ((bytes[i].toInt() and 0xFF) != text[i].code) return false
        return true
    }

    private fun readFully(source: Source, position: Long, into: ByteArray): Boolean = readUpTo(source, position, into) == into.size

    private fun readUpTo(source: Source, position: Long, into: ByteArray): Int {
        var done = 0
        while (done < into.size) {
            val n = source.read(position + done, into, done, into.size - done)
            if (n <= 0) break
            done += n
        }
        return done
    }

    private fun le32(b: ByteArray, at: Int): Long =
        (b[at].toLong() and 0xFF) or ((b[at + 1].toLong() and 0xFF) shl 8) or
            ((b[at + 2].toLong() and 0xFF) shl 16) or ((b[at + 3].toLong() and 0xFF) shl 24)

    private fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) { bytes.forEach { append("%02x".format(it)) } }
}
