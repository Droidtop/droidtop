package dev.droidtop.library.settings

import android.content.Context
import dev.droidtop.runtime.R

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

    /**
     * High contrast (docs/SPEC.md 7k, "High contrast"): solid plates and no art behind text, a plain backdrop,
     * the 4dp focus ring, body text at 7:1, and sentence case. Off by default; Android's own high-contrast text
     * turns it on as well (the shell reads both).
     */
    const val KEY_HIGH_CONTRAST = "pref_global_high_contrast"

    /**
     * Sentence case for the labels themes draw in capitals (section labels, tabs, chips, eyebrows): all caps is
     * harder to read. The owner's question 4 on Droidtop/tracker#485, answered with the coordinator's default
     * (off, on under High contrast); this constant is the one place to flip it.
     */
    const val KEY_SENTENCE_CASE = "pref_global_sentence_case"
    const val SENTENCE_CASE_DEFAULT = false

    fun highContrast(context: Context): Boolean = CatalogPrefs.prefs(context).getBoolean(KEY_HIGH_CONTRAST, false)

    fun sentenceCase(context: Context): Boolean = CatalogPrefs.prefs(context).getBoolean(KEY_SENTENCE_CASE, SENTENCE_CASE_DEFAULT)

    /** The keys a surface re-reads its palette for. */
    val PALETTE_KEYS: Set<String> =
        setOf(KEY_HIGH_CONTRAST, KEY_SENTENCE_CASE, AppearancePrefs.KEY_APPEARANCE) + AppearancePrefs.OUTSIDE_GAMING_KEYS

    /** A label as a theme's caps style draws it, or as written when sentence case is on. Pure. */
    fun caps(text: String, sentenceCase: Boolean): String = if (sentenceCase) text else text.uppercase()

    fun highContrastItem(context: Context): ToggleItem = ToggleItem(
        id = KEY_HIGH_CONTRAST,
        title = words(R.string.reading_high_contrast),
        subtitle = words(R.string.reading_solid_backgrounds_behind_text_stronger),
        current = highContrast(context),
        onToggle = { ctx, on -> CatalogPrefs.prefs(ctx).edit().putBoolean(KEY_HIGH_CONTRAST, on).apply() },
    )

    fun sentenceCaseItem(context: Context): ToggleItem = ToggleItem(
        id = KEY_SENTENCE_CASE,
        title = words(R.string.reading_sentence_case_labels),
        subtitle = words(R.string.reading_section_labels_and_tabs_as),
        current = sentenceCase(context),
        onToggle = { ctx, on -> CatalogPrefs.prefs(ctx).edit().putBoolean(KEY_SENTENCE_CASE, on).apply() },
    )

    /** The Settings row that chooses it, for the Accessibility group. */
    fun infoTextItem(context: Context): ChoiceItem = ChoiceItem(
        id = KEY_INFO_TEXT,
        title = words(R.string.reading_info_text),
        subtitle = words(R.string.reading_where_a_setting_s_explanation),
        options = listOf(ChoiceOption(INFO_BOTTOM, "Bottom line"), ChoiceOption(INFO_UNDER_ROWS, "Under each row")),
        current = infoText(context),
        onSelect = { ctx, value -> CatalogPrefs.prefs(ctx).edit().putString(KEY_INFO_TEXT, value).apply() },
    )
}
