package dev.droidtop.library.gameinfo

import android.content.Context
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.achievements.hash.DiscSerials
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/** One game's entry in an emulator's own public compatibility list. */
data class CompatInfo(val emulator: String, val rating: String, val listUrl: String)

sealed interface CompatResult {
    data object Off : CompatResult

    /** No emulator of droidtop's has a public list for this game's system. */
    data object Unsupported : CompatResult

    /** The list exists but cannot say anything about this game (not in it, not tested, or the file is not identifiable). */
    data class NotListed(val emulator: String) : CompatResult

    data class Failed(val message: String) : CompatResult

    data class Found(val info: CompatInfo) : CompatResult
}

/**
 * Emulator compatibility from the emulator's own public list, keyed by the game's identity and never by its name
 * (docs/SPEC.md 7h, "Game info"): DuckStation's game database for a PlayStation disc by its serial, Azahar's
 * compatibility list for a Nintendo 3DS cartridge by its title id. Evidence from other people on other hardware,
 * never a verdict and never a gate. A list is downloaded once a month the first time a game of its system opens its
 * page, and kept in the cache directory as only the rows it needs.
 */
object EmulatorCompat {
    private const val TTL_MS = 30L * 24 * 60 * 60 * 1000
    private const val TIMEOUT_MS = 30_000

    private const val DUCKSTATION = "DuckStation"
    private const val DUCKSTATION_URL = "https://raw.githubusercontent.com/stenzek/duckstation/master/data/resources/gamedb.yaml"
    private const val DUCKSTATION_PAGE = "https://github.com/stenzek/duckstation/blob/master/data/resources/gamedb.yaml"
    private const val AZAHAR = "Azahar"
    private const val AZAHAR_URL = "https://raw.githubusercontent.com/azahar-emu/compatibility-list/master/compatibility_list.json"
    private const val AZAHAR_PAGE = "https://github.com/azahar-emu/compatibility-list"

    fun lookup(context: Context, entry: LibraryEntry, now: Long = System.currentTimeMillis()): CompatResult {
        val system = entry.systemId
        if (system != "psx" && system != "n3ds") return CompatResult.Unsupported
        if (!GameInfoPrefs.lookupsOn(context)) return CompatResult.Off
        val emulator = if (system == "psx") DUCKSTATION else AZAHAR
        val file = File(entry.id)
        val key = (if (system == "psx") DiscSerials.playstation(file) else nintendo3dsTitleId(file)) ?: return CompatResult.NotListed(emulator)
        val directory = File(context.cacheDir, "gameinfo").also { it.mkdirs() }
        val cache = File(directory, "compat-${emulator.lowercase()}.tsv")
        val rows = try {
            load(cache, now) { if (system == "psx") downloadDuckStation() else downloadAzahar() }
        } catch (e: IOException) {
            return CompatResult.Failed("No connection to $emulator's compatibility list")
        } ?: return CompatResult.Failed("$emulator's compatibility list could not be read")
        val rating = rows[key] ?: return CompatResult.NotListed(emulator)
        return CompatResult.Found(CompatInfo(emulator, rating, if (system == "psx") DUCKSTATION_PAGE else AZAHAR_PAGE))
    }

    /** The cached rows while they are fresh, else a new download (kept, and used even when the next one fails offline). */
    private fun load(cache: File, now: Long, download: () -> Map<String, String>): Map<String, String>? {
        if (cache.isFile && now - cache.lastModified() < TTL_MS) readRows(cache)?.let { return it }
        return try {
            download().also { writeRows(cache, it) }
        } catch (e: IOException) {
            if (cache.isFile) readRows(cache) else throw e
        }
    }

