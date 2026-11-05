package dev.droidtop.stores.indiegala

import dev.droidtop.stores.util.StoreHttp
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Indiegala's library as its own Galaxy-style client reads it (docs/SPEC.md 7g, "Stores", Droidtop/tracker#335), after
 * Lacro59's playnite-indiegala-plugin (MIT; `Services/IndiegalaApi.cs`, `Models/GalaClient/GalaData.cs`,
 * `Models/Api/ApiGameDetails.cs`): the account's showcase collection from `login_new/user_info`, then each product's
 * details from `developers.indiegala.com/get_product_info`, which carry the DRM-free download links. Both calls send the
 * site's session cookies, which the person's sign-in on Indiegala's page leaves, and the client's user agent
 * (`galaClient`, what that plugin sends).
 */
internal object IndiegalaApi {
    const val SITE = "https://www.indiegala.com"
    private const val DEVELOPERS = "https://developers.indiegala.com"
    private const val CDN = "https://www.indiegalacdn.com/imgs/devs"
    private const val USER_AGENT = "galaClient"

    /** One game of the account's collection: [key] is Indiegala's id key name, [namespace] and [slug] find its details. */
    data class Product(
        val key: String,
        val title: String,
        val namespace: String,
        val slug: String,
        val coverUrl: String?,
        /** The newest version Indiegala lists (its highest version id), as text; blank when it lists none. */
        val version: String,
        /** Indiegala serves this one only through its own client: droidtop cannot download it. */
        val clientOnly: Boolean,
    )

    /** A product's download links by platform, from its details. */
    data class Downloads(val windows: String?, val linux: String?, val version: String)

    class SignedOut : IllegalStateException("Sign in to Indiegala again")

    /**
     * The account's collection from a `user_info` answer, or null when it says nobody is signed in (`user_found` is
     * not "true"). The collection is `showcase_content.content.user_collection`, at the root or under `data`. Pure.
     */
    fun parseCollection(json: String): List<Product>? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val found = root.opt("user_found")?.toString() ?: root.optJSONObject("data")?.opt("user_found")?.toString()
        if (found != null && found != "true") return null
        val showcase = root.optJSONObject("showcase_content") ?: root.optJSONObject("data")?.optJSONObject("showcase_content")
        val items = showcase?.optJSONObject("content")?.optJSONArray("user_collection") ?: return if (found == "true") emptyList() else null
        return (0 until items.length()).mapNotNull { i ->
            val item = items.optJSONObject(i) ?: return@mapNotNull null
            val key = item.optString("prod_id_key_name", "").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val namespace = item.optString("prod_dev_namespace", "")
            val image = item.optString("prod_dev_image", "")
            Product(
                key = key,
                title = item.optString("prod_name", key).trim().ifBlank { key },
                namespace = namespace,
                slug = item.optString("prod_slugged_name", ""),
                coverUrl = if (namespace.isNotBlank() && image.isNotBlank()) "$CDN/$namespace/products/$key/prodcover/$image" else null,
                version = newestVersion(item.optJSONArray("version")),
                clientOnly = item.optInt("galaclient_only", 0) == 1,
            )
        }.distinctBy { it.key }
    }

    /** The version with the highest id in an Indiegala version list, by its text (else its id); blank for none. Pure. */
    fun newestVersion(versions: JSONArray?): String {
        versions ?: return ""
        val newest = (0 until versions.length()).mapNotNull { versions.optJSONObject(it) }.maxByOrNull { it.optInt("id", 0) } ?: return ""
        return newest.optString("version", "").ifBlank { newest.optInt("id", 0).takeIf { it > 0 }?.toString().orEmpty() }
    }

    /** A `get_product_info` answer's download links (`product_data.downloadable_versions`), only those over https. Pure. */
    fun parseDownloads(json: String): Downloads? {
        val data = runCatching { JSONObject(json).optJSONObject("product_data") }.getOrNull() ?: return null
        val links = data.optJSONObject("downloadable_versions")
        fun link(key: String) = links?.optString(key, "")?.trim()?.takeIf { it.startsWith("https://") }
        return Downloads(link("win"), link("lin"), newestVersion(data.optJSONArray("version")))
    }

    /** The file name of a download link: its path's last part. Pure. */
    fun fileName(url: String): String = url.substringBefore('?').substringAfterLast('/').ifBlank { "download" }

    private fun get(cookies: String, url: String): String {
        val request = Request.Builder().url(url)
            .header("Cookie", cookies)
            .header("User-Agent", USER_AGENT)
            .build()
        return StoreHttp.http.newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 403) throw SignedOut()
            check(response.isSuccessful) { "Indiegala did not answer (HTTP ${response.code})" }
            response.body?.string().orEmpty()
        }
    }

    /** The account's collection; [SignedOut] when the session no longer reads it. */
    fun collection(cookies: String): List<Product> = parseCollection(get(cookies, "$SITE/login_new/user_info")) ?: throw SignedOut()

    fun downloads(cookies: String, product: Product): Downloads? {
        val url = "$DEVELOPERS/get_product_info".toHttpUrl().newBuilder()
            .addQueryParameter("dev_id", product.namespace)
            .addQueryParameter("prod_name", product.slug)
            .build()
        return parseDownloads(get(cookies, url.toString()))
    }
}
