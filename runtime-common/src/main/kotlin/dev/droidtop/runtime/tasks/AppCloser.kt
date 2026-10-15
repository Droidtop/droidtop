package dev.droidtop.runtime.tasks

/**
 * Ends another app by the strongest path the privileges allow ([TaskPolicy.closeSteps]) and reports what
 * it actually achieved ([CloseOutcome]), never more. Blocking: [close] may wait on a provider process, so
 * a caller runs it off the main thread ([TaskManager.close] does).
 *
 * [killBackground] is `ActivityManager.killBackgroundProcesses` and returns whether the call was made;
 * it is a lambda so the decision is testable without a device. [endEnginehostGame] asks Enginehost to end its
 * game ([EnginehostEndGame.request]): true when it ended one, false when none was running, null when Enginehost
 * could not be asked or refused. [onClosed] runs after a close that ended the session. [sendToBackground] is told
 * just before the background kill and moves the app off the screen it is on: Android's background kill leaves a
 * visible app alone, so without a helper a game in front was never ended (console, build 1773, RetroArch still
 * running after Kill, Droidtop/tracker#412).
 *
 * Before choosing, the helper is given [CONNECT_MS] to arrive ([PrivilegedShell.connect]), and a close that could
 * only ask Android says why no helper ended it ([PrivilegedShell.unavailableReason]): "Not confirmed" alone left the
 * person nothing to act on (console, build 1649, Quick Menu Kill on RetroArch, three tries).
 */
class AppCloser(
    private val ops: PrivilegedShell,
    private val killBackground: (String) -> Boolean,
    private val endEnginehostGame: () -> Boolean? = { null },
    private val sendToBackground: (String) -> Unit = {},
    private val onClosed: (String) -> Unit = LaunchLedger::forget,
) {
    fun close(packageName: String): CloseOutcome {
        ops.connect(CONNECT_MS)
        return closeConnected(packageName)
    }

    private fun closeConnected(packageName: String): CloseOutcome {
        var providerFailure: String? = null
        var asked = false
        var engineNothingRunning = false
        for (step in TaskPolicy.closeSteps(ops.available(), packageName)) {
            when (step) {
                CloseStep.FORCE_STOP -> when (val result = ops.forceStop(packageName)) {
                    ForceStopResult.Stopped -> {
                        onClosed(packageName)
                        return CloseOutcome.Closed
                    }
                    is ForceStopResult.Failed -> providerFailure = result.message
                    ForceStopResult.NoProvider -> Unit
                }
                // An ended game is a close. "None running" is not one, but it is not a failure either: whatever droidtop
                // believed was running there is not (a launch cancelled or crashed), and Enginehost's own screens may
                // still be open, so the background kill below is still asked.
                CloseStep.ENGINEHOST_END_GAME -> when (endEnginehostGame()) {
                    true -> {
                        onClosed(packageName)
                        return CloseOutcome.Closed
                    }
                    false -> engineNothingRunning = true
                    null -> Unit
                }
                CloseStep.KILL_BACKGROUND -> {
                    sendToBackground(packageName)
                    asked = killBackground(packageName)
                }
            }
        }
        if (engineNothingRunning && providerFailure == null) {
            onClosed(packageName)
            return CloseOutcome.NotRunning("Enginehost has no game running")
        }
        return when {
            providerFailure != null ->
                CloseOutcome.Failed("Helper failed: $providerFailure")
            asked -> CloseOutcome.Requested(TaskPolicy.notConfirmed(ops.unavailableReason()))
            else -> CloseOutcome.Failed("Android refused")
        }
    }

    private companion object {
        /** How long a close waits for a helper whose binder is still on its way: a person pressed Kill and is watching. */
        const val CONNECT_MS = 3_000L
    }

    /** Closes each of [packages] in turn and summarises; one that fails does not stop the rest. */
    fun closeAll(packages: List<String>): ClearAllSummary {
        if (packages.isNotEmpty()) ops.connect(CONNECT_MS)
        return ClearAllSummary.of(packages.map { closeConnected(it) })
    }
}
