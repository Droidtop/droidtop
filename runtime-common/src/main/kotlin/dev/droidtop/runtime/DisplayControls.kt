package dev.droidtop.runtime

import android.content.Context
import android.provider.Settings
import dev.droidtop.runtime.tasks.RiskyActions
import dev.droidtop.runtime.tasks.RiskyClass
import dev.droidtop.runtime.tasks.TaskManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The one write path for each display's brightness, power and refresh rate (docs/SPEC.md "The companion's tabs",
 * Displays; Droidtop/tracker#414 slice C14). Every surface that changes them comes here: the catalog's Brightness row
 * (the Quick Menu, the companion's System tab and pins), the companion's own dimming and screen off, and System >
 * Display's cards.
 *
 * - **The main screen's brightness** is Android's own setting (`SCREEN_BRIGHTNESS`, 0..255, with Modify system
 *   settings), the same value the system slider writes.
 * - **The companion screen's brightness** is its window's own brightness ([companionLevel], 0..1), which the companion
 *   window applies; it never changes the main screen. It stops at [FLOOR]: only Off goes lower.
 * - **Off**: Android gives no app, and no shell command on Android 13, a way to power one panel of several off, so Off
 *   is the companion window black at the lowest backlight ([OFF_LEVEL]), touch still reaching it to wake.
 * - **Refresh rate** of a display: `cmd display set-user-preferred-display-mode W H RATE ID` through the helper app
 *   (Android 13 and later), a write to the system's display settings, so a Risky actions class gates it.
 */
object DisplayControls {
    /** The lowest brightness the companion goes to while on (10%). */
    const val FLOOR = 0.10f

    /** The companion's window brightness while off: the lowest Android allows a window. */
    const val OFF_LEVEL = 0.01f

    private val level = MutableStateFlow(1f)

    /** The companion window's own brightness, 0..1, the person's choice (never below [FLOOR]). */
    val companionLevel: StateFlow<Float> = level

    fun canWriteBrightness(context: Context): Boolean = Settings.System.canWrite(context)

    /** The main screen's brightness, 0..255, or null when unreadable. */
    fun brightness(context: Context): Int? = runCatching {
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
    }.getOrNull()

    /** Sets the main screen's brightness (manual mode, as the system slider does); false without Modify system settings. */
    fun setBrightness(context: Context, value: Int): Boolean {
        if (!Settings.System.canWrite(context)) return false
        return runCatching {
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, value.coerceIn(0, 255))
        }.isSuccess
    }

    /** The companion's brightness, held at [FLOOR] or above. Pure. */
    fun clampCompanion(value: Float): Float = value.coerceIn(FLOOR, 1f)

    fun setCompanionLevel(value: Float) {
        level.value = clampCompanion(value)
    }

    /** What the companion window's brightness is in [state]: the person's level, a dimmed one (never under the floor), or off. Pure. */
    fun windowLevel(state: CompanionIdle.State, chosen: Float): Float = when (state) {
        CompanionIdle.State.ON -> clampCompanion(chosen)
        CompanionIdle.State.DIM -> clampCompanion(chosen * DIM_FACTOR)
        CompanionIdle.State.OFF -> OFF_LEVEL
    }

    private const val DIM_FACTOR = 0.3f

    /** The refresh-rate command for one display, or null for a rate it does not list. Pure. */
    fun refreshCommand(displayId: Int, widthPx: Int, heightPx: Int, rateHz: Float): List<String> =
        listOf("cmd", "display", "set-user-preferred-display-mode", widthPx.toString(), heightPx.toString(), rateHz.toString(), displayId.toString())

    /**
     * Sets [displayId]'s refresh rate through the helper app; the plain reason when it cannot. Blocks: off the main thread.
     */
    fun setRefreshRate(displayId: Int, widthPx: Int, heightPx: Int, rateHz: Float): String? {
        if (!TaskManager.shell.capabilities().shellCommand) return "Needs the helper app"
        if (!RiskyActions.allows(RiskyClass.DISPLAY_SETTINGS)) return "Turn on Risky actions, then \"${RiskyClass.DISPLAY_SETTINGS.title}\""
        val out = TaskManager.shell.exec(refreshCommand(displayId, widthPx, heightPx, rateHz)) ?: return "The helper app did not answer"
        return if (out.exit == 0) null else out.stderr.trim().ifEmpty { "Android refused" }
    }
}

/**
 * The companion screen's idle timer (docs/SPEC.md "The companion's tabs", Companion screen off and idle): with no touch
 * it dims after two minutes and goes off after five, or later when Android's accessibility settings ask for longer
 * (`AccessibilityManager.getRecommendedTimeoutMillis`). It does not run while an exemption holds (Social shown, a
 * keep-on plugin panel, the companion keyboard open, a runner that asks). Pure.
 */
object CompanionIdle {
    enum class State { ON, DIM, OFF }

    const val DIM_AFTER_MS = 120_000L
    const val OFF_AFTER_MS = 300_000L

    /** How long before dimming TalkBack hears "Companion screen will turn off soon". */
    const val WARN_BEFORE_MS = 10_000L

    /** The dim and off times, each stretched to at least what Android's accessibility settings recommend. */
    fun timeouts(recommendedMs: Long): Pair<Long, Long> {
        val dim = maxOf(DIM_AFTER_MS, recommendedMs)
        return dim to maxOf(OFF_AFTER_MS, dim + (OFF_AFTER_MS - DIM_AFTER_MS))
    }

