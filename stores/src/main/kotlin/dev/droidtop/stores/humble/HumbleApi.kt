package dev.droidtop.stores.humble

import dev.droidtop.stores.util.StoreHttp
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Humble Bundle's library API as its own site reads it (docs/SPEC.md 7g, "Stores", Droidtop/tracker#335): the account's
 * orders (`/api/v1/user/order`), then those orders in batches (`/api/v1/orders?gamekeys=...`), each with the DRM-free
 * products it holds and their downloads. Signed in by the site's own session cookie (`_simpleauth_sess`), which the
 * person's sign-in on Humble's page leaves; nothing else is sent.
 */
internal object HumbleApi {
    const val SITE = "https://www.humblebundle.com"
    const val SESSION_COOKIE = "_simpleauth_sess"

    /** How many orders one `/api/v1/orders` call asks for (the site's own batch). */
    private const val BATCH = 40

    /** One file of a product: [platform] (windows, linux, mac, android), its name, its link (signed, short-lived), size and MD5. */
    data class Download(val platform: String, val name: String, val url: String, val sizeBytes: Long, val md5: String)

    /** One DRM-free product in an order: what droidtop lists as a game. */
    data class Product(val id: String, val title: String, val iconUrl: String?, val gameKey: String, val downloads: List<Download>)

    class SignedOut : IllegalStateException("Sign in to Humble again")

    /** The account's order keys from `/api/v1/user/order`. Pure. */
    fun parseOrderKeys(json: String): List<String> {
        val array = JSONArray(json.ifBlank { "[]" })
        return (0 until array.length()).mapNotNull { array.optJSONObject(it)?.optString("gamekey", "")?.takeIf { key -> key.isNotBlank() } }
    }

    /**
     * The products of an `/api/v1/orders` answer (order key to order), each with its downloads that have a direct link.
     * Only games this device can run are kept: a product with no Windows or Linux build ([choose]: a key only, a
     * soundtrack, a book, a Mac-only game) is left out. Pure.
     */
    fun parseProducts(json: String): List<Product> {
        val orders = JSONObject(json.ifBlank { "{}" })
        val products = ArrayList<Product>()
        for (key in orders.keys()) {
            val order = orders.optJSONObject(key) ?: continue
            val sub = order.optJSONArray("subproducts") ?: continue
            for (i in 0 until sub.length()) {
                val product = sub.optJSONObject(i) ?: continue
                val id = product.optString("machine_name", "").takeIf { it.isNotBlank() } ?: continue
                val downloads = downloadsOf(product.optJSONArray("downloads"))
                if (choose(downloads) == null) continue
                products += Product(
                    id = id,
                    title = product.optString("human_name", id).trim().ifBlank { id },
                    iconUrl = product.optString("icon", "").takeIf { it.startsWith("http") },
                    gameKey = key,
                    downloads = downloads,
                )
            }
        }
        return products.distinctBy { it.id }
    }

    private fun downloadsOf(array: JSONArray?): List<Download> {
        array ?: return emptyList()
        val out = ArrayList<Download>()
        for (i in 0 until array.length()) {
            val entry = array.optJSONObject(i) ?: continue
            val platform = entry.optString("platform", "").lowercase()
            val files = entry.optJSONArray("download_struct") ?: continue
            for (j in 0 until files.length()) {
                val file = files.optJSONObject(j) ?: continue
                val url = file.optJSONObject("url")?.optString("web", "").orEmpty()
                if (!url.startsWith("https://")) continue
                out += Download(platform, file.optString("name", "").trim(), url, file.optLong("file_size", 0L), file.optString("md5", ""))
            }
        }
        return out
    }

    /**
     * The download a Windows device runs: a Windows build, else a Linux one (droidtop runs both), the first file of it
     * that is not a soundtrack or a document. Null when the product has neither. Pure.
     */
    fun choose(downloads: List<Download>): Download? {
        val playable = downloads.filter { d -> listOf("pdf", "mp3", "flac", "epub", "mobi", "cbz").none { d.url.substringBefore('?').lowercase().endsWith(".$it") } }
        return playable.firstOrNull { it.platform == "windows" } ?: playable.firstOrNull { it.platform == "linux" }
    }

    /** The file name of a Humble download link: its path's last part. Pure. */
    fun fileName(url: String): String = url.substringBefore('?').substringAfterLast('/').ifBlank { "download" }

    private fun get(session: String, url: String): String {
        val request = Request.Builder().url(url)
            .header("Cookie", "$SESSION_COOKIE=$session")
            .header("Accept", "application/json")
            .build()
        return StoreHttp.http.newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 403) throw SignedOut()
            check(response.isSuccessful) { "Humble did not answer (HTTP ${response.code})" }
            response.body?.string().orEmpty()
        }
    }

    fun orderKeys(session: String): List<String> = parseOrderKeys(get(session, "$SITE/api/v1/user/order?ajax=true"))

    /** The raw `/api/v1/orders` answers for [keys], one per batch. */
    fun orders(session: String, keys: List<String>): List<String> = keys.chunked(BATCH).map { batch ->
        val url = "$SITE/api/v1/orders".toHttpUrl().newBuilder().addQueryParameter("all_tpkds", "true")
            .apply { batch.forEach { addQueryParameter("gamekeys", it) } }
            .build()
        get(session, url.toString())
    }

    /** One order's answer, for a fresh signed link at install time. */
    fun order(session: String, key: String): String = get(session, "$SITE/api/v1/order/$key?all_tpkds=true").let { body ->
        // The single-order call answers the order itself; shaped as the batch answer so one parser reads both.
        JSONObject().put(key, JSONObject(body.ifBlank { "{}" })).toString()
    }
}
