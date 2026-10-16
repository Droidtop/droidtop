package dev.droidtop.pluginhost

import org.json.JSONArray
import org.json.JSONObject

/**
 * What a widget shows (docs/plugin-api.md 3 C10, Droidtop/tracker#563): one of a closed set of templates, each of which
 * droidtop draws with views an Android app widget can hold, so a plugin's widget sits on any launcher's page without a
 * line of plugin code running there. [action] is the id a tap on the whole widget sends back (`action {id}`).
 */
sealed class WidgetTemplate {
    abstract val action: String?

    data class Value(val title: String, val value: String, val subtitle: String?, val severity: String?, override val action: String?) : WidgetTemplate()

    data class ListOf(val title: String?, val items: List<Item>, val empty: String?, override val action: String?) : WidgetTemplate()

    /** [percent] is 0 to 100, or -1 when the plugin does not know how far along it is. */
    data class Gauge(val title: String, val percent: Int, val label: String?, override val action: String?) : WidgetTemplate()

    data class Media(val title: String, val subtitle: String?, val image: WidgetImage?, val buttons: List<Button>, override val action: String?) : WidgetTemplate()

    data class Picture(val image: WidgetImage, val title: String?, override val action: String?) : WidgetTemplate()

    data class Item(val id: String, val title: String, val subtitle: String?, val value: String?, val action: String?)

    data class Button(val id: String, val label: String)
}

/** An image a plugin handed over in its reply: checked bytes of [type], decoded by droidtop, never a URL. */
class WidgetImage(val bytes: ByteArray, val type: String) {
    override fun equals(other: Any?): Boolean = other is WidgetImage && type == other.type && bytes.contentEquals(other.bytes)
    override fun hashCode(): Int = 31 * type.hashCode() + bytes.contentHashCode()
}

object PluginWidgetProtocol {
    const val POINT = "ui.widget"

    /** The `context.surface` of every call made for an Android app widget, and the tiles' `surfaces` value that allows one. */
    const val SURFACE = "android.widget"

    const val TITLE_MAX = 60
    const val SUBTITLE_MAX = 140
    const val VALUE_MAX = 40
    const val LABEL_MAX = 20
    const val MAX_ITEMS = 20
    const val MAX_BUTTONS = 3
    const val IMAGE_MAX_BYTES = 96 * 1024

    private val ACTION_ID = Regex("^[A-Za-z0-9._:-]{1,64}$")

    /**
     * Whether [entry] may be drawn on a widget: a `ui.widget`, `ui.status_tile` or `ui.quick_tile` entry whose
     * `surfaces` is empty or names [SURFACE]. The same filter every tile surface uses, read from the manifest alone.
     */
    fun widgetCapable(entry: ProvidedPoint): Boolean =
        entry.point in POINTS && PluginTileProtocol.onSurface(entry, SURFACE)

    val POINTS: Set<String> = setOf(POINT, "ui.status_tile", "ui.quick_tile")

    /** The `snapshot` call's args: the widget's size in dp, and where it is drawn. */
    fun snapshotArgs(widthDp: Int, heightDp: Int): JSONObject = JSONObject()
        .put("size", JSONObject().put("widthDp", widthDp.coerceAtLeast(0)).put("heightDp", heightDp.coerceAtLeast(0)))
        .put("context", JSONObject().put("surface", SURFACE))

    /** The `action` call's args for the id a tap sent back. */
    fun actionArgs(id: String): JSONObject = JSONObject().put("id", id).put("context", JSONObject().put("surface", SURFACE))

    /** `snapshot` answers `{template, ...}`; anything droidtop cannot draw is null (the widget keeps its last snapshot). */
    fun parse(data: JSONObject): WidgetTemplate? = when (data.optString("template")) {
        "value" -> text(data, "title", TITLE_MAX)?.let { title ->
            ViewValue.format(data.opt("value"))?.take(VALUE_MAX)?.let { value ->
                WidgetTemplate.Value(title, value, text(data, "subtitle", SUBTITLE_MAX), text(data, "severity", LABEL_MAX), actionOf(data))
            }
        }
        "list" -> WidgetTemplate.ListOf(text(data, "title", TITLE_MAX), items(data.optJSONArray("items")), text(data, "empty", SUBTITLE_MAX), actionOf(data))
        "gauge" -> text(data, "title", TITLE_MAX)?.let { title ->
            val raw = data.optDouble("percent", Double.NaN)
            val percent = if (raw.isNaN() || raw < 0) -1 else raw.coerceAtMost(100.0).toInt()
            WidgetTemplate.Gauge(title, percent, text(data, "label", VALUE_MAX), actionOf(data))
        }
        "media" -> text(data, "title", TITLE_MAX)?.let { title ->
            WidgetTemplate.Media(title, text(data, "subtitle", SUBTITLE_MAX), image(data.optJSONObject("image")), buttons(data.optJSONArray("buttons")), actionOf(data))
        }
        "image" -> image(data.optJSONObject("image"))?.let { WidgetTemplate.Picture(it, text(data, "title", TITLE_MAX), actionOf(data)) }
        else -> null
    }

