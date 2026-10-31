package dev.droidtop.runtime

import android.content.Context
import android.os.StatFs

/**
 * Whether the app's own storage is too full for droidtop to start (docs/SPEC.md 10c, "Storage full";
 * Droidtop/tracker#849). With about 12 MB free on /data, opening droidtop raised "Input dispatching timed out
 * (Application does not have a focused window)" and never drew a window. A start that has too little room shows one
 * plain screen that says so instead, before anything reads or writes a file.
 *
 * Asking is one statfs call on the app's files directory, with no write, so it is safe on the main thread.
 */
object StorageGuard {
    /**
     * The least free space droidtop starts with: 64 MB (decimal, as [dev.droidtop.library.format.ValueFormat.size]
     * and Android's own Settings count it). The bundled ES-DE themes are extracted into the cache on first use, and
     * the larger one alone takes about 29 MB on disk, with the scan log, the library database and preferences
     * writing beside it. Android's own storage-low warning starts at 5 to 10% of the volume, far above this, so a
     * device that reaches this line is already well past it.
     */
    const val LOW_SPACE_BYTES: Long = 64L * 1_000_000L

    /**
     * Set once when the process starts (`DroidtopApplication.attachBaseContext`, before every content provider and
     * Application.onCreate) and again by each [check]. Read by the activities that show the storage-full screen.
     */
    @Volatile
    var full: Boolean = false

    /** Whether [availableBytes] free is too little to start on. Pure, for the tests. */
    fun lowSpace(availableBytes: Long): Boolean = availableBytes < LOW_SPACE_BYTES

    /**
     * Reads the free space of the app's files directory, records the answer in [full] and returns it. A StatFs that
     * fails reports not low: droidtop then starts as it always has rather than blocking on a reading it never got.
     */
    fun check(context: Context): Boolean {
        val low = availableBytes(context)?.let { lowSpace(it) } ?: false
        full = low
        return low
    }

    /** The bytes free to the app on its own storage, or null when StatFs fails. */
    fun availableBytes(context: Context): Long? = runCatching { StatFs(context.filesDir.path).availableBytes }.getOrNull()
}
