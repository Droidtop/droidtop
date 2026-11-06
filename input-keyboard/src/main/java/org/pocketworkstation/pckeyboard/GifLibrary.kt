/*
 * GIFs and stickers from the person's own folders (docs/SPEC.md 6a, "GIFs and stickers", Droidtop/tracker#903 H5).
 *
 * Parts of this file are ported from CleverKeys' offline GIF panel, https://github.com/tribixbite/CleverKeys at
 * c8cbb74b94689d7b4eb27392a8e653cb93b28e6f, GPL-3.0 (Copyright (C) tribixbite and contributors), which droidtop's
 * GPL-3.0 allows:
 *  - [GifInsertPolicy] is CleverKeys' `gif/GifInsertPolicy.kt`: its `editorAcceptsMime` unchanged, its delivery ladder
 *    cut down to what a local file can do (no Giphy URL, no clipboard copy: a file path is never typed);
 *  - [GifSearch.queryWords] follows `GifDatabase.sanitizeFtsQuery` (one alphabet for the index and the query, every
 *    word a prefix, all words required), widened from a-z0-9 to every letter and digit so a name in any script is
 *    found; the compound-word fallback in [GifSearch.rank] ("eyeroll" finds "eye roll") is `GifDatabase.searchGifs`';
 *  - recently used first, newest first, is `GifDatabase.getRecentlyUsedGifs`' order.
 * CleverKeys ships GIF packs it builds into an SQLite FTS4 table; droidtop has no packs, so the index here is the
 * person's own files, read by name and folder, and kept in a small text file. CleverKeys reads no tags sidecar, so
 * none is read here either.
 *
 * Everything in this file is pure (no Android classes) and tested on the JVM (GifLibraryTest).
 */
package org.pocketworkstation.pckeyboard

/** Which panel tab an image shows in. */
enum class GifKind { GIF, STICKER }

/**
 * One image in a picked folder, referenced where it is: [uri] is its document URI in the folder the person picked
 * ([folder], a tree URI). Nothing is copied into droidtop's storage to list it. [words] are the file name's words,
 * [tags] its folder's.
 */
data class GifItem(
    val uri: String,
    val mime: String,
    val kind: GifKind,
    val name: String,
    val words: List<String>,
    val tags: List<String>,
    val folder: String,
)

/**
 * Where GIFs and stickers come from (Droidtop/tracker#903 H5). The local library ([LocalGifLibrary]) is the only one:
 * an online search (Klipy, since Tenor's API closed on 2026-06-30) waits for the owner, and needs no change here.
 * [onResult] runs on the main thread.
 */
interface GifProvider {
    /** [kind]'s images matching [query], best first, recently used ([recent], document URIs newest first) ahead. */
    fun search(query: String, kind: GifKind, recent: List<String>, limit: Int, onResult: (List<GifItem>) -> Unit)
}

/** Names into words: what the index keeps and what a query is compared with. */
object GifNames {
    private val EXTENSION = Regex("\\.[A-Za-z0-9]{1,5}$")

    /**
     * The words of a file or folder [name], lower-cased: the extension dropped, split at anything not a letter or
     * digit, between a lower-case and an upper-case letter ("happyDance") and between letters and digits ("cat2").
     */
    fun words(name: String, dropExtension: Boolean = true): List<String> {
        val base = if (dropExtension) name.replace(EXTENSION, "") else name
        val out = ArrayList<String>()
        val word = StringBuilder()
        fun flush() {
            if (word.isNotEmpty()) out += word.toString().lowercase()
            word.setLength(0)
        }
        var previous: Char? = null
        for (c in base) {
            if (!c.isLetterOrDigit()) {
                flush()
                previous = null
                continue
            }
            val p = previous
            if (p != null && ((p.isLowerCase() && c.isUpperCase()) || (p.isLetter() && c.isDigit()) || (p.isDigit() && c.isLetter()))) flush()
            word.append(c)
            previous = c
        }
        flush()
        return out
    }

    /** What the panel calls an image: its name's words, or the name itself when it has none. */
    fun displayName(fileName: String): String = words(fileName).joinToString(" ").ifEmpty { fileName }
}

/** Which files are taken, and which tab each goes to. */
object GifFormats {
    const val GIF = "image/gif"
    const val WEBP = "image/webp"
    const val PNG = "image/png"
    private val BY_EXTENSION = mapOf("gif" to GIF, "webp" to WEBP, "png" to PNG)

