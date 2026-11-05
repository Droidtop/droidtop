package dev.droidtop.pluginhost

/**
 * What a plugin's own download (`net.download`, docs/plugin-api.md D2) is called on its job row. The plugin's `title`
 * when it gave one, kept to one short line. Without one, "Download" and the file's name, but never a path into the
 * plugin's data and never a name that is only a hash: the Quick Menu's Downloads tab read "Download
 * dl/8f6bd4fd62d6e3bb.json" (rig, build 2778, Droidtop/tracker#739). Such a file is "Download data"; the row already
 * names the plugin beside it. Pure, so it is a JVM test.
 */
object DownloadTitle {
    /** The longest title a row is given; a longer one is cut at a word and ends in an ellipsis. */
    const val MAX = 60

    private val HASHLIKE = Regex("[0-9A-Fa-f_-]{8,}")

    fun of(title: String?, fileName: String): String {
        val given = title?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        if (given.isNotEmpty()) return shorten(given)
        val base = fileName.substringAfterLast('/')
        val stem = base.substringBeforeLast('.', base)
        return if (stem.isEmpty() || HASHLIKE.matches(stem) || stem.none { it.isLetter() }) "Download data" else shorten("Download $base")
    }

    private fun shorten(text: String): String {
        if (text.length <= MAX) return text
        val cut = text.take(MAX - 1)
        val word = cut.substringBeforeLast(' ', cut).ifBlank { cut }
        return word.trimEnd() + "…"
    }
}
