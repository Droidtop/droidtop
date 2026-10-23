package dev.droidtop.runtime.windows

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.droidtop.library.diagnostics.BlackScreenWatch
import dev.droidtop.library.diagnostics.FrameDarkness

/**
 * Notices a Wine game showing nothing but a black screen and offers Report a problem (docs/SPEC.md 7e2, "A black
 * screen is noticed"; Droidtop/tracker#601: Clover Days stayed black with its audio playing and nothing said why).
 *
 * Every [BlackScreenWatch.SAMPLE_MS] it copies a small frame of what [surface] shows ([PixelCopy], so the GL and the
 * Vulkan renderer alike) on its own thread, and once the game's window is up and every frame has been flat dark for
 * [BlackScreenWatch.HOLD_MS] it puts a notice over the game: Report a problem ([report], given the last frame) and Keep
 * waiting. It never ends the game. While the notice is up it takes the pad ([onKey]).
 */
internal class WineBlackScreen(
    private val activity: Activity,
    private val parent: FrameLayout,
    private val surface: () -> SurfaceView?,
    private val windowShown: () -> Boolean,
    private val report: (frame: Bitmap?, done: (String) -> Unit) -> Unit,
) {
    private val thread = HandlerThread("droidtop-black-screen").apply { start() }
    private val handler = Handler(thread.looper)
    private val watch = BlackScreenWatch()
    private val frame = Bitmap.createBitmap(BlackScreenWatch.SAMPLE_WIDTH, BlackScreenWatch.SAMPLE_HEIGHT, Bitmap.Config.ARGB_8888)
    private val pixels = IntArray(BlackScreenWatch.SAMPLE_WIDTH * BlackScreenWatch.SAMPLE_HEIGHT)

    @Volatile
    private var running = false

    @Volatile
    private var showing = false

    private var notice: View? = null
    private var line: TextView? = null
    private var buttons: List<Button> = emptyList()

    private val tick = Runnable { sample() }

    /** Starts sampling (the screen is in front). */
    fun resume() {
        if (running) return
        running = true
        handler.postDelayed(tick, BlackScreenWatch.SAMPLE_MS)
    }

    /** Stops sampling while the screen is not in front; the count starts again on [resume]. */
    fun pause() {
        running = false
        handler.removeCallbacks(tick)
        handler.post { watch.keepWaiting() }
    }

    /** The screen is going away for good. */
    fun stop() {
        pause()
        thread.quitSafely()
    }

    private fun schedule() {
        if (running) handler.postDelayed(tick, BlackScreenWatch.SAMPLE_MS)
    }

    private fun sample() {
        val view = surface()
        if (!running || showing || view == null || !view.holder.surface.isValid || !windowShown()) {
            schedule()
            return
        }
        runCatching {
            PixelCopy.request(view, frame, { result ->
                val dark = result == PixelCopy.SUCCESS && run {
                    frame.getPixels(pixels, 0, frame.width, 0, 0, frame.width, frame.height)
                    FrameDarkness.isFlatDark(pixels)
                }
                if (watch.sample(SystemClock.elapsedRealtime(), windowShown(), dark)) {
                    val shot = frame.copy(Bitmap.Config.ARGB_8888, false)
                    showing = true
                    activity.runOnUiThread { show(shot) }
                }
                schedule()
            }, handler)
        }.onFailure { schedule() }
    }

    private fun show(shot: Bitmap) {
        if (activity.isFinishing || notice != null) return
        val density = activity.resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val text = TextView(activity).apply {
            text = "The game is showing a black screen.\nIt may still be loading. Report a problem saves what droidtop " +
                "knows about this run and lets you share it."
            setTextColor(Color.WHITE)
        }
        val reportButton = Button(activity).apply {
            text = "Report a problem"
            setOnClickListener { reportNow(shot) }
        }
        val waitButton = Button(activity).apply {
            text = "Keep waiting"
            setOnClickListener { keepWaiting() }
        }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(reportButton)
            addView(waitButton)
        }
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(230, 24, 24, 24))
            setPadding(pad, pad, pad, pad)
            addView(text)
            addView(row)
        }
        parent.addView(card, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = pad })
        notice = card
        line = text
        buttons = listOf(reportButton, waitButton)
        reportButton.requestFocus()
    }

    private fun reportNow(shot: Bitmap) {
        line?.text = "Saving the report..."
        buttons.forEach { it.isEnabled = false }
        report(shot) { said -> activity.runOnUiThread { hide(); android.util.Log.i(WinePresentation.LOG_TAG, said) } }
    }

    private fun keepWaiting() {
        hide()
    }

    private fun hide() {
        notice?.let(parent::removeView)
        notice = null
        line = null
        buttons = emptyList()
        handler.post {
            watch.keepWaiting()
            showing = false
        }
    }

    /**
     * The pad while the notice is up: Left and Right move between its two buttons, A or Enter presses one, B or Back
     * keeps waiting. Every key is the notice's while it is up; false when it is not.
     */
    fun onKey(event: KeyEvent): Boolean {
        if (notice == null) return false
        if (event.action != KeyEvent.ACTION_UP) return true
        val focused = buttons.indexOfFirst { it.isFocused }.coerceAtLeast(0)
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT ->
                buttons.getOrNull(if (focused == 0) 1 else 0)?.requestFocus()
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER ->
                buttons.getOrNull(focused)?.takeIf { it.isEnabled }?.performClick()
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> keepWaiting()
        }
        return true
    }
}
