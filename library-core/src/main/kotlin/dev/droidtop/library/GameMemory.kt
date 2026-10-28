package dev.droidtop.library

import android.content.Context
import dev.droidtop.runtime.DisplayControls
import dev.droidtop.runtime.systemstatus.GameMode
import dev.droidtop.runtime.systemstatus.GameModeControl
import dev.droidtop.runtime.systemstatus.OverlayLevel
import dev.droidtop.runtime.systemstatus.PerformanceOverlay
import dev.droidtop.runtime.tasks.TaskManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Per-game memory of the performance overlay's level, the companion screen's brightness and Android's performance mode
 * (docs/SPEC.md "The companion's tabs", Game; Droidtop/tracker#423). What the person changes while a game runs is kept
 * for that game, put back when it starts again, and the values from before the game are restored when it ends, so one
 * game's dim companion or full overlay does not follow the person into the next.
 *
 * Only what changed during a session is kept: a game the person never adjusted keeps following the global values.
 * Performance mode is Android's per-package setting, so it is kept per game with the package it was set for, which
 * matters for an emulator that runs many games under one package.
 */
object GameMemory {
    /** The three values, as they stand. [mode] is null where droidtop has not set one for the game's package. */
    data class Values(val overlay: OverlayLevel, val companion: Float, val mode: GameMode?)

    /** What a game remembers; null is "follows the global value". */
    data class Remembered(
        val overlay: OverlayLevel? = null,
        val companion: Float? = null,
        val mode: GameMode? = null,
        val modePackage: String? = null,
    ) {
        val isEmpty: Boolean get() = overlay == null && companion == null && mode == null
    }

    /**
     * What to keep after a session: each value that differs at the end from what the session started with replaces
     * the game's memory of it; an untouched value keeps what was remembered. Pure.
     */
    fun afterSession(atStart: Values, atEnd: Values, old: Remembered, packageName: String?): Remembered = Remembered(
        overlay = if (atEnd.overlay != atStart.overlay) atEnd.overlay else old.overlay,
        companion = if (atEnd.companion != atStart.companion) atEnd.companion else old.companion,
        mode = if (atEnd.mode != null && atEnd.mode != atStart.mode) atEnd.mode else old.mode,
        modePackage = if (atEnd.mode != null && atEnd.mode != atStart.mode) packageName else old.modePackage,
    )

    private const val PREFS = "game_memory"

    fun read(context: Context, gameId: String): Remembered {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Remembered(
            overlay = prefs.getString("$gameId|overlay", null)?.let { OverlayLevel.fromKey(it) },
            companion = prefs.getFloat("$gameId|companion", -1f).takeIf { it >= 0f },
            mode = prefs.getString("$gameId|mode", null)?.let { GameMode.fromKey(it) },
            modePackage = prefs.getString("$gameId|modePackage", null),
        )
    }

    private fun write(context: Context, gameId: String, remembered: Remembered) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            remembered.overlay?.let { putString("$gameId|overlay", it.key) } ?: remove("$gameId|overlay")
            remembered.companion?.let { putFloat("$gameId|companion", it) } ?: remove("$gameId|companion")
            remembered.mode?.let { putString("$gameId|mode", it.key) } ?: remove("$gameId|mode")
            remembered.modePackage?.let { putString("$gameId|modePackage", it) } ?: remove("$gameId|modePackage")
        }.apply()
    }

    private class Session(
        val gameId: String,
        val before: Values,
        val remembered: Remembered,
        var applied: Values,
        var packageName: String? = null,
        // The package's mode before the game's remembered one was set, to put back when it ends.
        var modeBefore: GameMode? = null,
        var modeSet: Boolean = false,
    )

    private var started = false

    /** Starts following the running game, once per process. */
    @Synchronized
    fun install(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        var session: Session? = null
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            combine(LaunchDisplay.running, LaunchDisplay.runningPackage) { running, pkg -> running?.context?.gameId to pkg }
                .collect { (gameId, pkg) ->
                    val current = session
                    if (current != null && current.gameId != gameId) {
                        end(app, current)
                        session = null
                    }
                    if (gameId != null && session == null) session = begin(app, gameId)
                    val now = session ?: return@collect
                    if (pkg != null && now.packageName == null) {
                        now.packageName = pkg
                        applyMode(now, pkg)
                    }
                }
        }
    }

    private fun values(packageName: String?): Values = Values(
        PerformanceOverlay.level.value,
        DisplayControls.companionLevel.value,
        packageName?.let { GameModeControl.lastSet(it) },
    )

    private fun begin(context: Context, gameId: String): Session {
        val before = values(null)
        val remembered = read(context, gameId)
        remembered.overlay?.let { PerformanceOverlay.setLevel(context, it) }
        remembered.companion?.let { DisplayControls.setCompanionLevel(it) }
        return Session(gameId, before, remembered, applied = values(null))
    }

    /** The game's remembered mode, once its package is known and it is the package the mode was kept for. */
    private fun applyMode(session: Session, packageName: String) {
        val before = GameModeControl.lastSet(packageName)
        session.applied = session.applied.copy(mode = before)
        val mode = session.remembered.mode ?: return
        if (session.remembered.modePackage != packageName || mode == before) return
        val shell = TaskManager.shell
        if (!runCatching { shell.capabilities().shellCommand }.getOrDefault(false)) return
        if (GameModeControl.set(shell, mode, packageName)) {
            session.applied = session.applied.copy(mode = mode)
            session.modeBefore = before
            session.modeSet = true
        }
    }

    private fun end(context: Context, session: Session) {
        val pkg = session.packageName
        val atEnd = values(pkg)
        val kept = afterSession(session.applied, atEnd, session.remembered, pkg)
        if (kept != session.remembered) write(context, session.gameId, kept)
        // The values from before the game, for whatever comes next.
        if (PerformanceOverlay.level.value != session.before.overlay) PerformanceOverlay.setLevel(context, session.before.overlay)
        if (DisplayControls.companionLevel.value != session.before.companion) DisplayControls.setCompanionLevel(session.before.companion)
        // A mode this game's memory set goes back to the one the package had, when droidtop knows it.
        val modeBefore = session.modeBefore
        if (session.modeSet && pkg != null && modeBefore != null && GameModeControl.lastSet(pkg) != modeBefore) {
            runCatching { GameModeControl.set(TaskManager.shell, modeBefore, pkg) }
        }
    }
}
