package dev.droidtop.library.consoles

import android.util.Xml
import java.io.File
import org.xmlpull.v1.XmlPullParser

/**
 * gamelist.xml reader -- the de-facto interchange format every
 * third-party scraper in this ecosystem writes (Skraper, Skyscraper,
 * ARRM, EmulationStation family, ES-DE itself), and the concrete first
 * half of docs/SPEC.md §7b's ES-DE data-level sync (directed
 * 2026-08-31: offload scraping to any external tool the user likes;
 * droidtop ingests its output instead of owning credentials).
 *
 * Field set transcribed from real ES-DE's MetaData.cpp GAME_METADATA
 * table (name/desc/rating/releasedate/developer/publisher/genre/
 * players/favorite/completed), plus the classic EmulationStation
 * media tags (`image`/`video`/`marquee`) ES-DE itself no longer writes
 * but every external scraper still does. `<path>` is relative to the
 * gamelist's own directory (GamelistFileParser.cpp resolves against the
 * system's start path). ES-DE's own "unset" defaults ("unknown", rating
 * 0, the 1970 epoch date) are treated as absent, not imported as data.
 */
object GamelistXml {

    data class Entry(
        val path: String,
        val name: String?,
        val description: String?,
        val rating: Float?,
        val releaseDate: String?,
        val developer: String?,
        val publisher: String?,
        val genre: String?,
        val players: String?,
        val favorite: Boolean,
        val completed: Boolean,
        val imagePath: String?,
        val videoPath: String?,
    )

    fun fileFor(systemFolder: File): File = File(systemFolder, "gamelist.xml")

    fun parse(gamelist: File): List<Entry> {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(gamelist.inputStream().bufferedReader())
        }
        val entries = mutableListOf<Entry>()
        var inGame = false
        var tag: String? = null
        val fields = mutableMapOf<String, String>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "game") {
                        inGame = true
                        fields.clear()
                    } else if (inGame) {
                        tag = parser.name
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inGame && tag != null) {
                        val text = parser.text
                        if (!text.isNullOrBlank()) fields[tag!!] = (fields[tag] ?: "") + text
                    }
                }
                XmlPullParser.END_TAG -> {
                    when {
                        parser.name == "game" -> {
                            inGame = false
                            fields["path"]?.let { entries += toEntry(it, fields) }
                        }
                        inGame -> tag = null
                    }
                }
            }
            event = parser.next()
        }
        return entries
    }

    /** Resolves a gamelist-relative path (`./Game.chd`, `media/x.png`) against the gamelist's directory. */
    fun resolve(systemFolder: File, raw: String): File {
        val cleaned = raw.trim().removePrefix("./")
        val candidate = File(cleaned)
        return if (candidate.isAbsolute) candidate else File(systemFolder, cleaned)
    }

    private fun toEntry(path: String, fields: Map<String, String>): Entry {
        fun value(key: String): String? = fields[key]?.trim()?.takeIf { it.isNotBlank() && it != "unknown" }
        val rating = value("rating")?.toFloatOrNull()?.takeIf { it > 0f }
        val releaseDate = value("releasedate")?.takeIf { !it.startsWith("19700101") }
        return Entry(
            path = path.trim(),
            name = value("name"),
            description = value("desc"),
            rating = rating,
            releaseDate = releaseDate,
            developer = value("developer"),
            publisher = value("publisher"),
            genre = value("genre"),
            players = value("players"),
            favorite = value("favorite")?.equals("true", ignoreCase = true) == true,
            completed = value("completed")?.equals("true", ignoreCase = true) == true,
            imagePath = value("image"),
            videoPath = value("video"),
        )
    }
}
