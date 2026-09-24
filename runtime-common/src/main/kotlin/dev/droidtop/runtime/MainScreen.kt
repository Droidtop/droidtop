package dev.droidtop.runtime

import android.content.Context
import dev.droidtop.library.settings.LAUNCHER_PREFS_FILE_NAME

/**
 * Which panel is the main output: the one persisted answer to "where does
 * the shell render" (docs/SPEC.md section 4, physical position and manual
 * override). Gaming and Desktop both read it; the other panel gets that
 * mode's companion or input surface.
 *
 * Relative, never a display id. Android assigns display ids by
 * connection order and hands the add-on a new one when it re-enumerates,
 * so a choice keyed by id is silently forgotten -- or, worse, applied to
 * the wrong panel -- after a replug. "The second screen when one is
 * connected" stays true whichever id it came up with, which is the
 * relative-targeting lesson of section 4c applied to the shell itself.
 */
enum class MainScreenChoice {
    /** The second display when one is present, built-in otherwise. The default: the add-on is the better screen. */
    SECOND_WHEN_PRESENT,

    /** Always the built-in screen; a second display gets the other surface. */
    BUILT_IN,
    ;

    fun flipped(): MainScreenChoice = if (this == SECOND_WHEN_PRESENT) BUILT_IN else SECOND_WHEN_PRESENT
}

object MainScreen {
    /** Also the settings catalog item's id, so the row and this reader share one key. */
    const val KEY = "pref_display_shell_target"

    // The per-display-id assignment store this replaced. Read once so a
    // swap made before the change is not lost, then deleted.
    private const val LEGACY_ASSIGNMENT_FILE = "dual_screen_assignment"

    fun choice(context: Context): MainScreenChoice {
        val prefs = context.getSharedPreferences(LAUNCHER_PREFS_FILE_NAME, Context.MODE_PRIVATE)
        prefs.getString(KEY, null)?.let { stored ->
            return runCatching { MainScreenChoice.valueOf(stored) }.getOrNull()
                ?: MainScreenChoice.SECOND_WHEN_PRESENT
        }
        val legacy = context.getSharedPreferences(LEGACY_ASSIGNMENT_FILE, Context.MODE_PRIVATE)
        val migrated = fromLegacyAssignment(legacy.all.mapValues { it.value as? String })
        if (legacy.all.isNotEmpty()) legacy.edit().clear().apply()
        if (migrated != null) prefs.edit().putString(KEY, migrated.name).apply()
        return migrated ?: MainScreenChoice.SECOND_WHEN_PRESENT
    }

    /**
     * Writes the choice and re-runs orchestration, so the shell moves now
     * rather than at the next unrelated display event.
     */
    fun set(context: Context, choice: MainScreenChoice) {
        context.getSharedPreferences(LAUNCHER_PREFS_FILE_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY, choice.name).apply()
        DisplayArrangement.changed()
    }

    /**
     * The old store mapped display ids ("0" is always the built-in panel)
     * to UPPER_OUTPUT/LOWER_INPUT. Whichever panel was UPPER_OUTPUT is the
     * main screen. Null when there is nothing usable to carry over.
     */
    internal fun fromLegacyAssignment(assignment: Map<String, String?>): MainScreenChoice? {
        val upper = assignment.entries.firstOrNull { it.value == "UPPER_OUTPUT" }?.key ?: return null
        return if (upper == "0") MainScreenChoice.BUILT_IN else MainScreenChoice.SECOND_WHEN_PRESENT
    }
}

/**
 * The user-facing display actions: swap which panel is the main output,
 * and force a fresh detection pass.
 *
 * These live here rather than on the Activity because a settings catalog
 * item only ever gets a `Context`.
 *
 * [refresh] is the signal back to whatever is orchestrating displays:
 * writing a display preference changes no `DisplayManager` state, so
 * nothing would re-emit on its own and a swap would appear to do nothing
 * until the next unrelated display event.
 */
object DisplayArrangement {
    val refresh = kotlinx.coroutines.flow.MutableStateFlow(0)

    /** Any display preference changed: re-run orchestration now. */
    fun changed() {
        refresh.value++
    }

    /**
     * Flips which panel is the main output and remembers it. Returns a
     * line for the user, since the screen they are reading may be the one
     * that just moved.
     */
    fun swap(context: Context): String {
        val outputs = DisplayOutputRepository(context).currentOutputsSnapshot()
        val second = outputs.firstOrNull { it.kind == DisplayOutputKind.SECOND_SCREEN }
            ?: return "Only one display is connected."
        val next = MainScreen.choice(context).flipped()
        MainScreen.set(context, next)
        return if (next == MainScreenChoice.SECOND_WHEN_PRESENT) {
            "Main screen is now ${second.name.ifBlank { "the second screen" }}."
        } else {
            "Main screen is now the built-in screen."
        }
    }

    /** Re-runs detection and orchestration from scratch. */
    fun reinitialize(): String {
        changed()
        return "Displays reinitialized."
    }
}
