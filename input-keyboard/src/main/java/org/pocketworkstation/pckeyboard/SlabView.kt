package org.pocketworkstation.pckeyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/**
 * The text-entry slab drawn (docs/SPEC.md 4c, "The text-entry slab"; Droidtop/tracker#485 S7 slice 6): the keys of
 * [Slab]'s state, the pad's cursor on one of them, and each key's pad button as a keycap in its lower right. The pad
 * reaches it through [handleKey], which the host's window forwards (the slab is never a focus target, so the field
 * keeps focus and its caret); a tap on a key presses it the same way. Every press gives the same 80 ms flash and a
 * haptic tick. [onOutput] receives what a press produces for the host to deliver.
 *
 * Colours come from the host's theme ([Colors]), and the pad buttons' printed names from its controller layout
 * ([capLabel]: on a Nintendo-layout pad the Backspace keycap reads Y), so the slab draws what is on the plastic.
 *
 * It lays out from its container's real size (Droidtop/tracker#567: the companion's keyboard "moves things around,
 * doesn't scale properly, and gets cut off"): the rows share the height it is given, between [MIN_ROW_DP] and
 * [MAX_ROW_DP], sized for the tallest layer so a layer switch never resizes a key; the gaps, labels and keycaps
 * scale with the key they sit on. Given no height, it asks for its design height (38 dp
 * rows). Nothing is drawn outside its bounds at any size.
 */
