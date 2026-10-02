package dev.droidtop.library

import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Reads up to [max] bytes from the front of this stream, returning
 * exactly what was available.
 *
 * This exists because `InputStream.readNBytes(int)` is API 33+ and
 * droidtop's minSdk is 26. On anything older it throws
 * `NoSuchMethodError` -- an `Error`, so the `IOException` handlers around
 * the two call sites did not catch it, and opening the PC game detail
 * screen took the whole app down on Android 9 (BlueStacks rig,
 * 2026-09-11). `InputStream.read(byte[], int, int)` has been there since
 * API 1, and the loop is needed because one `read` may return short.
 */
internal fun InputStream.readHeadBytes(max: Int): ByteArray {
    val buffer = ByteArray(max)
    var filled = 0
    while (filled < max) {
        val n = read(buffer, filled, max - filled)
        if (n < 0) break
        filled += n
    }
    return if (filled == max) buffer else buffer.copyOf(filled)
}

/**
 * How far into a page the Twine probe looks. A published story puts the
 * story format's own script and styles (SugarCube's are ~230 KB) ahead of
 * `<tw-storydata>`, so the 8 KB head the other probes read never reaches
 * it (Droidtop/tracker#287: every SugarCube game was "not an engine game").
 */
internal const val TWINE_SCAN_BYTES = 1024 * 1024

/** The front of a published Twine page as Latin-1 text (the markers are ASCII), or null when it cannot be read. */
internal fun File.readTwineHead(): String? = try {
    inputStream().use { String(it.readHeadBytes(minOf(length(), TWINE_SCAN_BYTES.toLong()).toInt()), Charsets.ISO_8859_1) }
} catch (e: IOException) {
    null
}

/**
 * Whether [head] (from [readTwineHead]) is a published Twine story: the
 * `<tw-storydata>` element, or the story format's banner comment in the
 * first 8 KB for a build that moved the story data into script (a compiled
 * SugarCube page has no such element).
 */
internal fun looksLikeTwine(head: String): Boolean =
    head.contains("<tw-storydata", ignoreCase = true) || head.take(8 * 1024).contains("story format", ignoreCase = true)
