package dev.droidtop.pluginhost

/**
 * Waiting for a process another app runs for droidtop, Shizuku's `ShizukuRemoteProcess` (Droidtop/tracker#412).
 *
 * `Process.waitFor(timeout, unit)` is not overridden there, so Java's default runs: it polls `exitValue()` and takes
 * only an `IllegalThreadStateException` as "still running". The remote process's `exitValue()` throws that in
 * Shizuku's server, and Binder carries it back as the parcelable type it extends, a plain `IllegalArgumentException`
 * ("process hasn't exited"), which the default wait does not catch. So the first poll of a command still running threw
 * out of the wait: Kill on the RP5 said "Couldn't end it: process hasn't exited" and RetroArch lived (rig, droidtop
 * 0.2.0-dev.1908). This wait takes either exception as "still running".
 */
internal object RemoteProcessWait {
    private const val POLL_MS = 20L

    /**
     * [process]'s exit value once it has exited, or null when it has not within [timeoutMs]. [nowMs] and [sleep] are
     * for tests.
     */
    fun exitWithin(
        process: Process,
        timeoutMs: Long,
        nowMs: () -> Long = System::currentTimeMillis,
        sleep: (Long) -> Unit = Thread::sleep,
    ): Int? {
        val deadline = nowMs() + timeoutMs
        while (true) {
            try {
                return process.exitValue()
            } catch (running: IllegalArgumentException) {
                // IllegalThreadStateException is one; across Binder it arrives as its parent.
            }
            val left = deadline - nowMs()
            if (left <= 0) return null
            sleep(minOf(POLL_MS, left))
        }
    }
}
