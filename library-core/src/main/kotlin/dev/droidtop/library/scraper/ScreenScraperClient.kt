package dev.droidtop.library.scraper

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Real ScreenScraper.fr client -- ES-DE's own actual default scraper, and
 * the strongest real match quality for ROMs specifically (checksum-based
 * lookup, not just filename search). Confirmed real API shape via
 * screenscraper.fr's own webapi2 docs: `GET jeuInfos.php` with
 * `systemeid`/`romnom`/`romtaille`/`crc` (or `md5`/`sha1`)/`output`, plus
 * BOTH developer credentials (`devid`/`devpassword`) AND a per-user
 * account (`ssid`/`sspassword`).
 *
 * **Real, current blocker, not a config gap**: `devid`/`devpassword`
 * aren't just an API key a user generates themselves -- ScreenScraper's
 * own docs are explicit that a developer has to "contact us via the forum
 * to present your software and obtain your identifiers," a manual,
 * human-reviewed process. droidtop has no such registration yet, and nothing
 * in this session can create one -- unlike IGDB/SteamGridDB/Lutris (see
 * their own client classes), this integration is real, working code
 * against a real API, but genuinely can't function until that forum
 * registration happens (a one-time, human task for whoever owns this
 * project, not something to fake or route around).
 */
object ScreenScraperClient {
    fun findCoverUrl(
        devId: String,
        devPassword: String,
        userId: String,
        userPassword: String,
        systemeId: String,
        romName: String,
        romSizeBytes: Long,
    ): String? {
        val url = URL(
            "https://www.screenscraper.fr/api2/jeuInfos.php?" +
                "devid=${URLEncoder.encode(devId, "UTF-8")}" +
                "&devpassword=${URLEncoder.encode(devPassword, "UTF-8")}" +
                "&softname=droidtop" +
                "&ssid=${URLEncoder.encode(userId, "UTF-8")}" +
                "&sspassword=${URLEncoder.encode(userPassword, "UTF-8")}" +
                "&output=json" +
                "&systemeid=$systemeId" +
                "&romnom=${URLEncoder.encode(romName, "UTF-8")}" +
                "&romtaille=$romSizeBytes",
        )
        val connection = (url.openConnection() as HttpURLConnection).apply { requestMethod = "GET" }
        if (connection.responseCode != 200) return null
        val response = JSONObject(connection.inputStream.bufferedReader().readText())
        val medias = response.optJSONObject("response")?.optJSONObject("jeu")?.optJSONArray("medias") ?: return null
        for (i in 0 until medias.length()) {
            val media = medias.getJSONObject(i)
            // "box-2D" is ScreenScraper's own real media-type name for a
            // flat cover image -- the closest equivalent to IGDB/SteamGridDB's "cover"/"grid".
            if (media.optString("type") == "box-2D") {
                return media.optString("url", "").ifBlank { null }
            }
        }
        return null
    }
}
