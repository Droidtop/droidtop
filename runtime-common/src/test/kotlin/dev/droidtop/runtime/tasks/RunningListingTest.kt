package dev.droidtop.runtime.tasks

import org.junit.Assert.assertEquals
import org.junit.Test

class RunningListingTest {
    private val labels = mapOf("com.android.calendar" to "Calendar", "org.example.browser" to "Browser")
    private val label: (String) -> String? = { labels[it] }

    @Test
    fun `a dump lists apps with names and displays, leaving out hidden packages`() {
        val tasks = listOf(
            DumpedTask(1, "com.android.calendar", 0, true),
            DumpedTask(2, "dev.droidtop.app", 0, false),
            DumpedTask(3, "org.example.browser", 2, false),
        )

        val apps = RunningListing.fromDump(tasks, hidden = setOf("dev.droidtop.app"), label = label)

        assertEquals(listOf("Calendar", "Browser"), apps.map { it.label })
        assertEquals(listOf(0, 2), apps.map { it.displayId })
        assertEquals(listOf(1, 3), apps.map { it.taskId })
    }

    @Test
    fun `a dump row keeps the package name when the package is not installed`() {
        val apps = RunningListing.fromDump(listOf(DumpedTask(1, "gone.app", 0, false)), emptySet(), label)

        assertEquals("gone.app", apps.single().label)
    }

    @Test
    fun `one package on one display is one row`() {
        val tasks = listOf(DumpedTask(1, "com.android.calendar", 0, true), DumpedTask(2, "com.android.calendar", 0, false))

        assertEquals(1, RunningListing.fromDump(tasks, emptySet(), label).size)
    }

    @Test
    fun `the ledger lists what droidtop opened, dropping what is no longer installed`() {
        val entries = listOf(
            LaunchLedger.Launched("org.example.browser", 2, 20),
            LaunchLedger.Launched("gone.app", 0, 10),
            LaunchLedger.Launched("dev.droidtop.app", 0, 5),
        )

        val apps = RunningListing.fromLedger(entries, hidden = setOf("dev.droidtop.app"), label = label)

        assertEquals(listOf("Browser"), apps.map { it.label })
        assertEquals(2, apps.single().displayId)
    }
}
