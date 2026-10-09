package dev.droidtop.library.settings

/**
 * The controls for a running game, and the one decision "does this row ask first" (docs/SPEC.md "The companion's
 * tabs" and "Quick Menu: a branching panel", Droidtop/tracker#414). Pure state: the companion's Game tab and the
 * Quick Menu's Game section both read it, so turning Ask before stopping off changes both the same way.
 */
enum class GameRow { RESUME, QUIT, RESTART, KILL, EMULATOR, OVERLAY, PERFORMANCE_MODE }

/**
 * What the runner says about itself: a stream (windowcast) disconnects rather than quits and has no restart or kill;
 * a runner may name its own Quit label and question.
 */
data class GameRunner(
    val stream: Boolean = false,
    val quitLabel: String? = null,
    val quitQuestion: String? = null,
    /** Restart and Kill apply; a stream's do not unless the runner says so. */
    val restartAndKill: Boolean = !stream,
) {
    companion object {
        val LOCAL = GameRunner()
        val STREAM = GameRunner(stream = true, quitLabel = "Disconnect", quitQuestion = "Disconnect from the PC?")
    }
}

/** The two person-set rules (Companion group): both on by default. */
data class AskFirst(val beforeStopping: Boolean = true, val beforeLoadAndOverwrite: Boolean = true)

object GameControls {
    private val STOPPING = setOf(GameRow.QUIT, GameRow.RESTART, GameRow.KILL)

    /**
     * The rows a game shows, in order: Resume and Quit only in Kid and Kiosk; a stream without Restart and Kill, and
     * without the emulator choice (a surface draws that row only for a console game with an emulator to choose).
     */
    fun rows(mode: UiMode, runner: GameRunner): List<GameRow> {
        val all = buildList {
            add(GameRow.RESUME)
            add(GameRow.QUIT)
            if (runner.restartAndKill) {
                add(GameRow.RESTART)
                add(GameRow.KILL)
            }
            if (!runner.stream) add(GameRow.EMULATOR)
            add(GameRow.OVERLAY)
            add(GameRow.PERFORMANCE_MODE)
        }
        return all.filter { row -> ControlAccess.shows(mode, controlRow(row)) }
    }

    private fun controlRow(row: GameRow): ControlRow = when (row) {
        GameRow.RESUME -> ControlRow.GAME_RESUME
        GameRow.QUIT -> ControlRow.GAME_QUIT
        GameRow.RESTART -> ControlRow.GAME_RESTART
        GameRow.KILL -> ControlRow.GAME_KILL
        GameRow.EMULATOR -> ControlRow.GAME_EMULATOR
        GameRow.OVERLAY -> ControlRow.GAME_OVERLAY
        GameRow.PERFORMANCE_MODE -> ControlRow.GAME_PERFORMANCE_MODE
    }

    fun label(row: GameRow, runner: GameRunner): String = when (row) {
        GameRow.RESUME -> "Resume"
        GameRow.QUIT -> runner.quitLabel ?: "Quit"
        GameRow.RESTART -> "Restart"
        GameRow.KILL -> "Kill"
        GameRow.EMULATOR -> "Emulator"
        GameRow.OVERLAY -> "Overlay"
        GameRow.PERFORMANCE_MODE -> "Performance mode"
    }

    /** The question a row asks first, naming the game. */
    fun question(row: GameRow, runner: GameRunner, game: String): String = when (row) {
        GameRow.QUIT -> runner.quitQuestion ?: "Quit $game? Progress you have not saved is lost."
        GameRow.RESTART -> "Restart $game? Progress you have not saved is lost."
        GameRow.KILL -> "Kill $game? It is stopped at once, without saving."
        else -> "${label(row, runner)}?"
    }

    /**
     * Whether [row] asks first: a stopping row (Quit, Restart, Kill) while Ask before stopping is on; any row whose
     * runner or plugin flags it [runnerConfirm]; and in Kid and Kiosk every stopping row, whatever the settings say.
     */
    fun asks(row: GameRow, mode: UiMode, ask: AskFirst, runnerConfirm: Boolean = false): Boolean =
        runnerConfirm || (row in STOPPING && (ask.beforeStopping || ControlAccess.rules(mode).alwaysAsk))

    /** A plugin row flagged `confirm` (Load state, saving into a filled slot): Ask before load and overwrite, or Kid. */
    fun asksPluginRow(confirm: Boolean, mode: UiMode, ask: AskFirst): Boolean =
        confirm && (ask.beforeLoadAndOverwrite || ControlAccess.rules(mode).alwaysAsk)

    /** Stop on an Apps row, Clear all, Stop foreground app: Ask before stopping, or Kid. */
    fun asksStop(mode: UiMode, ask: AskFirst): Boolean = ask.beforeStopping || ControlAccess.rules(mode).alwaysAsk

    /**
     * The Quick Menu's way of asking: a first A arms the row and a second runs it. [armed] is whether the row is
     * armed now; true means this press only arms it.
     */
    fun needsSecondPress(row: GameRow, armed: Boolean, mode: UiMode, ask: AskFirst): Boolean = asks(row, mode, ask) && !armed
}
