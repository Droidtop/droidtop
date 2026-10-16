package dev.droidtop.runtime.launcherimport

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * The parts of a Nova (`.novabackup`) or Apex (`.apexbackup`) backup an import reads (Droidtop/tracker#564; formats in
 * the tracker's coordination/standard/launcher-import-formats.md). The file is untrusted input the person picked:
 * entries are read by exact name only, never extracted by path, each and in total capped, and nothing is run.
 */
data class BackupFiles(
    val source: SourceLauncher,
    /** The launcher's preferences `<map>` XML (Nova `nova.xml` or the legacy file; Apex `apex_settings.bak`). */
    val prefsXml: String?,
    /** The launcher's SQLite database bytes (Nova `nova.db` or the legacy `launcher.db`; Apex `apex_data.bak`). */
    val database: ByteArray?,
    /** Apex's `DrawerGroups.bak` and `SwipeActions.bak` (preferences XML), Pro only. */
    val drawerGroupsXml: String? = null,
    val swipeActionsXml: String? = null,
    /** Every entry name the file had, for the summary and the rig check. */
    val entries: List<String> = emptyList(),
)

object BackupReader {
    /** One entry's cap: Apex itself refuses files of 10 MB or more. */
    const val MAX_ENTRY_BYTES = 32L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 64L * 1024 * 1024

    class NotABackup(message: String) : Exception(message)

    /**
     * Reads a backup file. A ZIP is read by entry name. An Apex backup of the older kind, a loose preferences
     * file or a loose SQLite file, is accepted too (Apex's own restore takes them). A ZIP that holds a
     * `.novabackup` (mail apps zip attachments) is unwrapped once.
     */
    fun read(input: InputStream, nameHint: String? = null): BackupFiles {
        val bytes = readCapped(input, MAX_TOTAL_BYTES)
        return read(bytes, nameHint, depth = 0)
    }

    private fun read(bytes: ByteArray, nameHint: String?, depth: Int): BackupFiles {
        if (bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            val entries = zipEntries(bytes)
            entries.entries.firstOrNull { it.key.endsWith(".novabackup") }?.takeIf { depth == 0 && "nova.db" !in entries }?.let {
                return read(it.value, it.key, depth + 1)
            }
            val names = entries.keys.toList()
            fun text(name: String) = entries[name]?.toString(Charsets.UTF_8)
            return when {
                "nova.db" in entries || "nova.xml" in entries || "com.teslacoilsw.launcher_preferences.xml" in entries -> BackupFiles(
                    SourceLauncher.NOVA,
                    prefsXml = text("nova.xml") ?: text("com.teslacoilsw.launcher_preferences.xml"),
                    database = entries["nova.db"] ?: entries["launcher.db"],
                    entries = names,
                )
                "apex_data.bak" in entries || "apex_settings.bak" in entries -> BackupFiles(
                    SourceLauncher.APEX,
                    prefsXml = text("apex_settings.bak"),
                    database = entries["apex_data.bak"],
                    drawerGroupsXml = text("DrawerGroups.bak"),
                    swipeActionsXml = text("SwipeActions.bak"),
                    entries = names,
                )
                else -> throw NotABackup("This ZIP has neither a Nova nor an Apex backup in it (it holds ${names.take(6).joinToString()})")
            }
        }
        // Apex's loose files: a preferences file or a SQLite database on its own.
        val head = String(bytes, 0, minOf(bytes.size, 64), Charsets.ISO_8859_1)
        if (head.startsWith("SQLite format 3")) {
            return BackupFiles(SourceLauncher.APEX, prefsXml = null, database = bytes, entries = listOfNotNull(nameHint))
        }
        if (head.trimStart().startsWith("<?xml") || head.trimStart().startsWith("<map")) {
            return BackupFiles(SourceLauncher.APEX, prefsXml = bytes.toString(Charsets.UTF_8), database = null, entries = listOfNotNull(nameHint))
        }
        throw NotABackup("This is not a Nova or Apex backup file")
    }

    /** Every entry by name, each at most [MAX_ENTRY_BYTES]; a directory or a repeated name is skipped. */
    private fun zipEntries(bytes: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                // Only the file's own name: a path inside the ZIP is never used for anything.
                val name = entry.name.substringAfterLast('/')
                if (entry.isDirectory || name.isEmpty() || name in out) continue
                val data = readCapped(zip, MAX_ENTRY_BYTES)
                total += data.size
                if (total > MAX_TOTAL_BYTES) throw NotABackup("This backup is larger than an import reads")
                out[name] = data
            }
        }
        return out
    }

    private fun readCapped(input: InputStream, cap: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var read = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            read += n
            if (read > cap) throw NotABackup("This backup is larger than an import reads")
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}
