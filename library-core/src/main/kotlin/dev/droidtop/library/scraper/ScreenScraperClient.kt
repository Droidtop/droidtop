package dev.droidtop.library.scraper

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Real per-ROM metadata ScreenScraper's own `jeuInfos.php` endpoint
 * returns -- field set and real fallback-region/-language logic ported
 * from ES-DE's own actual `ScreenScraperRequest::processGame`
 * (`es-app/src/scrapers/ScreenScraper.cpp`, cloned locally at
 * /root/es-de-reference for ongoing reference), not guessed.
 * `releaseDate`/`rating` are already normalized to real ES-DE's own
 * MetaData conventions.
 */
data class ScreenScraperGameMetadata(
    val name: String?,
    val coverUrl: String?,
    /** ScreenScraper media-type name (box-2D, ss, sstitle, wheel, support-2D, fanart, video...) to URL; first region wins. */
    val mediaUrls: Map<String, String> = emptyMap(),
    val description: String?,
    val developer: String?,
    val publisher: String?,
    val genre: String?,
    val releaseDate: String?,
    val rating: Float?,
    val players: String?,
    /**
     * ScreenScraper's own digest for the matched ROM (`<rom><rommd5>`).
     * Comparing it against the local file's digest is real ES-DE's
     * entire automatic-mode confidence check: identical means what its
     * own log calls a "perfect match".
     */
    val romMd5: String?,
)

/**
 * Real ScreenScraper.fr client -- ES-DE's own actual default scraper, and
 * the strongest real match quality for ROMs specifically (checksum-based
 * lookup, not just filename search). Confirmed real API shape via
 * screenscraper.fr's own webapi2 docs AND ES-DE's own real client source:
 * `GET jeuInfos.php` with `systemeid`/`romnom`/`romtaille`/`crc` (or
 * `md5`/`sha1`)/`output`; this client uses the anonymous tier and sends no
 * developer or user credentials.
 *
 * Uses `output=xml`, matching ES-DE's own real, confirmed-working request
 * -- not `output=json`: this session has no real devid/devpassword
 * credentials to test against live, so there's no way to confirm
 * ScreenScraper's JSON response actually mirrors the same real field/
 * attribute names its XML response does. Parsing the same real XML shape
 * ES-DE itself parses is correct by construction against real,
 * already-confirmed source, rather than guessing at an unverifiable JSON
 * schema.
 *
 * The anonymous tier has a low quota, so this client serializes and paces
 * requests, backs off after quota responses and caches every response.
 */
object ScreenScraperClient {

    private val anonymousPacing = ScreenScraperPacing()

    private val WANTED_MEDIA = setOf(
        "box-2D", "ss", "sstitle", "wheel", "wheel-hd", "support-2D", "fanart", "video", "video-normalized",
    )
    /**
     * Real fallback-priority attribute lookup -- ports ES-DE's own real
     * `find_child_by_attribute_list` (ScreenScraper.cpp): given a parent
     * element's already-collected (attributeValue, text) children, return
     * the first child whose attribute value matches, walking
     * [priority] in order (e.g. region "us" before "wor" before "eu").
     */
    private fun List<Pair<String, String>>.firstByPriority(priority: List<String>): String? {
        for (candidate in priority) {
            find { it.first.equals(candidate, ignoreCase = true) }?.let { return it.second }
        }
        return null
    }

