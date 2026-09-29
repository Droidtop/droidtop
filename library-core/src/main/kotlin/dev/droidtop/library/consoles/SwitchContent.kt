package dev.droidtop.library.consoles

import java.io.File
import java.io.RandomAccessFile
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What one Switch content file IS: the base game, an update to it, or
 * DLC for it -- decided WITHOUT console keys and without reading any
 * encrypted content (docs/SPEC.md 7m, "Switch content").
 *
 * Two answers exist for "which of the three is this file", and both are
 * outside the crypto: a title ID is 16 hex characters whose last THREE
 * hex characters say what the content is -- `...000` the base game,
 * `...800` its update, anything else an add-on (DLC) whose index that
 * suffix is -- and the title ID is printed in two places droidtop may
 * read, in this order:
 *
 *  1. a `[TitleID]` tag in the filename (the `[TitleID][vN][DLC]`
 *     convention scene release names carry);
 *  2. the ticket file's NAME inside a PFS0 container (.nsp/.nsz): the
 *     ticket is named `<rights id>.tik`, and a rights id is the title
 *     ID followed by its master-key revision, so the first 16 hex
 *     characters of the name are the title ID. Only the PFS0 file
 *     TABLE (a few KB at the head of the file) is read to learn the
 *     name; the ticket's own bytes are never opened, because they are
 *     the one part of a Switch package that carries key material, and
 *     droidtop never reads console keys.
 *
 * What droidtop does with the answer is the grouping's job
 * ([dev.droidtop.library.SwitchGameGrouping]): an update and a DLC are
 * parts of ONE game and never games of their own. This file only says
 * what a file is; it never renames, moves or copies anything.
 */
@Serializable
sealed class SwitchContent {
    abstract val titleId: String?
    abstract val baseTitleId: String?
    /** The `[vN]` tag from the filename, when the name carried one. */
    abstract val version: String?

    @Serializable
    @SerialName("base")
    data class BaseGame(
        override val titleId: String? = null,
        override val baseTitleId: String? = null,
        override val version: String? = null,
    ) : SwitchContent()

    @Serializable
    @SerialName("update")
    data class Update(
        override val titleId: String? = null,
        override val baseTitleId: String? = null,
        override val version: String? = null,
    ) : SwitchContent()

    @Serializable
    @SerialName("dlc")
    data class Dlc(
        override val titleId: String? = null,
        override val baseTitleId: String? = null,
        override val version: String? = null,
        /** The add-on's own index: the suffix the title ID ends in, as a number. */
        val addOnIndex: Int? = null,
    ) : SwitchContent()

