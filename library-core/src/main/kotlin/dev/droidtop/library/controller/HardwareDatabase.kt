package dev.droidtop.library.controller

import android.content.Context
import dev.droidtop.library.consoles.PlatformDatabaseTransport
import org.json.JSONObject

/** One value of a console's layout toggle: what the system does to the pad's face keys while the property holds it. */
data class ToggleValue(val keysSwapped: Boolean, val confirmOnRight: Boolean)

/** A system property that changes the face-button layout while the device runs, and what each of its values means. */
data class LayoutToggle(val property: String, val values: Map<String, ToggleValue>)

/** The built-in pad's identity: its ids or its name are enough to say a given input device is it. */
data class BuiltInPad(val name: String?, val vendorId: Int?, val productId: Int?) {
    fun matches(deviceName: String?, vendor: Int, product: Int): Boolean =
        (vendorId != null && productId != null && vendorId == vendor && productId == product) ||
            (name != null && deviceName != null && name.equals(deviceName.trim(), ignoreCase = true))
}

/** One row of the console table: the device, the pad built into it, what is printed on it and its layout toggle. */
data class ConsoleDef(
    val id: String,
    val name: String,
    val model: String,
    val manufacturer: String?,
    val pad: BuiltInPad?,
    val glyphFamily: GlyphFamily?,
    val toggle: LayoutToggle?,
    /** Where the add-on display sits: true above the built-in panel, false below, null when the row does not say. */
    val addonOnTop: Boolean? = null,
) {
    fun matches(deviceManufacturer: String?, deviceModel: String?): Boolean =
        deviceModel != null && model.equals(deviceModel.trim(), ignoreCase = true) &&
            (manufacturer == null || manufacturer.equals(deviceManufacturer?.trim(), ignoreCase = true))
}

/**
 * The console table (docs/SPEC.md 7b, "Console and controller detection"): the
 * `hardware` collection of the droidtop-platforms repository, composed into
 * `hardware-database.json` and kept like every other database there
 * ([dev.droidtop.library.consoles.PlatformDatabaseIndex] refreshes it; the
 * build's seed task bundles the pinned snapshot when it has one, and a
 * refreshed copy in filesDir wins when it is not older than that seed). A
 * device with no row is simply not in the table: nothing is inferred for it.
 */
object HardwareDatabase {
    private const val DB_FILE_NAME = "hardware-database.json"

    @Volatile
    private var cached: List<ConsoleDef>? = null

    /** Every row. Reads a file: not for the main thread. */
    fun defs(context: Context): List<ConsoleDef> {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val refreshed = dev.droidtop.library.consoles.PlatformDatabaseSnapshot.refreshedCopy(context, DB_FILE_NAME)
                ?.let { file -> runCatching { parse(file.readText()) }.getOrNull() }
            val loaded = refreshed ?: runCatching {
                parse(context.assets.open(DB_FILE_NAME).bufferedReader().use { it.readText() })
            }.getOrElse { emptyList() }
            cached = loaded
            return loaded
        }
    }

    /** The row for this device, or null. Not for the main thread. */
    fun forThisDevice(context: Context): ConsoleDef? =
        defs(context).firstOrNull { it.matches(android.os.Build.MANUFACTURER, android.os.Build.MODEL) }

    /**
     * This device's row when the table is already loaded, reading nothing: for a caller on the main thread,
     * which gets null until something has loaded the table ([defs] off the main thread).
     */
    fun loadedForThisDevice(): ConsoleDef? =
        cached?.firstOrNull { it.matches(android.os.Build.MANUFACTURER, android.os.Build.MODEL) }

    /** Validate-then-replace, like the other databases. Returns the row count. */
    fun install(context: Context, text: String): Int {
        val count = validate(text)
        PlatformDatabaseTransport.replace(context, DB_FILE_NAME, text)
        cached = null
        return count
    }

    fun validate(text: String): Int {
        val rows = parse(text)
        check(rows.isNotEmpty()) { "Hardware database has no matchable devices" }
        return rows.size
    }

    fun parse(text: String): List<ConsoleDef> {
        val devices = JSONObject(text).getJSONObject("devices")
        return devices.keys().asSequence().mapNotNull { id -> parseRow(id, devices.getJSONObject(id)) }.toList()
    }

    /** A row with no `match.model` cannot be matched to a device, so it carries nothing for this table. */
    private fun parseRow(id: String, json: JSONObject): ConsoleDef? {
        val match = json.optJSONObject("match") ?: return null
        val model = match.optString("model", "").ifEmpty { null } ?: return null
        val pad = json.optJSONObject("gamepad")
        val toggle = json.optJSONObject("layoutToggle")?.let { t ->
            val values = t.optJSONObject("values") ?: return@let null
            val property = t.optString("property", "").ifEmpty { null } ?: return@let null
            LayoutToggle(
                property,
                values.keys().asSequence().associateWith { key ->
                    val v = values.getJSONObject(key)
                    ToggleValue(v.getBoolean("keysSwapped"), v.getString("confirmOn") == "right")
                },
            )
        }
        return ConsoleDef(
            id = id,
            name = json.optString("name", id),
            model = model,
            manufacturer = match.optString("manufacturer", "").ifEmpty { null },
            pad = pad?.let {
                BuiltInPad(
                    name = it.optString("name", "").ifEmpty { null },
                    vendorId = hexOrNull(it.optString("vendorId", "")),
                    productId = hexOrNull(it.optString("productId", "")),
                )
            },
            glyphFamily = GlyphFamily.fromId(pad?.optString("glyphFamily", "")),
            toggle = toggle,
            addonOnTop = addonOnTop(json.optJSONArray("displays")),
        )
    }

    /**
     * The add-on display's `position` in a row's `displays` (hardware/_meta.json): "top" or "bottom", or,
     * when only the internal panel states one, the other side of it. Anything else says nothing.
     */
    private fun addonOnTop(displays: org.json.JSONArray?): Boolean? {
        if (displays == null) return null
        fun positionOf(role: String): String? = (0 until displays.length())
            .mapNotNull { displays.optJSONObject(it) }
            .firstOrNull { it.optString("role") == role }
            ?.optString("position", "")?.ifEmpty { null }
        return when (positionOf("addon")) {
            "top" -> true
            "bottom" -> false
            else -> when (positionOf("internal")) {
                "bottom" -> true
                "top" -> false
                else -> null
            }
        }
    }

    private fun hexOrNull(text: String): Int? = text.removePrefix("0x").toIntOrNull(16)
}
