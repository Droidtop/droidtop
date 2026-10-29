package dev.droidtop.app.settings

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * "Back up droidtop" and "Restore droidtop" (docs/SPEC.md "Data", decided 2026-09-24, Droidtop/tracker#625): one zip
 * archive with what a person did in droidtop, so a new device picks up where the old one was.
 *
 * What it holds: the settings ([settings.json][SETTINGS], the same JSON "Back up settings" wrote), the game records
 * under `files/library/games`, the play log, the person's own databases (play history, favourites, collections, links;
 * scraped metadata and edits), and the list of installed plugins. What it never holds: games, ROMs, downloaded themes,
 * folder grants and plugin approvals, which need the person's consent again, and plugins themselves, which are fetched
 * and verified anew from their sources.
 *
 * A restore applies the settings at once and stages every file in [PENDING]; the next start of droidtop moves them
 * into place ([finishPendingRestore]) before anything opens a database, so nothing is replaced under a running library.
 */
object DroidtopBackup {
    const val FORMAT = 1
    const val MANIFEST = "manifest.json"
    const val SETTINGS = "settings.json"
    const val PLUGINS = "plugins.json"

    /** The databases holding the person's own data, by file name. The scan caches and indexes are rebuilt, never copied. */
    val DATABASES = listOf("droidtop-play-history.db", "droidtop-rom-cache.db")

    /** Where the files of an archive wait for the next start. */
    private const val PENDING = "restore-pending"

    /** The trees of `filesDir` an archive carries, as the archive names them. */
    private val FILE_TREES = listOf("library/games")
    private val FILES = listOf("play-log.json")

    /**
     * Where an archive entry goes, relative to droidtop's own data, or null when it is not one an archive of this
     * format may hold: only the known files and trees, and never a name that climbs out of them. Pure.
     */
    fun destinationOf(name: String): String? {
        if (name.startsWith("/") || name.contains('\\') || name.split('/').any { it == ".." || it == "." }) return null
        if (name == SETTINGS || name == MANIFEST || name == PLUGINS) return null
        if (name.startsWith("databases/")) return name.removePrefix("databases/").takeIf { it in DATABASES }?.let { "databases/$it" }
        if (!name.startsWith("files/")) return null
        val rest = name.removePrefix("files/")
        if (rest in FILES) return name
        return name.takeIf { FILE_TREES.any { tree -> rest.startsWith("$tree/") && rest.length > tree.length + 1 } }
    }

    /** Writes the archive to [uri]: [settings] from the settings screen, the rest read here. Disk work. */
    fun write(context: Context, uri: Uri, settings: JSONObject): String {
        val files = context.filesDir
        val plugins = runCatching { dev.droidtop.pluginhost.PluginStore.installed(context) }.getOrDefault(emptyList())
        var games = 0
        context.contentResolver.openOutputStream(uri)?.use { raw ->
            ZipOutputStream(raw).use { zip ->
                fun put(name: String, bytes: ByteArray) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
                put(
                    MANIFEST,
                    JSONObject().put("format", FORMAT).put("createdAt", System.currentTimeMillis())
                        .put("app", dev.droidtop.app.update.AppSelfUpdate.installedVersionName(context)).toString(2).toByteArray(),
                )
                put(SETTINGS, settings.toString(2).toByteArray())
                put(
                    PLUGINS,
                    JSONArray(plugins.map { JSONObject().put("id", it.manifest.id).put("label", it.manifest.label).put("version", it.manifest.version).put("origin", it.manifest.origin) })
                        .toString(2).toByteArray(),
                )
                for (tree in FILE_TREES) {
                    val root = File(files, tree)
                    root.walkTopDown().filter { it.isFile }.forEach { file ->
                        put("files/$tree/" + file.relativeTo(root).invariantSeparatorsPath, file.readBytes())
                        if (tree == "library/games") games++
                    }
                }
                for (name in FILES) File(files, name).takeIf { it.isFile }?.let { put("files/$name", it.readBytes()) }
                for (name in DATABASES) {
                    checkpoint(context, name)
                    context.getDatabasePath(name).takeIf { it.isFile }?.let { put("databases/$name", it.readBytes()) }
                }
            }
        } ?: error("the file could not be opened")
        return "Backed up droidtop: settings, $games game records, play history, favourites, collections, scraped " +
            "details and a list of ${plugins.size} plugins"
    }

