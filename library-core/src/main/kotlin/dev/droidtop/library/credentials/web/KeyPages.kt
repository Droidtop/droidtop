package dev.droidtop.library.credentials.web

import java.net.URI
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** One numbered instruction beside the page; [urlMatch] says when the page in the pane is at this step. */
data class KeyStepSpec(val label: String, val hint: String?, val urlMatch: Regex?)

/**
 * What to read off the page, for one field. The value is taken from the first
 * element that matches a [selectors] entry, or sits beside a visible text in
 * [labels], and passes [pattern]; nothing else on the page is read.
 */
data class KeyCaptureField(
    val id: String,
    val selectors: List<String>,
    val labels: List<String>,
    val pattern: String,
)

/** Where, and only where, [fields] may be read: https on exactly [host], on a path matching [pathMatch]. */
data class KeyCaptureSpec(val host: String, val pathMatch: Regex, val fields: List<KeyCaptureField>)

/** The in-app browser page for one service, from `key-pages.json`. */
data class KeyPageSpec(
    val service: String,
    val startUrl: String,
    /** Hosts the pane may show (exact, or `*.example.com`); a link to anything else leaves the pane. */
    val hosts: List<String>,
    val steps: List<KeyStepSpec>,
    val capture: KeyCaptureSpec?,
    /** False until someone has watched the selectors work on the provider's live page (docs/SPEC.md 7h). */
    val verified: Boolean,
)

/**
 * The data behind the in-app key pages (docs/SPEC.md 7h, "Reading the key from
 * its page"). The page addresses, the instruction steps and the selectors live
 * in one JSON file (`app/src/main/assets/key-pages.json`) so a provider's
 * change is a data fix. Pure: the pane's WebView only feeds it addresses and
 * runs the script it builds.
 */
object KeyPages {

    fun parse(json: String): Map<String, KeyPageSpec> {
        val pages = JSONObject(json).getJSONObject("pages")
        return pages.keys().asSequence().associateWith { service ->
            val page = pages.getJSONObject(service)
            KeyPageSpec(
                service = service,
                startUrl = page.getString("startUrl"),
                hosts = strings(page.getJSONArray("hosts")),
                steps = page.getJSONArray("steps").objects().map { step ->
                    KeyStepSpec(
                        label = step.getString("label"),
                        hint = step.optString("hint").takeIf { it.isNotBlank() },
                        urlMatch = step.optString("urlMatch").takeIf { it.isNotBlank() }?.let(::Regex),
                    )
                },
                capture = page.optJSONObject("capture")?.let { capture ->
                    KeyCaptureSpec(
                        host = capture.getString("host"),
                        pathMatch = Regex(capture.getString("pathMatch")),
                        fields = capture.getJSONArray("fields").objects().map { field ->
                            KeyCaptureField(
                                id = field.getString("id"),
                                selectors = field.optJSONArray("selectors")?.let(::strings).orEmpty(),
                                labels = field.optJSONArray("labels")?.let(::strings).orEmpty(),
                                pattern = field.optString("pattern").ifBlank { DEFAULT_PATTERN },
                            )
                        },
                    )
                },
                verified = page.optBoolean("verified", false),
            )
        }
    }

    private fun strings(array: JSONArray): List<String> = (0 until array.length()).map { array.getString(it) }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

    private fun httpsHostAndPath(url: String): Pair<String, String>? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.host == null || (uri.port != -1 && uri.port != 443) || uri.userInfo != null) return null
        return uri.host.lowercase() to (uri.rawPath ?: "")
    }

    /** Whether the pane may show [url]: https, on one of the page's hosts. */
    fun canLoad(page: KeyPageSpec, url: String): Boolean {
        val host = httpsHostAndPath(url)?.first ?: return false
        return page.hosts.any { allowed ->
            if (allowed.startsWith("*.")) host.endsWith(allowed.drop(1)) else host == allowed
        }
    }

    /** Whether the page's fields may be read from [url]: its exact capture host and path, nothing nearby. */
    fun captureAllowed(page: KeyPageSpec, url: String?): Boolean {
        val capture = page.capture ?: return false
        val (host, path) = httpsHostAndPath(url ?: return false) ?: return false
        return host == capture.host && capture.pathMatch.containsMatchIn(path)
    }

    /**
     * The step the pane is at: the first whose [KeyStepSpec.urlMatch] matches
     * [url], else [previous] (a page between steps keeps the last one shown).
     */
    fun stepIndex(page: KeyPageSpec, url: String?, previous: Int): Int {
        if (url != null) page.steps.indexOfFirst { it.urlMatch?.containsMatchIn(url) == true }.let { if (it >= 0) return it }
        return previous.coerceIn(0, (page.steps.size - 1).coerceAtLeast(0))
    }

    /** A value read off a page is kept only if it fits its field's pattern. */
    fun accept(field: KeyCaptureField, raw: String?): String? =
        raw?.trim()?.takeIf { it.isNotEmpty() && Regex(field.pattern).matches(it) }

    /**
     * The script the pane runs on a matching page. It looks up only the
     * configured selectors and labels and answers a JSON object of the values
     * found; it changes nothing on the page and exposes no bridge.
     */
    fun script(capture: KeyCaptureSpec): String {
        val rules = JSONArray()
        capture.fields.forEach { field ->
            rules.put(
                JSONObject().put("id", field.id).put("selectors", JSONArray(field.selectors))
                    .put("labels", JSONArray(field.labels.map { it.lowercase() })).put("pattern", field.pattern),
            )
        }
        return "(function(){var rules=$rules;var out={};var used={};" +
            "function val(e){if(!e)return'';var v=(e.value!==undefined&&e.value!==''?e.value:e.textContent)||'';return v.trim();}" +
            "for(var i=0;i<rules.length;i++){var r=rules[i],re=new RegExp(r.pattern),found='';" +
            "for(var j=0;j<r.selectors.length&&!found;j++){var els=document.querySelectorAll(r.selectors[j]);" +
            "for(var k=0;k<els.length&&!found;k++){var v=val(els[k]);if(re.test(v)&&!used[v])found=v;}}" +
            "if(!found&&r.labels.length){var all=document.querySelectorAll('label,dt,th,h2,h3,h4,h5,span,div,p,strong,b');" +
            "for(var a=0;a<all.length&&!found;a++){var el=all[a];if(el.children.length)continue;" +
            "if(r.labels.indexOf(el.textContent.trim().toLowerCase())<0)continue;var scope=el;" +
            "for(var d=0;d<3&&!found;d++){scope=scope.parentElement;if(!scope)break;" +
            "var cs=scope.querySelectorAll('input,code,pre,span,div,td,dd');" +
            "for(var c=0;c<cs.length&&!found;c++){if(cs[c]===el)continue;var cv=val(cs[c]);if(re.test(cv)&&!used[cv])found=cv;}}}}" +
            "if(found){out[r.id]=found;used[found]=1;}}return JSON.stringify(out);})()"
    }

    /** `evaluateJavascript` answers a JSON string that itself holds JSON; this opens both layers. */
    fun parseResult(capture: KeyCaptureSpec, raw: String?): Map<String, String> {
        if (raw.isNullOrBlank() || raw == "null") return emptyMap()
        return runCatching {
            val inner = JSONTokener(raw).nextValue() as? String ?: return emptyMap()
            val obj = JSONObject(inner)
            capture.fields.mapNotNull { field -> accept(field, obj.optString(field.id))?.let { field.id to it } }.toMap()
        }.getOrDefault(emptyMap())
    }

    private const val DEFAULT_PATTERN = "[A-Za-z0-9_\\-]{16,128}"
}