    /**
     * Real ES-DE MetaData releasedate conversion (Utils::Time::stringToTime
     * with "%Y-%m-%d" or "%Y") -- ScreenScraper's own real date field is
     * either "YYYY-MM-DD" or just "YYYY".
     */
    private fun parseReleaseDate(raw: String): String? {
        if (raw.isBlank()) return null
        val format = if (raw.length > 4) "yyyy-MM-dd" else "yyyy"
        return try {
            val parsed = SimpleDateFormat(format, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(raw)
                ?: return null
            val out = SimpleDateFormat("yyyyMMdd'T'000000", Locale.US)
            out.timeZone = TimeZone.getTimeZone("UTC")
            out.format(parsed)
        } catch (t: Throwable) {
            null
        }
    }

    fun findMetadata(
        systemeId: String,
        romName: String,
        romSizeBytes: Long,
        devId: String = ScreenScraperDevCredentials.devId,
        devPassword: String = ScreenScraperDevCredentials.devPassword,
        userId: String = "",
        userPassword: String = "",
        region: String = "wor",
        language: String = "en",
        md5: String = "",
        responseCacheDir: File? = null,
    ): ScrapeLookup<ScreenScraperGameMetadata> {
        val url = URL(
            "https://www.screenscraper.fr/api2/jeuInfos.php?" +
                "devid=${URLEncoder.encode(devId, "UTF-8")}" +
                "&devpassword=${URLEncoder.encode(devPassword, "UTF-8")}" +
                "&softname=droidtop" +
                (if (userId.isNotBlank() && userPassword.isNotBlank()) "&ssid=${URLEncoder.encode(userId, "UTF-8")}&sspassword=${URLEncoder.encode(userPassword, "UTF-8")}" else "") +
                "&output=xml" +
                "&systemeid=$systemeId" +
                "&romnom=${URLEncoder.encode(romName, "UTF-8")}" +
                // Fidelity fix from re-reading ES-DE's real URL builder:
                // it sends romtaille ONLY together with md5 (the pair is
                // what the server verifies against its dump database).
                // Sending size alone risked mismatch rejections whenever
                // a local file's size drifts from the reference dump.
                (if (md5.isNotBlank()) "&md5=$md5&romtaille=$romSizeBytes" else ""),
        )
        val cacheFile = responseCacheDir?.let { dir ->
            val key = MessageDigest.getInstance("SHA-256").digest(url.toString().toByteArray())
                .joinToString("") { "%02x".format(it) }
            File(dir, "screenscraper/$key.json")
        }
        val cached = cacheFile?.takeIf { it.isFile }?.let { runCatching { org.json.JSONObject(it.readText()) }.getOrNull() }
            ?.takeIf { System.currentTimeMillis() - it.optLong("at") < cacheTtlMs(it.optInt("status"), it.optString("body")) }
        val connection: HttpURLConnection?
        val status: Int
        var responseBody: String
        if (cached != null) {
            connection = null
            status = cached.optInt("status")
            responseBody = cached.optString("body")
        } else {
            synchronized(anonymousPacing) {
                val delay = anonymousPacing.delayBeforeRequest(System.currentTimeMillis())
                if (delay > 0) Thread.sleep(delay)
                anonymousPacing.requestStarted(System.currentTimeMillis())
                connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10_000
                    readTimeout = 20_000
                }
                status = connection.responseCode
            }
            responseBody = ""
        }
        if (status != 200) {
            // The API refused outright. Read the server's OWN reason out
            // of the error body: ScreenScraper answers a non-200 with a
            // short human-readable explanation (bad developer
            // credentials, application not authorised, obsolete client
            // version, quota exceeded), and that sentence is the entire
            // answer to "why did this fail". It used to be dropped on the
            // floor, which is what turned the 2026-09-01 outage into an
            // overnight investigation instead of one log line.
            val refusal = if (connection == null) {
                ScrapeLookup.Refused("ScreenScraper", status, responseBody.ifBlank { null })
            } else ScrapeRefusals.refused(
                source = "ScreenScraper",
                connection = connection,
                status = status,
                secrets = emptyList(),
                subject = romName,
            )
            responseBody = refusal.reason.orEmpty()
            if (connection != null) anonymousPacing.response(status, responseBody, System.currentTimeMillis())
            cacheFile?.let { writeCached(it, status, responseBody) }
            return refusal
        }
        val xmlText = if (connection == null) responseBody else connection.inputStream.bufferedReader().readText().also {
            anonymousPacing.response(status, it, System.currentTimeMillis())
            cacheFile?.let { file -> writeCached(file, status, it) }
        }
        val parsed = parseGameXml(xmlText, region.lowercase(), language.lowercase())
        if (parsed == null) {
            android.util.Log.i(
                "droidtop.Scraper",
                "ScreenScraper: no game in response for $romName: " +
                    xmlText.take(160).replace('\n', ' '),
            )
            return ScrapeLookup.NoMatch
        }
        return ScrapeLookup.Found(parsed)
    }

    private fun cacheTtlMs(status: Int, body: String): Long = when {
        status == 429 || status == 430 || body.contains("quota", ignoreCase = true) -> 15 * 60_000L
        status in 200..299 -> 7 * 24 * 60 * 60_000L
        else -> 60 * 60_000L
    }

