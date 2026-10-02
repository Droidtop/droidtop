package dev.droidtop.runtime.tasks

/**
 * Turns a source's raw answer into the list surfaces draw. Pure, so the filtering is unit-tested.
 * [label] is the app's name, or null for a package that is not installed.
 */
object RunningListing {
    /** From the system's task list: one row per package and display, home and system surfaces left out. */
    fun fromDump(tasks: List<DumpedTask>, hidden: Set<String>, label: (String) -> String?): List<RunningApp> =
        tasks
            .filter { it.packageName !in hidden }
            .distinctBy { it.packageName to it.displayId }
            .map { RunningApp(it.packageName, label(it.packageName) ?: it.packageName, it.displayId, it.taskId, it.visible) }

    /** From what droidtop opened: an app that is no longer installed cannot be running, so it is dropped. */
    fun fromLedger(entries: List<LaunchLedger.Launched>, hidden: Set<String>, label: (String) -> String?): List<RunningApp> =
        entries.mapNotNull {
            if (it.packageName in hidden) return@mapNotNull null
            val name = label(it.packageName) ?: return@mapNotNull null
            RunningApp(it.packageName, name, it.displayId, taskId = null, visible = false)
        }
}
