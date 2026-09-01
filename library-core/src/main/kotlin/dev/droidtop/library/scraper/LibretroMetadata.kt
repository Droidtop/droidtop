package dev.droidtop.library.scraper

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Keyless METADATA source: the libretro-database `metadat` DAT files,
 * the same per-system registries RetroArch's own playlist scanner
 * annotates from (github.com/libretro/libretro-database). No
 * credentials of any kind (directed 2026-08-31: use other metadata
 * sources while ScreenScraper access is unresolved).
 *
 * Honest scope: these DATs carry genre, developer, publisher, and
 * release year (and player counts in `users`), keyed by No-Intro
 * display name -- the same naming [LibretroThumbnails] already matches
 * boxart by. They carry NO descriptions; a credentialed source or an
 * imported gamelist.xml remains the only path to those, and the
 * settings screen says so.
 *
 * One download per system per category, cached in filesDir and
 * refreshed only when the cache is deleted (the files change rarely).
 * DAT format is ClrMamePro: `game (` blocks of `key "value"` lines --
 * parsed tolerantly, unknown keys ignored.
 */
object LibretroMetadata {

    data class Fields(
        val genre: String?,
        val developer: String?,
        val publisher: String?,
        val releaseDate: String?,
        val players: String?,
    )

    class Lookup internal constructor(private val byName: Map<String, MutableMap<String, String>>) {
        fun find(gameName: String): Fields? {
            val values = byName[gameName.lowercase()] ?: return null
            return Fields(
                genre = values["genre"],
                developer = values["developer"],
                publisher = values["publisher"],
                releaseDate = values["releaseyear"]?.takeIf { it.length == 4 }?.let { "${it}0101T000000" },
                players = values["users"],
            )
        }

        val size: Int get() = byName.size
    }

    private val CATEGORIES = listOf("genre", "developer", "publisher", "releaseyear")

    fun systemName(systemId: String): String? = LibretroThumbnails.systemNameFor(systemId)

    /** Null when the system has no known libretro name; an empty lookup when the DATs exist but carry nothing. */
    fun load(context: Context, systemId: String): Lookup? {
        val systemName = systemName(systemId) ?: return null
        val byName = HashMap<String, MutableMap<String, String>>()
        CATEGORIES.forEach { category ->
            val text = cachedDat(context, category, systemName) ?: return@forEach
            parseInto(text, byName)
        }
        return Lookup(byName)
    }

    private fun cachedDat(context: Context, category: String, systemName: String): String? {
        val cache = File(File(context.filesDir, "libretro-metadat"), "$category-$systemName.dat")
        if (cache.isFile) return cache.readText()
        val encoded = URLEncoder.encode(systemName, "UTF-8").replace("+", "%20")
        val url = "https://raw.githubusercontent.com/libretro/libretro-database/master/metadat/$category/$encoded.dat"
        return runCatching {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 20000
            if (connection.responseCode != 200) {
                connection.disconnect()
                null
            } else {
                val text = connection.inputStream.bufferedReader().use { it.readText() }
                cache.parentFile?.mkdirs()
                cache.writeText(text)
                text
            }
        }.getOrNull()
    }

    private val KEY_VALUE = Regex("^\\s*(\\w+)\\s+\"(.*)\"\\s*$")

    private fun parseInto(text: String, byName: HashMap<String, MutableMap<String, String>>) {
        var currentName: String? = null
        var inGame = false
        text.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("game (") || trimmed == "game (") {
                inGame = true
                currentName = null
                return@forEach
            }
            if (!inGame) return@forEach
            if (trimmed == ")") {
                inGame = false
                currentName = null
                return@forEach
            }
            val match = KEY_VALUE.matchEntire(line) ?: return@forEach
            val (key, value) = match.destructured
            if (value.isBlank()) return@forEach
            if (key == "name") {
                currentName = value.lowercase()
            } else {
                currentName?.let { byName.getOrPut(it) { mutableMapOf() }[key] = value }
            }
        }
    }
}
