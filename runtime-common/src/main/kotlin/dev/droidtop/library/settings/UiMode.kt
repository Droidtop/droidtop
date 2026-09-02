package dev.droidtop.library.settings

import android.content.Context

/**
 * How much of the shell a person is allowed to see (real ES-DE's own UI
 * modes, which exist for the same reason: a handheld gets handed to
 * somebody else).
 *
 * FULL is droidtop as normal. KIOSK hides the parts that change the
 * device rather than play a game -- Settings, and the destructive
 * actions inside gamelist options. KID additionally shows only games
 * marked as kid-friendly (the `kidGame` flag droidtop already stores per
 * game and real ES-DE calls the same thing).
 *
 * The way OUT deliberately does not live in Settings, because Settings
 * is the thing being hidden: the Quick Menu's System tab keeps a row for
 * it. That is a real escape hatch a parent can find and a child is
 * unlikely to stumble into, without pretending a passcode droidtop does
 * not have.
 */
enum class UiMode(val label: String) {
    FULL("Full"),
    KIOSK("Kiosk (no settings)"),
    KID("Kid (kid-friendly games only)"),
    ;

    val hidesSettings: Boolean get() = this != FULL
    val kidGamesOnly: Boolean get() = this == KID
}

object UiModePrefs {
    private const val PREFS_NAME = LAUNCHER_PREFS_FILE_NAME
    private const val KEY = "droidtop_ui_mode"

    fun get(context: Context): UiMode {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return UiMode.FULL
        return runCatching { UiMode.valueOf(raw) }.getOrDefault(UiMode.FULL)
    }

    fun set(context: Context, mode: UiMode) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY, mode.name).apply()
    }
}

/**
 * The live UI mode, so a change made in the Quick Menu reaches the shell
 * without a restart. Same "one writer bumps, everyone observes" shape
 * the theme and collection refreshes already use.
 */
object UiModeRefresh {
    private val state = kotlinx.coroutines.flow.MutableStateFlow(UiMode.FULL)
    val mode: kotlinx.coroutines.flow.StateFlow<UiMode> = state

    /** Call once at shell start so the flow reflects what is stored. */
    fun load(context: Context) {
        state.value = UiModePrefs.get(context)
    }

    fun set(context: Context, mode: UiMode) {
        UiModePrefs.set(context, mode)
        state.value = mode
    }
}
