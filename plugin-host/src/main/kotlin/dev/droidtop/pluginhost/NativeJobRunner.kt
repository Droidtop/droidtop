package dev.droidtop.pluginhost

/**
 * What a job droidtop runs itself (not a plugin) hands [PluginJobsCenter]
 * so it shows in the same list and gets the same Pause, Resume and Cancel
 * (docs/SPEC.md 12a "Jobs", Droidtop/tracker#174). `args` are the job's own
 * stable arguments; `checkpoint` is null on a fresh start and otherwise the
 * last value the job passed to `report` before it was paused or the process
 * died; `report(percent, statusLine, checkpoint)` publishes progress, and a
 * non-null checkpoint replaces the stored one. Returns the one-line (or
 * few-line) outcome the list shows when the job is done.
 *
 * Pause and Cancel both cancel the coroutine: a runner must reach a
 * suspension point or check for cancellation at the boundary where a
 * checkpoint is cheap, and must never swallow a CancellationException.
 */
typealias NativeJobRunner = suspend (
    args: Map<String, String>,
    checkpoint: String?,
    report: (percent: Int, statusLine: String, checkpoint: String?) -> Unit,
) -> String
