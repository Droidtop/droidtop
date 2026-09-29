package dev.droidtop.library.settings

/**
 * The pure rules of Standard mode's taskbar (docs/SPEC.md 2c "Taskbar on
 * large screens"): where it is drawn and which apps it lists as open. The
 * window itself lives in `:shell-default` (`StandardTaskbar`); the rules are
 * here so they run in a plain unit test.
 */
object TaskbarPolicy {
    /** Android's own line between a phone and a tablet: 600dp of smallest width. */
    const val MIN_SMALLEST_WIDTH_DP = 600

    /** How many apps the taskbar keeps in its open list before it drops the oldest. */
    const val MAX_OPEN_APPS = 8

    /**
     * A display gets a taskbar when it is tablet-sized or larger, and any
     * external (non-default) display gets one whatever its size, since an
     * external display is a desktop-style surface. A phone's own screen
     * does not.
     */
    fun shownOnDisplay(smallestWidthDp: Int, isDefaultDisplay: Boolean): Boolean =
        !isDefaultDisplay || smallestWidthDp >= MIN_SMALLEST_WIDTH_DP

    /**
     * [open] with [key] added at the end when it is not there yet. An app
     * that is already listed keeps its place, so the buttons do not shuffle
     * while the person switches between them; past [max] the oldest goes.
     */
    fun withOpened(open: List<String>, key: String, max: Int = MAX_OPEN_APPS): List<String> {
        if (key in open) return open
        return (open + key).takeLast(max)
    }

    fun without(open: List<String>, key: String): List<String> = open.filterNot { it == key }
}
