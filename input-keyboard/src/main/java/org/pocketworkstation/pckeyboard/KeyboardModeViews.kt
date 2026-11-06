// The one-handed controls are ported from HeliBoard's KeyboardWrapperView (https://github.com/Helium314/HeliBoard at
// 2d9869b2, app/src/main/java/helium314/keyboard/latin/KeyboardWrapperView.kt, GPL-3.0-only, Copyright (C) HeliBoard
// contributors): three buttons centred in the free side's width at 20%, 50% and 80% of the keys' height, and a resize
// button dragged sideways. The floating frame follows FlorisBoard's floating window (https://github.com/florisboard/
// florisboard at bac346e8, ime/window/ImeWindowController.onComputeInsets and ImeWindowProps.Floating.constrained,
// Apache-2.0, Copyright (C) The FlorisBoard Contributors): the window fills the screen, reports no inset to the app and
// takes touches only inside the frame, whose offsets are kept on the screen. See NOTICE.md.
package org.pocketworkstation.pckeyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * The one-handed mode's keys with their three controls on the free side (Droidtop/tracker#903 H12c; docs/SPEC.md 6a,
 * "Modes"): switch side, back to full (Normal), and a handle dragged sideways to make the keys wider or narrower. The
 * width is kept per orientation ([KeyboardModes.setOneHandedWidth]); the input method builds the keys again at the new
 * width when the handle is let go.
 */
@SuppressLint("ViewConstructor")
internal class OneHandedFrame(
    context: Context,
    private val keys: View,
    private val atEnd: Boolean,
    colors: KeyboardColors,
) : FrameLayout(context) {
    private val switchSide: Button
    private val toFull: Button
    private val resize: Button

    /** The controls a pad reaches from the tool strip, in order. */
    val controls: List<View>

    init {
        addView(keys, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        switchSide = control(colors, context.getString(R.string.mode_switch_side_glyph), R.string.mode_switch_side) {
            KeyboardModes.choose(context, if (atEnd) KeyboardModes.Mode.LEFT else KeyboardModes.Mode.RIGHT)
        }
        toFull = control(colors, context.getString(R.string.mode_full_glyph), R.string.mode_back_to_full) {
            KeyboardModes.choose(context, KeyboardModes.Mode.NORMAL)
        }
        resize = control(colors, context.getString(R.string.mode_resize_glyph), R.string.mode_resize) { }
        resize.setOnTouchListener(ResizeDrag())
        controls = listOf(switchSide, toFull)
    }

    private fun control(colors: KeyboardColors, glyph: String, description: Int, onClick: () -> Unit): Button =
        flatButton(context, colors, glyph, onClick = onClick).also {
            it.textSize = 18f
            it.contentDescription = context.getString(description)
            it.minimumWidth = dp(MIN_CONTROL_DP)
            it.minimumHeight = dp(MIN_CONTROL_DP)
            it.background = GradientDrawable().apply {
                cornerRadius = dp(CORNER_DP).toFloat()
                setColor(colors.key)
            }
            addView(it, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        keys.measure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val spare = (MeasureSpec.getSize(widthMeasureSpec) - keys.measuredWidth).coerceAtLeast(0)
        val buttonHeight = (keys.measuredHeight / 4).coerceAtLeast(1)
        for (button in listOf(switchSide, toFull, resize)) {
            button.measure(MeasureSpec.makeMeasureSpec(spare, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(buttonHeight, MeasureSpec.AT_MOST))
        }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), keys.measuredHeight)
    }

    // HeliBoard's layout: the keys at their edge, the buttons centred in what is left, at 20%, 50% and 80% down.
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val width = right - left
        val spare = width - keys.measuredWidth
        val keysLeft = if (atEnd) spare else 0
        keys.layout(keysLeft, 0, keysLeft + keys.measuredWidth, keys.measuredHeight)
        val freeLeft = if (atEnd) 0 else keys.measuredWidth
        fun View.at(share: Float) {
            val x = freeLeft + (spare - measuredWidth) / 2
            val y = (keys.measuredHeight * share).toInt() - measuredHeight / 2
            layout(x, y, x + measuredWidth, y + measuredHeight)
        }
        toFull.at(0.2f)
        switchSide.at(0.5f)
        resize.at(0.8f)
    }

    /** Dragged sideways: the handle follows the finger, showing the width it would give; let go, the width is kept. */
    private inner class ResizeDrag : OnTouchListener {
        private var startX = 0f
        private var startWidth = KeyboardModes.ONE_HANDED_DEFAULT
        private var width = startWidth

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            val screen = resources.displayMetrics.widthPixels
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startWidth = KeyboardModes.oneHandedWidth(context)
                    width = startWidth
                    v.isPressed = true
                }
                MotionEvent.ACTION_MOVE -> {
                    width = KeyboardModes.oneHandedAfterDrag(startWidth, event.rawX - startX, screen, atEnd)
                    // Where the keys' free edge would be: the handle moves with it.
                    v.translationX = (width - startWidth) * screen * (if (atEnd) -1 else 1)
                    resize.text = context.getString(R.string.mode_resize_percent, (width * 100).roundToInt())
                }
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    if (width != startWidth) KeyboardModes.setOneHandedWidth(context, width) else reset(v)
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    reset(v)
                }
            }
            return true
        }

        private fun reset(v: View) {
            v.translationX = 0f
            resize.text = context.getString(R.string.mode_resize_glyph)
        }
    }

    private companion object {
        const val MIN_CONTROL_DP = 40
        const val CORNER_DP = 8
    }
}

