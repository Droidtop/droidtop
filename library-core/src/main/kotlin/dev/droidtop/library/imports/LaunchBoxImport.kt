package dev.droidtop.library.imports

/**
 * LaunchBox's per-platform files (`Data/Platforms/<platform>.xml`), read for an import (Droidtop/tracker#724): each
 * game's file, whether it is a favourite, its play time and when it was last played. Only read, never written. A
 * small extractor of the `<Game>` blocks rather than an XML library, so it runs the same in a JVM test. Pure.
 */
object LaunchBoxImport {
    data class Game(
        val id: String,
        val title: String,
        /** The game's file as LaunchBox stores it, relative to LaunchBox's folder (`..\Games\...`) or absolute. */
        val path: String,
        val favorite: Boolean,
        val playSeconds: Long,
        /** When it was last played, as LaunchBox wrote it (ISO 8601); null if never. */
        val lastPlayed: String?,
    ) {
        /** [path] in the form the matcher takes: forward slashes, no leading `..` steps, no drive letter. */
        val matchPath: String
            get() = path.replace('\\', '/').split('/').dropWhile { it == ".." || it == "." || it.matches(Regex("[A-Za-z]:")) }
                .joinToString("/")
    }

    private val GAME = Regex("<Game>(.*?)</Game>", RegexOption.DOT_MATCHES_ALL)

    fun parse(xml: String): List<Game> = GAME.findAll(xml).mapNotNull { block ->
        val body = block.groupValues[1]
        fun tag(name: String): String? =
            Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1)?.let(::unescape)?.trim()
        val path = tag("ApplicationPath")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        Game(
            id = tag("ID").orEmpty(),
            title = tag("Title").orEmpty(),
            path = path,
            favorite = tag("Favorite").equals("true", ignoreCase = true),
            playSeconds = tag("PlayTime")?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L,
            lastPlayed = tag("LastPlayedDate")?.takeIf { it.isNotEmpty() },
        )
    }.toList()

    private fun unescape(text: String): String = text
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    /** [Game.lastPlayed] as epoch ms, or null when it is missing or not ISO 8601. Pure. */
    fun lastPlayedMs(game: Game): Long? = game.lastPlayed?.let { text ->
        runCatching { java.time.OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { java.time.LocalDateTime.parse(text).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()
    }
}
