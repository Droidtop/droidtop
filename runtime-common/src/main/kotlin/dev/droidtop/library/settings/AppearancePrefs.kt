package dev.droidtop.library.settings

import android.content.Context
import android.content.res.Configuration
import dev.droidtop.runtime.R

/**
 * Light or dark for droidtop's whole UI (docs/SPEC.md 7k, "Light and dark"; decided 2026-10-16, Droidtop/tracker#559):
 * Light, Dark, or Follow Android, the default. Every surface takes its colours from the one palette this chooses
 * between: the Gaming theme's scheme whose ground matches, else one derived for it, else droidtop's own light or dark
 * default. Lives in :runtime-common so every renderer reads the one value; Settings > Appearance writes it.
 */
object AppearancePrefs {
    const val KEY_APPEARANCE = "pref_global_appearance"
    const val FOLLOW_ANDROID = "system"
    const val LIGHT = "light"
    const val DARK = "dark"

    fun choice(context: Context): String =
        CatalogPrefs.prefs(context).getString(KEY_APPEARANCE, FOLLOW_ANDROID)?.takeIf { it == LIGHT || it == DARK } ?: FOLLOW_ANDROID

    /** Whether droidtop draws light now, from [context]'s configuration for Follow Android. */
    fun light(context: Context): Boolean = light(choice(context), context.resources.configuration.uiMode)

