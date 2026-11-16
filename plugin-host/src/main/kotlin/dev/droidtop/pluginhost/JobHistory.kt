package dev.droidtop.pluginhost

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import org.json.JSONArray

/**
 * Finished jobs older than the newest [PluginJobsCenter.FINISHED_KEPT] (owner, 2026-11-15, Droidtop/tracker#940: "why does
 * our downloads history seem to be capped at 200? And can we provide filters for that download history"). The newest
 * stay in [PluginJobsCenter]'s live list, which the Downloads page reads on every report; the ones pushed out of it come
 * here, one row each, written once, instead of being forgotten. One SQLite file in droidtop's own storage. Kept until
 * the person clears it, or for the days [keepDays] says. Disk work only: never on the main thread.
 */
object JobHistory {
    private const val FILE = "job-history.db"
    private const val PREFS = "job_history"
    private const val KEY_KEEP_DAYS = "keep_days"
    private const val DAY_MS = 24L * 60 * 60 * 1000

    @Volatile private var open: SQLiteDatabase? = null

    /**
     * How many jobs history holds, kept in memory so the Downloads page can offer Older jobs without reading the disk
     * while it draws. Read at start ([refreshCount]) and after every archive, clear and prune.
     */
    val count = kotlinx.coroutines.flow.MutableStateFlow(0)

    /** Reads [count] from the file. Disk work: call off the main thread. */
    fun refreshCount(context: Context) {
        count.value = if (!exists(context)) 0 else db(context)?.let { android.database.DatabaseUtils.queryNumEntries(it, "jobs").toInt() } ?: 0
    }

    /** Which of [ids] history already holds. */
    fun has(context: Context, ids: List<String>): Set<String> {
        if (ids.isEmpty() || !exists(context)) return emptySet()
        val db = db(context) ?: return emptySet()
        val found = HashSet<String>()
        ids.chunked(500).forEach { chunk ->
            val marks = chunk.joinToString(",") { "?" }
            db.rawQuery("SELECT id FROM jobs WHERE id IN ($marks)", chunk.toTypedArray()).use { cursor ->
                while (cursor.moveToNext()) found += cursor.getString(0)
            }
        }
        return found
    }

    /** The kinds the Downloads page filters by. Pure. */
    enum class Kind(val label: String) {
        DOWNLOADS("Downloads"),
        SCRAPES("Scrapes"),
        LIBRARY("Library"),
        PLUGINS("Plugins"),
        OTHER("Other"),
        ;

        companion object {
            /** Which kind [entry] is: by droidtop's own job kind, else a plugin's job. Pure. */
            fun of(entry: PluginJobsCenter.Entry): Kind {
                val kind = entry.nativeKind ?: return PLUGINS
                return when {
                    // Every kind registered as a download (downloads, store installs, a computer's game copy, fonts).
                    PluginJobsCenter.isDownloadKind(kind) -> DOWNLOADS
                    kind == SCRAPE_KIND -> SCRAPES
                    kind in LIBRARY_KINDS -> LIBRARY
                    else -> OTHER
                }
            }

            // The kinds library-core registers (LibraryScrapeJob.KIND, LibraryRescanJob.KIND, SourceIndexRefresh.KIND),
            // which plugin-host cannot import.
            private const val SCRAPE_KIND = "library_scrape"
            private val LIBRARY_KINDS = setOf("library_rescan", "source-index")
        }
    }

    /** How many days a finished job is kept; 0 is until the person clears it (the default). */
    fun keepDays(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_KEEP_DAYS, 0)

    fun setKeepDays(context: Context, days: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_KEEP_DAYS, days.coerceAtLeast(0)).apply()
    }

    /** Stores [entries] (finished jobs leaving the live list), replacing any earlier copy of the same job. */
    fun archive(context: Context, entries: List<PluginJobsCenter.Entry>) {
        val finished = entries.filter { it.done }
        if (finished.isEmpty()) return
        val db = db(context) ?: return
        db.beginTransaction()
        try {
            finished.forEach { entry ->
                val encoded = JSONArray(PluginJobsCenter.encodeEntries(listOf(entry))).optJSONObject(0)?.toString() ?: return@forEach
                db.insertWithOnConflict(
                    "jobs",
                    null,
                    ContentValues().apply {
                        put("id", entry.jobId)
                        put("kind", Kind.of(entry).name)
                        put("finished", entry.finishedAtMs ?: entry.startedAtMs)
                        put("entry", encoded)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        refreshCount(context)
    }

    /** Up to [limit] archived jobs of [kind] (null: every kind), newest first, from [offset]. */
    fun page(context: Context, kind: Kind?, limit: Int, offset: Int = 0): List<PluginJobsCenter.Entry> {
        if (!exists(context)) return emptyList()
        val db = db(context) ?: return emptyList()
        val where = if (kind == null) "" else "WHERE kind = ?"
        val args = if (kind == null) null else arrayOf(kind.name)
        val rows = JSONArray()
        db.rawQuery("SELECT entry FROM jobs $where ORDER BY finished DESC LIMIT $limit OFFSET $offset", args).use { cursor ->
            while (cursor.moveToNext()) runCatching { rows.put(org.json.JSONObject(cursor.getString(0))) }
        }
        return PluginJobsCenter.decodeEntries(rows.toString(), register = false)
    }

    /** How many archived jobs there are of each kind. */
    fun counts(context: Context): Map<Kind, Int> {
        if (!exists(context)) return emptyMap()
        val db = db(context) ?: return emptyMap()
        val counts = HashMap<Kind, Int>()
        db.rawQuery("SELECT kind, COUNT(*) FROM jobs GROUP BY kind", null).use { cursor ->
            while (cursor.moveToNext()) {
                val kind = runCatching { Kind.valueOf(cursor.getString(0)) }.getOrNull() ?: continue
                counts[kind] = cursor.getInt(1)
            }
        }
        return counts
    }

    /** Forgets the archived jobs of [kind], or every one when it is null. */
    fun clear(context: Context, kind: Kind? = null) {
        if (!exists(context)) return
        val db = db(context) ?: return
        if (kind == null) db.delete("jobs", null, null) else db.delete("jobs", "kind = ?", arrayOf(kind.name))
        refreshCount(context)
    }

    /** Drops what is older than [keepDays] allows, at [nowMs]; nothing when history is kept until cleared. */
    fun prune(context: Context, nowMs: Long = System.currentTimeMillis()) {
        val days = keepDays(context)
        if (days <= 0 || !exists(context)) return
        val db = db(context) ?: return
        db.delete("jobs", "finished < ?", arrayOf((nowMs - days * DAY_MS).toString()))
        refreshCount(context)
    }

    private fun exists(context: Context) = File(context.filesDir, FILE).isFile

    private fun db(context: Context): SQLiteDatabase? {
        open?.let { return it }
        synchronized(this) {
            open?.let { return it }
            val created = runCatching {
                SQLiteDatabase.openOrCreateDatabase(File(context.filesDir, FILE), null).apply {
                    execSQL("CREATE TABLE IF NOT EXISTS jobs (id TEXT PRIMARY KEY, kind TEXT NOT NULL, finished INTEGER NOT NULL, entry TEXT NOT NULL)")
                    execSQL("CREATE INDEX IF NOT EXISTS jobs_finished ON jobs (finished)")
                }
            }.getOrNull()
            open = created
            return created
        }
    }
}
