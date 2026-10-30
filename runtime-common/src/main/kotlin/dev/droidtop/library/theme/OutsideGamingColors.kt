package dev.droidtop.library.theme

import android.content.Context
import androidx.annotation.StringRes
import dev.droidtop.library.settings.AppearancePrefs
import dev.droidtop.runtime.R
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull

/**
 * The colours everything outside Gaming draws in (docs/SPEC.md 7k, "Colours outside Gaming"): Mobile's screens and
 * taskbar, Desktop, the shared menus, and the role tokens plugins read there. The owner, 2026-10-30,
 * Droidtop/tracker#827: "Maybe we set the default coloration to the retro theme, and let users pick between that (the
 * "droidtop recommendation: consistent coloration across all themes" option), and a custom droidtop-only theme".
 *
 * One setting, the colour source ([AppearancePrefs.KEY_OUTSIDE_GAMING]), with three choices (Droidtop/tracker#844,
 * owner: "That feels like a good coloring implementation"): Device colours, Android's system palette
 * ([GamingThemeMapping.fromDevicePalette]), the default where there is one; Gaming theme, [ThemeColorStore]'s resolved
 * colours, the default elsewhere; Custom droidtop theme, droidtop's own palette for Light or dark in one of [ACCENTS].
 * This is the one reader: nothing outside Gaming picks its colours anywhere else.
 */
object OutsideGamingColors {
    /** A Custom droidtop theme accent: its stored [id], its name in Settings and its colour. */
    data class Accent(val id: String, @StringRes val label: Int, val argb: Int)

    /**
     * The Custom droidtop theme's accents, deliberately few: droidtop blue (the default, droidtop's own palette as it
     * is) and five more, each on droidtop's own palette ([GamingThemeMapping.withAccent]).
     */
    val ACCENTS: List<Accent> = listOf(
        Accent("blue", R.string.appearance_accent_droidtop_blue, 0xFF3BB5FF.toInt()),
        Accent("teal", R.string.appearance_accent_teal, 0xFF2EC4B6.toInt()),
        Accent("green", R.string.appearance_accent_green, 0xFF5BC85B.toInt()),
        Accent("violet", R.string.appearance_accent_violet, 0xFFA67CFF.toInt()),
        Accent("pink", R.string.appearance_accent_pink, 0xFFFF6FB5.toInt()),
        Accent("orange", R.string.appearance_accent_orange, 0xFFFF8A3D.toInt()),
    )

    /** The accent stored as [id]; droidtop blue when none is, or the id is not one of [ACCENTS]. Pure. */
    fun accentOf(id: String?): Accent = ACCENTS.firstOrNull { it.id == id } ?: ACCENTS.first()

    /**
     * The Custom droidtop theme: droidtop's own palette for [light] ([GamingThemeMapping.defaultFor]) in [accent],
     * under High contrast when it is on. droidtop blue is droidtop's own palette exactly. Pure.
     */
    fun custom(accent: Accent, light: Boolean, highContrast: Boolean): GamingThemeColors {
        val base = GamingThemeMapping.defaultFor(light)
        val colors = if (accent == ACCENTS.first()) base else GamingThemeMapping.withAccent(base, accent.argb)
        return if (highContrast) GamingThemeMapping.highContrast(colors) else colors
    }

    /**
     * The colour source's colours: [device] (Android's system palette, already mapped) for Device colours, the Custom
     * droidtop theme for Custom, else [matched] (the Gaming theme's, [ThemeColorStore]). Device colours with no system
     * palette read is the Gaming theme's. Pure.
     */
    fun choose(
        source: String,
        matched: GamingThemeColors,
        device: GamingThemeColors?,
        accent: Accent,
        light: Boolean,
        highContrast: Boolean,
    ): GamingThemeColors = when (source) {
        AppearancePrefs.CUSTOM -> custom(accent, light, highContrast)
        AppearancePrefs.DEVICE -> device?.let { if (highContrast) GamingThemeMapping.highContrast(it) else it } ?: matched
        else -> matched
    }

    /**
     * The accent "Tint themes to my colours" gives the Gaming theme ([GamingThemeMapping.tint]): the active palette's
     * ([active], the Device or Custom source's colours) while the toggle is on, and none while it is off or the source
     * is the Gaming theme itself. Pure.
     */
    fun tintAccent(source: String, tintOn: Boolean, active: GamingThemeColors?): Int? =
        if (!tintOn || source == AppearancePrefs.GAMING_THEME) null else active?.accent

