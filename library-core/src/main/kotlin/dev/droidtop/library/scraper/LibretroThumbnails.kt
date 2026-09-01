package dev.droidtop.library.scraper

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Keyless BOX-ART fallback: the libretro-thumbnails repositories, the
 * exact same mechanism RetroArch itself fetches thumbnails through
 * (`thumbnails.libretro.com/<playlist name>/Named_Boxarts/<game>.png`).
 * No credentials of any kind, which matters because both real metadata
 * scrapers are credentialed (ScreenScraper needs a dev ID, TheGamesDB
 * an API key) and a fresh install has neither.
 *
 * Artwork only, deliberately: these repos carry images, not
 * descriptions/ratings, so this can never substitute for the real
 * scrapers -- it fills boxes while credentials don't exist yet, and the
 * real sources overwrite nothing they already filled (the caller only
 * consults this when the selected source returned no cover).
 *
 * Naming rules are libretro's own, applied by RetroArch when saving
 * playlists: the characters ampersand, asterisk, slash, colon, backtick,
 * angle brackets, question mark, backslash, pipe, and double quote in a
 * display name each become an underscore (see FORBIDDEN below),
 * and the file is `<name>.png` under the system's playlist-name
 * directory. The system map below covers droidtop's common systems with
 * their real playlist names; a system not listed simply returns null
 * (no guessing), and a name miss is a plain 404 skipped silently.
 */
object LibretroThumbnails {

    private val SYSTEM_NAMES = mapOf(
        "psx" to "Sony - PlayStation",
        "ps2" to "Sony - PlayStation 2",
        "ps3" to "Sony - PlayStation 3",
        "psp" to "Sony - PlayStation Portable",
        "psvita" to "Sony - PlayStation Vita",
        "nes" to "Nintendo - Nintendo Entertainment System",
        "snes" to "Nintendo - Super Nintendo Entertainment System",
        "n64" to "Nintendo - Nintendo 64",
        "gc" to "Nintendo - GameCube",
        "wii" to "Nintendo - Wii",
        "wiiu" to "Nintendo - Wii U",
        "gb" to "Nintendo - Game Boy",
        "gbc" to "Nintendo - Game Boy Color",
        "gba" to "Nintendo - Game Boy Advance",
        "nds" to "Nintendo - Nintendo DS",
        "n3ds" to "Nintendo - Nintendo 3DS",
        "megadrive" to "Sega - Mega Drive - Genesis",
        "genesis" to "Sega - Mega Drive - Genesis",
        "mastersystem" to "Sega - Master System - Mark III",
        "gamegear" to "Sega - Game Gear",
        "saturn" to "Sega - Saturn",
        "dreamcast" to "Sega - Dreamcast",
        "atari2600" to "Atari - 2600",
        "atari7800" to "Atari - 7800",
        "lynx" to "Atari - Lynx",
        "pcengine" to "NEC - PC Engine - TurboGrafx 16",
        "neogeo" to "SNK - Neo Geo",
        "ngp" to "SNK - Neo Geo Pocket",
        "ngpc" to "SNK - Neo Geo Pocket Color",
        "wonderswan" to "Bandai - WonderSwan",
        "wonderswancolor" to "Bandai - WonderSwan Color",
        "dos" to "DOS",
    )

    fun systemNameFor(systemId: String): String? = SYSTEM_NAMES[systemId]

    private val FORBIDDEN = Regex("[&*/:`<>?\\\\|\"]")

    /** The repository's own filename for a game title. */
    fun thumbnailName(gameName: String): String = FORBIDDEN.replace(gameName, "_")

    /**
     * A real, verified (HTTP 200) boxart URL for this game, or null when
     * the system has no known playlist name or the repo has no file
     * under this name. One HEAD-equivalent GET; the caller downloads.
     */
    fun coverUrl(systemId: String, gameName: String): String? = urlFor(systemId, gameName, "Named_Boxarts")

    /** In-game screenshot repo (the thumbnails project's Named_Snaps). */
    fun screenshotUrl(systemId: String, gameName: String): String? = urlFor(systemId, gameName, "Named_Snaps")

    /** Title-screen repo (Named_Titles). */
    fun titleUrl(systemId: String, gameName: String): String? = urlFor(systemId, gameName, "Named_Titles")

    private fun urlFor(systemId: String, gameName: String, repo: String): String? {
        val systemName = SYSTEM_NAMES[systemId] ?: return null
        val encodedSystem = URLEncoder.encode(systemName, "UTF-8").replace("+", "%20")
        val encodedName = URLEncoder.encode(thumbnailName(gameName), "UTF-8").replace("+", "%20")
        val url = "https://thumbnails.libretro.com/$encodedSystem/$repo/$encodedName.png"
        return runCatching {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "HEAD"
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            val ok = connection.responseCode == 200
            connection.disconnect()
            if (ok) url else null
        }.getOrNull()
    }
}