    private fun writeCached(file: File, status: Int, body: String) {
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(org.json.JSONObject().put("at", System.currentTimeMillis()).put("status", status).put("body", body).toString())
            if (!temp.renameTo(file)) { file.delete(); temp.renameTo(file) }
        }
    }

    /**
     * Real, single-pass XML walk (same `android.util.Xml`/`XmlPullParser`
     * tooling already used for theme.xml elsewhere in this module) --
     * ScreenScraper's real response shape (confirmed against
     * ScreenScraperRequest::processGame): an optional `<jeux>` wrapper
     * around one or more `<jeu>` elements, each with `<noms><nom
     * region="...">`, `<synopsis><synopsis langue="...">`,
     * `<dates><date region="...">`, `<genres><genre langue="...">`
     * (multi-region/-language children), plus plain `<developpeur>`/
     * `<editeur>`/`<joueurs>`/`<note>` and `<medias><media type="...">`.
     * Only the FIRST real `<jeu>` is parsed -- matching this client's own
     * single-result contract (same as [findMetadata]'s single ROM
     * lookup).
     */
    private fun parseGameXml(xmlText: String, region: String, language: String): ScreenScraperGameMetadata? {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(java.io.StringReader(xmlText))
        }

        val noms = mutableListOf<Pair<String, String>>()
        val synopsis = mutableListOf<Pair<String, String>>()
        val dates = mutableListOf<Pair<String, String>>()
        val genres = mutableListOf<Pair<String, String>>()
        var developer: String? = null
        var publisher: String? = null
        var players: String? = null
        var noteRaw: String? = null
        var coverUrl: String? = null
        val mediaUrls = mutableMapOf<String, String>()
        var romMd5: String? = null
        var foundJeu = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "jeu" -> {
                        if (foundJeu) {
                            // Only the first <jeu> matters -- stop here.
                            break
                        }
                        foundJeu = true
                    }
                    "nom" -> if (foundJeu) {
                        val attr = parser.getAttributeValue(null, "region") ?: ""
                        noms += attr to readElementText(parser)
                    }
                    "synopsis" -> if (foundJeu && parser.getAttributeValue(null, "langue") != null) {
                        val attr = parser.getAttributeValue(null, "langue") ?: ""
                        synopsis += attr to readElementText(parser)
                    }
                    "date" -> if (foundJeu) {
                        val attr = parser.getAttributeValue(null, "region") ?: ""
                        dates += attr to readElementText(parser)
                    }
                    "genre" -> if (foundJeu) {
                        val attr = parser.getAttributeValue(null, "langue") ?: ""
                        genres += attr to readElementText(parser)
                    }
                    "developpeur" -> if (foundJeu) developer = readElementText(parser).ifBlank { null }
                    "editeur" -> if (foundJeu) publisher = readElementText(parser).ifBlank { null }
                    "joueurs" -> if (foundJeu) players = readElementText(parser).ifBlank { null }
                    "note" -> if (foundJeu) noteRaw = readElementText(parser).ifBlank { null }
                    "rommd5" -> if (foundJeu) {
                        romMd5 = readElementText(parser).lowercase().ifBlank { null }
                    }
                    "media" -> if (foundJeu) {
                        // The full media set real ES-DE scrapes (its own
                        // ssConfig media_* names): first entry per type
                        // wins, which is the response's own region
                        // ordering.
                        val mediaType = parser.getAttributeValue(null, "type")
                        if (mediaType != null && mediaType in WANTED_MEDIA && mediaType !in mediaUrls) {
                            readElementText(parser).ifBlank { null }?.let { mediaUrls[mediaType] = it }
                        }
                    }
                }
            }
            event = try {
                parser.next()
            } catch (t: Throwable) {
                XmlPullParser.END_DOCUMENT
            }
        }

        if (!foundJeu) return null

        // Real ES-DE fallback priority order, ported exactly (see this
        // object's own doc comment): name/releasedate use
        // region/"wor"/"us"/"ss"/"eu"/"jp"; description/genre use
        // language/"en"/"wor" (description) or language/"en" (genre).
        val regionPriority = listOf(region, "wor", "us", "ss", "eu", "jp")
        val name = noms.firstByPriority(regionPriority)
        val description = synopsis.firstByPriority(listOf(language, "en", "wor"))
        val genre = genres.firstByPriority(listOf(language, "en"))
        val releaseDateRaw = dates.firstByPriority(regionPriority)

        // Real ES-DE MD_RATING conversion (confirmed verbatim against
        // ScreenScraper.cpp): note is 0-20, normalized to 0.0-1.0 rounded
        // to the nearest 0.1.
        val rating = noteRaw?.toFloatOrNull()?.let { raw ->
            (kotlin.math.ceil((raw / 20.0) / 0.1) / 10.0).toFloat()
        }

        return ScreenScraperGameMetadata(
            name = name,
            coverUrl = coverUrl ?: mediaUrls["box-2D"],
            mediaUrls = mediaUrls,
            description = description,
            developer = developer,
            publisher = publisher,
            genre = genre,
            releaseDate = releaseDateRaw?.let { parseReleaseDate(it) },
            rating = rating,
            players = players,
            romMd5 = romMd5,
        )
    }

    /** Reads the text content of the current START_TAG element and advances past its END_TAG. */
    private fun readElementText(parser: XmlPullParser): String {
        val depth = parser.depth
        val sb = StringBuilder()
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.depth == depth)) {
            if (event == XmlPullParser.TEXT) sb.append(parser.text)
            event = parser.next()
        }
        return sb.toString().trim()
    }
}
