package org.pocketworkstation.pckeyboard

import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Glide (swipe) typing's word guesser (Droidtop/tracker#341): a finger's path over the keys in, the dictionary words
 * whose key-to-key path is closest out. Ported from AnySoftKeyboard's `GestureTypingDetector`
 * (ime/app/src/main/java/com/anysoftkeyboard/gesturetyping/GestureTypingDetector.java, revision 8cf81a3a, Apache-2.0),
 * with its scoring unchanged:
 * - a path is reduced to its corners (its ends and every point turning more sharply than 170 degrees);
 * - each word's corners are its letters' key centres, repeated letters once;
 * - a word scores the summed distance from the gesture's corners to its own, each step weighted by how far the two
 *   directions disagree (same direction 1x, opposite 3x), less its frequency times [frequencyFactor], plus a quadratic
 *   penalty when the gesture starts or ends off the word's first or last key;
 * - only words whose first key is within [startKeyProximity] of the start are scored, and a word is dropped as soon as
 *   its running score passes the worst kept candidate.
 * Pure: no Android types, no threads. [load] is the slow part (a path per word); run it off the main thread.
 */
class GlideClassifier(
    private val keys: List<Key>,
    private val frequencyFactor: Double = 1.0,
    private val maxSuggestions: Int = 15,
    minPointDistance: Int = 16,
    startKeyProximity: Int = 150,
) {
    /** A key the glide passes over: the letters it types (lower case) and its rectangle, in the view's pixels. */
    class Key(val letters: String, val left: Int, val top: Int, val width: Int, val height: Int) {
        val centerX: Int get() = left + width / 2
        val centerY: Int get() = top + height / 2

        fun isInside(x: Int, y: Int) = x >= left && x < left + width && y >= top && y < top + height

        fun squaredDistanceFrom(x: Int, y: Int): Int {
            val dx = centerX - x
            val dy = centerY - y
            return dx * dx + dy * dy
        }
    }

    private val minPointDistanceSquared = minPointDistance * minPointDistance
    private val startProximitySquared = startKeyProximity * startKeyProximity

    private val keyByLetter = HashMap<Char, Key>()
    private var words: List<String> = emptyList()
    private var frequencies: IntArray = IntArray(0)
    private var corners: Array<ShortArray> = emptyArray()
    private val wordsByStartKey = HashMap<Key, IntArrayList>()

    @Volatile
    var loaded: Boolean = false
        private set

    private val gestureXs = IntArray(MAX_GESTURE_LENGTH)
    private val gestureYs = IntArray(MAX_GESTURE_LENGTH)
    private var gestureSize = 0

    init {
        for (key in keys) for (c in key.letters) keyByLetter[c.lowercaseChar()] = key
    }

    /** Takes [words] with their [frequencies] (0..255) and works out every word's path. Slow: off the main thread. */
    fun load(words: List<String>, frequencies: IntArray) {
        require(words.size == frequencies.size) { "one frequency per word" }
        loaded = false
        wordsByStartKey.clear()
        val paths = arrayOfNulls<ShortArray>(words.size)
        val xs = IntArray(MAX_GESTURE_LENGTH)
        val ys = IntArray(MAX_GESTURE_LENGTH)
        for ((index, word) in words.withIndex()) {
            var n = 0
            var last = '\u0000'
            for (raw in word) {
                val c = raw.lowercaseChar()
                if (c == last) continue
                val key = keyByLetter[c] ?: keyByLetter[base(c)] ?: continue
                last = c
                if (n < MAX_GESTURE_LENGTH) {
                    xs[n] = key.centerX
                    ys[n] = key.centerY
                    n++
                }
            }
            paths[index] = corners(xs, ys, n)
            val start = word.firstOrNull()?.let { keyByLetter[it.lowercaseChar()] ?: keyByLetter[base(it.lowercaseChar())] }
            if (start != null) wordsByStartKey.getOrPut(start) { IntArrayList() }.add(index)
        }
        this.words = words
        this.frequencies = frequencies
        corners = Array(paths.size) { paths[it] ?: ShortArray(0) }
        loaded = true
    }

    /** Adds a point of the finger's path; one closer than the minimum distance to the last is skipped. */
    fun addPoint(x: Int, y: Int) {
        if (gestureSize > 0) {
            val dx = gestureXs[gestureSize - 1] - x
            val dy = gestureYs[gestureSize - 1] - y
            if (dx * dx + dy * dy <= minPointDistanceSquared) return
        }
        if (gestureSize == MAX_GESTURE_LENGTH) return
        gestureXs[gestureSize] = x
        gestureYs[gestureSize] = y
        gestureSize++
    }

    fun clear() {
        gestureSize = 0
    }

    /** The best words for the path so far, best first; empty before [load] or with no path. */
    fun candidates(): List<String> {
        if (!loaded || gestureSize == 0) return emptyList()
        val path = corners(gestureXs, gestureYs, gestureSize)
        val startX = path[0].toInt()
        val startY = path[1].toInt()
        val endX = path[path.size - 2].toInt()
        val endY = path[path.size - 1].toInt()
        val startKey = keys.firstOrNull { it.isInside(startX, startY) }
        val names = ArrayList<String>(maxSuggestions + 1)
        val weights = ArrayList<Double>(maxSuggestions + 1)
        for ((wordStart, list) in wordsByStartKey) {
            var startPenalty = 0.0
            if (wordStart !== startKey) {
                val d = wordStart.squaredDistanceFrom(startX, startY)
                if (d > startProximitySquared) continue
                startPenalty = d * PROXIMITY_PENALTY_FACTOR
            }
            for (i in 0 until list.size) {
                val index = list[i]
                val word = words[index]
                var endPenalty = 0.0
                val last = word.lastOrNull()?.let { keyByLetter[base(it.lowercaseChar())] ?: keyByLetter[it.lowercaseChar()] }
                if (last != null) {
                    val d = last.squaredDistanceFrom(endX, endY)
                    if (d > startProximitySquared * END_DIST_THRESHOLD_MULTIPLIER) continue
                    endPenalty = d * END_PROXIMITY_PENALTY_FACTOR
                }
                val failFast = if (weights.size >= maxSuggestions) min(MINIMUM_DISTANCE_FILTER, weights[maxSuggestions - 1]) else MINIMUM_DISTANCE_FILTER
                val distance = distanceBetween(path, corners[index], failFast)
                if (distance > MINIMUM_DISTANCE_FILTER) continue
                val weight = distance - frequencyFactor * frequencies[index] + startPenalty + endPenalty
                var at = 0
                while (at < weights.size && weights[at] <= weight) at++
                if (at < maxSuggestions) {
                    weights.add(at, weight)
                    names.add(at, word)
                    if (weights.size > maxSuggestions) {
                        weights.removeAt(maxSuggestions)
                        names.removeAt(maxSuggestions)
                    }
                }
            }
        }
        return names
    }

    /** A growable list of ints, so the start-key index holds no boxed values. */
    private class IntArrayList {
        private var data = IntArray(64)
        var size = 0
            private set

        fun add(value: Int) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = value
        }

        operator fun get(i: Int) = data[i]
    }

    companion object {
        const val MAX_GESTURE_LENGTH = 1024
        private val CURVATURE_THRESHOLD = Math.toRadians(170.0)
        private const val MINIMUM_DISTANCE_FILTER = 1_000_000.0
        private const val DIRECTION_PENALTY_FACTOR = 1.0
        private const val PROXIMITY_PENALTY_FACTOR = 0.000667
        private const val END_PROXIMITY_PENALTY_FACTOR = 0.000333
        private const val END_DIST_THRESHOLD_MULTIPLIER = 25

        /** The letter a key shows for an accented one ("é" on the "e" key). */
        private fun base(c: Char): Char {
            val decomposed = java.text.Normalizer.normalize(c.toString(), java.text.Normalizer.Form.NFD)
            return decomposed.firstOrNull() ?: c
        }

        /** The path's corners as x, y pairs: its ends and every point turning more sharply than 170 degrees. */
        fun corners(xs: IntArray, ys: IntArray, n: Int): ShortArray {
            val out = ArrayList<Int>()
            if (n > 0) {
                out += xs[0]
                out += ys[0]
            }
            for (i in 1 until n - 1) {
                if (angle(xs[i - 1], ys[i - 1], xs[i], ys[i], xs[i + 1], ys[i + 1]) <= CURVATURE_THRESHOLD) {
                    out += xs[i]
                    out += ys[i]
                }
            }
            if (n > 1) {
                out += xs[n - 1]
                out += ys[n - 1]
            }
            return ShortArray(out.size) { out[it].toShort() }
        }

        private fun angle(sx: Int, sy: Int, mx: Int, my: Int, ex: Int, ey: Int): Double {
            val ax = (sx - mx).toDouble()
            val ay = (sy - my).toDouble()
            val bx = (ex - mx).toDouble()
            val by = (ey - my).toDouble()
            val la = sqrt(ax * ax + ay * ay)
            val lb = sqrt(bx * bx + by * by)
            // A point repeated is no turn; the cosine is clamped against rounding past 1.
            if (la == 0.0 || lb == 0.0) return Math.PI
            return acos(max(-1.0, min(1.0, (ax * bx + ay * by) / la / lb)))
        }

        /**
         * The gesture's corners against a word's: each gesture corner is matched to the word corner it is nearest,
         * walking forward only, the distance weighted by direction; leftover word corners count from the gesture's
         * end. [Double.MAX_VALUE] once past [maxDistance], or when the word has more corners than the gesture.
         */
        fun distanceBetween(user: ShortArray, word: ShortArray, maxDistance: Double): Double {
            if (user.size < 2 || word.isEmpty()) return Double.MAX_VALUE
            if (word.size > user.size) return Double.MAX_VALUE
            var total = 0.0
            var w = 0
            var lastDx = 0.0
            var lastDy = 0.0
            var hasLast = false
            var u = 0
            while (u < user.size) {
                val ux = user[u].toInt()
                val uy = user[u + 1].toInt()
                var wx = word[w].toInt()
                var wy = word[w + 1].toInt()
                var d = dist(ux, uy, wx, wy)
                if (w < word.size - 2) {
                    val nx = word[w + 2].toInt()
                    val ny = word[w + 3].toInt()
                    val nd = dist(ux, uy, nx, ny)
                    if (nd < d) {
                        w += 2
                        d = nd
                        wx = nx
                        wy = ny
                    }
                }
                var penalty = 1.0
                if (u >= 2 && w >= 2) {
                    val gdx = (ux - user[u - 2]).toDouble()
                    val gdy = (uy - user[u - 1]).toDouble()
                    penalty = directionPenalty(gdx, gdy, (wx - word[w - 2]).toDouble(), (wy - word[w - 1]).toDouble())
                    lastDx = gdx
                    lastDy = gdy
                    hasLast = true
                }
                total += d * penalty
                if (total > maxDistance) return Double.MAX_VALUE
                u += 2
            }
            val lux = user[user.size - 2].toInt()
            val luy = user[user.size - 1].toInt()
            while (w < word.size) {
                val wx = word[w].toInt()
                val wy = word[w + 1].toInt()
                var penalty = 1.0
                if (hasLast && w >= 2) {
                    penalty = directionPenalty(lastDx, lastDy, (wx - word[w - 2]).toDouble(), (wy - word[w - 1]).toDouble())
                }
                total += dist(lux, luy, wx, wy) * penalty
                if (total > maxDistance) return Double.MAX_VALUE
                w += 2
            }
            return total
        }

        private fun directionPenalty(gx: Double, gy: Double, wx: Double, wy: Double): Double =
            1.0 + DIRECTION_PENALTY_FACTOR * (1.0 - cosine(gx, gy, wx, wy))

        fun cosine(ax: Double, ay: Double, bx: Double, by: Double): Double {
            val ma = ax * ax + ay * ay
            val mb = bx * bx + by * by
            if (ma == 0.0 || mb == 0.0) return 1.0
            return max(-1.0, min(1.0, (ax * bx + ay * by) / sqrt(ma * mb)))
        }

        private fun dist(x1: Int, y1: Int, x2: Int, y2: Int): Double {
            val dx = (x2 - x1).toDouble()
            val dy = (y2 - y1).toDouble()
            return sqrt(dx * dx + dy * dy)
        }
    }
}
