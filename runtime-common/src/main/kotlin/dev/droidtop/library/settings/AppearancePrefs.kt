package dev.droidtop.library.settings

import android.content.Context
import android.content.res.Configuration

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
            title = "Light or dark",
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
            chip = schemeChip(scheme),
        )
    }

    /** The row's chip while the theme's named scheme [schemeLabel] overrides it, or null. Pure, for tests. */
    fun schemeChip(schemeLabel: String?): String? = schemeLabel?.let { "Theme keeps $it" }

    /** The label [schemeLabels] gives [scheme], else [scheme] itself; null stays null. Pure, for tests. */
    fun schemeLabel(scheme: String?, schemeLabels: Map<String, String>): String? = scheme?.let { schemeLabels[it] ?: it }

    /** The colour scheme the person named for the active Gaming theme, by its label, or null when it is Automatic or there is no theme. */
    private fun chosenScheme(context: Context): String? = runCatching {
        val theme = dev.droidtop.library.theme.ThemeAssets.activeThemeName(context) ?: return null
        val labels = dev.droidtop.library.theme.ThemeAssets.activeThemeCapabilities(context)?.colorSchemeLabels.orEmpty()
        schemeLabel(dev.droidtop.library.theme.ThemePrefs.colorScheme(context, theme), labels)
    }.getOrNull()
}
