package dev.droidtop.library.theme

import android.content.Context
import android.util.Log

/**
 * Real per-system accent color, straight from the bundled DEcaffe theme's
 * own `system/metadata/<id>.xml` (`<systemColor>`, a bare 6-hex-digit RGB
 * value, no '#') -- the same real per-platform color data ES-DE itself
 * uses to theme things like `${systemColor}` variable references. Direct
 * response to real on-device feedback that a flat, uniform black
 * background/border everywhere read as static compared to Daijishō's own
 * real per-platform colored borders (confirmed via a live screenshot this
 * session -- iiSU's system grid, red/pink/purple/teal/green tiles) and
 * ES-DE's own theme data being dynamic per system in the first place.
 *
 * Lives in library-core (not shell-gamepad, where the theme assets are
 * physically bundled) so both `:app` (ConsoleSystemsActivity) and
 * `:shell-gamepad` (GamepadShell) can use it -- Android merges every
 * module's `assets/` folder into one flat set in the final APK, so
 * `context.assets.open(...)` finds these files regardless of which
 * module's code is asking, as long as `:shell-gamepad` (which physically
 * bundles the DEcaffe theme) is somewhere in the app's module graph.
 *
 * Loaded lazily and cached in memory -- 195 small XML files is cheap once,
 * not worth re-parsing per recomposition.
 */
object SystemThemeColors {
    private val cache = mutableMapOf<String, Int?>()
    private val colorRegex = Regex("<systemColor>([0-9a-fA-F]{6})</systemColor>")

    /** ARGB int (opaque) for [systemId], or null if this system has no DEcaffe metadata / no systemColor. */
    fun forSystem(context: Context, systemId: String): Int? {
        if (cache.containsKey(systemId)) return cache[systemId]

        val color = try {
            context.assets.open("themes/decaffe-es-de/system/metadata/$systemId.xml").use { input ->
                val text = input.bufferedReader().readText()
                colorRegex.find(text)?.groupValues?.get(1)?.let { hex ->
                    (0xFF000000.toInt()) or hex.toInt(16)
                }
            }
        } catch (t: Exception) {
            Log.d("droidtop.SystemThemeColors", "No DEcaffe metadata for system '$systemId'", t)
            null
        }
        cache[systemId] = color
        return color
    }
}
