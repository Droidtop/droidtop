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
) : View(context) {

    /** The slab's colours, from the host's theme tokens (overlaySurface, card, cardFocused, onSurface, accent). */
    data class Colors(val ground: Int, val key: Int, val keyFocused: Int, val text: Int, val muted: Int, val accent: Int)

    private val density = resources.displayMetrics.density
    private val scaledDensity = density * resources.configuration.fontScale
    private val large = resources.configuration.fontScale >= Slab.LARGE_KEYS_FONT_SCALE

    var state: Slab.State = Slab.open(kind, large, form)
        private set

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

    private val rowHeight get() = (if (large) LARGE_ROW_DP else ROW_DP) * density
    private val gap get() = GAP_DP * density

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val rows = state.keys.size
        val height = (rows * rowHeight + (rows + 1) * gap).toInt()
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), height)
    }

    private inline fun forEachKey(action: (row: Int, col: Int, key: Slab.Key, rect: RectF) -> Unit) {
        val usable = width - 2 * gap
        state.keys.forEachIndexed { r, row ->
            val units = row.sumOf { it.width.toDouble() }.toFloat()
            val unit = (usable - gap * (row.size - 1)) / units
            var x = gap
            val top = gap + r * (rowHeight + gap)
            row.forEachIndexed { c, key ->
                val w = unit * key.width
                keyRect.set(x, top, x + w, top + rowHeight)
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
                focused -> colors.keyFocused
                else -> colors.key
            }
            val corner = CORNER_DP * density
            canvas.drawRoundRect(rect, corner, corner, fill)
            if (focused) {
                ring.color = colors.accent
                canvas.drawRoundRect(rect, corner, corner, ring)
            }
            // The label: 18 sp for a character, 15 sp for a named key, both capped at 60% of the row so it never clips.
            val text = state.label(key, enter)
            val sp = if (key.action is Slab.Action.Char) 18f else 15f
            label.textSize = min(sp * scaledDensity, rect.height() * 0.6f)
            label.color = colors.text
            val fitted = fit(text, rect.width() - 8 * density)
            canvas.drawText(fitted, rect.centerX(), rect.centerY() - (label.descent() + label.ascent()) / 2, label)
            // The pad button that also does this key, in its lower right, at least 14 sp.
            key.cap?.let { button ->
                cap.textSize = maxOf(14f * scaledDensity, rect.height() * 0.32f).coerceAtMost(rect.height() * 0.45f)
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
        if (pressed != null) {
            flashKey = pressed
            flashUntil = SystemClock.uptimeMillis() + FLASH_MS
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
        if (layerChanged) requestLayout()
        invalidate()
        next.second?.let(onOutput)
    }

    /**
     * A pad key from the host's window: the D-pad moves the cursor, A presses the focused key, and X, Y, L1, R1, R2,
     * B and Select do their keycap's job. Returns whether the slab took it. Only key-down acts; a held X repeats
     * Backspace through the event's own repeats.
     */
    fun handleKey(event: KeyEvent): Boolean {
        val button = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> return move(Slab.Move.UP, event)
            KeyEvent.KEYCODE_DPAD_DOWN -> return move(Slab.Move.DOWN, event)
            KeyEvent.KEYCODE_DPAD_LEFT -> return move(Slab.Move.LEFT, event)
            KeyEvent.KEYCODE_DPAD_RIGHT -> return move(Slab.Move.RIGHT, event)
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    state.focused?.let { key -> apply(Slab.press(state, key.action, event.eventTime), key) }
                }
                return true
            }
            KeyEvent.KEYCODE_BUTTON_X -> Slab.Button.X
            KeyEvent.KEYCODE_BUTTON_Y -> Slab.Button.Y
            KeyEvent.KEYCODE_BUTTON_L1 -> Slab.Button.L1
            KeyEvent.KEYCODE_BUTTON_R1 -> Slab.Button.R1
            KeyEvent.KEYCODE_BUTTON_R2, KeyEvent.KEYCODE_BUTTON_START -> Slab.Button.R2
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> Slab.Button.B
            KeyEvent.KEYCODE_BUTTON_SELECT -> Slab.Button.SELECT
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

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_UP) return true
        var hit: Triple<Int, Int, Slab.Key>? = null
        forEachKey { r, c, key, rect -> if (hit == null && rect.contains(event.x, event.y)) hit = Triple(r, c, key) }
        hit?.let { (r, c, key) ->
            state = state.copy(row = r, col = c)
            apply(Slab.press(state, key.action, event.eventTime), key)
        }
        return true
    }

    companion object {
        const val ROW_DP = 38f
        const val LARGE_ROW_DP = 48f
        const val GAP_DP = 6f
        const val CORNER_DP = 6f
        const val FOCUS_SCALE = 1.06f
        const val FLASH_MS = 80L
    }
}