    /** The state [sinceTouchMs] after the last touch, or ON while the timer is off or an exemption holds. */
    fun state(sinceTouchMs: Long, enabled: Boolean, exempt: Boolean, recommendedMs: Long = 0L): State {
        if (!enabled || exempt) return State.ON
        val (dim, off) = timeouts(recommendedMs)
        return when {
            sinceTouchMs >= off -> State.OFF
            sinceTouchMs >= dim -> State.DIM
            else -> State.ON
        }
    }

    /** How long until the state changes next, or null when it will not (off, or the timer not running). */
    fun nextChangeInMs(sinceTouchMs: Long, enabled: Boolean, exempt: Boolean, recommendedMs: Long = 0L): Long? {
        if (!enabled || exempt) return null
        val (dim, off) = timeouts(recommendedMs)
        return when {
            sinceTouchMs < dim - WARN_BEFORE_MS -> dim - WARN_BEFORE_MS - sinceTouchMs
            sinceTouchMs < dim -> dim - sinceTouchMs
            sinceTouchMs < off -> off - sinceTouchMs
            else -> null
        }
    }

    /** Whether TalkBack should hear the warning now: in the last [WARN_BEFORE_MS] before dimming. */
    fun warnNow(sinceTouchMs: Long, enabled: Boolean, exempt: Boolean, recommendedMs: Long = 0L): Boolean {
        if (!enabled || exempt) return false
        val dim = timeouts(recommendedMs).first
        return sinceTouchMs in (dim - WARN_BEFORE_MS) until dim
    }

    /**
     * A new message notification brings an off or dimmed screen back to dimmed (not in Kid or Kiosk): the time since
     * the last touch to use from now, so it reads as DIM; null when nothing changes.
     */
    fun afterMessage(state: State, restricted: Boolean, recommendedMs: Long = 0L): Long? =
        if (restricted || state == State.ON) null else timeouts(recommendedMs).first

    /** How a sleeping companion wakes (the Companion group's "Wake the companion screen"). */
    enum class Wake(val key: String, val label: String) {
        DOUBLE_TAP("double", "Double tap (default)"),
        SINGLE_TAP("single", "Single tap"),
        NONE("none", "Only from the Quick Menu"),
        ;

        companion object {
            fun of(key: String?): Wake = entries.firstOrNull { it.key == key } ?: DOUBLE_TAP
        }
    }

    /** Whether [taps] taps (within the double-tap time) wake an off screen with [wake]. Pure. */
    fun wakes(wake: Wake, taps: Int): Boolean = when (wake) {
        Wake.DOUBLE_TAP -> taps >= 2
        Wake.SINGLE_TAP -> taps >= 1
        Wake.NONE -> false
    }
}

/**
 * Which screen shows the companion (docs/SPEC.md "The companion's tabs", Which screen; slice C14). The person's choice is
 * remembered per set of displays, keyed by each screen's name, native size and built-in or external, never by display
 * id (ids change on reconnect). Two identical monitors give the same key, so they share a choice. Pure, except the
 * preference reads and writes.
 */
object CompanionScreens {
    private const val PREFS = "companion_screens"

    /** One screen's key: Android's name, its native size and built-in or external. */
    fun screenKey(screen: ScreenFacts): String =
        "${screen.androidName}|${screen.nativeWidthPx}x${screen.nativeHeightPx}|${screen.screenClass.name}"

    /** The set's key: every screen's key, sorted, so the order displays come up in does not matter. */
    fun setKey(screens: List<ScreenFacts>): String = screens.map(::screenKey).sorted().joinToString(";")

    /**
     * The companion's screen: the remembered one for this set when it is here, else the default, the screen that is not
     * the main one, built-in before external (a second built-in touch screen, as on an AYN Thor, before a monitor),
     * then the lowest id. Null with one screen.
     */
    fun pick(screens: List<ScreenFacts>, mainDisplayId: Int, chosenKey: String?): Int? {
        val others = screens.filter { it.displayId != mainDisplayId }
        if (others.isEmpty()) return null
        chosenKey?.let { key -> others.firstOrNull { screenKey(it) == key }?.let { return it.displayId } }
        return others.sortedWith(compareBy<ScreenFacts>({ it.screenClass != ScreenClass.BUILT_IN }, { it.displayId })).first().displayId
    }

    /** The screen key chosen for [set], or null for the default. */
    fun chosen(context: Context, set: String): String? =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(set, null)

    /** Remembers [screenKey] for [set] (null: back to the default) and re-runs the screen arrangement. */
    fun choose(context: Context, set: String, screenKey: String?) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (screenKey == null) remove(set) else putString(set, screenKey)
        }.apply()
        DisplayArrangement.changed()
    }

    /** The companion's display among [outputs] with the main screen on display 0. Reads a preference: off the main thread. */
    fun companionDisplay(context: Context, outputs: List<DisplayOutput>, mainDisplayId: Int = android.view.Display.DEFAULT_DISPLAY): Int? {
        val facts = outputs.map(ScreenNaming::facts)
        return pick(facts, mainDisplayId, chosen(context, setKey(facts)))
    }
}
