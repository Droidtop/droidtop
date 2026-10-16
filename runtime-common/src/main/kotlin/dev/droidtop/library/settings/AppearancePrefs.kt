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

    /** The Settings row that chooses it. */
    fun item(context: Context): ChoiceItem = ChoiceItem(
        id = KEY_APPEARANCE,
        title = "Light or dark",
        subtitle = "How droidtop draws its screens; the Gaming theme's matching colour scheme is used when it has one",
        options = listOf(
            ChoiceOption(FOLLOW_ANDROID, "Follow Android"),
            ChoiceOption(LIGHT, "Light"),
            ChoiceOption(DARK, "Dark"),
        ),
        current = choice(context),
        onSelect = { ctx, value -> CatalogPrefs.prefs(ctx).edit().putString(KEY_APPEARANCE, value).apply() },
    )
}
