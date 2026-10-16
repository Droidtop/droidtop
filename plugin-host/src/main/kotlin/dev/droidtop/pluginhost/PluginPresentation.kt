package dev.droidtop.pluginhost

import org.json.JSONArray
import org.json.JSONObject

/**
 * How a plugin presents itself beyond `label` and `description` (docs/plugin-api.md 1.2 "Presentation",
 * Droidtop/tracker#521 item 2): the facts droidtop draws on the plugin's page and the list row in its own layout,
 * so no plugin stuffs its publisher, its home page or a "what it is" line into its description.
 *
 * Read from the signed manifest and kept on the record with the same keys. Everything is optional and checked, never
 * refused: text is cut, a home page that is not https is dropped, an unknown category is "other", and an image is kept
 * only when it names a PNG or WebP file of the signed payload (so droidtop draws only files whose hash it checked).
 */
data class PluginPresentation(
    /** One line saying what the plugin is, shown under its name (at most [SUMMARY_MAX]). */
    val summary: String? = null,
    /** Who makes it, as the person would know them (at most [PUBLISHER_MAX]). */
    val publisher: String? = null,
    /** The plugin's own page, https only. */
    val homepage: String? = null,
    /** One of [CATEGORIES]. */
    val category: String? = null,
    /** A payload path of a square PNG or WebP icon. */
    val icon: String? = null,
    /** Payload paths of PNG or WebP screenshots, at most [SCREENSHOTS_MAX]. */
    val screenshots: List<String> = emptyList(),
) {
    fun putInto(json: JSONObject) {
        summary?.let { json.put("summary", it) }
        publisher?.let { json.put("publisher", it) }
        homepage?.let { json.put("homepage", it) }
        category?.let { json.put("category", it) }
        icon?.let { json.put("icon", it) }
        if (screenshots.isNotEmpty()) json.put("screenshots", JSONArray(screenshots))
    }

    companion object {
        const val SUMMARY_MAX = 80
        const val PUBLISHER_MAX = 60
        const val SCREENSHOTS_MAX = 6
        /** The fixed category list (docs/plugin-api.md 1.2, "Categories", Droidtop/tracker#179), in the order droidtop shows it. */
        val CATEGORIES = listOf("sources", "emulators", "system", "sync", "integrations", "appearance", "other")

        /** Each category in the person's words. */
        val CATEGORY_LABELS = mapOf(
            "sources" to "Sources and fetchers",
            "emulators" to "Emulators and runners",
            "system" to "System tools",
            "sync" to "Sync and backup",
            "integrations" to "Integrations",
            "appearance" to "Appearance",
            "other" to "Other",
        )

        /** The first list's ids (2026-10-15), read as their successors. */
        private val CATEGORY_SUCCESSORS = mapOf("tools" to "system", "themes" to "appearance", "social" to "integrations", "games" to "sources")

        /** [raw] as one of [CATEGORIES]: a first-list id as its successor, anything else (or nothing) "other". */
        fun category(raw: String?): String {
            val id = raw?.trim()?.lowercase().orEmpty()
            return CATEGORY_SUCCESSORS[id] ?: id.takeIf { it in CATEGORIES } ?: "other"
        }
        private val IMAGE = Regex("(?i)\\.(png|webp)$")

        /** The presentation in [json] (a manifest or a record); images must be among [payloadPaths]. */
        fun fromJson(json: JSONObject, payloadPaths: Set<String>): PluginPresentation {
            fun text(key: String, max: Int): String? = json.takeUnless { it.isNull(key) }?.optString(key)?.trim()
                ?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotEmpty() }
                ?.let { if (it.length <= max) it else it.take(max - 1).trimEnd() + "…" }
            fun image(path: String?): String? = path?.trim()?.takeIf { IMAGE.containsMatchIn(it) && it in payloadPaths }
            val shots = json.optJSONArray("screenshots")?.let { array ->
                (0 until array.length()).mapNotNull { image(array.optString(it)) }.distinct().take(SCREENSHOTS_MAX)
            }.orEmpty()
            return PluginPresentation(
                summary = text("summary", SUMMARY_MAX),
                publisher = text("publisher", PUBLISHER_MAX),
                homepage = text("homepage", 2048)?.takeIf { it.startsWith("https://") && !it.contains(' ') },
                category = text("category", 32)?.let(::category),
                icon = image(json.takeUnless { it.isNull("icon") }?.optString("icon")),
                screenshots = shots,
            )
        }
    }
}
