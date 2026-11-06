package org.pocketworkstation.pckeyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Glide typing on the keyboard (Droidtop/tracker#341): one finger drawn across the letter keys types a word. The view
 * hands it each single-finger touch ([down], [move], [up]) in keyboard coordinates; a touch that starts on a letter
 * and travels [GLIDE_START_KEYS] key widths becomes a glide, after which the view stops the ordinary key press and
 * draws the [trail]. On release the guesser's words go to [onWords], best first.
 *
 * The guesser is a [GestureEngine] (Droidtop/tracker#903 H6, the gesture seam): droidtop's [GlideClassifier] unless a
 * gesture library is set ([GestureEngines]). It is made for each keyboard's letter keys and the language's glide words
 * ([KeyboardDictionaries.glideWords]) on a background thread, and is handed each stroke whole, with its timing, at
 * release. Until it is ready, or with no words for the language,
 * nothing becomes a glide and the keys type as before. Main thread, except the build and the scoring at release,
 * which run on the glide's own thread; a guess that comes back after the keyboard changed or after a newer glide
 * ended is dropped.
 */
class GlideTyping(private val onWords: (List<String>) -> Unit) {
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var engine: GestureEngine? = null
    private var generation = 0
    /** Counts finished glides, so only the newest one's words are typed. */
    private var glides = 0
    private var keyboard: Keyboard? = null
    private var words: (() -> Pair<List<String>, IntArray>?)? = null

    private var keyWidth = 1
    private var tracking = false
    private var active = false
    private var downX = 0
    private var downY = 0
    /** The touch's points with their times, for the engine. */
    private val stroke = GestureStroke()
    private val trailX = IntArray(GlideClassifier.MAX_GESTURE_LENGTH)
    private val trailY = IntArray(GlideClassifier.MAX_GESTURE_LENGTH)
    private var trailSize = 0

    /** Whether a glide is under way, so the view keeps the touch from the keys. */
    val gliding: Boolean get() = active

    /** The words the language's glide guesses from, read off the main thread when a keyboard is prepared. */
    fun setWords(source: (() -> Pair<List<String>, IntArray>?)?) {
        words = source
        keyboard?.let(::prepare)
    }

    /** Builds the guesser for [keyboard]'s letter keys, in the background. A keyboard shown later replaces it. */
    fun prepare(keyboard: Keyboard) {
        this.keyboard = keyboard
        val gen = ++generation
        engine = null
        val keys = keyboard.keys.filter { it.codes != null && it.codes.isNotEmpty() && Character.isLetter(it.codes[0]) }
            .map { GestureKey(String(Character.toChars(it.codes[0])).lowercase(), it.x, it.y, it.width, it.height) }
        if (keys.size < MIN_LETTER_KEYS) return
        val layout = GestureLayout(keys)
        keyWidth = layout.keyWidth
        val source = words ?: return
        worker.execute {
            val loaded = logged("glide words not read") { source() }.getOrNull() ?: return@execute
            val built = GestureEngines.create(layout, loaded.first, loaded.second)
            main.post { if (gen == generation) engine = built }
        }
    }

    /** A finger went down at (x, y); [onLetter] when that is a letter key. */
    fun down(x: Int, y: Int, onLetter: Boolean) {
        cancel()
        if (engine == null) return
        if (!onLetter) return
        tracking = true
        downX = x
        downY = y
        stroke.clear()
        add(x, y)
    }

    /** The finger moved; true once this touch is a glide (the view then keeps it from the keys). */
    fun move(x: Int, y: Int): Boolean {
        if (!tracking) return false
        if (engine == null) return false.also { cancel() }
        add(x, y)
        if (!active) {
            val dx = (x - downX).toLong()
            val dy = (y - downY).toLong()
            val reach = (keyWidth * GLIDE_START_KEYS).toLong()
            if (dx * dx + dy * dy >= reach * reach) active = true
        }
        return active
    }

    /** The finger lifted; true when it ended a glide, whose words have gone to [onWords]. */
    fun up(x: Int, y: Int): Boolean {
        if (!active) {
            cancel()
            return false
        }
        val e = engine
        if (e != null) {
            add(x, y)
            if (stroke.size > 0) {
                // The engine scores a copy, so the next touch never changes a stroke being scored.
                val whole = stroke.copy()
                val gen = generation
                val glide = ++glides
                worker.execute {
                    val found = e.recognize(whole)
                    if (found.isNotEmpty()) main.post { if (gen == generation && glide == glides) onWords(found) }
                }
            }
        }
        cancel()
        return true
    }

    fun cancel() {
        tracking = false
        active = false
        trailSize = 0
    }

    /** Forgets the guesser (the language changed, or glide was turned off). */
    fun clear() {
        generation++
        engine = null
        cancel()
    }

    private fun add(x: Int, y: Int) {
        stroke.add(x, y, SystemClock.uptimeMillis())
        if (trailSize < trailX.size) {
            trailX[trailSize] = x
            trailY[trailSize] = y
            trailSize++
        }
    }

    /** The glide's trail over the keys, shifted by the view's padding; nothing unless a glide is under way. */
    fun drawTrail(canvas: Canvas, offsetX: Int, offsetY: Int, paint: Paint) {
        if (!active || trailSize < 2) return
        val path = Path()
        path.moveTo((trailX[0] + offsetX).toFloat(), (trailY[0] + offsetY).toFloat())
        for (i in 1 until trailSize) path.lineTo((trailX[i] + offsetX).toFloat(), (trailY[i] + offsetY).toFloat())
        canvas.drawPath(path, paint)
    }

    companion object {
        /** How far, in key widths, a touch travels before it is a glide rather than a press. */
        const val GLIDE_START_KEYS = 1.0
        private const val MIN_LETTER_KEYS = 10

        private val worker by lazy { KeyboardThreads.single("keyboard-glide") }

        /** The trail's paint: the keyboard's accent ([KeyboardAppearance.colors]) at 80%, rounded, [widthPx] wide. */
        @JvmStatic
        fun trailPaint(context: Context, widthPx: Float): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = (KeyboardAppearance.colors(context).accent and 0x00FFFFFF) or (0xCC shl 24)
            style = Paint.Style.STROKE
            strokeWidth = widthPx
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
    }
}
