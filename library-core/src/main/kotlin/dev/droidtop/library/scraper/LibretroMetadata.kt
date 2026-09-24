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

    /**
     * Null when the system has no known libretro name. Otherwise the lookup
     * (empty when the DATs exist but carry nothing), or [ScrapeLookup.Refused]
     * when not one category could be read because the server refused every
     * download: a whole system's worth of "no match" would otherwise be
     * reported for a download that never happened (docs/SPEC.md section 7h).
     * A category that is simply absent upstream is a 404 and is not fatal
     * on its own -- several systems lack one or two of the four.
     */
    fun load(context: Context, systemId: String): ScrapeLookup<Lookup>? {
        val systemName = systemName(systemId) ?: return null
        val byName = HashMap<String, MutableMap<String, String>>()
        var loaded = 0
        var lastRefusal: ScrapeLookup.Refused? = null
        CATEGORIES.forEach { category ->
            when (val dat = cachedDat(context, category, systemName)) {
                is ScrapeLookup.Found -> {
                    loaded++
                    parseInto(dat.value, byName)
                }
                // A 404 is a category this system does not have upstream.
                is ScrapeLookup.Refused -> if (dat.httpStatus != 404) lastRefusal = dat
                ScrapeLookup.NoMatch, null -> Unit
            }
        }
        lastRefusal?.let { if (loaded == 0) return it }
        return ScrapeLookup.Found(Lookup(byName))
    }

    /** The DAT text, a refusal, or null for a transport failure (logged). */
    private fun cachedDat(context: Context, category: String, systemName: String): ScrapeLookup<String>? {
        val cache = File(File(context.filesDir, "libretro-metadat"), "$category-$systemName.dat")
        if (cache.isFile) return ScrapeLookup.Found(cache.readText())
        val encoded = URLEncoder.encode(systemName, "UTF-8").replace("+", "%20")
        val url = "https://raw.githubusercontent.com/libretro/libretro-database/master/metadat/$category/$encoded.dat"
        return runCatching<ScrapeLookup<String>> {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 20000
            val status = connection.responseCode
            if (status != 200) {
                ScrapeRefusals.refused("libretro-database", connection, status, emptyList(), "$category/$systemName")
                    .also { connection.disconnect() }
            } else {
                val text = connection.inputStream.bufferedReader().use { it.readText() }
                cache.parentFile?.mkdirs()
                cache.writeText(text)
                ScrapeLookup.Found(text)
            }
        }.getOrElse {
            android.util.Log.w("droidtop.Scraper", "libretro-database $category/$systemName: ${it.message}")
            null
        }
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
