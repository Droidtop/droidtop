package org.pocketworkstation.pckeyboard

import kotlin.math.max

/**
 * The gesture seam (Droidtop/tracker#903 H6; docs/SPEC.md 6a, "Glide typing" and "The gesture seam"): a finger's
 * stroke over the keys, with its timing, and the keys' geometry in; ranked words out. The owner: "Gesture typing:
 * still a seam only. The official swipe libraries come later." droidtop's own guesser ([GlideClassifier], #341) is the
 * first engine ([ClassifierGestureEngine]), behind it with its results unchanged; [GestureLibrary] is the slot a later
 * library fills. Nothing is bundled or downloaded for it.
 *
 * Pure: no Android types, so the classifier's results through the seam are tested on the JVM.
 */
interface GestureEngine {
    /** Ranked words for [stroke], best first; empty when it has none. Slow: off the main thread. */
    fun recognize(stroke: GestureStroke): List<String>
}

/** A key a stroke can pass over: the letters it types (lower case) and its rectangle, in the keyboard's pixels. */
class GestureKey(val letters: String, val left: Int, val top: Int, val width: Int, val height: Int)

/** The keyboard a stroke is drawn on: its letter keys, and the width of a typical one. */
class GestureLayout(val keys: List<GestureKey>) {
    /** The median letter key's width; at least 1. */
    val keyWidth: Int = if (keys.isEmpty()) 1 else max(1, keys.map { it.width }.sorted()[keys.size / 2])
}

/**
 * A finger's stroke: every point it reported, in the keyboard's pixels, with the time of each in milliseconds.
 * Grows as the finger moves; [copy] hands a fixed one to an engine on another thread.
 */
class GestureStroke(capacity: Int = 128) {
    private var xs = IntArray(max(1, capacity))
    private var ys = IntArray(max(1, capacity))
    private var times = LongArray(max(1, capacity))

    var size = 0
        private set

    fun add(x: Int, y: Int, timeMs: Long) {
        if (size == MAX_POINTS) return
        if (size == xs.size) {
            val grown = minOf(MAX_POINTS, size * 2)
            xs = xs.copyOf(grown)
            ys = ys.copyOf(grown)
            times = times.copyOf(grown)
        }
        xs[size] = x
        ys[size] = y
        times[size] = timeMs
        size++
    }

    fun x(i: Int): Int = xs[i]
    fun y(i: Int): Int = ys[i]
    fun time(i: Int): Long = times[i]

    /** The points' arrays as kept (the first [size] are the stroke's), for an engine to read without copying. */
    internal val xValues: IntArray get() = xs
    internal val yValues: IntArray get() = ys

    fun clear() {
        size = 0
    }

    fun copy(): GestureStroke = GestureStroke(size).also { out ->
        for (i in 0 until size) out.add(xs[i], ys[i], times[i])
    }

    companion object {
        /** More than any finger draws in one stroke; a longer one keeps its first points. */
        const val MAX_POINTS = 16384
    }
}

/**
 * droidtop's own guesser behind the seam: [GlideClassifier] built for [layout] with the parameters glide typing has
 * always given it (a point every sixth of a key, at least 4 pixels; words starting within one and a half keys), so a
 * stroke ranks exactly as it did before the seam.
 */
class ClassifierGestureEngine(layout: GestureLayout, words: List<String>, frequencies: IntArray) : GestureEngine {
    private val classifier = GlideClassifier(
        layout.keys.map { GlideClassifier.Key(it.letters, it.left, it.top, it.width, it.height) },
        minPointDistance = max(4, layout.keyWidth / 6),
        startKeyProximity = layout.keyWidth * 3 / 2,
    ).apply { load(words, frequencies) }

    override fun recognize(stroke: GestureStroke): List<String> {
        val path = classifier.pathOf(stroke.xValues, stroke.yValues, stroke.size) ?: return emptyList()
        return classifier.candidates(path)
    }
}

/**
 * The slot for a gesture library that is not droidtop's own: "The official swipe libraries come later." (the owner,
 * #903). A library, when one comes, implements this and is set as [GestureEngines.library] by whoever loads it; how it
 * arrives (on request, never in the APK) and how it fits the licence rule in SPEC 6a are decided with it. [NONE], the
 * default, has no engine, and droidtop's classifier is used.
 */
interface GestureLibrary {
    /** An engine for [layout] and the language's [words], or null when the library cannot serve them. Off the main thread. */
    fun create(layout: GestureLayout, words: List<String>, frequencies: IntArray): GestureEngine?

    companion object {
        @JvmField
        val NONE: GestureLibrary = object : GestureLibrary {
            override fun create(layout: GestureLayout, words: List<String>, frequencies: IntArray): GestureEngine? = null
        }
    }
}

/** Which gesture engine glide typing uses: the library's when one is set and serves the keyboard, else the classifier. */
object GestureEngines {
    @Volatile
    @JvmStatic
    var library: GestureLibrary = GestureLibrary.NONE

    /** The engine for [layout] and [words]. Slow (it reads every word's path): off the main thread. */
    @JvmStatic
    fun create(layout: GestureLayout, words: List<String>, frequencies: IntArray): GestureEngine =
        library.create(layout, words, frequencies) ?: ClassifierGestureEngine(layout, words, frequencies)
}
