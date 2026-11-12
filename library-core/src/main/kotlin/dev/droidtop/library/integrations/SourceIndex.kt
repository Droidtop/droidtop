package dev.droidtop.library.integrations

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/**
 * Each source's own index of what it has (owner, 2026-11-12, Droidtop/tracker#937: "per-source indexes that are built
 * dynamically from searches", "indexes are per-source. This is because sources can be individually toggled"). Typing
 * searches these and the library only; the live sources are asked on a press. Every live search and featured list a
 * source answers is added to that source's index, and a source that publishes a whole list fills it in bulk on its
 * refresh. One SQLite file per source (`source-index/<id>.db`) in droidtop's own storage: a source turned off is simply
 * not read, and removing its plugin removes its file ([forget]). A source opts out with `"index": false` on its
 * `library.sources` entry and is then searched only on the press. Disk work: never on the main thread.
 */
object SourceIndex {
    /** The most entries one source keeps; the oldest seen go first. */
    const val MAX_ENTRIES = 100_000

    private const val DIR = "source-index"
    private val open = ConcurrentHashMap<String, SQLiteDatabase>()

    /** Whether [record]'s source lets droidtop keep an index of it (its `library.sources` entry has no `"index": false`). */
    fun allowed(record: dev.droidtop.pluginhost.PluginRecord): Boolean =
        record.manifest.v2.provides.filter { it.point == "library.sources" }.none { point ->
            runCatching { JSONObject(point.extra).optBoolean("index", true) }.getOrDefault(true).not()
        }

    /**
     * Adds what [sourceId] answered ([data], a `library.sources` reply with its `results`) to its index: each result as
     * the source sent it, so the index is read back through the same parser ([SourceResultProtocol]) as a live answer.
     */
    fun record(context: Context, sourceId: String, data: JSONObject, now: Long = System.currentTimeMillis()) {
        val results = data.optJSONArray("results") ?: return
        if (results.length() == 0) return
        val db = db(context, sourceId) ?: return
        db.beginTransaction()
        try {
            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                val title = item.optString("title").trim().takeIf { it.isNotEmpty() } ?: continue
                val id = item.optString("id").trim().ifEmpty { title }
                db.insertWithOnConflict(
                    "items",
                    null,
                    ContentValues().apply {
                        put("id", id)
                        put("fold", fold(title))
                        put("platform", item.optString("platform").takeIf { it.isNotBlank() })
                        put("item", item.toString())
                        put("seen", now)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
            db.execSQL(
                "DELETE FROM items WHERE id IN (SELECT id FROM items ORDER BY seen DESC LIMIT -1 OFFSET $MAX_ENTRIES)",
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * What [sourceId]'s index has for [query] (every word of it in the title, in any order), for [platform] or any,
     * newest seen first, at most [limit]: a `library.sources` reply ({results}) as the source itself would answer.
     */
    fun search(context: Context, sourceId: String, query: String, platform: String?, limit: Int): JSONObject {
        val words = fold(query).split(' ').filter { it.isNotEmpty() }
        val empty = JSONObject().put("results", JSONArray())
        if (words.isEmpty() || !File(dir(context), fileName(sourceId)).isFile) return empty
        val db = db(context, sourceId) ?: return empty
        val where = buildList {
            words.forEach { add("fold LIKE ? ESCAPE '\\'") }
            if (!platform.isNullOrBlank()) add("(platform IS NULL OR platform = ?)")
        }.joinToString(" AND ")
        val args = words.map { "%${it.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")}%" } +
            listOfNotNull(platform?.takeIf { it.isNotBlank() })
        val results = JSONArray()
        db.rawQuery("SELECT item FROM items WHERE $where ORDER BY seen DESC LIMIT $limit", args.toTypedArray()).use { cursor ->
            while (cursor.moveToNext()) runCatching { results.put(JSONObject(cursor.getString(0))) }
        }
        return JSONObject().put("results", results)
    }

    /** When [sourceId]'s whole list was last read in ([SourceIndexRefresh]); null when never. */
    fun refreshedAt(context: Context, sourceId: String): Long? {
        if (!File(dir(context), fileName(sourceId)).isFile) return null
        val db = db(context, sourceId) ?: return null
        return db.rawQuery("SELECT v FROM meta WHERE k = 'refreshed'", null).use { if (it.moveToFirst()) it.getString(0).toLongOrNull() else null }
    }

    /** Records that [sourceId]'s whole list was read in at [at]. */
    fun markRefreshed(context: Context, sourceId: String, at: Long) {
        val db = db(context, sourceId) ?: return
        db.insertWithOnConflict("meta", null, ContentValues().apply { put("k", "refreshed"); put("v", at.toString()) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** How many entries [sourceId]'s index has; 0 when it has none yet. */
    fun size(context: Context, sourceId: String): Long {
        if (!File(dir(context), fileName(sourceId)).isFile) return 0
        val db = db(context, sourceId) ?: return 0
        return android.database.DatabaseUtils.queryNumEntries(db, "items")
    }

    /** Removes the index of the source plugin [pluginId] is ([AcquireContentSource.Plugin.id]): it was uninstalled. */
    fun forgetPlugin(context: Context, pluginId: String) = forget(context, "plugin_$pluginId")

    /** Removes [sourceId]'s index: its plugin was removed, or it opted out. */
    fun forget(context: Context, sourceId: String) {
        open.remove(sourceId)?.close()
        File(dir(context), fileName(sourceId)).let { SQLiteDatabase.deleteDatabase(it) }
    }

    /** A title as the index compares it: no accents, lower case, words separated by single spaces. Pure. */
    fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()

    /** The file name for [sourceId]: its id with anything but letters, digits, dot, dash and underscore made `_`. Pure. */
    internal fun fileName(sourceId: String): String = sourceId.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".db"

    private fun dir(context: Context) = File(context.filesDir, DIR)

    private fun db(context: Context, sourceId: String): SQLiteDatabase? = open.getOrPut(sourceId) {
        val file = File(dir(context).apply { mkdirs() }, fileName(sourceId))
        runCatching {
            SQLiteDatabase.openOrCreateDatabase(file, null).apply {
                execSQL("CREATE TABLE IF NOT EXISTS items (id TEXT PRIMARY KEY, fold TEXT NOT NULL, platform TEXT, item TEXT NOT NULL, seen INTEGER NOT NULL)")
                execSQL("CREATE INDEX IF NOT EXISTS items_seen ON items (seen)")
                execSQL("CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT NOT NULL)")
            }
        }.getOrNull() ?: return null
    }
}
