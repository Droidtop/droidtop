package dev.droidtop.pluginhost

import dev.droidtop.library.format.ValueFormat
import org.json.JSONObject
import java.util.Locale

/**
 * A view's typed value (docs/plugin-api.md 1.6 "Typed values", Droidtop/tracker#521 item 7): an `info` or `row`
 * `value` may be an object instead of a string, and droidtop writes it in its own words, so every plugin's sizes,
 * durations and switches read the same as droidtop's ([ValueFormat]). A plain string is shown as given, as before.
 *
 * - `{"bool": true}` -> "On" / "Off"
 * - `{"number": 1500000000, "unit": "bytes"}` -> "1.5 GB"; `"seconds"` -> "2 h 5 min"; `"percent"` (0 to 100) -> "42%";
 *   no unit -> "1,500"; any other unit is written after the number ("60 fps"), cut at [UNIT_MAX]
 * - `{"enum": "eu", "options": [{"value": "eu", "label": "Europe"}]}` -> "Europe" (the value itself when unlisted)
 *
 * An object droidtop cannot read is no value at all (null), never its JSON text.
 */
object ViewValue {
    const val UNIT_MAX = 12

    fun format(raw: Any?): String? = when (raw) {
        null, JSONObject.NULL -> null
        is JSONObject -> typed(raw)
        is Boolean -> onOff(raw)
        else -> raw.toString().trim().takeIf { it.isNotEmpty() }
    }

    private fun typed(json: JSONObject): String? = when {
        json.has("bool") -> (json.opt("bool") as? Boolean)?.let(::onOff)
        json.has("number") -> number(json)
        json.has("enum") -> enumLabel(json)
        else -> null
    }

    private fun onOff(on: Boolean): String = if (on) "On" else "Off"

    private fun number(json: JSONObject): String? {
        val n = (json.opt("number") as? Number)?.toDouble()?.takeIf { it.isFinite() } ?: return null
        val unit = json.optString("unit").trim()
        return when (unit.lowercase(Locale.ROOT)) {
            "bytes" -> ValueFormat.size(n.toLong())
            "seconds" -> ValueFormat.duration(n.toLong(), none = "None")
            "percent" -> "${n.coerceIn(0.0, 100.0).toInt()}%"
            "" -> plain(n)
            else -> "${plain(n)} ${unit.take(UNIT_MAX)}"
        }
    }

    /** A whole number with thousands separators, or up to two decimals. */
    private fun plain(n: Double): String =
        if (n == Math.rint(n) && kotlin.math.abs(n) < 1e15) ValueFormat.count(n.toLong())
        else String.format(Locale.US, "%,.2f", n).trimEnd('0').trimEnd('.')

    private fun enumLabel(json: JSONObject): String? {
        val value = json.optString("enum").trim().takeIf { it.isNotEmpty() } ?: return null
        val options = json.optJSONArray("options") ?: return value
        for (i in 0 until options.length()) {
            val o = options.optJSONObject(i) ?: continue
            if (o.optString("value") == value) return o.optString("label").trim().ifEmpty { value }
        }
        return value
    }
}
