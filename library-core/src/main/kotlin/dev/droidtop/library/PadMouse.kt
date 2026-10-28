package dev.droidtop.library

import android.content.Context
import android.view.KeyEvent
import dev.droidtop.library.settings.LAUNCHER_PREFS_FILE_NAME
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * The controller as a mouse in a PC game (docs/SPEC.md 5b, "Controller mouse mode"; owner, 2026-10-16,
 * Droidtop/tracker#565: "Perhaps something like GPD's solution, where they can set the controller into mouse mode?").
 * Pure: the stick's pointer speed, which buttons click, the stick click that scrolls, the held combination that
 * turns it on and off, and the per-game settings' encoding. The game screen applies it through the pointer calls the
 * screen's trackpad already makes.
 */
object PadMouse {
    /** Which stick moves the pointer. */
    enum class Stick { RIGHT, LEFT }

    /** Which buttons click: the shoulders (R1 left, L1 right) or the face buttons (A left, B right). */
    enum class Clicks { SHOULDERS, FACE }

    enum class Button { LEFT, RIGHT }

    data class Settings(val on: Boolean = false, val stick: Stick = Stick.RIGHT, val clicks: Clicks = Clicks.SHOULDERS)

    /** Below this deflection the stick is at rest (a worn stick drifts). */
    const val DEADZONE = 0.15f

    /** Pointer speed at full deflection, in screen widths per second: a full push crosses the screen in 2/3 s. */
    const val FULL_SPEED_WIDTHS_PER_S = 1.5f

    /** Held together this long, both stick clicks turn mouse mode on or off (no game holds both for this long). */
    const val TOGGLE_HOLD_MS = 600L

    /** Wheel steps per second at full deflection while the stick click is held. */
    const val SCROLL_STEPS_PER_S = 12f

    /**
     * Pointer velocity in pixels per second for a stick at ([x], [y]), axes from -1 to 1, on a screen [widthPx] wide.
     * The deadzone is cut out and the rest rises with the square of the deflection, so a small push places the
     * pointer precisely and a full one crosses the screen quickly (the acceleration).
     */
    fun velocity(x: Float, y: Float, widthPx: Int): Pair<Float, Float> {
        val magnitude = sqrt(x * x + y * y).coerceAtMost(1f)
        if (magnitude <= DEADZONE) return 0f to 0f
        val scaled = (magnitude - DEADZONE) / (1f - DEADZONE)
        val speed = scaled * scaled * FULL_SPEED_WIDTHS_PER_S * widthPx
        return speed * x / magnitude to speed * y / magnitude
    }

    /** Wheel steps per second while scrolling, from the stick's vertical axis (down is positive, scrolling down). */
    fun scrollRate(y: Float): Float {
        val a = abs(y)
        if (a <= DEADZONE) return 0f
        val scaled = (a - DEADZONE) / (1f - DEADZONE)
        return sign(y) * scaled * SCROLL_STEPS_PER_S
    }

    /** The mouse button [keyCode] presses under [clicks], or null when it is not a click. */
    fun buttonFor(keyCode: Int, clicks: Clicks): Button? = when (clicks) {
        Clicks.SHOULDERS -> when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_R1 -> Button.LEFT
            KeyEvent.KEYCODE_BUTTON_L1 -> Button.RIGHT
            else -> null
        }
        Clicks.FACE -> when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> Button.LEFT
            KeyEvent.KEYCODE_BUTTON_B -> Button.RIGHT
            else -> null
        }
    }

    /** The click of the stick that moves the pointer: held, the stick scrolls instead. */
    fun scrollKey(stick: Stick): Int = if (stick == Stick.RIGHT) KeyEvent.KEYCODE_BUTTON_THUMBR else KeyEvent.KEYCODE_BUTTON_THUMBL

    /**
     * The held combination (both stick clicks), as a small state machine: [press] and [release] report the stick
     * clicks, and [due] says, at [now], whether both have been held for [TOGGLE_HOLD_MS] and the toggle has not fired
     * for this hold yet.
     */
    class Combo {
        private var leftSince: Long? = null
        private var rightSince: Long? = null
        private var fired = false

        fun press(keyCode: Int, now: Long) {
            when (keyCode) {
                KeyEvent.KEYCODE_BUTTON_THUMBL -> if (leftSince == null) leftSince = now
                KeyEvent.KEYCODE_BUTTON_THUMBR -> if (rightSince == null) rightSince = now
            }
        }

        fun release(keyCode: Int) {
            when (keyCode) {
                KeyEvent.KEYCODE_BUTTON_THUMBL -> leftSince = null
                KeyEvent.KEYCODE_BUTTON_THUMBR -> rightSince = null
            }
            if (leftSince == null || rightSince == null) fired = false
        }

        fun due(now: Long): Boolean {
            val since = maxOf(leftSince ?: return false, rightSince ?: return false)
            if (fired || now - since < TOGGLE_HOLD_MS) return false
            fired = true
            return true
        }
    }

    /** The settings as stored: "on|stick|clicks". Anything unreadable is the default. */
    fun encode(settings: Settings): String = "${settings.on}|${settings.stick.name}|${settings.clicks.name}"

    fun decode(text: String?): Settings {
        val parts = text?.split('|') ?: return Settings()
        return Settings(
            on = parts.getOrNull(0) == "true",
            stick = Stick.entries.firstOrNull { it.name == parts.getOrNull(1) } ?: Stick.RIGHT,
            clicks = Clicks.entries.firstOrNull { it.name == parts.getOrNull(2) } ?: Clicks.SHOULDERS,
        )
    }

    /** What a game starts with: its own remembered settings, else off unless its runner says it needs a mouse. */
    fun initial(stored: String?, runnerNeedsMouse: Boolean): Settings =
        if (stored != null) decode(stored) else Settings(on = runnerNeedsMouse)

    /**
     * The words in a game's genre that mean it is played with a pointer (Droidtop/tracker#618 C2): point-and-click
     * and hidden-object games, visual novels, strategy (real-time and turn-based), card and board games. A genre is
     * the scraped one, often several joined ("Adventure, Point-and-click"), in any source's wording.
     */
    private val POINTER_GENRES = listOf(
        "point-and-click", "point and click", "point & click", "hidden object", "visual novel",
        "strategy", "rts", "card & board", "card game", "board game",
    )

    /** Whether a game of [genre] needs a mouse, so mouse mode starts on until the person says otherwise. Pure. */
    fun genreNeedsMouse(genre: String?): Boolean {
        val g = genre?.lowercase() ?: return false
        return POINTER_GENRES.any { word -> Regex("(^|[^a-z])" + Regex.escape(word) + "($|[^a-z])").containsMatchIn(g) }
    }
}

/** Mouse mode remembered per game (Droidtop/tracker#565). Reads and writes preferences: call off the main thread. */
object PadMousePrefs {
    private const val KEY_PREFIX = "droidtop_pad_mouse_"

    fun stored(context: Context, entryId: String): String? =
        context.getSharedPreferences(LAUNCHER_PREFS_FILE_NAME, Context.MODE_PRIVATE).getString(KEY_PREFIX + entryId, null)

    /** True the first time a game asks, then never again: its first start shows how mouse mode is reached. */
    fun firstHint(context: Context, entryId: String): Boolean {
        val prefs = context.getSharedPreferences(LAUNCHER_PREFS_FILE_NAME, Context.MODE_PRIVATE)
        val key = HINT_PREFIX + entryId
        if (prefs.getBoolean(key, false)) return false
        prefs.edit().putBoolean(key, true).apply()
        return true
    }

    private const val HINT_PREFIX = "droidtop_mouse_hint_"

    fun set(context: Context, entryId: String, settings: PadMouse.Settings) {
        context.getSharedPreferences(LAUNCHER_PREFS_FILE_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_PREFIX + entryId, PadMouse.encode(settings)).apply()
    }
}
