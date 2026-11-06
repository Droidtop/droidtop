package dev.droidtop.stores.zoom

import dev.droidtop.stores.util.StoreHttp
import java.net.URLDecoder
import java.net.URLEncoder
import okhttp3.Request
import org.json.JSONObject

/**
 * ZOOM Platform's launcher API (docs/SPEC.md 7g, "Stores", Droidtop/tracker#335), as Heroic Games Launcher reads it
 * (`src/backend/storeManagers/zoom`, GPL-3.0): the person signs in on ZOOM's own page, which returns with a launcher
 * token (`li_token`), and every call carries it as a bearer token. `li/loggedin` names the account, `li/games` lists
 * it a page at a time, `li/game/<id>/files` gives each game's installer files per system and `li/download/<file>` a
 * fresh link to one. ZOOM's games are DRM-free installers.
 */
internal object ZoomApi {
    const val SITE = "https://www.zoom-platform.com"

    /**
     * Who signs in, as ZOOM's sign-in page asks (`li=`): droidtop names itself, never another launcher. Owner to
     * confirm: Heroic sends `heroic`; if ZOOM answers only launchers it knows, this is the line to change.
     */
    const val LAUNCHER = "droidtop"

    /** The pause between two library pages, as Heroic keeps, so a long library never hurries ZOOM's API. */
    const val PAGE_PAUSE_MS = 1_000L

    /** The most library pages one sync reads (a guard against an answer that never ends). */
    const val MAX_PAGES = 200

    /** ZOOM's sign-in page, returning to droidtop with the launcher token in its address. */
    val signInUrl: String get() = "$SITE/login?li=$LAUNCHER&return_li_token=true"

    data class Game(val id: String, val title: String, val posterUrl: String?, val storeUrl: String?, val systems: List<String>)

    /** One installer file: its id (for [downloadUrl]), its file name and its size in bytes. */
    data class File(val id: String, val name: String, val fileName: String, val sizeBytes: Long)

    data class Page(val current: Int, val total: Int, val games: List<Game>)

    class SignedOut : IllegalStateException("Sign in to ZOOM Platform again")

    /** The launcher token in a sign-in return address (`li_token`), or null when [address] carries none. Pure. */
    fun tokenIn(address: String): String? {
        val query = address.substringBefore('#').substringAfter('?', "")
        return query.split('&').firstNotNullOfOrNull { part ->
            if (part.substringBefore('=') != "li_token") return@firstNotNullOfOrNull null
            runCatching { URLDecoder.decode(part.substringAfter('=', ""), "UTF-8") }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        }
    }

    private fun segment(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    /** `li/loggedin`'s account name. Pure. */
    fun nameOf(json: String): String? = runCatching { JSONObject(json).optString("name", "") }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    /** One `li/games` page: its number, how many there are and its games. Pure. */
    fun pageOf(json: String): Page {
        val root = JSONObject(json.ifBlank { "{}" })
        val array = root.optJSONArray("games")
        val games = (0 until (array?.length() ?: 0)).mapNotNull { i ->
            val g = array?.optJSONObject(i) ?: return@mapNotNull null
            val id = g.opt("id")?.toString()?.takeIf { it.isNotBlank() && it != "null" } ?: return@mapNotNull null
            val systems = g.optJSONArray("operating_systems")?.let { s -> (0 until s.length()).map { s.optString(it).lowercase() } }.orEmpty()
            Game(
                id = id,
                title = g.optString("name", "").trim().ifEmpty { g.optString("slug", id) },
                posterUrl = g.optString("poster_url", "").takeIf { it.startsWith("https://") },
                storeUrl = g.optString("store_url", "").takeIf { it.startsWith("https://") },
                systems = systems,
            )
        }
        val current = root.optInt("current_page", 1)
        return Page(current, root.optInt("total_pages", current).coerceAtLeast(current), games)
    }

    /**
     * The installer files of `li/game/<id>/files` for the system this device runs: Windows, else Linux (droidtop runs
     * both); the manuals, extras and soundtracks are not the game. Empty when it has neither. Pure.
     */
    fun installersOf(json: String): List<File> {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        for (system in listOf("windows", "linux")) {
            val array = root.optJSONArray(system) ?: continue
            val files = (0 until array.length()).mapNotNull { i ->
                val f = array.optJSONObject(i) ?: return@mapNotNull null
                val id = f.opt("id")?.toString()?.takeIf { it.isNotBlank() && it != "null" } ?: return@mapNotNull null
                val fileName = f.optString("filename", "").substringAfterLast('/').substringAfterLast('\\').trim()
                    .takeIf { it.isNotEmpty() && it != "." && it != ".." } ?: return@mapNotNull null
                File(id, f.optString("name", fileName).trim().ifEmpty { fileName }, fileName, f.optLong("total_size", 0L).coerceAtLeast(0L))
            }
            if (files.isNotEmpty()) return files
        }
        return emptyList()
    }

    /** `li/download/<file>`'s link, https only. Pure. */
    fun linkOf(json: String): String? = runCatching { JSONObject(json).optString("url", "") }.getOrNull()?.trim()?.takeIf { it.startsWith("https://") }

    /**
     * What an installed game was installed from, to compare with what ZOOM lists now: its files' names and sizes, in
     * order (ZOOM names no versions and gives no checksums). Pure.
     */
    fun signature(files: List<File>): String = files.joinToString("|") { "${it.fileName}:${it.sizeBytes}" }

    private fun get(token: String, url: String): String {
        val request = Request.Builder().url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .build()
        return StoreHttp.http.newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 403) throw SignedOut()
            check(response.isSuccessful) { "ZOOM Platform did not answer (HTTP ${response.code})" }
            response.body?.string().orEmpty()
        }
    }

    /** The account's name; [SignedOut] when the token is no longer good. */
    fun accountName(token: String): String = nameOf(get(token, "$SITE/li/loggedin")) ?: throw SignedOut()

    /** Every game of the account, a page at a time, pausing [PAGE_PAUSE_MS] between pages. */
    suspend fun games(token: String): List<Game> {
        val first = pageOf(get(token, "$SITE/li/games"))
        val all = first.games.toMutableList()
        var page = first.current
        while (page < first.total && page < MAX_PAGES) {
            kotlinx.coroutines.delay(PAGE_PAUSE_MS)
            page++
            all += pageOf(get(token, "$SITE/li/games?page=$page")).games
        }
        return all.distinctBy { it.id }
    }

    fun installers(token: String, gameId: String): List<File> = installersOf(get(token, "$SITE/li/game/${segment(gameId)}/files"))

    /** A fresh link to one installer file (links may expire). */
    fun downloadUrl(token: String, file: File): String =
        linkOf(get(token, "$SITE/li/download/${segment(file.id)}")) ?: error("ZOOM Platform gave no link for ${file.fileName}")
}
