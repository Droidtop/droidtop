package dev.droidtop.library

import android.content.Context
import dev.droidtop.runtime.DisplayControls
import dev.droidtop.runtime.systemstatus.OverlayLevel
import dev.droidtop.runtime.systemstatus.PerformanceOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Per-game memory of the performance overlay's level and the companion screen's brightness (docs/SPEC.md, the Quick
 * Menu's sections, "Each game remembers its own"; Droidtop/tracker#423). What the person changes while a game runs is
 * kept for that game, put back when it starts again, and the values from before the game are restored when it ends,
 * so one game's dim companion or full overlay does not follow the person into the next.
 *
 * Only what changed during a session is kept: a game the person never adjusted keeps following the global values.
 * Android's performance mode is not kept here: the power policy (Droidtop/tracker#841, its rules and its journal) is
 * the one mechanism for it.
 */
object GameMemory {
    /** The two values, as they stand. */
    data class Values(val overlay: OverlayLevel, val companion: Float)

    /** What a game remembers; null is "follows the global value". */
    data class Remembered(
        val overlay: OverlayLevel? = null,
        val companion: Float? = null,
    ) {
        val isEmpty: Boolean get() = overlay == null && companion == null
    }

    /**
     * What to keep after a session: each value that differs at the end from what the session started with replaces
     * the game's memory of it; an untouched value keeps what was remembered. Pure.
     */
    fun afterSession(atStart: Values, atEnd: Values, old: Remembered): Remembered = Remembered(
        overlay = if (atEnd.overlay != atStart.overlay) atEnd.overlay else old.overlay,
        companion = if (atEnd.companion != atStart.companion) atEnd.companion else old.companion,
    )

    private const val PREFS = "game_memory"

    /** Reads the preferences file: not for the main thread. */
    fun read(context: Context, gameId: String): Remembered {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Remembered(
            overlay = prefs.getString("$gameId|overlay", null)?.let { OverlayLevel.fromKey(it) },
            companion = prefs.getFloat("$gameId|companion", -1f).takeIf { it >= 0f },
        )
    }

    private fun write(context: Context, gameId: String, remembered: Remembered) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            remembered.overlay?.let { putString("$gameId|overlay", it.key) } ?: remove("$gameId|overlay")
            remembered.companion?.let { putFloat("$gameId|companion", it) } ?: remove("$gameId|companion")
        }.apply()
    }

    private class Session(val gameId: String, val before: Values, val remembered: Remembered, val applied: Values)

    private var started = false

    /** Starts following the running game, once per process. Its reads and writes run on the IO dispatcher. */
    @Synchronized
    fun install(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        var session: Session? = null
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            LaunchDisplay.running.map { it?.context?.gameId }.distinctUntilChanged().collect { gameId ->
                session?.let { end(app, it) }
                session = gameId?.let { begin(app, it) }
            }
        }
    }

    private fun values(): Values = Values(PerformanceOverlay.level.value, DisplayControls.companionLevel.value)

    private fun begin(context: Context, gameId: String): Session {
        val before = values()
        val remembered = read(context, gameId)
        remembered.overlay?.let { PerformanceOverlay.setLevel(context, it) }
        remembered.companion?.let { DisplayControls.setCompanionLevel(it) }
        return Session(gameId, before, remembered, applied = values())
    }

    private fun end(context: Context, session: Session) {
        val kept = afterSession(session.applied, values(), session.remembered)
        if (kept != session.remembered) write(context, session.gameId, kept)
        // The values from before the game, for whatever comes next.
        if (PerformanceOverlay.level.value != session.before.overlay) PerformanceOverlay.setLevel(context, session.before.overlay)
        if (DisplayControls.companionLevel.value != session.before.companion) DisplayControls.setCompanionLevel(session.before.companion)
    }
}
