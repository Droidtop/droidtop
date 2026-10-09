package org.pocketworkstation.pckeyboard

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

data class EmojiEntry(val emoji: String, val name: String, val keywords: List<String>)

data class EmojiGroup(val id: String, val entries: List<EmojiEntry>)

/**
 * The emoji the keyboard offers, with the names and keywords search reads (docs/SPEC.md 6a, "Emoji panel and
 * search", Droidtop/tracker#342). The data is `assets/emoji/en.txt`, FlorisBoard's generated CLDR v48 list
 * (Apache-2.0 app, Unicode data; see `assets/emoji/SOURCE.txt`), parsed here as it is: `[group]` headers, then
 * `emoji;name;keyword|keyword` lines. A line starting with a tab is a skin-tone variant of the line above and is
 * left out of the grid and of search. No Android classes, so it is tested on the JVM.
 */
class EmojiCatalog(val groups: List<EmojiGroup>) {
    private val all: List<EmojiEntry> = groups.flatMap { it.entries }

    /**
     * Entries matching [query], best first, at most [limit]. Every word of the query has to start a word of the
     * name (best: the whole name equals or starts with the query) or, failing that, of the name or a keyword.
     */
    fun search(query: String, limit: Int = 40): List<EmojiEntry> {
        val q = query.trim().lowercase()
        val tokens = q.split(' ', '\t').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return emptyList()
        val scored = ArrayList<Pair<Int, EmojiEntry>>()
        for (entry in all) {
            val name = entry.name.lowercase()
            val nameWords = name.split(' ', '-', ':')
            val score = when {
                name == q -> 0
                name.startsWith(q) -> 1
                tokens.all { token -> nameWords.any { it.startsWith(token) } } -> 2
                tokens.all { token -> nameWords.any { it.startsWith(token) } || entry.keywords.any { it.lowercase().startsWith(token) } } -> 3
                else -> -1
            }
            if (score >= 0) scored += score to entry
        }
        // sortedBy is stable: within a score the data's own order (the Unicode order) stays.
        return scored.sortedBy { it.first }.take(limit).map { it.second }
    }

    companion object {
        fun parse(text: String): EmojiCatalog {
            val groups = ArrayList<EmojiGroup>()
            var id: String? = null
            var entries = ArrayList<EmojiEntry>()
            fun close() {
                id?.let { if (entries.isNotEmpty()) groups += EmojiGroup(it, entries) }
                entries = ArrayList()
            }
            for (line in text.lineSequence()) {
                if (line.isBlank() || line.startsWith("#") || line.startsWith("\t")) continue
                if (line.startsWith("[") && line.endsWith("]")) {
                    close()
                    id = line.substring(1, line.length - 1)
                    continue
                }
                val parts = line.split(';', limit = 3)
                if (parts.size < 2 || parts[0].isEmpty() || parts[1].isEmpty()) continue
                val keywords = if (parts.size == 3 && parts[2].isNotEmpty()) parts[2].split('|') else emptyList()
                entries.add(EmojiEntry(parts[0], parts[1], keywords))
            }
            close()
            return EmojiCatalog(groups)
        }
    }
}

/** The most recently used emoji, newest first (Droidtop/tracker#342). One per line when saved. */
object EmojiRecents {
    const val MAX = 30

    fun push(recent: List<String>, emoji: String, max: Int = MAX): List<String> = (listOf(emoji) + recent.filter { it != emoji }).take(max)

    fun encode(recent: List<String>): String = recent.joinToString("\n")

    fun decode(saved: String?): List<String> = saved.orEmpty().split('\n').filter { it.isNotEmpty() }.take(MAX)
}

/**
 * Loads the emoji list once per process: the asset is read and parsed on a background thread, the callback runs on
 * the main thread (at once when it is already loaded). No file work on the main thread.
 */
object EmojiAssets {
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "hk-emoji-load").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var loaded: EmojiCatalog? = null
    private var loading = false
    private val waiting = ArrayList<(EmojiCatalog) -> Unit>()

    /** The catalog if it is loaded already. */
    fun current(): EmojiCatalog? = loaded

    fun load(context: Context, onLoaded: (EmojiCatalog) -> Unit) {
        loaded?.let {
            onLoaded(it)
            return
        }
        waiting += onLoaded
        if (loading) return
        loading = true
        val assets = context.applicationContext.assets
        io.execute {
            val catalog = try {
                assets.open("emoji/en.txt").bufferedReader().use { EmojiCatalog.parse(it.readText()) }
            } catch (e: java.io.IOException) {
                EmojiCatalog(emptyList())
            }
            main.post {
                loaded = catalog
                loading = false
                val callbacks = ArrayList(waiting)
                waiting.clear()
                callbacks.forEach { it(catalog) }
            }
        }
    }
}

/**
 * Keys the keyboard would send to the editor, taken by a tool instead while it is open (the emoji search types its
 * query on the keyboard itself, which has no text field of its own to focus). Offered by the input method's
 * `onKey` and `onText` and by the hardware-key listener; main thread only.
 */
object KeyboardCapture {
    interface Target {
        /** True when the key was taken. Key codes are the keyboard's own: a character, or -5 for Delete. */
        fun key(code: Int): Boolean

        fun text(chars: CharSequence): Boolean
    }

    private var target: Target? = null

    fun set(value: Target) {
        target = value
    }

    fun clear(value: Target) {
        if (target === value) target = null
    }

    @JvmStatic
    fun offerKey(code: Int): Boolean = target?.key(code) ?: false

    @JvmStatic
    fun offerText(chars: CharSequence): Boolean = target?.text(chars) ?: false
}
