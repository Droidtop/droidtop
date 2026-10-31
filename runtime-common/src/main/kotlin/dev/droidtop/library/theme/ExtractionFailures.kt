package dev.droidtop.library.theme

import java.util.concurrent.ConcurrentHashMap

/**
 * The bundled themes whose extraction failed in this process (docs/SPEC.md 10c, "Storage full";
 * Droidtop/tracker#849). A failed parse is never cached, on purpose, so before this every load of a theme that could
 * not be extracted (a full disk) deleted the half-written copy and extracted the whole theme again, under the one
 * extraction lock, while the first composition waited on that lock. A theme named here is not tried again until the
 * process restarts or the storage-full screen's Try again finds room and calls [clear]; until then it loads from the
 * last complete extraction if one is left (an older build's), else as no theme, which the shell already draws its
 * unthemed fallback for. Pure, for the tests.
 */
object ExtractionFailures {
    private val keys: MutableSet<String> = ConcurrentHashMap.newKeySet<String>()

    /** Whether [key] may be extracted: it has not failed in this process. */
    fun shouldTry(key: String): Boolean = key !in keys

    /** [key]'s extraction failed: it is not tried again until [clear]. */
    fun failed(key: String) {
        keys += key
    }

    /** Every theme may be tried again. */
    fun clear() {
        keys.clear()
    }
}
