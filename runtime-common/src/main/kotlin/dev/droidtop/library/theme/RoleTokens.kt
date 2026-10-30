package dev.droidtop.library.theme

import android.content.Context
import org.json.JSONObject

/**
 * The role tokens for UI droidtop does not draw itself (docs/SPEC.md 7k, "Light and dark"; Droidtop/tracker#559): a
 * plugin's own pages through `ui.get_theme` (docs/plugin-api.md), and droidtop's own screens built from plain Android
 * views (the web sign-in screen). One set, read from the palette in use and never restated: the Gaming theme's while
 * the Gaming shell holds one ([provider]), the colours outside Gaming otherwise ([OutsideGamingColors]).
 */
object RoleTokens {
    /** The token names, in the order SPEC 7k gives them. */
    val NAMES = listOf("ground", "surface", "surfaceVariant", "onSurface", "onSurfaceMuted", "primary", "onPrimary", "caution", "danger", "focus", "focusInner")

    /** One token set: [light] is the theme's lightness, [colors] each name's ARGB. */
    data class Set(val light: Boolean, val colors: Map<String, Int>) {
        fun color(name: String): Int = colors.getValue(name)
    }

    /**
     * The Gaming theme's set while the Gaming shell holds one (its palette and high-contrast state), set by the shell;
     * null from it, or no provider, is the colours outside Gaming.
     */
    @Volatile
    var provider: (() -> Set?)? = null

    /** Set by :app at start: tells the plugins that asked (`host.theme_changed`). Called on [checks]' thread. */
    @Volatile
    var onChanged: ((Context, Set) -> Unit)? = null

    @Volatile
    private var last: Set? = null

    @Volatile
    private var appContext: Context? = null

    /** One thread for [checkSoon]: building a set derives the notice colours, which is not the main thread's work. */
    private val checks = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "role-tokens").apply { isDaemon = true } }

    /** :app at start: the context [checkSoon] reads the appearance with. */
    fun attach(context: Context) {
        appContext = context.applicationContext
        checkSoon()
    }

    /** Looks again, off the caller's thread, whether the set in use changed (a theme, Light or dark, Android's night mode). */
    fun checkSoon() {
        val context = appContext ?: return
        checks.execute { runCatching { check(context) } }
    }

    /** The set a palette's colours give. Pure. */
    fun of(colors: GamingThemeColors, highContrast: Boolean = false): Set {
        val notices = colors.noticeColors(highContrast)
        return Set(
            light = colors.isLight,
            colors = linkedMapOf(
                "ground" to colors.ground,
                "surface" to colors.overlaySurface,
                "surfaceVariant" to colors.cardFocused,
                "onSurface" to colors.onSurface,
                "onSurfaceMuted" to colors.onSurfaceMuted,
                "primary" to colors.accent,
                "onPrimary" to colors.onAccent,
                "caution" to notices.cautionInk,
                "danger" to notices.dangerInk,
                "focus" to colors.accent,
                "focusInner" to FocusRingInks.inner(colors.accent),
            ),
        )
    }

    /** The set in use now: the Gaming theme's while the shell holds one, else the colours outside Gaming ([OutsideGamingColors]). */
    fun current(context: Context): Set = provider?.invoke() ?: of(OutsideGamingColors.current(context))

    /** Tells [onChanged] when the set in use differs from the last one seen; the first look only remembers it. */
    fun check(context: Context) {
        val now = current(context)
        val before = last
        last = now
        if (before != null && before != now) onChanged?.invoke(context.applicationContext, now)
    }

    /** `#RRGGBB`, the form a plugin reads. Pure. */
    fun hex(argb: Int): String = String.format("#%06X", argb and 0xFFFFFF)

    /** What `ui.get_theme` answers: `{theme: "light"|"dark", tokens: {name: "#RRGGBB"}}`. Pure. */
    fun toJson(set: Set): JSONObject = JSONObject()
        .put("theme", if (set.light) "light" else "dark")
        .put("tokens", JSONObject().apply { NAMES.forEach { put(it, hex(set.color(it))) } })
}
