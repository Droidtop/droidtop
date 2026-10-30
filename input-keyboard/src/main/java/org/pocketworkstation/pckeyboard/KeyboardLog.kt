package org.pocketworkstation.pckeyboard

import android.util.Log

/** The log tag of droidtop's own keyboard code. */
internal const val KEYBOARD_TAG = "HK/droidtop"

/**
 * [block]'s result, as `runCatching` gives it, with a failure logged as [what] at warning level
 * (Droidtop/tracker#772: no failure in the keyboard vanishes without a line in the log).
 */
internal inline fun <T> logged(what: String, block: () -> T): Result<T> =
    runCatching(block).onFailure { Log.w(KEYBOARD_TAG, what, it) }
