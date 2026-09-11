package dev.droidtop.library

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
