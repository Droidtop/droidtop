package dev.droidtop.runtime

/**
 * One value kept as a string in preferences, read once and then held in memory (Droidtop/tracker#771). Standard's
 * widget stacks, drawer tabs, recent apps and icon swipes each wrote this by hand, each with its own cache and its
 * own parse that fell back to empty, so a stored value that did not parse was silently dropped and then saved over.
 * Here a value that does not parse is handed to [Backing.unreadable] as it was, once, before anything replaces it.
 *
 * Every change goes through [update] under one lock, so a change on one thread never undoes one made on another;
 * an unchanged value is not written. Pure, so it is tested without Android; [Backing] is the preferences file.
 */
class CachedPref<T>(
    private val key: String,
    private val empty: T,
    private val decode: (String) -> T,
    private val encode: (T) -> String,
) {
    /** Where the string lives. */
    interface Backing {
        fun read(key: String): String?
        fun write(key: String, value: String)

        /** [raw], stored under [key], did not decode ([error]); the store goes on from empty. */
        fun unreadable(key: String, raw: String, error: Throwable)
    }

    private class Held<T>(val value: T)

    private val lock = Any()
    @Volatile private var held: Held<T>? = null

    fun get(backing: Backing): T = (held ?: synchronized(lock) { held ?: Held(load(backing)).also { held = it } }).value

    /** Applies [change] to the value and saves the result when it differs; returns the result. */
    fun update(backing: Backing, change: (T) -> T): T = synchronized(lock) {
        val before = get(backing)
        val after = change(before)
        if (after != before) {
            held = Held(after)
            backing.write(key, encode(after))
        }
        after
    }

    private fun load(backing: Backing): T {
        val raw = backing.read(key)
        if (raw.isNullOrEmpty()) return empty
        return try {
            decode(raw)
        } catch (e: Exception) {
            backing.unreadable(key, raw, e)
            empty
        }
    }
}
