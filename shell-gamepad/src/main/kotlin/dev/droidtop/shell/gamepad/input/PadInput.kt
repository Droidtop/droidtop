package dev.droidtop.shell.gamepad.input

import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent

/**
 * One press as a screen sees it: the [action] it means, and whether it is
 * a held direction coming round again ([repeat]) rather than a fresh press.
 * A screen decides what an action does; it never looks at key codes or
 * edges (docs/SPEC.md 6e).
 */
class PadPress(val action: GamepadAction, val repeat: Boolean)

/**
 * How fast a held direction moves, as real ES-DE's `IList` scroll tiers
 * (es-core/src/components/IList.h:38-69): each tier is how long it lasts
 * and how long it waits between steps, the last one lasting for ever.
 */
class PadCadence internal constructor(private val tiers: List<Pair<Long, Long>>) {
    /** The wait between two steps once a direction has been held [heldMs]. */
    fun delayAt(heldMs: Long): Long {
        var start = 0L
        for ((length, delay) in tiers) {
            if (length == 0L || heldMs < start + length) return delay
            start += length
        }
        return tiers.last().second
    }

    companion object {
        /**
         * droidtop's own chrome -- menus, settings, sheets, pickers, grids:
         * ES-DE's MEDIUM tiers. Half a second before the first repeat, so
         * a tap never becomes two steps, then a steady 180 ms, then 80 ms
         * once a long list is being run through.
         */
        val CHROME = PadCadence(listOf(500L to 500L, 1100L to 180L, 0L to 80L))

        /**
         * A themed ES-DE list (carousel, textlist, grid): ES-DE's own QUICK
         * tiers, `IList`'s default, which is what those components scroll
         * with in ES-DE itself.
         */
        val THEMED_LIST = PadCadence(listOf(500L to 500L, 1200L to 114L, 0L to 16L))
    }
}

/**
 * The edge rule, in one place (docs/SPEC.md 6e).
 *
 * An action fires on the DOWN edge of a press. Whoever takes that DOWN owns
 * the press: its repeats (directions only) and its release come back to the
 * same owner and go nowhere else, and a release whose press this owner did
 * not take is not its business. That one rule replaces every guard the
 * shell grew for the other half of a press landing somewhere new: the
 * opening R2's release arriving in the Quick Menu, a B that closed a screen
 * on its DOWN and backed out of the screen under it on its UP, a Select
 * hold whose release opened gamelist options.
 *
 * A held direction comes round at [cadence]'s pace, measured from when the
 * press began, whatever rate the device repeats at; buttons never repeat.
 *
 * Pure: it takes key codes and times, so the unit tests drive it with no
 * Android `KeyEvent` (which throws `Stub!` outside a device).
 */
internal class PadEdges(private val cadence: PadCadence) {
    private val lastStep = HashMap<Int, Long>()

    /**
     * A DOWN edge. [repeatCount] is the platform's own (0 for a fresh
     * press), [downTime] when the press began, [eventTime] now. Returns
     * whether this owner consumed it.
     */
    fun down(
        keyCode: Int,
        action: GamepadAction,
        repeatCount: Int,
        downTime: Long,
        eventTime: Long,
        handle: (PadPress) -> Boolean,
    ): Boolean {
        if (repeatCount == 0) {
            // A fresh press: whatever this key's last press left behind (a
            // release that went to another window) is over.
            lastStep.remove(keyCode)
            if (!handle(PadPress(action, repeat = false))) return false
            lastStep[keyCode] = eventTime
            return true
        }
        val last = lastStep[keyCode] ?: return false
        if (!action.isDirection) return true
        if (eventTime - last < cadence.delayAt(eventTime - downTime)) return true
        lastStep[keyCode] = eventTime
        handle(PadPress(action, repeat = true))
        return true
    }

    /** An UP edge: consumed exactly when this owner took the press. */
    fun up(keyCode: Int): Boolean = lastStep.remove(keyCode) != null

    /**
     * One raw key event. The system back key is never taken: it belongs to
     * the back dispatcher, and taking its DOWN would also cost the long
     * press the activity opens the mode switcher with. Nor is a FALLBACK
     * key: that is Android re-sending a pad button nobody took as some
     * other key (`Generic.kcm`: Y becomes SPACE, X becomes DEL, A becomes
     * DPAD_CENTER), and acting on it would make an unused Y mean X.
     */
    fun dispatch(event: android.view.KeyEvent, preview: Boolean, handle: (PadPress) -> Boolean): Boolean {
        val keyCode = event.keyCode
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK) return false
        if ((event.flags and android.view.KeyEvent.FLAG_FALLBACK) != 0) return false
        if (preview && GamepadKeyMap.isTextKey(keyCode)) return false
        val action = GamepadKeyMap.actionFor(keyCode, event.isShiftPressed) ?: return false
        return when (event.action) {
            android.view.KeyEvent.ACTION_DOWN ->
                down(keyCode, action, event.repeatCount, event.downTime, event.eventTime, handle)
            android.view.KeyEvent.ACTION_UP -> up(keyCode)
            else -> false
        }
    }
}

/**
 * How a screen takes the pad: [handler] gets every [PadPress] that reaches
 * this node and answers whether it used it (docs/SPEC.md 6e). The edges,
 * the repeats and who owns a release are [PadEdges]'s, not the screen's.
 *
 * [preview] for the rare node that must answer before the focused child
 * does (a sheet's tab switching over the list inside it); a preview node
 * leaves the keys a text field types to the field.
 */
fun Modifier.onPad(
    cadence: PadCadence = PadCadence.CHROME,
    preview: Boolean = false,
    handler: (PadPress) -> Boolean,
): Modifier = composed {
    val edges = remember(cadence) { PadEdges(cadence) }
    val current by rememberUpdatedState(handler)
    if (preview) {
        onPreviewKeyEvent { event -> edges.dispatch(event.nativeKeyEvent, preview = true) { current(it) } }
    } else {
        onKeyEvent { event -> edges.dispatch(event.nativeKeyEvent, preview = false) { current(it) } }
    }
}

/**
 * One step of a menu's cursor. Real ES-DE's menus never loop
 * (`ComponentList`, es-core/src/components/ComponentList.cpp:17,
 * `LIST_NEVER_LOOP`): the cursor stops at both ends, held or not, so a held
 * direction can never spin through a menu and land somewhere unexpected.
 */
fun menuStep(index: Int, count: Int, delta: Int): Int =
    if (count <= 0) 0 else (index + delta).coerceIn(0, count - 1)
