package dev.droidtop.library

import java.io.File
import java.security.MessageDigest

/**
 * The numeric id of each PC game folder, kept in droidtop's own storage
 * and never in the user's game folders (docs/SPEC.md 7g, tracker#269).
 *
 * The vendored gamenative scanner gives every game folder an id and by
 * default remembers it in a `.gamenative` file inside the folder.
 * droidtop installs this as the scanner's id store instead, so a scan
 * leaves the user's folders byte for byte as it found them.
 *
 * Without a file in the folder, identity has to come from the folder
 * itself, in this order:
 *  1. the same path (the usual case, one map lookup);
 *  2. a remembered folder whose path no longer exists and which is the
 *     same filesystem object ([stat]: device and inode survive a rename
 *     inside one storage volume) or has the same content shape
 *     ([fingerprint]: the relative paths and sizes of its files, which
 *     survives a move across volumes). That is a renamed or moved game
 *     and keeps its id, so its playtime, scraped art and settings stay;
 *  3. a `.gamenative` file left by an earlier build or by gamenative-tux
 *     itself, READ through [legacyRead] only: it is adopted into this
 *     store and never written back or deleted.
 *
 * A folder that matches none of these is new and gets no id here; the
 * scanner then makes one and calls [remember].
 *
 * Nothing here touches the disk per game in list rendering: the map is
 * read once, held in memory, and the file is rewritten (atomically) only
 * when a folder is added or rebound.
 */
class GameFolderIds(
    private val store: File,
    /** An id a previous `.gamenative` file holds, or null. Must not write. */
    private val legacyRead: (File) -> Int? = { null },
    /** `device:inode` of a folder, or null where the platform cannot say. */
    private val stat: (File) -> String? = { null },
) {
    private data class Record(val id: Int, val stat: String?, val fingerprint: String?, val path: String)

    private val byPath = LinkedHashMap<String, Record>()
    private var loaded = false

    @Synchronized
    fun idFor(folder: File): Int? {
        load()
        val path = folder.absolutePath
        byPath[path]?.let { return it.id }
        movedFrom(folder)?.let { old ->
            byPath.remove(old.path)
            byPath[path] = old.copy(path = path, stat = stat(folder) ?: old.stat)
            save()
            return old.id
        }
        val legacy = legacyRead(folder) ?: return null
        // An id another live folder already holds is a copy of that
        // folder (its .gamenative came along); the scanner decides, so
        // nothing is adopted here.
        if (byPath.values.any { it.id == legacy && File(it.path).isDirectory }) return legacy
        byPath[path] = record(legacy, folder)
        save()
        return legacy
    }

    @Synchronized
    fun remember(folder: File, id: Int) {
        load()
        byPath[folder.absolutePath] = record(id, folder)
        save()
    }

    /** The remembered folder that [folder] is the renamed or moved form of. */
    private fun movedFrom(folder: File): Record? {
        val orphans = byPath.values.filter { !File(it.path).isDirectory }
        if (orphans.isEmpty()) return null
        val here = stat(folder)
        if (here != null) orphans.firstOrNull { it.stat == here }?.let { return it }
        val shape = fingerprint(folder) ?: return null
        return orphans.firstOrNull { it.fingerprint == shape }
    }

    private fun record(id: Int, folder: File) =
        Record(id, stat(folder), fingerprint(folder), folder.absolutePath)

    private fun load() {
        if (loaded) return
        loaded = true
        val lines = runCatching { store.readLines() }.getOrDefault(emptyList())
        for (line in lines) {
            val parts = line.split(TAB)
            if (parts.size != 4) continue
            val id = parts[0].toIntOrNull() ?: continue
            val path = unescape(parts[3])
            byPath[path] = Record(id, parts[1].ifEmpty { null }, parts[2].ifEmpty { null }, path)
        }
    }

    private fun save() {
        runCatching {
            store.parentFile?.mkdirs()
            val tmp = File(store.parentFile, store.name + ".tmp")
            tmp.writeText(
                byPath.values.joinToString(NL, postfix = NL) {
                    listOf(it.id.toString(), it.stat.orEmpty(), it.fingerprint.orEmpty(), escape(it.path)).joinToString(TAB)
                },
            )
            if (!tmp.renameTo(store)) {
                store.delete()
                tmp.renameTo(store)
            }
        }
    }

    companion object {
        private const val MAX_FILES = 200
        private const val MAX_DEPTH = 3
        private val TAB = Char(9).toString()
        private val NL = Char(10).toString()

        // Percent-encoding for the three characters the line format cannot hold.
        private fun escape(s: String) =
            s.replace("%", "%25").replace(Char(9).toString(), "%09").replace(Char(10).toString(), "%0A")

        private fun unescape(s: String) =
            s.replace("%0A", Char(10).toString()).replace("%09", Char(9).toString()).replace("%25", "%")

        /**
         * The content shape of a game folder: its files' relative paths and
         * sizes, hidden files left out, bounded in count and depth. Null when
         * the folder holds no file at all, so an empty folder never matches
         * another empty folder.
         */
        fun fingerprint(folder: File): String? {
            val entries = ArrayList<String>()
            fun walk(dir: File, depth: Int) {
                val children = (dir.listFiles() ?: return).sortedBy { it.name }
                for (child in children) {
                    if (entries.size >= MAX_FILES) return
                    if (child.name.startsWith(".")) continue
                    if (child.isFile) {
                        entries += child.toRelativeString(folder).replace(File.separatorChar, '/') + ":" + child.length()
                    } else if (child.isDirectory && depth < MAX_DEPTH) {
                        walk(child, depth + 1)
                    }
                }
            }
            walk(folder, 1)
            if (entries.isEmpty()) return null
            val digest = MessageDigest.getInstance("SHA-1").digest(entries.joinToString(NL).toByteArray())
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