@SuppressLint("ViewConstructor")
class SlabView(
    context: Context,
    kind: Slab.Kind,
    form: Slab.Form,
    private val enter: Slab.EnterPurpose,
    private val colors: Colors,
    private val capLabel: (Slab.Cap) -> String,
    private val onOutput: (Slab.Output) -> Unit,
    /** Up from the top row: the host moves its cursor into the plate's controls. */
    private val onLeaveTop: () -> Unit = {},
    /** A whole keyboard's keys too (Esc, Tab, Ctrl, Alt, Super, arrows, F1 to F12): the companion's Input tab. */
    extras: Boolean = false,
    /** With [extras]: whether a held key repeats here, for a destination that does not repeat it itself. */
    private val repeatHeld: () -> Boolean = { false },
) : View(context) {

    /** The slab's colours, from the host's theme tokens (overlaySurface, card, cardFocused, onSurface, accent). */
    data class Colors(val ground: Int, val key: Int, val keyFocused: Int, val text: Int, val muted: Int, val accent: Int)

    private val density = resources.displayMetrics.density
    private val scaledDensity = density * resources.configuration.fontScale
    private val largeByFont = resources.configuration.fontScale >= Slab.LARGE_KEYS_FONT_SCALE

    var state: Slab.State = Slab.open(kind, largeByFont, form, extras = extras)
        private set

    /** The current layout: row height and gap, from the size the view was given. */
    private var rowHeight = ROW_DP * density
    private var gap = GAP_DP * density

    private var flashKey: Slab.Key? = null
    private var flashUntil = 0L
    private val keyRect = RectF()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3 * density }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val cap = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT; typeface = Typeface.DEFAULT_BOLD }

    init {
        isFocusable = false
        setBackgroundColor(colors.ground)
        contentDescription = "Keyboard"
    }

    /** The height the slab asks for with nothing else to go by: its design rows (38 dp, 48 dp with Large keys). */
    private fun designHeight(): Int {
        val rows = tallest(state.large || largeByFont)
        val row = (if (largeByFont) LARGE_ROW_DP else ROW_DP) * density
        return (rows * row + (rows + 1) * GAP_DP * density).toInt()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = MeasureSpec.getSize(heightMeasureSpec)
        val height = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.EXACTLY -> size
            MeasureSpec.AT_MOST -> min(designHeight(), size)
            else -> designHeight()
        }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        relayout(h)
    }

    /**
     * The rows fitted into [height]. A row is the height shared out over the tallest layer's rows (letters or symbols,
     * so switching layers never resizes a key) with a gap of a sixth of a row (3 to 6 dp) between and around them, at
     * most [MAX_ROW_DP]. A container shorter than [minimumHeight] gets rows under [MIN_ROW_DP] rather than rows past
     * its edge: nothing is ever drawn outside the view, and a host that wants the minimum reserves it
     * (Droidtop/tracker#567). The four-row Large keys form follows the font scale only: the symbols layer keeps its
     * digits row, so dropping the letters' number row would not make a row taller.
     */
    private fun relayout(height: Int) {
        if (height <= 0) return
        rowHeight = min(fit(height, tallest(state.large)), MAX_ROW_DP * density)
        gap = (rowHeight / 6f).coerceIn(min(3 * density, rowHeight / 6f), GAP_DP * density)
        val rows = state.keys.size
        state = state.copy(row = state.row.coerceIn(0, rows - 1), col = state.col.coerceIn(0, state.keys[state.row.coerceIn(0, rows - 1)].lastIndex))
        invalidate()
    }

    /** The most rows any layer has in this form. */
    private fun tallest(large: Boolean): Int = Slab.Layer.entries.maxOf { Slab.rows(it, large, state.form, state.extras).size }

    /** The row height that shares [height] out over [rows] rows and their gaps (a gap is a sixth of a row). */
    private fun fit(height: Int, rows: Int): Float = height / (rows + (rows + 1) / 6f)

    /** Where the current layer's first row starts: its rows are centred in the height, so a shorter layer sits mid-slab. */
    private fun firstRowTop(): Float {
        val rows = state.keys.size
        val used = rows * rowHeight + (rows + 1) * gap
        return ((height - used) / 2f).coerceAtLeast(0f) + gap
    }

    /** Every key's rectangle as drawn now, for tests: none may leave the view. */
    internal fun keyRects(): List<RectF> = buildList { forEachKey { _, _, _, rect -> add(RectF(rect)) } }

    /** The label size a character key is drawn with now, in pixels. */
    internal fun labelPx(): Float = labelSize(rowHeight, (width - 2 * gap) / 10f, 18f)

    /** A label of [sp] scaled down with its key (60% of the row, 70% of a letter key's width), never under MIN_LABEL_SP. */
    private fun labelSize(keyHeight: Float, unitWidth: Float, sp: Float): Float =
        maxOf(min(sp * scaledDensity, min(keyHeight * 0.6f, unitWidth * 0.7f)), MIN_LABEL_SP * density)

    private inline fun forEachKey(action: (row: Int, col: Int, key: Slab.Key, rect: RectF) -> Unit) {
        val usable = width - 2 * gap
        state.keys.forEachIndexed { r, row ->
            val units = row.sumOf { it.width.toDouble() }.toFloat()
            val unit = (usable - gap * (row.size - 1)) / units
            var x = gap
            val rowTop = firstRowTop() + r * (rowHeight + gap)
            row.forEachIndexed { c, key ->
                val w = unit * key.width
                keyRect.set(x, rowTop, x + w, rowTop + rowHeight)
                action(r, c, key, keyRect)
                x += w + gap
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        forEachKey { r, c, key, rect ->
            val focused = r == state.row && c == state.col
            val flashing = key == flashKey && now < flashUntil
            canvas.save()
            if (focused) canvas.scale(FOCUS_SCALE, FOCUS_SCALE, rect.centerX(), rect.centerY())
            fill.color = when {
                flashing -> colors.accent
                focused || state.latched(key) -> colors.keyFocused
                else -> colors.key
            }
            val corner = CORNER_DP * density
            canvas.drawRoundRect(rect, corner, corner, fill)
            if (focused) {
                ring.color = colors.accent
                canvas.drawRoundRect(rect, corner, corner, ring)
            }
            // The label: 18 sp for a character, 15 sp for a named key, scaled down with the key it sits on (60% of
            // the row, 70% of a letter key's width) and never under MIN_LABEL_SP, then ellipsised if it is still wide.
            val text = state.label(key, enter)
            val sp = if (key.action is Slab.Action.Char) 18f else 15f
            label.textSize = labelSize(rect.height(), rect.width() / key.width, sp)
            label.color = colors.text
            val fitted = fit(text, rect.width() - 8 * density)
            canvas.drawText(fitted, rect.centerX(), rect.centerY() - (label.descent() + label.ascent()) / 2, label)
            // The pad button that also does this key, in its lower right, at least 14 sp.
            key.cap?.let { button ->
                // The keycap scales with its key: a third of the row, 14 sp at the design size, never under 8 sp.
                cap.textSize = (rect.height() * 0.36f).coerceIn(8f * density, 14f * scaledDensity)
                cap.color = colors.muted
                canvas.drawText(capLabel(button), rect.right - 4 * density, rect.bottom - 4 * density, cap)
            }
            canvas.restore()
        }
        if (now < flashUntil) postInvalidateDelayed(flashUntil - now)
    }

    /** [text] shortened with an ellipsis to fit [width] at the label's size, so a long name never runs off its key. */
    private fun fit(text: String, width: Float): String {
        if (label.measureText(text) <= width) return text
        var end = text.length
        while (end > 1 && label.measureText(text, 0, end) + label.measureText("…") > width) end--
        return text.take(end) + "…"
    }

    private fun apply(next: Pair<Slab.State, Slab.Output?>, pressed: Slab.Key?) {
        val layerChanged = next.first.layer != state.layer || next.first.keys.size != state.keys.size
        state = next.first
        if (pressed != null) flash(pressed)
        if (layerChanged) requestLayout()
        invalidate()
        next.second?.let(onOutput)
    }

    /** Every press: the same 80 ms flash and a haptic tick. */
    private fun flash(key: Slab.Key) {
        flashKey = key
        flashUntil = SystemClock.uptimeMillis() + FLASH_MS
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        invalidate()
    }

    /**
     * A pad key from the host's window: the D-pad moves the cursor, A presses the focused key, and X, Y, L1, R1, R2,
     * B and Select do their keycap's job. Returns whether the slab took it. Only key-down acts; a held X repeats
     * Backspace through the event's own repeats.
     */
    fun handleKey(event: KeyEvent): Boolean {
        // The face keys by what they mean on the pad in hand, the swap applied ([FaceKeys]); Enter and Escape are
        // left to a hardware keyboard's own typing here, as before.
        val face = if (event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
            event.keyCode == KeyEvent.KEYCODE_ESCAPE
        ) null else FaceKeys.roleOf(event.keyCode)
        val button = when {
            event.keyCode == KeyEvent.KEYCODE_DPAD_UP -> return move(Slab.Move.UP, event)
            event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> return move(Slab.Move.DOWN, event)
            event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> return move(Slab.Move.LEFT, event)
            event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> return move(Slab.Move.RIGHT, event)
            face == FaceKeys.Role.CONFIRM -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    state.focused?.let { key -> apply(Slab.press(state, key.action, event.eventTime), key) }
                }
                return true
            }
            face == FaceKeys.Role.X -> Slab.Button.X
            face == FaceKeys.Role.Y -> Slab.Button.Y
            face == FaceKeys.Role.CANCEL || event.keyCode == KeyEvent.KEYCODE_BACK -> Slab.Button.B
            event.keyCode == KeyEvent.KEYCODE_BUTTON_L1 -> Slab.Button.L1
            event.keyCode == KeyEvent.KEYCODE_BUTTON_R1 -> Slab.Button.R1
            event.keyCode == KeyEvent.KEYCODE_BUTTON_R2 || event.keyCode == KeyEvent.KEYCODE_BUTTON_START -> Slab.Button.R2
            event.keyCode == KeyEvent.KEYCODE_BUTTON_SELECT -> Slab.Button.SELECT
            else -> return false
        }
        if (event.action != KeyEvent.ACTION_DOWN) return true
        // Only Backspace repeats while held; every other button acts once per press.
        if (event.repeatCount > 0 && button != Slab.Button.X) return true
        val key = state.keys.flatten().firstOrNull { it.cap == Slab.Cap.valueOf(button.name) }
        apply(Slab.button(state, button, event.eventTime), key)
        return true
    }

    private fun move(direction: Slab.Move, event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return true
        val next = Slab.move(state, direction)
        if (next == null) onLeaveTop() else apply(next to null, null)
        return true
    }

    /** The key held under a finger on a whole keyboard, and what it sent down. */
    private var holding: Slab.Output.Hold? = null

    private val repeat = object : Runnable {
        override fun run() {
            val held = holding ?: return
            onOutput(Slab.Output.Down(held, repeat = true))
            postDelayed(this, REPEAT_EVERY_MS)
        }
    }

    /**
     * A tap presses on release. On a whole keyboard ([Slab.State.extras]) a key that is a key press goes down on touch
     * and up on release, with what is latched held under it, as a real keyboard's does: a PC game sees an arrow or a
     * letter held for as long as the finger is on it (Droidtop/tracker#567). Shift, Ctrl, Alt, Super and the layer
     * keys act on release.
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!state.extras) return true
                val (r, c, key) = hitAt(event.x, event.y) ?: return true
                val held = Slab.held(state, key.action) ?: return true
                state = state.copy(row = r, col = c)
                holding = held
                flash(key)
                onOutput(Slab.Output.Down(held))
                if (repeatHeld() && Slab.repeats(key.action)) postDelayed(repeat, REPEAT_AFTER_MS)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val held = holding
                if (held != null) {
                    letGo(held)
                    return true
                }
                if (event.actionMasked == MotionEvent.ACTION_CANCEL) return true
                hitAt(event.x, event.y)?.let { (r, c, key) ->
                    state = state.copy(row = r, col = c)
                    apply(Slab.press(state, key.action, event.eventTime), key)
                }
            }
        }
        return true
    }

    private fun letGo(held: Slab.Output.Hold) {
        removeCallbacks(repeat)
        holding = null
        onOutput(Slab.Output.Up(held))
        state = Slab.released(state)
        invalidate()
    }

    override fun onDetachedFromWindow() {
        // A key never stays down in the destination because the view went away under the finger.
        holding?.let(::letGo)
        super.onDetachedFromWindow()
    }

    private fun hitAt(x: Float, y: Float): Triple<Int, Int, Slab.Key>? {
        var hit: Triple<Int, Int, Slab.Key>? = null
        forEachKey { r, c, key, rect -> if (hit == null && rect.contains(x, y)) hit = Triple(r, c, key) }
        return hit
    }

    companion object {
        /**
         * The height that gives every row of every layer at least [MIN_ROW_DP], in pixels: what a host reserves before
         * sharing the rest out (the companion's Input tab gives its trackpad only what is left over).
         */
        fun minimumHeight(density: Float, form: Slab.Form = Slab.Form.FIELD, extras: Boolean = false): Int {
            val rows = Slab.Layer.entries.maxOf { layer -> listOf(true, false).maxOf { Slab.rows(layer, it, form, extras).size } }
            return kotlin.math.ceil(MIN_ROW_DP * density * (rows + (rows + 1) / 6f)).toInt()
        }

        const val MIN_ROW_DP = 28f
        const val MAX_ROW_DP = 56f
        const val MIN_LABEL_SP = 10f
        const val ROW_DP = 38f
        const val LARGE_ROW_DP = 48f
        const val GAP_DP = 6f
        const val CORNER_DP = 6f
        const val FOCUS_SCALE = 1.06f
        const val FLASH_MS = 80L
        const val REPEAT_AFTER_MS = 400L
        const val REPEAT_EVERY_MS = 50L
    }
}
