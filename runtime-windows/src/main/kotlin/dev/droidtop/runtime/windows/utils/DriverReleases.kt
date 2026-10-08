package dev.droidtop.runtime.windows.utils

import android.content.Context
import android.net.Uri
import com.winlator.contents.AdrenotoolsManager
import dev.droidtop.runtime.windows.RuntimeDownloads
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Turnip driver builds offered straight from the projects that publish them,
 * beside GameNative's component list: the arm64 "Driver build" choices a
 * Wrapper driver loads (docs/SPEC.md 5a). Borrowed from DroidDeck's
 * gpu/TurnipReleases.kt (GPL-3.0, github.com/Droid-Deck/DroidDeck): each
 * source's recent GitHub releases are read newest first and every variant is
 * offered from the newest release carrying it, only when GitHub has a SHA-256
 * for the asset, which the download is checked against. Read only when the
 * person asks ([refresh]); the last answer is remembered, so the list shows
 * without going online. A download is installed the way GameNative installs a
 * driver zip ([AdrenotoolsManager.installDriver]) and then is one more
 * installed driver build, chosen in the same row as the others.
 *
 * Android (adrenotools) builds only: the Linux builds those projects publish
 * are for a glibc Wine, which droidtop's bionic runtime is not.
 */
object DriverReleases {

    data class Offer(
        val source: String,
        val tag: String,
        val name: String,
        val url: String,
        val size: Long,
        val label: String,
        val sha256: String,
    )

    data class Check(val offers: List<Offer>, val failed: List<String>, val checkedAt: Long)

    private class Source(val label: String, val repo: String, val classify: (name: String, tag: String) -> String?)

    private val SOURCES = listOf(
        // Turnip-<tag>[-variant][-Linux|-Wayland].zip (The412Banner's builds for Winlator-family apps).
        Source("Banners-Turnip", "The412Banner/Banners-Turnip") { name, tag ->
            val prefix = "Turnip-$tag"
            if (!name.startsWith(prefix) || !name.endsWith(".zip")) return@Source null
            val variant = name.removePrefix(prefix).removeSuffix(".zip")
            if (variant.endsWith("-Wayland") || variant.endsWith("-Linux")) return@Source null
            when {
                variant.isEmpty() -> "Adreno 6xx/7xx"
                variant == "-A8xx" -> "Adreno 8xx"
                variant.startsWith("-710-720") -> "Adreno 710/720" + if (variant.contains("Test")) " (test)" else ""
                variant.contains("OneUI", ignoreCase = true) -> "8 Gen 2 on One UI"
                else -> variant.trimStart('-')
            }
        },
        // WN-Turnip-<ver>-<b|p>_Axxx.zip: b = Balanced, p = Performance; WN-Linux-* are Linux builds.
        Source("WinNative", "WinNative-Emu/Drivers") { name, _ ->
            val m = Regex("""^WN-Turnip-[^-]+-([a-z]+)_(\w+)\.zip$""").find(name) ?: return@Source null
            val flavour = when (m.groupValues[1]) { "b" -> "Balanced"; "p" -> "Performance"; else -> m.groupValues[1] }
            val gpus = if (m.groupValues[2] == "Axxx") "all Adreno" else m.groupValues[2]
            "$gpus, $flavour"
        },
    )

    private const val PREFS = "droidtop_driver_releases"
    private const val KEY_CHECK = "check"
    private const val KEY_INSTALLED = "installed"

    /** The last check, or null when nobody has checked. */
    fun cached(context: Context): Check? =
        prefs(context).getString(KEY_CHECK, null)?.let { runCatching { parse(JSONObject(it)) }.getOrNull() }