    companion object {
        /** The platforms database's id for the Switch (see [ConsoleSystemDef]). */
        const val SYSTEM_ID = "switch"

        /** The four content-file extensions the platforms database's Switch set covers. */
        val EXTENSIONS = setOf("nsp", "nsz", "xci", "xcz")

        /** A title ID, as the classification reads it: 16 hex characters. */
        private const val TITLE_ID_LENGTH = 16

        /** The last 3 hex characters that say base/update/add-on, so [TITLE_ID_LENGTH] minus the suffix. */
        private const val TITLE_ID_PREFIX_LENGTH = 13

        private const val BASE_SUFFIX = "000"
        private const val UPDATE_SUFFIX = "800"

        private val TITLE_ID_TAG = Regex("""\[(\p{XDigit}{16})\]""")
        private val VERSION_TAG = Regex("""\[[vV](\d+(?:\.\d+)*)\]""")
        private val DLC_TAG = Regex("""\[\s*DLC\s*]""", RegexOption.IGNORE_CASE)

        // The PFS0 read is bounded by real numbers: a PFS0 header is a
        // magic, three u32s, then 24 bytes per entry, then the string
        // table. Real .nsp/.nsz files carry at most a few hundred
        // entries and a few KB of names; anything past these caps is
        // not a table droidtop wants to buffer, and the file is left
        // unclassified (its own row) rather than read wholesale.
        private const val MAX_FILE_ENTRIES = 4096
        private const val MAX_STRING_TABLE_BYTES = 64 * 1024
        private const val MAGIC = "PFS0"
        private const val HEADER_BYTES = 16
        private const val ENTRY_BYTES = 24

        /**
         * What [titleId] says its content is, or null when [titleId] is
         * not a title ID (wrong length, or not hex).
         *
         * The suffix is the last 3 hex characters: a 16-character ID
         * like `0100123456789000` ends in `000` and is the BASE GAME;
         * `0100123456789800` ends in `800` and is that game's UPDATE;
         * anything else (`...001`, `...0D0`) is an ADD-ON whose own
         * index is that suffix as a number. The game all three belong
         * to is the ID with its suffix replaced by `000`.
         */
        fun classifyByTitleId(titleId: String): SwitchContent? {
            if (titleId.length != TITLE_ID_LENGTH || !titleId.all { it.digitToIntOrNull(16) != null }) return null
            val suffix = titleId.substring(TITLE_ID_PREFIX_LENGTH)
            val base = titleId.substring(0, TITLE_ID_PREFIX_LENGTH) + BASE_SUFFIX
            return when (suffix) {
                BASE_SUFFIX -> BaseGame(titleId = titleId, baseTitleId = base)
                UPDATE_SUFFIX -> Update(titleId = titleId, baseTitleId = base)
                else -> Dlc(titleId = titleId, baseTitleId = base, addOnIndex = suffix.toIntOrNull(16))
            }
        }

        /**
         * The `[TitleID]` tag of a filename, or null: the tag is the
         * one part of a scene name that is not prose.
         */
        fun titleIdFromFilename(name: String): String? = TITLE_ID_TAG.find(name)?.groupValues?.get(1)

        /** The `[vN]` tag of a filename, or null. */
        fun versionFromFilename(name: String): String? = VERSION_TAG.find(name)?.groupValues?.get(1)

        /**
         * The title ID a PFS0 (.nsp/.nsz) container's ticket name
         * carries, or null -- from the file TABLE alone, never the
         * ticket's contents (see the class comment for why that line
         * is never crossed). Null for a wrong magic, a table past the
         * caps, a read failure, or a container with no `.tik` entry.
         */
        fun ticketTitleId(file: File): String? = runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val header = ByteArray(HEADER_BYTES)
                raf.readFully(header)
                if (String(header, 0, MAGIC.length, Charsets.US_ASCII) != MAGIC) return@runCatching null
                val entryCount = leInt(header, 4)
                val tableBytes = leInt(header, 8)
                if (entryCount <= 0 || entryCount > MAX_FILE_ENTRIES) return@runCatching null
                if (tableBytes <= 0 || tableBytes > MAX_STRING_TABLE_BYTES) return@runCatching null
                val entries = ByteArray(entryCount * ENTRY_BYTES)
                raf.seek(HEADER_BYTES.toLong())
                raf.readFully(entries)
                val table = ByteArray(tableBytes)
                raf.readFully(table)
                for (index in 0 until entryCount) {
                    val name = table.cStringAt(leInt(entries, index * ENTRY_BYTES + 16)) ?: continue
                    if (!name.endsWith(".tik", ignoreCase = true)) continue
                    val titleId = name.dropLast(4).take(16)
                    if (titleId.length == 16 && titleId.all { it.digitToIntOrNull(16) != null }) return@runCatching titleId
                }
                null
            }
        }.getOrNull()

        /**
         * What [file] is, or null when droidtop has nothing to say:
         * the title ID tag first, then the container's ticket name,
         * then the file's own nature -- a cartridge dump (.xci/.xcz)
         * IS base content -- and last an explicit `[DLC]` tag.
         *
         * Never guesses past what it can see: an .nsp/.nsz with no
         * tag and no readable ticket is null (its own row, the same
         * as before this classification existed), not a pretend base
         * game.
         */
        fun classify(file: File): SwitchContent? {
            if (file.extension.lowercase() !in EXTENSIONS) return null
            val version = versionFromFilename(file.name)
            titleIdFromFilename(file.name)?.let { id ->
                return classifyByTitleId(id)?.withVersion(version)
            }
            val ext = file.extension.lowercase()
            if (ext == "nsp" || ext == "nsz") {
                ticketTitleId(file)?.let { id ->
                    return classifyByTitleId(id)?.withVersion(version)
                }
            }
            if (DLC_TAG.containsMatchIn(file.name)) {
                return Dlc(version = version)
            }
            if (ext == "xci" || ext == "xcz") {
                return BaseGame(version = version)
            }
            return null
        }

        private fun SwitchContent.withVersion(version: String?): SwitchContent = when (this) {
            is BaseGame -> copy(version = version ?: this.version)
            is Update -> copy(version = version ?: this.version)
            is Dlc -> copy(version = version ?: this.version)
        }

        private fun leInt(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 3].toInt() and 0xFF) shl 24)

        /** The NUL-terminated string at [offset] of a string table, or null when the offset is out of it. */
        private fun ByteArray.cStringAt(offset: Int): String? {
            if (offset < 0 || offset >= size) return null
            var end = offset
            while (end < size && this[end] != 0.toByte()) end++
            return decodeToString(offset, end)
        }
    }
}

