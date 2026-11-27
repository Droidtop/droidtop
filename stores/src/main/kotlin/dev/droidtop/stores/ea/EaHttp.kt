package dev.droidtop.stores.ea

// Ported from DroidDeck stores/ea/EaHttp.kt at cc63b403872b (GPL-3.0; see NOTICE.md).
// Requests use droidtop's existing store client instead of adding a second HTTP stack.
import dev.droidtop.stores.util.StoreHttp
import java.net.URLEncoder
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal class EaReply(val status: Int, val body: ByteArray, val headers: Map<String, List<String>>) {
    val ok: Boolean get() = status in 200..299
    val text: String get() = body.toString(Charsets.UTF_8)
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull()
}

internal object EaHttp {
    const val USER_AGENT = "Mozilla/5.0 EA Desktop/13.735.2.6250"
    private val noRedirects by lazy { StoreHttp.http.newBuilder().followRedirects(false).followSslRedirects(false).build() }

    fun form(values: List<Pair<String, String>>): String = values.joinToString("&") { (key, value) ->
        URLEncoder.encode(key, "UTF-8") + "=" + URLEncoder.encode(value, "UTF-8")
    }

    fun request(url: String, method: String = "GET", headers: Map<String, String> = emptyMap(), body: ByteArray? = null, follow: Boolean = true): EaReply {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT)
        headers.forEach { (key, value) -> request.header(key, value) }
        request.method(method, body?.toRequestBody())
        return (if (follow) StoreHttp.http else noRedirects).newCall(request.build()).execute().use {
            EaReply(it.code, it.body?.bytes() ?: ByteArray(0), it.headers.toMultimap())
        }
    }
}
