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
     * Colours outside Gaming (docs/SPEC.md 7k, "Colours outside Gaming"; owner, 2026-10-30, Droidtop/tracker#827): what
     * Mobile, Desktop, Mobile's taskbar and the shared menus are drawn in. [MATCH_GAMING], the default and droidtop's
     * recommendation, is the selected Gaming theme's resolved colours; [CUSTOM] is a droidtop-only palette in the accent
     * [KEY_CUSTOM_ACCENT] names. [dev.droidtop.library.theme.OutsideGamingColors] is the one reader.
     */
    const val KEY_OUTSIDE_GAMING = "pref_global_outside_gaming_colours"
    const val MATCH_GAMING = "match_gaming"
    const val CUSTOM = "custom"

    /** The Custom droidtop theme's accent, one of [dev.droidtop.library.theme.OutsideGamingColors.ACCENTS] by id. */
    const val KEY_CUSTOM_ACCENT = "pref_global_custom_accent"

    /** The keys the colours outside Gaming are worked out again for. */
    val OUTSIDE_GAMING_KEYS: Set<String> = setOf(KEY_OUTSIDE_GAMING, KEY_CUSTOM_ACCENT)

    fun outsideGaming(context: Context): String =
        CatalogPrefs.prefs(context).getString(KEY_OUTSIDE_GAMING, MATCH_GAMING)?.takeIf { it == CUSTOM } ?: MATCH_GAMING

    fun customAccent(context: Context): String? = CatalogPrefs.prefs(context).getString(KEY_CUSTOM_ACCENT, null)

    /** The Settings row that chooses where the colours outside Gaming come from. */
    fun outsideGamingItem(context: Context): ChoiceItem = ChoiceItem(
        id = KEY_OUTSIDE_GAMING,
        title = words(R.string.appearance_colours_outside_gaming),
        subtitle = words(R.string.appearance_colours_outside_gaming_explained),
        options = listOf(
            ChoiceOption(MATCH_GAMING, words(R.string.appearance_match_the_gaming_theme)),
            ChoiceOption(CUSTOM, words(R.string.appearance_custom_droidtop_theme)),
        ),
        current = outsideGaming(context),
        onSelect = { ctx, value -> CatalogPrefs.prefs(ctx).edit().putString(KEY_OUTSIDE_GAMING, value).apply() },
    )

    /** The Settings row that picks the Custom droidtop theme's accent. */
    fun customAccentItem(context: Context): ChoiceItem = ChoiceItem(
        id = KEY_CUSTOM_ACCENT,
        title = words(R.string.appearance_custom_accent),
        subtitle = words(R.string.appearance_custom_accent_explained),
        options = dev.droidtop.library.theme.OutsideGamingColors.ACCENTS.map { ChoiceOption(it.id, words(it.label)) },
        current = dev.droidtop.library.theme.OutsideGamingColors.accentOf(customAccent(context)).id,
        onSelect = { ctx, value -> CatalogPrefs.prefs(ctx).edit().putString(KEY_CUSTOM_ACCENT, value).apply() },
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