    private fun readRows(file: File): Map<String, String>? = runCatching {
        file.readLines().mapNotNull { line -> line.split('\t').takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun writeRows(file: File, rows: Map<String, String>) {
        if (rows.isEmpty()) return
        runCatching { file.writeText(rows.entries.joinToString("\n") { "${it.key}\t${it.value}" }) }
    }

    private fun get(address: String): HttpURLConnection =
        (URL(address).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("User-Agent", "droidtop (Android game launcher; https://github.com/Droidtop/droidtop)")
        }

    private fun downloadDuckStation(): Map<String, String> {
        val connection = get(DUCKSTATION_URL)
        try {
            if (connection.responseCode != 200) throw IOException("HTTP ${connection.responseCode}")
            return connection.inputStream.bufferedReader().use { parseDuckStation(it.lineSequence()) }
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadAzahar(): Map<String, String> {
        val connection = get(AZAHAR_URL)
        try {
            if (connection.responseCode != 200) throw IOException("HTTP ${connection.responseCode}")
            return parseAzahar(connection.inputStream.bufferedReader().use { it.readText().take(8 * 1024 * 1024) })
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Pure, for the JVM tests. DuckStation's game database is a YAML file of serial keys; the rows it rates carry
     * `compatibility:` then `rating:` (NoIssues, GraphicalAudioIssues, CrashesInGame ...), and `codes:` lists the
     * other serials of the same game. Only a line-level reading of that shape, keeping serial to rating.
     */
    internal fun parseDuckStation(lines: Sequence<String>): Map<String, String> {
        val rows = LinkedHashMap<String, String>()
        var serial: String? = null
        var codes = ArrayList<String>()
        var section = ""
        fun finish(rating: String?) {
            val key = serial ?: return
            if (rating != null) {
                rows[key] = rating
                codes.forEach { rows.putIfAbsent(it, rating) }
            }
        }
        var rating: String? = null
        for (line in lines) {
            if (line.isEmpty() || line.startsWith("#")) continue
            if (!line.startsWith(" ")) {
                finish(rating)
                serial = line.trimEnd().removeSuffix(":").trim('"', '\'').takeIf { line.trimEnd().endsWith(":") }
                rating = null
                codes = ArrayList()
                section = ""
                continue
            }
            if (line.startsWith("  ") && !line.startsWith("   ")) {
                section = line.trim().removeSuffix(":").takeIf { line.trimEnd().endsWith(":") }.orEmpty()
                continue
            }
            val text = line.trim()
            when (section) {
                "compatibility" -> if (text.startsWith("rating:")) rating = text.removePrefix("rating:").trim().trim('"', '\'')
                "codes" -> if (text.startsWith("- ")) codes.add(text.removePrefix("- ").trim().trim('"', '\''))
            }
        }
        finish(rating)
        return rows.mapValues { humanize(it.value) }
    }

    /** "GraphicalAudioIssues" as "Graphical audio issues". */
    internal fun humanize(word: String): String =
        word.replace(Regex("(?<=[a-z])(?=[A-Z])"), " ").lowercase().replaceFirstChar { it.uppercase() }

    private val AZAHAR_RATINGS = mapOf(
        0 to "Perfect", 1 to "Great", 2 to "Okay", 3 to "Bad", 4 to "Intro or menu only", 5 to "Does not boot",
    )

    /** Pure, for the JVM tests. Azahar's list: `releases[].id` title ids with a 0 (best) to 5 rating; 99 is not tested and left out. */
    internal fun parseAzahar(text: String): Map<String, String> {
        val array = runCatching { JSONArray(text) }.getOrNull() ?: return emptyMap()
        val rows = LinkedHashMap<String, String>()
        for (i in 0 until array.length()) {
            val game = array.optJSONObject(i) ?: continue
            val label = AZAHAR_RATINGS[game.optInt("compatibility", 99)] ?: continue
            val releases = game.optJSONArray("releases") ?: continue
            for (r in 0 until releases.length()) {
                val id = releases.optJSONObject(r)?.optString("id").orEmpty().uppercase()
                if (id.length == 16) rows[id] = label
            }
        }
        return rows
    }

    /** The title id of a Nintendo 3DS cartridge dump (.3ds or .cci): NCSD at 0x100, partition 0's title id at 0x108. */
    internal fun nintendo3dsTitleId(file: File): String? {
        if (!file.isFile) return null
        return try {
            RandomAccessFile(file, "r").use { raf ->
                if (raf.length() < 0x110) return null
                val header = ByteArray(0x10)
                raf.seek(0x100)
                raf.readFully(header)
                if (String(header, 0, 4, Charsets.ISO_8859_1) != "NCSD") return null
                var id = 0L
                for (i in 7 downTo 0) id = (id shl 8) or (header[8 + i].toLong() and 0xFF)
                "%016X".format(id)
            }
        } catch (e: IOException) {
            null
        }
    }
}