    /** Every id a tap on [template] may send back: the only ones droidtop passes to `action`. */
    fun actionIds(template: WidgetTemplate): Set<String> = buildSet {
        template.action?.let(::add)
        when (template) {
            is WidgetTemplate.ListOf -> template.items.forEach { item -> item.action?.let(::add) }
            is WidgetTemplate.Media -> template.buttons.forEach { add(it.id) }
            else -> Unit
        }
    }

    private fun text(json: JSONObject, key: String, max: Int): String? =
        if (json.isNull(key)) null else json.optString(key).trim().takeIf { it.isNotEmpty() }?.let { cut(it, max) }

    private fun cut(s: String, max: Int): String = if (s.length <= max) s else s.take(max - 1).trimEnd() + "…"

    /** An action is the C2 shape, `{id}`; a bare string is read as the id. */
    private fun actionOf(json: JSONObject): String? {
        val raw = json.opt("action")
        val id = when (raw) {
            is JSONObject -> raw.optString("id")
            is String -> raw
            else -> null
        }?.trim()
        return id?.takeIf { ACTION_ID.matches(it) }
    }

    private fun items(array: JSONArray?): List<WidgetTemplate.Item> {
        array ?: return emptyList()
        val seen = HashSet<String>()
        return buildList {
            for (i in 0 until array.length()) {
                if (size >= MAX_ITEMS) break
                val row = array.optJSONObject(i) ?: continue
                val title = text(row, "title", TITLE_MAX) ?: continue
                val id = row.optString("id").trim().takeIf { it.isNotEmpty() } ?: "item$i"
                if (!seen.add(id)) continue
                add(WidgetTemplate.Item(id, title, text(row, "subtitle", SUBTITLE_MAX), ViewValue.format(row.opt("value"))?.take(VALUE_MAX), actionOf(row)))
            }
        }
    }

    private fun buttons(array: JSONArray?): List<WidgetTemplate.Button> {
        array ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                if (size >= MAX_BUTTONS) break
                val row = array.optJSONObject(i) ?: continue
                val id = row.optString("id").trim().takeIf { ACTION_ID.matches(it) } ?: continue
                val label = text(row, "label", LABEL_MAX) ?: continue
                if (none { it.id == id }) add(WidgetTemplate.Button(id, label))
            }
        }
    }

    /**
     * `{data: base64, type}`: PNG, JPEG or WebP, at most [IMAGE_MAX_BYTES]. The bytes must be what [type] says (their
     * first bytes are checked), so nothing else reaches the decoder. Anything else is no image.
     */
    fun image(json: JSONObject?): WidgetImage? {
        json ?: return null
        val encoded = json.optString("data").takeIf { it.isNotEmpty() && it.length <= (IMAGE_MAX_BYTES / 3 + 1) * 4 + 4 } ?: return null
        val bytes = runCatching { java.util.Base64.getMimeDecoder().decode(encoded) }.getOrNull() ?: return null
        if (bytes.isEmpty() || bytes.size > IMAGE_MAX_BYTES) return null
        val sniffed = sniff(bytes) ?: return null
        val declared = json.optString("type").trim().lowercase().ifEmpty { sniffed }
        return if (declared == sniffed) WidgetImage(bytes, sniffed) else null
    }

    private fun sniff(b: ByteArray): String? = when {
        b.size >= 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte() -> "image/png"
        b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte() -> "image/jpeg"
        b.size >= 12 && String(b, 0, 4, Charsets.US_ASCII) == "RIFF" && String(b, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
        else -> null
    }
}
