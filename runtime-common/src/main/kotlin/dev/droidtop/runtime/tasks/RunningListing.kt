package dev.droidtop.runtime.tasks

/**
 * Turns a source's raw answer into the list surfaces draw. Pure, so the filtering is unit-tested.
 * [label] is the app's name, or null for a package that is not installed.
 */
object RunningListing {
    /**
     * From the system's task list: one row per package and display, home and system surfaces left out. A task's
     * name there is its affinity, which an app may set to more than its package (`com.android.settings.root`, rig
     * 2026-10-29: the bar read "com.android.setti..."), so the row is the installed package it starts with: the name
     * itself, else with its last parts dropped. One that is no package at all keeps its name.
     */
    fun fromDump(tasks: List<DumpedTask>, hidden: Set<String>, label: (String) -> String?): List<RunningApp> =
        tasks
            .map { task -> task to (installed(task.packageName, label) ?: (task.packageName to task.packageName)) }
            .filter { (_, app) -> app.first !in hidden }
            .distinctBy { (task, app) -> app.first to task.displayId }
            .map { (task, app) -> RunningApp(app.first, app.second, task.displayId, task.taskId, task.visible) }

    /** The installed package [name] is or starts with, and its label; null for none. */
    private fun installed(name: String, label: (String) -> String?): Pair<String, String>? {
        var candidate = name
        while (true) {
            label(candidate)?.let { return candidate to it }
            // A package has at least two parts; a shorter prefix is not one.
            if (candidate.count { it == '.' } < 2) return null
            candidate = candidate.substringBeforeLast('.')
        }
    }

    /**
     * From what droidtop opened: an app that is no longer installed cannot be running, and one Android
     * reports in the stopped state ([stopped]) was force-stopped after droidtop opened it, so both are dropped.
     */
    fun fromLedger(
        entries: List<LaunchLedger.Launched>,
        hidden: Set<String>,
        label: (String) -> String?,
        stopped: (String) -> Boolean = { false },
    ): List<RunningApp> =
        entries.mapNotNull {
            if (it.packageName in hidden) return@mapNotNull null
            if (stopped(it.packageName)) return@mapNotNull null
            val name = label(it.packageName) ?: return@mapNotNull null
            RunningApp(it.packageName, name, it.displayId, taskId = null, visible = false)
        }
}
