package dev.droidtop.library.guides

import java.io.File

/** How a guide is read: text and PDF in droidtop's reader, a web page in the browser for now (docs/SPEC.md 7i "Guides"). */
enum class GuideFormat { TEXT, PDF, WEB }

/**
 * One guide for a game (Droidtop/tracker#817): what it is called, who it is from, how it is read, and where it is, a
 * file ([path]) or an address ([url]). [id] is stable for the game, so the reader's place is kept under it.
 */
data class Guide(
    val id: String,
    val title: String,
    val source: String,
    val format: GuideFormat,
    val path: String? = null,
    val url: String? = null,
)

/**
 * The guides a game's own folder carries (the walkthrough, guide and readme files a game often ships), found by name in
 * the folder and one level down. Read where they are, never moved or copied. Pure but for listing the folder: call it
 * off the main thread.
 */
object GameGuides {
    private val NAME = Regex("""^(walkthrough|guide|readme|faq|manual)([ _.\-].*)?$""", RegexOption.IGNORE_CASE)

    /** The most guides one folder lists, so a folder full of readmes cannot flood the list. */
    const val MAX_IN_FOLDER = 12

    fun inFolder(folder: File): List<Guide> {
        if (!folder.isDirectory) return emptyList()
        val candidates = sequence {
            yieldAll(folder.listFiles().orEmpty().asSequence())
            folder.listFiles().orEmpty().asSequence().filter { it.isDirectory }.forEach { yieldAll(it.listFiles().orEmpty().asSequence()) }
        }
        return candidates
            .filter { it.isFile && NAME.matches(it.nameWithoutExtension) }
            .mapNotNull { file -> formatOf(file)?.let { file to it } }
            .sortedWith(compareBy({ rank(it.first) }, { it.first.name.lowercase() }))
            .take(MAX_IN_FOLDER)
            .map { (file, format) ->
                Guide(
                    id = "folder:${file.relativeTo(folder).path}",
                    title = file.name,
                    source = "In the game's folder",
                    format = format,
                    path = file.absolutePath,
                )
            }
            .toList()
    }

    /** How a file is read, by its extension; null for a file droidtop does not read. */
    fun formatOf(file: File): GuideFormat? = when (file.extension.lowercase()) {
        "txt", "md", "nfo" -> GuideFormat.TEXT
        "pdf" -> GuideFormat.PDF
        "html", "htm" -> GuideFormat.WEB
        else -> null
    }

    // Walkthroughs before guides before the rest, so the most useful is first.
    private fun rank(file: File): Int {
        val name = file.name.lowercase()
        return when {
            name.startsWith("walkthrough") -> 0
            name.startsWith("guide") -> 1
            name.startsWith("faq") -> 2
            name.startsWith("manual") -> 3
            else -> 4
        }
    }
}