/**
 * The floating mode's keyboard (Droidtop/tracker#903 H12c; docs/SPEC.md 6a, "Modes"): the keys with their tool strip
 * in a frame over the app, with a bar along its top to drag it by and a corner to drag to resize it. It fills the
 * input method's window, so the frame can go anywhere on the screen; [computeInsets] tells Android the app keeps the
 * whole screen and only the frame takes touches. The place and size are kept per orientation; the place is kept on the
 * screen whatever it was ([KeyboardModes.clampFrame]). The bar's Dock goes back to Normal.
 */
@SuppressLint("ViewConstructor")
class FloatingKeyboardFrame(
    context: Context,
    content: View,
    /** The key grid inside [content], whose height the resize scales. */
    private val keys: View,
    colors: KeyboardColors,
) : FrameLayout(context) {
    private val frame = FrameLayout(context)
    private val column = LinearLayout(context)
    private val bar = LinearLayout(context)
    private val footer = LinearLayout(context)
    private val corner = TextView(context)
    private val ghost = View(context)
    private val dock: Button

    /** Where the frame is now, once laid out; null until then. */
    private var placed: KeyboardModes.Frame? = null

    /** The bar's button, for the pad (ToolsDeck.padTargets). */
    val controls: List<View>

    init {
        isFocusable = false
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        column.orientation = LinearLayout.VERTICAL
        bar.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(colors.panel)
            contentDescription = context.getString(R.string.mode_float_move)
        }
        bar.addView(
            TextView(context).apply {
                text = context.getString(R.string.mode_float_grip)
                setTextColor(colors.muted)
                textSize = 14f
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f),
        )
        dock = flatButton(context, colors, context.getString(R.string.mode_float_dock)) {
            KeyboardModes.choose(context, KeyboardModes.Mode.NORMAL)
        }
        bar.addView(dock)
        controls = listOf(dock)
        bar.minimumHeight = dp(BAR_DP)
        bar.setOnTouchListener(MoveDrag())
        column.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(BAR_DP)))
        (content.parent as? android.view.ViewGroup)?.removeView(content)
        column.addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        frame.addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        // The corner sits in a footer of its own under the keys, so it never covers a key (Enter is often there).
        footer.orientation = LinearLayout.HORIZONTAL
        footer.setBackgroundColor(colors.panel)
        footer.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        corner.apply {
            text = context.getString(R.string.mode_float_corner)
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(colors.text)
            contentDescription = context.getString(R.string.mode_float_resize)
            background = GradientDrawable().apply {
                cornerRadius = dp(CORNER_RADIUS_DP).toFloat()
                setColor(colors.keyFocused)
            }
            setOnTouchListener(ResizeDrag())
        }
        footer.addView(corner, LinearLayout.LayoutParams(dp(CORNER_DP), dp(FOOTER_DP)))
        column.addView(footer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(FOOTER_DP)))
        frame.background = GradientDrawable().apply {
            cornerRadius = dp(CORNER_RADIUS_DP).toFloat()
            setColor(colors.ground)
            setStroke(dp(1), colors.muted)
        }
        frame.clipToOutline = true
        frame.elevation = dp(ELEVATION_DP).toFloat()
        addView(frame)
        ghost.background = GradientDrawable().apply {
            cornerRadius = dp(CORNER_RADIUS_DP).toFloat()
            setStroke(dp(2), colors.accent)
        }
        ghost.visibility = GONE
        addView(ghost)
    }

    /** The frame's width: the floating size's share of the screen. */
    private fun frameWidth(): Int = (KeyboardModes.floatSize(context).width * resources.displayMetrics.widthPixels).roundToInt()

    // The window is as tall as Android lets it be (the input method's window wraps its content): the frame is placed in it.
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        // Asked with no bound at all (not how the input method's window measures), the screen's height.
        val height = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            resources.displayMetrics.heightPixels
        } else {
            MeasureSpec.getSize(heightMeasureSpec)
        }
        frame.measure(
            MeasureSpec.makeMeasureSpec(frameWidth().coerceAtMost(width), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST),
        )
        if (ghost.visibility != GONE) {
            ghost.measure(MeasureSpec.makeMeasureSpec(ghost.layoutParams.width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ghost.layoutParams.height, MeasureSpec.EXACTLY))
        }
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val rootWidth = right - left
        val rootHeight = bottom - top
        val at = placed?.let { KeyboardModes.clampFrame(it.copy(width = frame.measuredWidth, height = frame.measuredHeight), rootWidth, rootHeight) }
            ?: KeyboardModes.place(KeyboardModes.floatPlace(context), frame.measuredWidth, frame.measuredHeight, rootWidth, rootHeight)
        placed = at
        frame.layout(at.x, at.y, at.x + at.width, at.y + at.height)
        if (ghost.visibility != GONE) ghost.layout(at.x, at.y, at.x + ghost.measuredWidth, at.y + ghost.measuredHeight)
    }

    /**
     * The insets of a keyboard that floats (FlorisBoard's `ImeWindowController.onComputeInsets`, floating case): the
     * content and visible insets at the window's bottom, so the app is not resized or panned under it, and touches taken
     * only inside the frame, so a touch anywhere else reaches the app.
     */
    fun computeInsets(outInsets: InputMethodService.Insets) {
        val bottom = rootView.height
        outInsets.contentTopInsets = bottom
        outInsets.visibleTopInsets = bottom
        val at = IntArray(2)
        frame.getLocationInWindow(at)
        outInsets.touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.set(at[0], at[1], at[0] + frame.width, at[1] + frame.height)
    }

    /** The bar dragged: the frame follows the finger, kept on the screen; let go, the place is kept. */
    private inner class MoveDrag : OnTouchListener {
        private var lastX = 0f
        private var lastY = 0f

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.rawX
                    lastY = event.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    val at = placed ?: return true
                    val moved = at.copy(x = at.x + (event.rawX - lastX).roundToInt(), y = at.y + (event.rawY - lastY).roundToInt())
                    lastX = event.rawX
                    lastY = event.rawY
                    placed = KeyboardModes.clampFrame(moved, width, height)
                    requestLayout()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> placed?.let {
                    KeyboardModes.setFloatPlace(context, KeyboardModes.encodePlace(it, width, height))
                }
            }
            return true
        }
    }

    /** The corner dragged: an outline shows the new size; let go, the size is kept and the keys are built again at it. */
    private inner class ResizeDrag : OnTouchListener {
        private var startX = 0f
        private var startY = 0f
        private var start = KeyboardModes.floatDefault(false)
        private var size = start

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            val landscape = KeyboardModes.landscape(context)
            val screen = resources.displayMetrics.widthPixels
            // Hacker's own key height, which the floating height is a share of.
            val keysHeight = keys.height.coerceAtLeast(1) / start.heightScale
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    start = KeyboardModes.floatSize(context)
                    size = start
                }
                MotionEvent.ACTION_MOVE -> {
                    size = KeyboardModes.resized(start, event.rawX - startX, event.rawY - startY, screen, keysHeight.roundToInt(), landscape)
                    ghost.layoutParams = LayoutParams((size.width * screen).roundToInt(), (frame.height - keys.height + keysHeight * size.heightScale).roundToInt())
                    ghost.visibility = VISIBLE
                    requestLayout()
                }
                MotionEvent.ACTION_UP -> {
                    ghost.visibility = GONE
                    if (size != start) KeyboardModes.setFloatSize(context, size) else requestLayout()
                }
                MotionEvent.ACTION_CANCEL -> {
                    ghost.visibility = GONE
                    requestLayout()
                }
            }
            return true
        }
    }

    private companion object {
        const val BAR_DP = 32
        const val CORNER_DP = 48
        const val FOOTER_DP = 28
        const val CORNER_RADIUS_DP = 12
        const val ELEVATION_DP = 8
    }
}