    /**
     * The accent the Gaming theme is tinted with now, or null ([tintAccent]): what [GamingThemeMapping.resolved] is
     * given by the Gaming palette and [ThemeColorStore] alike. Reads preferences and the system palette.
     */
    fun tintFor(context: Context, light: Boolean): Int? {
        if (!AppearancePrefs.tintThemes(context)) return null
        val source = AppearancePrefs.colourSource(context)
        val active = when (source) {
            AppearancePrefs.CUSTOM -> custom(accentOf(AppearancePrefs.customAccent(context)), light, highContrast = false)
            AppearancePrefs.DEVICE -> devicePalette(context)?.let { GamingThemeMapping.fromDevicePalette(it, light) }
            else -> null
        }
        return tintAccent(source, tintOn = true, active = active)
    }

    /**
     * Android's system palette (Material You), read from its resource colours without any permission, or null before
     * Android 12 or where the palette is not filled in (every tone the same). Reads resources: not the main thread's
     * work in a loop.
     */
    fun devicePalette(context: Context): GamingThemeMapping.DevicePalette? {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return null
        val palette = try {
            readPalette(context)
        } catch (e: Exception) {
            return null
        }
        return palette.takeIf { it.accent1.toSet().size > 1 && it.neutral1.toSet().size > 1 }
    }

    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.S)
    private fun readPalette(context: Context): GamingThemeMapping.DevicePalette {
        val accent1 = listOf(
            android.R.color.system_accent1_0, android.R.color.system_accent1_10, android.R.color.system_accent1_50,
            android.R.color.system_accent1_100, android.R.color.system_accent1_200, android.R.color.system_accent1_300,
            android.R.color.system_accent1_400, android.R.color.system_accent1_500, android.R.color.system_accent1_600,
            android.R.color.system_accent1_700, android.R.color.system_accent1_800, android.R.color.system_accent1_900,
            android.R.color.system_accent1_1000,
        )
        val neutral1 = listOf(
            android.R.color.system_neutral1_0, android.R.color.system_neutral1_10, android.R.color.system_neutral1_50,
            android.R.color.system_neutral1_100, android.R.color.system_neutral1_200, android.R.color.system_neutral1_300,
            android.R.color.system_neutral1_400, android.R.color.system_neutral1_500, android.R.color.system_neutral1_600,
            android.R.color.system_neutral1_700, android.R.color.system_neutral1_800, android.R.color.system_neutral1_900,
            android.R.color.system_neutral1_1000,
        )
        return GamingThemeMapping.DevicePalette(accent1.map { context.getColor(it) }, neutral1.map { context.getColor(it) })
    }

    private val published = MutableStateFlow<GamingThemeColors?>(null)
    private val gamingSourceState = MutableStateFlow(true)

    /** Whether the colour source is the Gaming theme, as of the last [publish]: Desktop wears the whole Gaming theme only then. */
    val gamingThemeSource: StateFlow<Boolean> get() = gamingSourceState

    /** The colours now: a cheap read, droidtop's own palette until the first resolve in this process has finished. */
    fun current(context: Context): GamingThemeColors {
        ThemeColorStore.start(context)
        return published.value ?: GamingThemeColors.DEFAULT
    }

    /** The colours as they change, from the first resolve on. */
    fun colors(context: Context): Flow<GamingThemeColors> {
        ThemeColorStore.start(context)
        return published.filterNotNull()
    }

    /** From [ThemeColorStore], off the main thread, whenever it has looked again: reads the setting and publishes. */
    internal fun publish(context: Context, matched: GamingThemeColors) {
        val choice = AppearancePrefs.colourSource(context)
        val light = AppearancePrefs.light(context)
        // Read again on every look: a new wallpaper's palette arrives as a configuration change, which looks again.
        val device = if (choice == AppearancePrefs.DEVICE) devicePalette(context)?.let { GamingThemeMapping.fromDevicePalette(it, light) } else null
        val next = choose(
            choice,
            matched,
            device,
            accentOf(AppearancePrefs.customAccent(context)),
            light,
            ThemeColorStore.highContrast(context),
        )
        gamingSourceState.value = choice == AppearancePrefs.GAMING_THEME
        if (published.value != next) {
            published.value = next
            // The role tokens plugins read outside Gaming are these colours (RoleTokens.current).
            RoleTokens.checkSoon()
        }
    }
}
