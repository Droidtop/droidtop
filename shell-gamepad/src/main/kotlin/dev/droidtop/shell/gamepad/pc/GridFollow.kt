package dev.droidtop.shell.gamepad.pc

/**
 * The PC grid behind the search panel follows what is typed once the text rests, not on every key
 * (Droidtop/tracker#884). Each key used to narrow the grid at once, recomposing a new set of capsules behind a
 * panel that covers them; on a device that keeps little file cache (BlueStacks, the rig) that work paged code in
 * from disk on the UI thread and the field dropped keys ("Stardew" kept "Sta", then an ANR). The panel's own
 * results still follow every key. Closing the panel applies the last text at once ([flush]), so the grid is never
 * left on an older text. Pure: the caller passes the time.
 */
internal class GridFollow(private val restMs: Long = GRID_FOLLOW_MS) {
    private var pending: String? = null
    private var typedAt = 0L

    /** [text] was typed at [nowMs]; returns how long to wait before asking [due]. */
    fun typed(text: String, nowMs: Long): Long {
        pending = text
        typedAt = nowMs
        return restMs
    }

    /** The text to narrow the grid to now, once it has rested [restMs]; null while typing goes on or nothing waits. */
    fun due(nowMs: Long): String? {
        val text = pending ?: return null
        if (nowMs - typedAt < restMs) return null
        pending = null
        return text
    }

    /** The text still waiting, taken now (the panel is closing); null when nothing waits. */
    fun flush(): String? = pending.also { pending = null }
}

/** Long enough to cover a run of keys typed together, short enough that the grid has caught up when the panel closes. */
internal const val GRID_FOLLOW_MS = 300L