    /**
     * The MIME type a file is taken as, or null when it is not GIF, WebP or PNG: the type its folder reports, or,
     * when that says nothing useful, its extension.
     */
    fun accepted(fileName: String, reported: String?): String? {
        val type = reported?.trim()?.lowercase().orEmpty()
        if (type in BY_EXTENSION.values) return type
        if (type.isNotEmpty() && type != "application/octet-stream" && !type.startsWith("image/")) return null
        return BY_EXTENSION[fileName.substringAfterLast('.', "").lowercase()]
    }

    /** How many bytes of a WebP [isAnimatedWebp] reads. */
    const val WEBP_HEADER = 21

    /** Whether a WebP file's first bytes say it is animated: a RIFF/WEBP file whose VP8X chunk has the animation bit. */
    fun isAnimatedWebp(header: ByteArray): Boolean {
        if (header.size < WEBP_HEADER) return false
        fun at(offset: Int, text: String) = text.indices.all { header[offset + it] == text[it].code.toByte() }
        return at(0, "RIFF") && at(8, "WEBP") && at(12, "VP8X") && (header[20].toInt() and 0x02) != 0
    }

    /**
     * The tab: a folder named for stickers or GIFs ([folderWords], every folder from the picked one down) decides;
     * otherwise a GIF or an animated WebP is a GIF, a PNG or a still WebP a sticker.
     */
    fun kindOf(mime: String, animated: Boolean, folderWords: List<String>): GifKind = when {
        folderWords.any { it == "sticker" || it == "stickers" } -> GifKind.STICKER
        folderWords.any { it == "gif" || it == "gifs" } -> GifKind.GIF
        mime == GIF || (mime == WEBP && animated) -> GifKind.GIF
        else -> GifKind.STICKER
    }
}

/** Search over the index: ranking ported from CleverKeys (see the file header). */
object GifSearch {
    /** A query's words, in the index's alphabet (CleverKeys' sanitizeFtsQuery, every letter and digit kept). */
    fun queryWords(query: String): List<String> = GifNames.words(query, dropExtension = false)

    /**
     * [items] matching [query], at most [limit]: those in [recent] first (newest first), then by how well they match:
     * a name equal to the query, a name starting with it, every query word starting a word of the name, then of the
     * name or the folder's tags. A blank query lists everything, recent first, then in folder order. When nothing
     * matches a single word of five letters or more, it is tried as two words split anywhere ("eyeroll" as
     * "eye roll"), the first split with matches kept (CleverKeys' compound fallback).
     */
    fun rank(items: List<GifItem>, query: String, recent: List<String>, limit: Int = 200): List<GifItem> {
        val recency = HashMap<String, Int>().apply { recent.forEachIndexed { i, uri -> putIfAbsent(uri, i) } }
        val tokens = queryWords(query)
        val scored: List<Pair<Int, GifItem>> = if (tokens.isEmpty()) {
            items.map { 0 to it }
        } else {
            score(items, tokens).ifEmpty { compound(items, tokens) }
        }
        // sortedWith is stable: within a score the folder order stays.
        return scored.sortedWith(compareBy({ recency[it.second.uri] ?: Int.MAX_VALUE }, { it.first }))
            .take(limit).map { it.second }
    }

    private fun score(items: List<GifItem>, tokens: List<String>): List<Pair<Int, GifItem>> {
        val q = tokens.joinToString(" ")
        val out = ArrayList<Pair<Int, GifItem>>()
        for (item in items) {
            val name = item.words.joinToString(" ")
            val score = when {
                name == q -> 0
                name.startsWith(q) -> 1
                tokens.all { t -> item.words.any { it.startsWith(t) } } -> 2
                tokens.all { t -> item.words.any { it.startsWith(t) } || item.tags.any { it.startsWith(t) } } -> 3
                else -> -1
            }
            if (score >= 0) out += score to item
        }
        return out
    }

    private fun compound(items: List<GifItem>, tokens: List<String>): List<Pair<Int, GifItem>> {
        val word = tokens.singleOrNull() ?: return emptyList()
        if (word.length <= 4) return emptyList()
        for (i in 3..(word.length - 3)) {
            val found = score(items, listOf(word.substring(0, i), word.substring(i)))
            if (found.isNotEmpty()) return found.map { 4 to it.second }
        }
        return emptyList()
    }
}

/**
 * The index file: one image per line, its fields separated by tabs, a backslash, tab or line break in a field
 * escaped. A first line names the format, so a later change can tell an old file and scan again.
 */
object GifIndexFile {
    const val HEADER = "hkr-gifs 1"

    fun encode(items: List<GifItem>): String = buildString {
        append(HEADER).append('\n')
        for (item in items) {
            append(listOf(item.kind.name, item.mime, item.folder, item.uri, item.name, item.words.joinToString(" "), item.tags.joinToString(" "))
                .joinToString("\t") { escape(it) })
            append('\n')
        }
    }

