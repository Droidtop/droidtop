package dev.droidtop.library.integrations

import android.content.Context
import dev.droidtop.pluginhost.OpenTarget

/**
 * Open on a job or a line of its report (docs/SPEC.md "Jobs and their results", review finding 15): the game's page,
 * the folder or the file it made, or one of droidtop's places. Opening a game's page or a file needs :app, which
 * sets [opener] at start; without it nothing opens and the row offers no Open.
 */
object JobOpen {
    @Volatile
    var opener: ((Context, OpenTarget) -> Boolean)? = null

    /** Whether Open can be offered at all in this process. */
    val available: Boolean get() = opener != null

    /** The row's words for [target]. Pure. */
    fun label(target: OpenTarget): String = when (target) {
        is OpenTarget.Game -> "Open the game's page"
        is OpenTarget.Folder -> "Open the folder"
        is OpenTarget.File -> "Open the file"
        is OpenTarget.Place -> "Open"
    }

    /** Opens [target]; false when nothing could. */
    fun open(context: Context, target: OpenTarget): Boolean = runCatching { opener?.invoke(context, target) == true }.getOrDefault(false)
}