    /** [choice] resolved against Android's night mode in [uiMode] (a Configuration.uiMode). Pure, for tests. */
    fun light(choice: String, uiMode: Int): Boolean = when (choice) {
        LIGHT -> true
        DARK -> false
        else -> (uiMode and Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_YES
    }

    /**
     * The Settings row that chooses it. Built where the settings catalog is built (it reads the active theme, as the
     * theme rows beside it do). When the Gaming theme has a colour scheme of its own chosen, that scheme wins over this
     * on every Gaming-themed screen, so the row says so ON the row, as its chip with the scheme's own label, and how
     * to undo it in its explanation (rig 2026-10-16, Droidtop/tracker#559: the settings page stayed dark on Light
     * until the scheme was Automatic; the follow-up rig found the explanation alone unseen, since Gaming keeps a
     * subtitle on the focus line and in Info).
     */
    fun item(context: Context): ChoiceItem {
        val scheme = chosenScheme(context)
        return ChoiceItem(
            id = KEY_APPEARANCE,
            title = words(R.string.appearance_light_or_dark),
            subtitle = scheme?.let {
                "The Gaming theme's colour scheme is set to $it, which droidtop's screens keep whatever this says; " +
                    "set Theme color scheme to Automatic to follow it"
            } ?: "How droidtop draws its screens; the Gaming theme's matching colour scheme is used when it has one",
            options = listOf(
                ChoiceOption(FOLLOW_ANDROID, "Follow Android"),
                ChoiceOption(LIGHT, "Light"),
                ChoiceOption(DARK, "Dark"),
            ),
            current = choice(context),
            onSelect = { ctx, value -> CatalogPrefs.prefs(ctx).edit().putString(KEY_APPEARANCE, value).apply() },
            chip = if (scheme != null) RowTag.SET_BY_THEME else null,
        )
    }

    /**
     * The colour source (docs/SPEC.md 7k, "Colours outside Gaming"; owner, 2026-10-30, Droidtop/tracker#827 and #844):
     * what Mobile, Desktop, Mobile's taskbar and the shared menus are drawn in. [DEVICE] is Android's own system
     * palette (Android 12 and later); [GAMING_THEME] is the selected Gaming theme's resolved colours; [CUSTOM] is a
     * droidtop-only palette in the accent [KEY_CUSTOM_ACCENT] names. Nothing stored is [defaultSource].
     * [dev.droidtop.library.theme.OutsideGamingColors] is the one reader.
     *
     * The key and the two values the first version stored ([GAMING_THEME] as "match_gaming", [CUSTOM]) are kept, so a
     * choice made then still means what it meant.
     */
    const val KEY_OUTSIDE_GAMING = "pref_global_outside_gaming_colours"
    const val DEVICE = "device"
    const val GAMING_THEME = "match_gaming"
    const val CUSTOM = "custom"

    /** The Custom droidtop theme's accent, one of [dev.droidtop.library.theme.OutsideGamingColors.ACCENTS] by id. */
    const val KEY_CUSTOM_ACCENT = "pref_global_custom_accent"

    /**
     * "Tint themes to my colours" (Droidtop/tracker#844), off by default: the Gaming theme's accent and highlight become
     * the colour source's, Device or Custom; with Gaming theme as the source it has nothing to tint with.
     */
    const val KEY_TINT_THEMES = "pref_global_tint_themes"

    /** The keys the colours, outside Gaming and the Gaming palette's tint, are worked out again for. */
    val OUTSIDE_GAMING_KEYS: Set<String> = setOf(KEY_OUTSIDE_GAMING, KEY_CUSTOM_ACCENT, KEY_TINT_THEMES)

    /** The first Android with a system palette (Android 12, API 31). */
    const val FIRST_SDK_WITH_PALETTE = 31

    /** The source when none is chosen: Device colours where the device has a system palette, else Gaming theme. Pure. */
    fun defaultSource(sdkInt: Int, hasPalette: Boolean): String =
        if (sdkInt >= FIRST_SDK_WITH_PALETTE && hasPalette) DEVICE else GAMING_THEME

    /**
     * The source [stored] means on this device: one of the three, the default when nothing (or nothing known) is
     * stored, and Gaming theme for Device colours on a device without a system palette (a backup restored onto an
     * older one). Pure.
     */
    fun sourceOf(stored: String?, sdkInt: Int, hasPalette: Boolean): String {
        val palette = sdkInt >= FIRST_SDK_WITH_PALETTE && hasPalette
        return when (stored) {
            GAMING_THEME -> GAMING_THEME
            CUSTOM -> CUSTOM
            DEVICE -> if (palette) DEVICE else GAMING_THEME
            else -> defaultSource(sdkInt, hasPalette)
        }
    }

    /** The colour source in use. Reads a preference and the system palette's presence. */
    fun colourSource(context: Context): String = sourceOf(
        CatalogPrefs.prefs(context).getString(KEY_OUTSIDE_GAMING, null),
        android.os.Build.VERSION.SDK_INT,
        dev.droidtop.library.theme.OutsideGamingColors.devicePalette(context) != null,
    )

    fun customAccent(context: Context): String? = CatalogPrefs.prefs(context).getString(KEY_CUSTOM_ACCENT, null)

    fun tintThemes(context: Context): Boolean = CatalogPrefs.prefs(context).getBoolean(KEY_TINT_THEMES, false)

    /** The Settings row that chooses the colour source; Device colours only where there is a system palette. */
    fun colourSourceItem(context: Context): ChoiceItem {
        val palette = dev.droidtop.library.theme.OutsideGamingColors.devicePalette(context) != null
        return ChoiceItem(
            id = KEY_OUTSIDE_GAMING,
            title = words(R.string.appearance_colour_source),
            subtitle = words(R.string.appearance_colour_source_explained),
            options = listOfNotNull(
                if (palette) ChoiceOption(DEVICE, words(R.string.appearance_device_colours)) else null,
                ChoiceOption(GAMING_THEME, words(R.string.appearance_gaming_theme)),
                ChoiceOption(CUSTOM, words(R.string.appearance_custom_droidtop_theme)),
            ),
            current = colourSource(context),
            onSelect = { ctx, value -> CatalogPrefs.prefs(ctx).edit().putString(KEY_OUTSIDE_GAMING, value).apply() },
        )
    }

    /** The Settings row that picks the Custom droidtop theme's accent. */
    fun customAccentItem(context: Context): ChoiceItem = ChoiceItem(
        id = KEY_CUSTOM_ACCENT,
        title = words(R.string.appearance_custom_accent),
        subtitle = words(R.string.appearance_custom_accent_explained),
        options = dev.droidtop.library.theme.OutsideGamingColors.ACCENTS.map { ChoiceOption(it.id, words(it.label)) },
        current = dev.droidtop.library.theme.OutsideGamingColors.accentOf(customAccent(context)).id,
        onSelect = { ctx, value -> CatalogPrefs.prefs(ctx).edit().putString(KEY_CUSTOM_ACCENT, value).apply() },
    )

    /**
     * The Settings row for "Tint themes to my colours". Its explanation warns that art with colours drawn into it can
     * look off; with Gaming theme as the source it says instead that it has nothing to tint with, and does nothing.
     */
    fun tintThemesItem(context: Context): ToggleItem = ToggleItem(
        id = KEY_TINT_THEMES,
        title = words(R.string.appearance_tint_themes),
        subtitle = words(
            if (colourSource(context) == GAMING_THEME) R.string.appearance_tint_themes_off_with_gaming_theme
            else R.string.appearance_tint_themes_explained,
        ),
        current = tintThemes(context),
        onToggle = { ctx, on -> CatalogPrefs.prefs(ctx).edit().putBoolean(KEY_TINT_THEMES, on).apply() },
    )

    /** The label [schemeLabels] gives [scheme], else [scheme] itself; null stays null. Pure, for tests. */
    fun schemeLabel(scheme: String?, schemeLabels: Map<String, String>): String? = scheme?.let { schemeLabels[it] ?: it }

    /** The colour scheme the person named for the active Gaming theme, by its label, or null when it is Automatic or there is no theme. */
    private fun chosenScheme(context: Context): String? = runCatching {
        val theme = dev.droidtop.library.theme.ThemeAssets.activeThemeName(context) ?: return null
        val labels = dev.droidtop.library.theme.ThemeAssets.activeThemeCapabilities(context)?.colorSchemeLabels.orEmpty()
        schemeLabel(dev.droidtop.library.theme.ThemePrefs.colorScheme(context, theme), labels)
    }.getOrNull()
}
