package dev.droidtop.runtime.tasks

/**
 * Ends another app by the strongest path the privileges allow ([TaskPolicy.closeSteps]) and reports what
 * it actually achieved ([CloseOutcome]), never more. Blocking: [close] may wait on a provider process, so
 * a caller runs it off the main thread ([TaskManager.close] does).
 *
 * [killBackground] is `ActivityManager.killBackgroundProcesses` and returns whether the call was made;
 * it is a lambda so the decision is testable without a device. [onClosed] runs after a confirmed close.
 */
class AppCloser(
    private val ops: PrivilegedShell,
    private val killBackground: (String) -> Boolean,
    private val onClosed: (String) -> Unit = LaunchLedger::forget,
) {
    fun close(packageName: String): CloseOutcome {
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
                CloseOutcome.Failed("The privileged helper could not close $packageName: $providerFailure. ${TaskPolicy.ENABLE_HINT}")
            asked -> CloseOutcome.Requested("Asked Android to close it, but cannot confirm it did. ${TaskPolicy.ENABLE_HINT}")
            else -> CloseOutcome.Failed("Android refused to let droidtop ask. ${TaskPolicy.ENABLE_HINT}")
        }
    }

    /** Closes each of [packages] in turn and summarises; one that fails does not stop the rest. */
    fun closeAll(packages: List<String>): ClearAllSummary =
        ClearAllSummary.of(packages.map { close(it) })
}