    /** The images in [text]; null when it is not this format (a scan makes it again). A damaged line is skipped. */
    fun decode(text: String): List<GifItem>? {
        val lines = text.split('\n')
        if (lines.firstOrNull() != HEADER) return null
        return lines.drop(1).mapNotNull { line ->
            val f = line.split('\t').map { unescape(it) }
            if (f.size != 7) return@mapNotNull null
            val kind = GifKind.entries.firstOrNull { it.name == f[0] } ?: return@mapNotNull null
            GifItem(f[3], f[1], kind, f[4], split(f[5]), split(f[6]), f[2])
        }
    }

    private fun split(words: String): List<String> = words.split(' ').filter { it.isNotEmpty() }

    private fun escape(s: String): String = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")

    private fun unescape(s: String): String {
        if ('\\' !in s) return s
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                out.append(when (s[i + 1]) { 't' -> '\t'; 'n' -> '\n'; else -> s[i + 1] })
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }
}

/**
 * What a tap on an image does (ported from CleverKeys' GifInsertPolicy, see the file header). The image goes in through
 * the editor's content channel or not at all: never as a file path or a link typed as text.
 */
object GifInsertPolicy {
    enum class Action {
        /** Commit the image (InputConnectionCompat.commitContent). */
        COMMIT_MEDIA,

        /** The editor takes no image of this type: say so in one line. */
        NOT_ACCEPTED,

        /** A password or no-learning field: nothing is inserted (the tabs are hidden there too). */
        SENSITIVE,

        /** No editor to insert into. */
        NO_EDITOR,
    }

    fun decide(editorPresent: Boolean, sensitive: Boolean, editorAccepts: Boolean): Action = when {
        !editorPresent -> Action.NO_EDITOR
        sensitive -> Action.SENSITIVE
        editorAccepts -> Action.COMMIT_MEDIA
        else -> Action.NOT_ACCEPTED
    }

    /**
     * Pure editor MIME acceptance check (EditorInfo.contentMimeTypes semantics): exact match, a type wildcard
     * ("image/&#42;"), or the full wildcards "&#42;" and "&#42;/&#42;". Case-insensitive per RFC 2045. A null or empty
     * declaration means the editor accepts no content. (CleverKeys, unchanged.)
     */
    fun editorAcceptsMime(declared: Array<String>?, mime: String): Boolean {
        if (declared == null || declared.isEmpty()) return false
        val wanted = mime.lowercase()
        val wantedType = wanted.substringBefore('/')
        return declared.any { decl ->
            val d = decl.trim().lowercase()
            d == wanted || d == "*" || d == "*/*" || d == "$wantedType/*"
        }
    }
}

/**
 * The copies made for inserting (GifShare): only the newest [KEEP] stay, since the receiving app may read its copy a
 * moment after the insert; every older one is deleted at the next insert.
 */
object GifShareCache {
    const val KEEP = 3

    /** Of [files] (name to last-modified time), the names to delete. */
    fun stale(files: List<Pair<String, Long>>, keep: Int = KEEP): List<String> =
        files.sortedByDescending { it.second }.drop(keep).map { it.first }
}

/** Thumbnail sizes: the first frame decoded no larger than the cell needs. */
object GifThumbSize {
    /** [width] by [height] scaled down (never up) to fit a [target] square, keeping the shape; at least 1 by 1. */
    fun fit(width: Int, height: Int, target: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return target to target
        if (width <= target && height <= target) return width to height
        val scale = target.toDouble() / maxOf(width, height)
        return maxOf(1, (width * scale).toInt()) to maxOf(1, (height * scale).toInt())
    }

    /** BitmapFactory's inSampleSize: the largest power of two that keeps both sides at least [target]. */
    fun sampleSize(width: Int, height: Int, target: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= target && height / (sample * 2) >= target) sample *= 2
        return sample
    }
}

/** The picked folders, as kept in the keyboard's preferences: one tree URI per line. */
object GifFolderList {
    fun decode(saved: String?): List<String> = saved.orEmpty().split('\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    fun encode(folders: List<String>): String = folders.distinct().joinToString("\n")

    /** A folder's name for the settings list, from its tree document id ("primary:Pictures/GIFs" is "GIFs"). */
    fun label(treeDocumentId: String): String {
        val path = treeDocumentId.substringAfter(':', treeDocumentId).trimEnd('/')
        return path.substringAfterLast('/').ifEmpty { treeDocumentId.substringBefore(':').ifEmpty { treeDocumentId } }
    }
}
