package org.pocketworkstation.pckeyboard

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Glide typing on the keyboard (Droidtop/tracker#341): one finger drawn across the letter keys types a word. The view
 * hands it each single-finger touch ([down], [move], [up]) in keyboard coordinates; a touch that starts on a letter
 * and travels [GLIDE_START_KEYS] key widths becomes a glide, after which the view stops the ordinary key press and
 * draws the [trail]. On release the guesser's words go to [onWords], best first.
 *
 * The guesser ([GlideClassifier]) is built for each keyboard's letter keys and the language's glide words
 * ([KeyboardDictionaries.glideWords]) on a background thread; until it is ready, or with no words for the language,
 * nothing becomes a glide and the keys type as before. Main thread, except the build.
 */
class GlideTyping(private val onWords: (List<String>) -> Unit) {
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var classifier: GlideClassifier? = null
    private var generation = 0
    private var keyboard: Keyboard? = null
    private var words: (() -> Pair<List<String>, IntArray>?)? = null

    private var keyWidth = 1
    private var tracking = false
    private var active = false
    private var downX = 0
    private var downY = 0
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
        classifier = null
        val keys = keyboard.keys.filter { it.codes != null && it.codes.isNotEmpty() && Character.isLetter(it.codes[0]) }
            .map { GlideClassifier.Key(String(Character.toChars(it.codes[0])).lowercase(), it.x, it.y, it.width, it.height) }
        if (keys.size < MIN_LETTER_KEYS) return
        keyWidth = max(1, keys.map { it.width }.sorted()[keys.size / 2])
        val source = words ?: return
        worker.execute {
            val loaded = runCatching { source() }.getOrNull() ?: return@execute
            val built = GlideClassifier(keys, minPointDistance = max(4, keyWidth / 6), startKeyProximity = keyWidth * 3 / 2)
            built.load(loaded.first, loaded.second)
            main.post { if (gen == generation) classifier = built }
        }
    }

    /** A finger went down at (x, y); [onLetter] when that is a letter key. */
    fun down(x: Int, y: Int, onLetter: Boolean) {
        cancel()
        val c = classifier ?: return
        if (!onLetter) return
        tracking = true
        downX = x
        downY = y
        c.clear()
        add(c, x, y)
    }

    /** The finger moved; true once this touch is a glide (the view then keeps it from the keys). */
    fun move(x: Int, y: Int): Boolean {
        if (!tracking) return false
        val c = classifier ?: return false.also { cancel() }
        add(c, x, y)
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
        val c = classifier
        if (c != null) {
            add(c, x, y)
            val found = c.candidates()
            if (found.isNotEmpty()) onWords(found)
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
        classifier = null
        cancel()
    }

    private fun add(c: GlideClassifier, x: Int, y: Int) {
        c.addPoint(x, y)
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

        private val worker by lazy { Executors.newSingleThreadExecutor { r -> Thread(r, "keyboard-glide").apply { isDaemon = true } } }

        /** The trail's paint: droidtop's focus azure, rounded, [widthPx] wide. */
        @JvmStatic
        fun trailPaint(widthPx: Float): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xCC3BB5FF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = widthPx
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
    }
}