    /** Folds the write-ahead log into [name]'s main file, so the copy is whole. */
    private fun checkpoint(context: Context, name: String) {
        val db = when (name) {
            "droidtop-play-history.db" -> dev.droidtop.library.PlayHistoryDatabase.get(context)
            "droidtop-rom-cache.db" -> dev.droidtop.library.consoles.RomDatabase.get(context)
            else -> return
        }
        runCatching { db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() } }
    }

    /** What reading an archive found: its settings, and how many files wait for the next start. */
    data class Staged(val settings: JSONObject?, val files: Int, val plugins: List<String>)

    /**
     * Reads the archive at [uri]: its settings are handed back to be applied now, and every file it may hold is staged
     * for the next start. An entry it may not hold is skipped. Disk work.
     */
    fun stage(context: Context, uri: Uri): Staged {
        val pending = File(context.filesDir, PENDING)
        pending.deleteRecursively()
        var settings: JSONObject? = null
        var manifest: JSONObject? = null
        var plugins = emptyList<String>()
        var files = 0
        context.contentResolver.openInputStream(uri)?.use { raw ->
            ZipInputStream(raw).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    when (entry.name) {
                        MANIFEST -> manifest = JSONObject(zip.readBytes().decodeToString())
                        SETTINGS -> settings = JSONObject(zip.readBytes().decodeToString())
                        PLUGINS -> plugins = JSONArray(zip.readBytes().decodeToString()).let { list ->
                            (0 until list.length()).mapNotNull { list.optJSONObject(it)?.optString("label")?.takeIf { l -> l.isNotBlank() } }
                        }
                        else -> {
                            val to = destinationOf(entry.name) ?: continue
                            File(pending, to).apply { parentFile?.mkdirs() }.outputStream().use { zip.copyTo(it) }
                            files++
                        }
                    }
                }
            }
        } ?: error("the file could not be opened")
        val format = manifest?.optInt("format", 0) ?: 0
        check(format in 1..FORMAT) { "not a droidtop backup this version can read" }
        return Staged(settings, files, plugins)
    }

    /**
     * Moves a staged restore into place: the game records' tree and the databases are replaced whole, the play log
     * replaced. Called first thing at start (DroidtopApplication), before any database is opened. Disk work, small.
     */
    fun finishPendingRestore(context: Context) {
        val pending = File(context.filesDir, PENDING)
        if (!pending.isDirectory) return
        runCatching {
            for (tree in FILE_TREES) {
                val staged = File(pending, "files/$tree")
                if (!staged.isDirectory) continue
                val target = File(context.filesDir, tree)
                target.deleteRecursively()
                target.parentFile?.mkdirs()
                staged.copyRecursively(target, overwrite = true)
            }
            for (name in FILES) {
                File(pending, "files/$name").takeIf { it.isFile }?.copyTo(File(context.filesDir, name), overwrite = true)
            }
            for (name in DATABASES) {
                val staged = File(pending, "databases/$name").takeIf { it.isFile } ?: continue
                val target = context.getDatabasePath(name)
                target.parentFile?.mkdirs()
                for (suffix in listOf("-wal", "-shm", "-journal")) File(target.path + suffix).delete()
                staged.copyTo(target, overwrite = true)
            }
        }.onFailure { android.util.Log.w("droidtop.backup", "Finishing a restore failed", it) }
        pending.deleteRecursively()
    }
}
