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
 * One setting ([AppearancePrefs.KEY_OUTSIDE_GAMING]) with two choices: Match the Gaming theme, the default, is
 * [ThemeColorStore]'s resolved colours; Custom droidtop theme is droidtop's own palette for Light or dark in one of
 * [ACCENTS]. This is the one reader: nothing outside Gaming picks its colours anywhere else.
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

    /** The setting's choice: [matched] (the Gaming theme's, [ThemeColorStore]) unless [choice] is Custom. Pure. */
    fun choose(choice: String, matched: GamingThemeColors, accent: Accent, light: Boolean, highContrast: Boolean): GamingThemeColors =
        if (choice == AppearancePrefs.CUSTOM) custom(accent, light, highContrast) else matched

    private val published = MutableStateFlow<GamingThemeColors?>(null)
    private val customState = MutableStateFlow(false)

    /** Whether the Custom droidtop theme is chosen, as of the last [publish]: Desktop wears the Gaming theme only when it is not. */
    val customChosen: StateFlow<Boolean> get() = customState

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
        val choice = AppearancePrefs.outsideGaming(context)
        val next = choose(
            choice,
            matched,
            accentOf(AppearancePrefs.customAccent(context)),
            AppearancePrefs.light(context),
            ThemeColorStore.highContrast(context),
        )
        customState.value = choice == AppearancePrefs.CUSTOM
        if (published.value != next) {
            published.value = next
            // The role tokens plugins read outside Gaming are these colours (RoleTokens.current).
            RoleTokens.checkSoon()
        }
    }
}
