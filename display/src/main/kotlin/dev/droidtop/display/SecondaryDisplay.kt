package dev.droidtop.display

import android.content.Context
import androidx.compose.runtime.Composable

/**
 * The one place droidtop decides what the secondary screen shows.
 *
 * Before this module there were two implementations of the same job:
 * `shell-default` carried Launcher3's own `SecondaryDisplayLauncher`
 * registered for `android.intent.category.SECONDARY_HOME`, and the
 * Handheld shell separately pushed a `Presentation` onto the second
 * display and relocated itself with `setLaunchDisplayId`. Both wanted the
 * same display, and the platform — which places a `SECONDARY_HOME`
 * activity on secondary displays itself — was a third party to the
 * argument. That competition is documented in docs/SPEC.md §4c along with
 * the symptoms it produced.
 *
 * Now: one activity holds the category, and the active mode selects what
 * it renders. Per direction — no duplication, a single controlling
 * configuration read by every mode.
 */
object SecondaryDisplayContent {

    /** Which shell is currently active; the one input this module's behaviour keys on. */
    enum class Mode { STANDARD, HANDHELD, DESKTOP }

    /**
     * What a mode draws on the secondary screen. Registered by whoever
     * owns that mode's UI rather than pulled in here, so this module never
     * needs to depend on the shells — the same seam pattern
     * `PcGameRuntimeRegistry` and `SettingsScreenRegistry` already use.
     */
    private val contents = mutableMapOf<Mode, @Composable () -> Unit>()

    /**
     * What to do when a mode draws nothing of its own — Standard hands
     * off to Launcher3's own secondary-display UI, which is an Activity,
     * not a composable.
     */
    private val handoffs = mutableMapOf<Mode, (Context) -> Boolean>()

    fun register(mode: Mode, content: @Composable () -> Unit) {
        contents[mode] = content
    }

    /**
     * Registers a mode that answers by starting its own Activity instead
     * of composing. Returning false means it declined, and the composable
     * content (if any) is used instead.
     */
    fun registerHandoff(mode: Mode, handoff: (Context) -> Boolean) {
        handoffs[mode] = handoff
    }

    internal fun contentFor(mode: Mode): (@Composable () -> Unit)? = contents[mode]

    internal fun handoffFor(mode: Mode): ((Context) -> Boolean)? = handoffs[mode]

    /**
     * The active mode, read from the same preference `ModePrefs` writes.
     *
     * Deliberately read by key rather than by depending on
     * `:shell-default`: this module must stay usable by every shell, and
     * one of them owning the type the others read would recreate exactly
     * the coupling this consolidation removes. The key and file are
     * droidtop's established settings convention.
     */
    fun currentMode(context: Context): Mode {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_MODE, null)
        return when (raw) {
            "handheld" -> Mode.HANDHELD
            "desktop" -> Mode.DESKTOP
            else -> Mode.STANDARD
        }
    }

    private const val PREFS_NAME = "com.android.launcher3.prefs"
    private const val KEY_LAST_MODE = "droidtop_last_mode"
}
