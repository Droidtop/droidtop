package org.pocketworkstation.pckeyboard

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * One emoji, with its skin-tone [variants] in the data's order (Droidtop/tracker#903 H4): five for one person (light,
 * medium-light, medium, medium-dark, dark), 25 for two people (the first person's tone times the second's), more for a
 * few groups; none for an emoji without tones.
 */
data class EmojiEntry(val emoji: String, val name: String, val keywords: List<String>, val variants: List<String> = emptyList()) {
    // What search compares, lower-cased and split once when the entry is made (on the loader thread), not on every
    // key typed into the search (Droidtop/tracker#772).
    internal val lowerName: String = name.lowercase()
    internal val nameWords: List<String> = lowerName.split(' ', '-', ':')
    internal val lowerKeywords: List<String> = keywords.map { it.lowercase() }
}

data class EmojiGroup(val id: String, val entries: List<EmojiEntry>)

/**
 * The emoji the keyboard offers, with the names and keywords search reads (docs/SPEC.md 6a, "Emoji panel and
 * search", Droidtop/tracker#342). The data is `assets/emoji/en.txt`, FlorisBoard's generated CLDR v48 list
 * (Apache-2.0 app, Unicode data; see `assets/emoji/SOURCE.txt`), parsed here as it is: `[group]` headers, then
 * `emoji;name;keyword|keyword` lines. A line starting with a tab is a skin-tone variant of the line above: kept on that
 * entry ([EmojiEntry.variants]), not shown in the grid or search on its own. No Android classes, so it is tested on
 * the JVM.
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
            val nameWords = entry.nameWords
            val score = when {
                entry.lowerName == q -> 0
                entry.lowerName.startsWith(q) -> 1
                tokens.all { token -> nameWords.any { it.startsWith(token) } } -> 2
                tokens.all { token -> nameWords.any { it.startsWith(token) } || entry.lowerKeywords.any { it.startsWith(token) } } -> 3
                else -> -1
            }
            if (score >= 0) scored += score to entry
        }
        // sortedBy is stable: within a score the data's own order (the Unicode order) stays.
        return scored.sortedBy { it.first }.take(limit).map { it.second }
    }

    /**
     * Only the entries [canDraw] says this device can draw, a group left out when none of its entries can be. The list
     * is CLDR v48, newer than the emoji font of most Android versions, so without it the grid showed boxes for the
     * newest emoji (rig 2026-11-04, AVD droidtop-rig4-dual, Droidtop/tracker#342: two in the Smileys grid).
     */
    fun drawable(canDraw: (String) -> Boolean): EmojiCatalog =
        EmojiCatalog(
            groups.mapNotNull { group ->
                group.entries.filter { canDraw(it.emoji) }
                    // A tone the device's font can't draw is left out; an emoji keeps its tones only while all of
                    // them can be drawn, so a tone index always means the same tone.
                    .map { entry -> if (entry.variants.all(canDraw)) entry else entry.copy(variants = emptyList()) }
                    .takeIf { it.isNotEmpty() }?.let { EmojiGroup(group.id, it) }
            },
        )

    private val byEmoji: Map<String, EmojiEntry> by lazy {
        HashMap<String, EmojiEntry>().also { map -> all.forEach { entry -> map[entry.emoji] = entry; entry.variants.forEach { map[it] = entry } } }
    }

    /** The entry an emoji is, or is a tone of (a recent can be either). */
    fun entryOf(emoji: String): EmojiEntry? = byEmoji[emoji]

    companion object {
        fun parse(text: String): EmojiCatalog {
            val groups = ArrayList<EmojiGroup>()
            var id: String? = null
            var entries = ArrayList<EmojiEntry>()
            var tones = ArrayList<String>()
            fun closeEntry() {
                if (tones.isNotEmpty() && entries.isNotEmpty()) entries[entries.size - 1] = entries.last().copy(variants = tones)
                tones = ArrayList()
            }
            fun close() {
                closeEntry()
                id?.let { if (entries.isNotEmpty()) groups += EmojiGroup(it, entries) }
                entries = ArrayList()
            }
            for (line in text.lineSequence()) {
                if (line.startsWith("\t")) {
                    // A skin-tone variant of the entry above (Droidtop/tracker#903 H4): kept on it, not in the grid.
                    line.trimStart('\t').substringBefore(';').takeIf { it.isNotEmpty() && entries.isNotEmpty() }?.let { tones += it }
                    continue
                }
                if (line.isBlank() || line.startsWith("#")) continue
                closeEntry()
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

/**
 * The skin tone the grid and search show (Droidtop/tracker#903 H4): 0 is the default (yellow), 1 to 5 light to dark.
 * Picking a tone from an emoji's long-press row keeps it for every emoji that has it, as Gboard does. Pure.
 */
object EmojiTones {
    const val PREF = "pref_emoji_skin_tone"
    const val COUNT = 5

    /** [entry] in [tone]: its one-person variant, or the two-person one where both have it; else the emoji itself. */
    fun shown(entry: EmojiEntry, tone: Int): String {
        if (tone !in 1..COUNT) return entry.emoji
        return when (entry.variants.size) {
            COUNT -> entry.variants[tone - 1]
            COUNT * COUNT -> entry.variants[(tone - 1) * (COUNT + 1)]
            else -> entry.emoji
        }
    }

    /** The tone a pick from [entry]'s row means: 0 for the plain emoji, 1 to 5 for a one-person tone, else null. */
    fun toneOfPick(entry: EmojiEntry, picked: String): Int? = when {
        picked == entry.emoji -> 0
        entry.variants.size == COUNT -> entry.variants.indexOf(picked).takeIf { it >= 0 }?.plus(1)
        entry.variants.size == COUNT * COUNT -> entry.variants.indexOf(picked).takeIf { it >= 0 && it % (COUNT + 1) == 0 }?.let { it / (COUNT + 1) + 1 }
        else -> null
    }
}

/**
 * Kaomoji (Droidtop/tracker#903 H4): `assets/kaomoji/kaomoji.txt`, AnySoftKeyboard's list (Apache-2.0; see SOURCE.txt
 * there), `[Group]` lines then one kaomoji per line, `#` for comments. Each becomes an [EmojiEntry] named "kaomoji
 * <group>", so emoji search finds them by their group ("joy", "love"), in one [GROUP] tab. Pure.
 */
object Kaomoji {
    const val GROUP = "kaomoji"

    fun parse(text: String): EmojiGroup {
        val entries = ArrayList<EmojiEntry>()
        var group = ""
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            if (line.startsWith("[") && line.endsWith("]")) {
                group = line.substring(1, line.length - 1).lowercase()
                continue
            }
            entries += EmojiEntry(line, if (group.isEmpty()) GROUP else "$GROUP $group", listOfNotNull(group.ifEmpty { null }))
        }
        return EmojiGroup(GROUP, entries)
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
    private val io = KeyboardThreads.single("hk-emoji-load")
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
                // Asked of the system's font once, here on the loader thread: the grid and search offer only what draws.
                val paint = android.graphics.Paint()
                val emoji = assets.open("emoji/en.txt").bufferedReader().use { EmojiCatalog.parse(it.readText()) }.drawable(paint::hasGlyph)
                // Kaomoji after the font check: they are several characters, which hasGlyph never answers yes for.
                val kaomoji = runCatching { assets.open("kaomoji/kaomoji.txt").bufferedReader().use { Kaomoji.parse(it.readText()) } }
                    .getOrNull()?.takeIf { it.entries.isNotEmpty() }
                if (kaomoji == null) emoji else EmojiCatalog(emoji.groups + kaomoji)
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
