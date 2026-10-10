package dev.droidtop.library

import java.io.File

/**
 * Flashpoint (BlueMaxima's web game archive) met in a game folder
 * (docs/SPEC.md 7g, "Flashpoint"): one launcher, plus each game it has
 * downloaded into `Data/Games`, named from the launcher's own database
 * (`Data/flashpoint.sqlite`, opened read-only). Its `FPSoftware` folder holds
 * the players and browser plugins it runs games with, which are no games of
 * their own. The desktop agent reads the same shape.
 *
 * Files are read, never written. Disk work; never on the main thread.
 */
object FlashpointInstall {

    /** One downloaded game: Flashpoint's id for it, its title, and the `<id>-<timestamp>.zip` that holds it. */
    data class Download(val id: String, val title: String, val file: File) {
        val sizeBytes: Long get() = file.length()
    }

    /** Whether [folder] is a Flashpoint install: `version.txt` naming Flashpoint beside an `FPSoftware` folder. */
    fun isInstall(folder: File): Boolean {
        if (!File(folder, "FPSoftware").isDirectory) return false
        val first = runCatching { File(folder, "version.txt").useLines { it.firstOrNull() } }.getOrNull() ?: return false
        return first.contains("Flashpoint", ignoreCase = true)
    }

    /** A download's file is `<game id>-<timestamp>.zip`; the id is a UUID. Null for any other name. */
    internal fun gameId(fileName: String): String? {
        val id = fileName.take(36)
        if (id.length != 36 || !fileName.endsWith(".zip", ignoreCase = true)) return null
        val uuid = id.withIndex().all { (i, c) -> if (i in UUID_DASHES) c == '-' else c.isDigit() || c.lowercaseChar() in 'a'..'f' }
        return id.takeIf { uuid }
    }

    private val UUID_DASHES = setOf(8, 13, 18, 23)

    /**
     * The games [folder] has downloaded, in file name order. A title the
     * database cannot give (no database, one that cannot be opened) is the
     * id, so the game is still listed. [titleOf] is how a title is read;
     * the default opens the launcher's database read-only once.
     */
    fun downloads(
        folder: File,
        titleOf: (databaseFile: File, ids: List<String>) -> Map<String, String> = { file, ids -> readTitles(file, ids) },
    ): List<Download> {
        if (!isInstall(folder)) return emptyList()
        val files = File(folder, "Data/Games").listFiles { f -> f.isFile && gameId(f.name) != null }?.sortedBy { it.name } ?: return emptyList()
        val ids = files.mapNotNull { gameId(it.name) }
        val titles = runCatching { titleOf(File(folder, "Data/flashpoint.sqlite"), ids) }.getOrDefault(emptyMap())
        return files.mapNotNull { file ->
            val id = gameId(file.name) ?: return@mapNotNull null
            Download(id, titles[id]?.takeIf { it.isNotBlank() } ?: id, file)
        }
    }

    /** Titles from `game.title` by id, from the launcher's database opened read-only; empty when it cannot be. */
    private fun readTitles(databaseFile: File, ids: List<String>): Map<String, String> {
        if (!databaseFile.isFile) return emptyMap()
        val db = android.database.sqlite.SQLiteDatabase.openDatabase(
            databaseFile.path,
            null,
            android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
        )
        return db.use {
            buildMap {
                for (id in ids) {
                    it.rawQuery("SELECT title FROM game WHERE id = ?", arrayOf(id)).use { cursor ->
                        if (cursor.moveToFirst()) put(id, cursor.getString(0).orEmpty())
                    }
                }
            }
        }
    }
}
