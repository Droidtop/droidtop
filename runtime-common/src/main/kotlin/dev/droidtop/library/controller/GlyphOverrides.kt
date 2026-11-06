package dev.droidtop.library.controller

import android.content.Context
import dev.droidtop.library.settings.LAUNCHER_PREFS_FILE_NAME

/**
 * The button labels a person chose for one pad (Settings > Controls, Droidtop/tracker#723): Xbox, PlayStation or
 * Nintendo glyphs in place of what droidtop worked out, by the pad's input-device descriptor. Only the printed glyphs
 * change ([FaceLayout.copy] of the family): which key confirms and which cancels stays the resolver's. No entry is
 * Automatic.
 */
object GlyphOverrides {
    const val KEY = "droidtop_pad_glyph_overrides"
    private const val SEPARATOR = "|"

    /** The stored overrides, by descriptor. A preferences read: off the main thread where it can be. */
    fun get(context: Context): Map<String, GlyphFamily> = parse(prefs(context).getString(KEY, null))

    /** Sets [descriptor]'s labels to [family], or back to Automatic for null. */
    fun set(context: Context, descriptor: String, family: GlyphFamily?) {
        val next = get(context).let { if (family == null) it - descriptor else it + (descriptor to family) }
        prefs(context).edit().putString(KEY, format(next)).apply()
    }

    /** [layout] with [override]'s glyphs, the key map unchanged; [layout] itself without one. Pure. */
    fun apply(layout: FaceLayout, override: GlyphFamily?): FaceLayout = if (override == null) layout else layout.copy(family = override)

    fun parse(raw: String?): Map<String, GlyphFamily> = raw.orEmpty().split(SEPARATOR).mapNotNull { entry ->
        val descriptor = entry.substringBeforeLast('=', "")
        val family = GlyphFamily.fromId(entry.substringAfterLast('=', ""))
        if (descriptor.isEmpty() || family == null) null else descriptor to family
    }.toMap()

    fun format(map: Map<String, GlyphFamily>): String = map.entries.joinToString(SEPARATOR) { "${it.key}=${it.value.id}" }

    private fun prefs(context: Context) = context.getSharedPreferences(LAUNCHER_PREFS_FILE_NAME, Context.MODE_PRIVATE)
}