    /**
     * Reads every source now and remembers the answer. A source that fails is
     * named in [Check.failed] and the others still count; throws only when
     * every source failed. Network.
     */
    suspend fun refresh(context: Context): Check = withContext(Dispatchers.IO) {
        val offers = JSONArray()
        val failed = ArrayList<String>()
        var lastError: Exception? = null
        for (src in SOURCES) {
            try {
                val releases = JSONArray(get("https://api.github.com/repos/${src.repo}/releases?per_page=15"))
                val seen = HashSet<String>()
                for (i in 0 until releases.length()) {
                    val r = releases.getJSONObject(i)
                    if (r.optBoolean("draft")) continue
                    val tag = r.getString("tag_name")
                    val assets = r.optJSONArray("assets") ?: continue
                    for (j in 0 until assets.length()) {
                        val a = assets.getJSONObject(j)
                        val name = a.getString("name")
                        val label = src.classify(name, tag) ?: continue
                        val sha = a.optString("digest").removePrefix("sha256:").takeIf { it.length == 64 } ?: continue
                        if (!seen.add(label)) continue
                        offers.put(
                            JSONObject().put("source", src.label).put("tag", tag).put("name", name)
                                .put("url", a.getString("browser_download_url")).put("size", a.optLong("size"))
                                .put("label", label).put("sha256", sha),
                        )
                    }
                }
            } catch (e: Exception) {
                failed += src.label
                lastError = e
            }
        }
        if (failed.size == SOURCES.size) throw lastError ?: IOException("no driver source answered")
        val stored = JSONObject().put("offers", offers).put("failed", JSONArray(failed)).put("checkedAt", System.currentTimeMillis())
        prefs(context).edit().putString(KEY_CHECK, stored.toString()).apply()
        parse(stored)
    }

    /** The driver id [offer] was installed as, when it is still installed. Disk. */
    fun installedId(context: Context, offer: Offer): String? {
        val id = installed(context).optString(offer.name, "").takeIf { it.isNotEmpty() } ?: return null
        return id.takeIf { runCatching { AdrenotoolsManager(context).enumarateInstalledDrivers().contains(it) }.getOrDefault(false) }
    }

    /**
     * Downloads [offer], checks it against GitHub's SHA-256, installs it as a
     * driver build and returns the id it was installed as. Network and disk.
     */
    suspend fun install(context: Context, offer: Offer, onProgress: (Float) -> Unit): String = withContext(Dispatchers.IO) {
        val file = File(context.cacheDir, offer.name)
        try {
            RuntimeDownloads.fetchUrl(offer.url, file, onProgress)
            val actual = sha256(file)
            if (!actual.equals(offer.sha256, ignoreCase = true)) {
                throw IOException("${offer.name} did not match its published checksum")
            }
            val id = AdrenotoolsManager(context).installDriver(Uri.fromFile(file))
            if (id.isNullOrEmpty()) throw IOException("${offer.name} is not a driver package this runtime can install")
            prefs(context).edit().putString(KEY_INSTALLED, installed(context).put(offer.name, id).toString()).apply()
            id
        } finally {
            file.delete()
        }
    }

    private fun installed(context: Context): JSONObject =
        runCatching { JSONObject(prefs(context).getString(KEY_INSTALLED, "{}")!!) }.getOrDefault(JSONObject())

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "droidtop")
        try {
            val code = c.responseCode
            if (code == 403 || code == 429) throw IOException("GitHub's rate limit was reached; try again later")
            if (code != 200) throw IOException("GitHub answered HTTP $code")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun parse(json: JSONObject): Check {
        val list = json.optJSONArray("offers") ?: JSONArray()
        val offers = (0 until list.length()).map { i ->
            val a = list.getJSONObject(i)
            Offer(
                a.getString("source"), a.getString("tag"), a.getString("name"), a.getString("url"),
                a.optLong("size"), a.getString("label"), a.getString("sha256"),
            )
        }
        val f = json.optJSONArray("failed") ?: JSONArray()
        return Check(offers, (0 until f.length()).map { f.getString(it) }, json.optLong("checkedAt"))
    }
}
