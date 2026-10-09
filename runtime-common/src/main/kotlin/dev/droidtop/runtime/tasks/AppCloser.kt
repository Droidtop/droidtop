package dev.droidtop.runtime.tasks

/**
 * Ends another app by the strongest path the privileges allow ([TaskPolicy.closeSteps]) and reports what
 * it actually achieved ([CloseOutcome]), never more. Blocking: [close] may wait on a provider process, so
 * a caller runs it off the main thread ([TaskManager.close] does).
 *
 * [killBackground] is `ActivityManager.killBackgroundProcesses` and returns whether the call was made;
 * it is a lambda so the decision is testable without a device. [onClosed] runs after a confirmed close.
 *
 * Before choosing, the helper is given [CONNECT_MS] to arrive ([PrivilegedShell.connect]), and a close that could
 * only ask Android says why no helper ended it ([PrivilegedShell.unavailableReason]): "Not confirmed" alone left the
 * person nothing to act on (console, build 1649, Quick Menu Kill on RetroArch, three tries).
 */
class AppCloser(
    private val ops: PrivilegedShell,
    private val killBackground: (String) -> Boolean,
    private val onClosed: (String) -> Unit = LaunchLedger::forget,
) {
    fun close(packageName: String): CloseOutcome {
        ops.connect(CONNECT_MS)
        return closeConnected(packageName)
    }

    private fun closeConnected(packageName: String): CloseOutcome {
        var providerFailure: String? = null
        var asked = false
        for (step in TaskPolicy.closeSteps(ops.available())) {
            when (step) {
                CloseStep.FORCE_STOP -> when (val result = ops.forceStop(packageName)) {
                    ForceStopResult.Stopped -> {
                        onClosed(packageName)
                        return CloseOutcome.Closed
                    }
                    is ForceStopResult.Failed -> providerFailure = result.message
                    ForceStopResult.NoProvider -> Unit
                }
                CloseStep.KILL_BACKGROUND -> asked = killBackground(packageName)
            }
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
