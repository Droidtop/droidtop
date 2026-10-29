package dev.droidtop.runtime

import android.content.Context
import android.view.Display
import dev.droidtop.library.settings.CatalogPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Which of the two screens a window is on: the one the shell renders on, or the other one. */
enum class DisplayRole { MAIN, COMPANION }

/**
 * Settings > Displays > Interface size, one for each screen role (owner, 2026-10-29, Droidtop/tracker#656 point 3):
 * Auto, then 75 to 150 percent. It multiplies the canvas density the shell already works out from what that display
 * reports (`canvasDensity`, docs/SPEC.md 7k), so layout and text scale together, and the main screen and the companion
 * screen are set separately. Auto is the canvas as reported, never a size picked from the device model.
 */
object InterfaceSize {
    const val KEY_MAIN = "pref_display_interface_size_main"
    const val KEY_COMPANION = "pref_display_interface_size_companion"
    const val AUTO = "auto"

    /** The sizes offered after Auto, in percent. Auto is 100. */
    val PERCENTS = listOf(75, 90, 110, 125, 150)

    private val changes = MutableStateFlow(0)

    /** Bumps when either size changes, so a window that is not an activity (a companion surface) can follow. */
    val changed: StateFlow<Int> get() = changes

    fun key(role: DisplayRole): String = if (role == DisplayRole.MAIN) KEY_MAIN else KEY_COMPANION

    /** The stored value for [role]: [AUTO] or a percent as text. */
    fun value(context: Context, role: DisplayRole): String =
        CatalogPrefs.prefs(context).getString(key(role), AUTO) ?: AUTO

    fun set(context: Context, role: DisplayRole, value: String) {
        CatalogPrefs.prefs(context).edit().putString(key(role), value).apply()
        changes.value++
    }

    /** What [value] multiplies the canvas density by: 1 for Auto or anything not offered. Pure. */
    fun factorOf(value: String?): Float =
        value?.toIntOrNull()?.takeIf { it in PERCENTS }?.let { it / 100f } ?: 1f

    fun factor(context: Context, role: DisplayRole): Float = factorOf(value(context, role))

    /** The factor for the window on [displayId]. */
    fun factorFor(context: Context, displayId: Int): Float = factor(context, roleOf(context, displayId))

    /**
     * The role of [displayId]: the main screen is the second screen when one is present and the person's choice
     * says so ([MainScreen]), else the built-in one; every other display is the companion. Reads the displays now.
     */
    fun roleOf(context: Context, displayId: Int): DisplayRole {
        val outputs = runCatching { DisplayOutputRepository(context).currentOutputsSnapshot() }.getOrDefault(emptyList())
        return roleOf(displayId, secondDisplayId(outputs), MainScreen.choice(context))
    }

    /** The second screen among [outputs]: a second built-in panel first, else an external display. Pure. */
    fun secondDisplayId(outputs: List<DisplayOutput>): Int? =
        (outputs.firstOrNull { it.kind == DisplayOutputKind.SECOND_SCREEN } ?: outputs.firstOrNull { it.kind == DisplayOutputKind.EXTERNAL })
            ?.androidDisplayId

    /** Pure: [displayId]'s role when the second screen is [secondId] (null for none) and the main-screen choice is [choice]. */
    fun roleOf(displayId: Int, secondId: Int?, choice: MainScreenChoice): DisplayRole {
        val mainId = if (secondId != null && choice == MainScreenChoice.SECOND_WHEN_PRESENT) secondId else Display.DEFAULT_DISPLAY
        return if (displayId == mainId) DisplayRole.MAIN else DisplayRole.COMPANION
    }
}
