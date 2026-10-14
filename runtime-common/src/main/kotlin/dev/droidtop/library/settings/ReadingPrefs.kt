package dev.droidtop.library.settings

import android.content.Context

/**
 * How droidtop's pages present their words (docs/SPEC.md 7k, "Explanations: no tooltips"; Droidtop/
 * tracker#485): where a row's explanation is shown. Lives in :runtime-common so every renderer
 * (:shell-gamepad, :shell-default, the companion in :app) reads the one value; the Accessibility group in
 * Settings writes it. Read on each page build, never in a composition.
 */
object ReadingPrefs {
    /** Where a row's explanation shows: on the focus line under the list, or under each row. */
    const val KEY_INFO_TEXT = "pref_global_info_text"
    const val INFO_BOTTOM = "bottom"
    const val INFO_UNDER_ROWS = "rows"

    /** The default: the focus line (the final design on tracker#485, C.9). */
    const val INFO_DEFAULT = INFO_BOTTOM

    fun infoText(context: Context): String =
        CatalogPrefs.prefs(context).getString(KEY_INFO_TEXT, INFO_DEFAULT)?.takeIf { it == INFO_BOTTOM || it == INFO_UNDER_ROWS } ?: INFO_DEFAULT

    fun infoUnderRows(context: Context): Boolean = infoText(context) == INFO_UNDER_ROWS

    /** The Settings row that chooses it, for the Accessibility group. */
    fun infoTextItem(context: Context): ChoiceItem = ChoiceItem(
        id = KEY_INFO_TEXT,
        title = "Info text",
        subtitle = "Where a setting's explanation shows",
        options = listOf(ChoiceOption(INFO_BOTTOM, "Bottom line"), ChoiceOption(INFO_UNDER_ROWS, "Under each row")),
        current = infoText(context),
        onSelect = { ctx, value -> CatalogPrefs.prefs(ctx).edit().putString(KEY_INFO_TEXT, value).apply() },
    )
}
