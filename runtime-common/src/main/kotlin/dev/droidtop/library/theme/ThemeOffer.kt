package dev.droidtop.library.theme

import android.content.Context
import dev.droidtop.library.settings.LAUNCHER_PREFS_FILE_NAME
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * "Your theme is ready: Art Book Next. Use it / Later" (docs/SPEC.md 7f, Droidtop/tracker#532). A theme that finishes
 * downloading while nobody is looking at the choice is installed and offered, never made the active one under the
 * person: the whole shell used to change its look mid-session without a word. The shell draws the question from
 * [pending]; an unanswered one is kept in the preferences and raised again by [restore]. Both answers end it.
 * An explicit theme choice made meanwhile ends it too, so it is never offered over a choice.
 */
object ThemeOffer {
    /** The theme's folder name (what [ThemePrefs] stores) and the name to show. */
    data class Offer(val dir: String, val name: String)

    private const val PREFS_NAME = LAUNCHER_PREFS_FILE_NAME
    private const val KEY_DIR = "droidtop_theme_offer_dir"
    private const val KEY_NAME = "droidtop_theme_offer_name"

    private val pendingState = MutableStateFlow<Offer?>(null)

    /** The question waiting to be answered on screen, or null. */
    val pending: StateFlow<Offer?> get() = pendingState

    /** Called when [dir] finished downloading and was not activated; nothing is offered over a theme the person chose. */
    fun offer(context: Context, dir: String, name: String) {
        if (ThemePrefs.get(context) != null) return
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_DIR, dir).putString(KEY_NAME, name).apply()
        pendingState.value = Offer(dir, name)
    }

    /** Raises a question left unanswered by an earlier process. Reads preferences, so call it off the main thread. */
    fun restore(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val dir = prefs.getString(KEY_DIR, null) ?: return
        val name = prefs.getString(KEY_NAME, null) ?: dir
        if (ThemePrefs.get(context) != null) clear(context) else pendingState.value = Offer(dir, name)
    }

    /** The answer: yes makes the theme the active one, no is remembered by the theme staying in Browse themes only. */
    fun respond(context: Context, use: Boolean) {
        val offer = pendingState.value
        clear(context)
        if (use && offer != null && ThemePrefs.get(context) == null) ThemePrefs.set(context, offer.dir)
    }

    private fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().remove(KEY_DIR).remove(KEY_NAME).apply()
        pendingState.value = null
    }
}
