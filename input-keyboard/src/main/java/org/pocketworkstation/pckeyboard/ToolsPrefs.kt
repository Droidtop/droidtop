package org.pocketworkstation.pckeyboard

import android.content.Context
import android.content.SharedPreferences
import android.preference.PreferenceManager
import android.view.inputmethod.EditorInfo

/**
 * The settings of the keyboard's editing helpers (docs/SPEC.md 6a, "Editing helpers", Droidtop/tracker#340). They
 * live in the same default preference store as every other Hacker's Keyboard setting, so the settings screen, the
 * input method and the panels droidtop draws all read one value.
 */
object ToolsPrefs {
    /** The strip of tool buttons above the keys (clipboard history, macros, incognito). */
    const val STRIP = "pref_tools_strip"

    /** No learning of typed words and no clipboard history while on. */
    const val INCOGNITO = "pref_incognito"

    /** Whether copied text is recorded into the clipboard history. */
    const val CLIPBOARD = "pref_clipboard_history"

    /** Dragging a finger along the space bar moves the cursor. */
    const val SPACE_DRAG = "pref_space_drag"

    /** The emoji panel and its search. */
    const val EMOJI = "pref_emoji"

    /** The emoji used last, newest first, one per line. */
    const val RECENT_EMOJI = "pref_emoji_recent"

    /** The macros, one per line (see [MacroParser]). */
    const val MACROS = "pref_macros"

    @Suppress("DEPRECATION")
    fun prefs(context: Context): SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

    fun strip(context: Context): Boolean = prefs(context).getBoolean(STRIP, true)

    fun incognito(context: Context): Boolean = prefs(context).getBoolean(INCOGNITO, false)

    fun clipboardHistory(context: Context): Boolean = prefs(context).getBoolean(CLIPBOARD, true)

    fun emoji(context: Context): Boolean = prefs(context).getBoolean(EMOJI, true)

    fun spaceDrag(context: Context): Boolean = prefs(context).getBoolean(SPACE_DRAG, true)

    fun macros(context: Context): List<Macro> = MacroParser.parse(prefs(context).getString(MACROS, "") ?: "")
}

/** When the keyboard may learn from what is typed (Droidtop/tracker#340). */
object IncognitoRules {
    /**
     * Learning is off in incognito mode, in a password field and where the editor asked for no personalised
     * learning (`IME_FLAG_NO_PERSONALIZED_LEARNING`).
     */
    @JvmStatic
    fun learningAllowed(incognito: Boolean, imeOptions: Int, passwordField: Boolean): Boolean =
        !incognito && !passwordField && imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING == 0
}
