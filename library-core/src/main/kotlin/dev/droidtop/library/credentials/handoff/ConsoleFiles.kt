package dev.droidtop.library.credentials.handoff

import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

/** A file being written into the folder the person chose; one at a time, on the server's thread. */
interface IncomingFile {
    /** The name it was stored under (a provider may have changed it; the console shows this one). */
    val name: String

    val out: OutputStream

    /** The whole file arrived and was flushed: close it and make it visible. */
    fun finish()

    /** It did not arrive in full: close it and delete what was written, so no part of a file is left. */
    fun discard()
}

/**
 * The folder phone-sent files go into (docs/SPEC.md, "Type from another device", file transfer, Droidtop/tracker#645):
 * the one the person chose on the console beforehand, else Download/droidtop. The Android implementation lives in
 * `:app`; the session calls it from the server's thread, never the main thread, outside its own lock, so a fake stands in
 * for it in tests. It never picks a folder of its own: a game folder is written into only when the person picked it.
 */
interface ConsoleFiles {
    /** Where files go, in words for the console ("Download/droidtop", or the chosen folder's name). */
    fun folderLabel(): String

    /** Whether a file called [name] is already in the folder. */
    fun exists(name: String): Boolean

    /** Creates [name] (which does not exist); null when the folder cannot be written to. Throws nothing the session cannot take as a failure. */
    fun create(name: String): IncomingFile?

    /** No folder at all: what a session has until Android gives it one. */
    object None : ConsoleFiles {
        override fun folderLabel() = "no folder"

        override fun exists(name: String) = false

        override fun create(name: String): IncomingFile? = null
    }
}

/** The one file the console offers the phone; [open] is called once per download, on the server's thread. */
class OfferedFile(val name: String, val size: Long, val open: () -> InputStream)

/**
 * The rules for names and sizes of transferred files, with no Android in them so they are unit-tested.
 */
object FileNames {
    /** 4 GB per file, each way. */
    const val MAX_BYTES = 4L * 1024 * 1024 * 1024

    private const val MAX_NAME_BYTES = 120
    const val FALLBACK = "file"

    /**
     * A name a file may be stored under, from whatever the phone called it: only what follows the last `/` or `\`
     * (no path parts, so `../x` and `a/b` and `C:\dir\x` are all just their last part), control characters and the
     * characters Android's storage rejects (`: * ? " < > |`) replaced by an underscore, leading dots dropped (nothing
     * hidden, no `..`), trailing dots and spaces dropped, and at most 120 bytes of UTF-8 with the extension kept.
     * A name with nothing left is [FALLBACK].
     */
    fun sanitise(raw: String): String {
        val last = raw.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = last.map { if (it.isISOControl() || it in RESERVED || it == '\u2028' || it == '\u2029') '_' else it }
            .joinToString("")
            .trim()
            .trimStart('.', ' ')
            .trimEnd('.', ' ')
        if (cleaned.isBlank()) return FALLBACK
        return shorten(cleaned)
    }

    private val RESERVED = setOf(':', '*', '?', '"', '<', '>', '|')

    private fun shorten(name: String): String {
        if (name.toByteArray(Charsets.UTF_8).size <= MAX_NAME_BYTES) return name
        val dot = name.lastIndexOf('.')
        val extension = if (dot > 0 && name.length - dot <= 16) name.substring(dot) else ""
        val room = MAX_NAME_BYTES - extension.toByteArray(Charsets.UTF_8).size
        val stem = name.substring(0, if (extension.isEmpty()) name.length else dot)
        val out = StringBuilder()
        var bytes = 0
        var i = 0
        while (i < stem.length) {
            val cp = stem.codePointAt(i)
            i += Character.charCount(cp)
            val piece = String(Character.toChars(cp))
            val size = piece.toByteArray(Charsets.UTF_8).size
            if (bytes + size > room) break
            out.append(piece)
            bytes += size
        }
        return (out.toString().trimEnd('.', ' ').ifEmpty { FALLBACK }) + extension
    }

    /**
     * [name] if no file of that name exists, else "Name (2)", "Name (3)" and so on before the extension
     * ("photo.jpg" is followed by "photo (2).jpg"; "x.tar.gz" by "x (2).tar.gz"); null after 9 999 tries.
     */
    fun unique(name: String, exists: (String) -> Boolean): String? {
        if (!exists(name)) return name
        val dot = name.lastIndexOf('.')
        var stem = if (dot > 0) name.substring(0, dot) else name
        var extension = if (dot > 0) name.substring(dot) else ""
        if (stem.endsWith(".tar", ignoreCase = true)) {
            extension = stem.substring(stem.length - 4) + extension
            stem = stem.dropLast(4)
        }
        for (n in 2..MAX_TRIES) {
            val candidate = "$stem ($n)$extension"
            if (!exists(candidate)) return candidate
        }
        return null
    }

    private const val MAX_TRIES = 9_999

    /**
     * A chosen folder in words, from its document id (`primary:Download/Games` is "Download/Games"; a card's
     * `1A2B-3C4D:Roms` is "1A2B-3C4D: Roms"; the root of a volume is its name).
     */
    fun folderLabel(documentId: String): String {
        val volume = documentId.substringBefore(':')
        val path = documentId.substringAfter(':', "").trim('/')
        return when {
            ':' !in documentId -> documentId
            volume.equals("primary", ignoreCase = true) -> path.ifEmpty { "Internal storage" }
            path.isEmpty() -> volume
            else -> "$volume: $path"
        }
    }

    /** "812 B", "1.4 MB", "3.2 GB": 1 024 to a step, one decimal from a kilobyte up. */
    fun size(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = listOf("KB", "MB", "GB", "TB")
        var value = bytes / 1024.0
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit])
    }
}
