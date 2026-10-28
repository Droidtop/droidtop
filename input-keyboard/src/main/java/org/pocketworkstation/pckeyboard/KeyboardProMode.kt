package org.pocketworkstation.pckeyboard

import android.content.Context
import android.content.SharedPreferences
import android.preference.PreferenceCategory
import android.preference.PreferenceGroup
import org.xmlpull.v1.XmlPullParser

/**
 * Pro mode (Droidtop/tracker#337; docs/SPEC.md 6a). The owner, 2026-10-08: "It is extra important that we keep Hacker
 * Keyboard flexibility, though we can probably put it behind a pro mode toggle?"
 *
 * With pro mode off the settings screen is short: size, layout and theme, languages, sound and vibration, capitals,
 * suggestions with the dictionaries, glide typing, offline voice typing and droidtop's editing helpers ([SIMPLE]). With
 * it on it is Hacker's Keyboard's whole tree. Nothing is removed: a hidden option keeps its value and keeps working,
 * and turning pro mode off changes no value. An install
 * that already has keyboard settings starts in pro mode, so nobody who set the keyboard up loses sight of an option.
 * "Reset to modern defaults" asks first, then clears every option the settings screens declare back to its default,
 * keeping the person's own content ([KEPT_ON_RESET]).
 */
object KeyboardProMode {
    const val KEY = "pref_pro_mode"
    const val RESET_KEY = "pref_reset_modern"

    /** What the short screen shows, by preference key; a category stays while anything in it does. */
    val SIMPLE: Set<String> = setOf(
        KEY, RESET_KEY,
        "settings_height_portrait", "settings_height_landscape",
        "pref_keyboard_mode_portrait", "pref_keyboard_mode_landscape", "pref_screen_view",
        "pref_screen_languages",
        "auto_cap", "pref_screen_feedback",
        "show_suggestions", "pref_dictionaries", "pref_glide", "pref_offline_voice",
        "pref_tools_strip", "pref_keyboard_form", "pref_emoji", "pref_inline_autofill", "pref_incognito",
        "pref_clipboard_history", "pref_space_drag",
    )

    /** Kept by a reset: the mode itself and what the person wrote (their macros). */
    val KEPT_ON_RESET: Set<String> = setOf(KEY, "pref_macros")

    /** The settings screens whose options a reset clears. */
    private val SCREENS = intArrayOf(R.xml.prefs, R.xml.prefs_view, R.xml.prefs_actions, R.xml.prefs_feedback)

    /** Whether the short screen shows [key]. Pure. */
    @JvmStatic
    fun keepsInSimple(key: String?): Boolean = key != null && key in SIMPLE

    /** Pro mode for an install seen for the first time: on when the keyboard already holds settings. Pure. */
    @JvmStatic
    fun initialFor(storedKeys: Set<String>): Boolean = storedKeys.any { it != KEY }

    /** What a reset clears from what the screens declare. Pure. */
    @JvmStatic
    fun resetKeys(declared: Set<String>): Set<String> = declared - KEPT_ON_RESET

    /** Whether pro mode is on, deciding it once for an install that has never had it. */
    @JvmStatic
    fun isPro(prefs: SharedPreferences): Boolean {
        if (!prefs.contains(KEY)) {
            val pro = initialFor(prefs.all.keys)
            prefs.edit().putBoolean(KEY, pro).apply()
            return pro
        }
        return prefs.getBoolean(KEY, false)
    }

    /** Hides what the short screen leaves out of [group], and any category left empty. */
    @JvmStatic
    fun applySimple(group: PreferenceGroup) {
        for (i in group.preferenceCount - 1 downTo 0) {
            val preference = group.getPreference(i)
            if (preference is PreferenceCategory) {
                applySimple(preference)
                if (preference.preferenceCount == 0) group.removePreference(preference)
            } else if (!keepsInSimple(preference.key)) {
                group.removePreference(preference)
            }
        }
    }

    /** Every option key the settings screens declare. Reads resources. */
    @JvmStatic
    fun declaredKeys(context: Context): Set<String> = buildSet {
        for (screen in SCREENS) {
            context.resources.getXml(screen).use { parser ->
                while (parser.next() != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType != XmlPullParser.START_TAG) continue
                    parser.getAttributeValue(ANDROID_NS, "key")?.let(::add)
                }
            }
        }
    }

    /** Clears every declared option but [KEPT_ON_RESET]; each falls back to its default. */
    @JvmStatic
    fun reset(context: Context, prefs: SharedPreferences) {
        val editor = prefs.edit()
        resetKeys(declaredKeys(context)).forEach { editor.remove(it) }
        editor.apply()
    }

    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
}
