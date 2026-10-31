package dev.droidtop.app.settings

import dev.droidtop.pluginhost.JobState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Updates page's one row shape, its state and its views (Droidtop/tracker#792). */
class UpdatesPageRowsTest {
    @Test
    fun `a row's line is the version step, the size and where it comes from`() {
        assertEquals("v1.2 → v1.3 · 12 MB · from droidtop's catalog", UpdatesPage.line("v1.2 → v1.3", 12L * 1024 * 1024, "droidtop's catalog"))
        assertEquals("v1.2 → v1.3", UpdatesPage.line("v1.2 → v1.3", null, null))
        assertEquals("v1.2 → v1.3 · from F-Droid", UpdatesPage.line("v1.2 → v1.3", 0L, " F-Droid "))
    }

    @Test
    fun `a row's state is its job's, and its press only covers the moments with no job running`() {
        // No job: offered, or starting while its press resolves where to download from.
        assertEquals(UpdatesPage.State.AVAILABLE, UpdatesPage.stateOf(null, pressed = false))
        assertEquals(UpdatesPage.State.STARTING, UpdatesPage.stateOf(null, pressed = true))
        // A job: its own state, whoever started it (Update all, the row, an automatic update).
        assertEquals(UpdatesPage.State.DOWNLOADING, UpdatesPage.stateOf(JobState.RUNNING, pressed = false))
        assertEquals(UpdatesPage.State.WAITING, UpdatesPage.stateOf(JobState.HELD, pressed = true))
        assertEquals(UpdatesPage.State.WAITING, UpdatesPage.stateOf(JobState.WAITING_FOR_NETWORK, pressed = false))
        assertEquals(UpdatesPage.State.PAUSED, UpdatesPage.stateOf(JobState.PAUSED, pressed = false))
        assertEquals(UpdatesPage.State.FAILED, UpdatesPage.stateOf(JobState.FAILED, pressed = false))
        assertEquals("Failed · Retry", UpdatesPage.State.FAILED.label)
        // Retry pressed: the new attempt, not the old failure.
        assertEquals(UpdatesPage.State.STARTING, UpdatesPage.stateOf(JobState.FAILED, pressed = true))
        // The download ended: installing while the press runs, then whatever the next read says.
        assertEquals(UpdatesPage.State.INSTALLING, UpdatesPage.stateOf(JobState.DONE, pressed = true))
        assertEquals(UpdatesPage.State.AVAILABLE, UpdatesPage.stateOf(JobState.DONE, pressed = false))
    }

    @Test
    fun `a downloading row draws its job's own percent, never a bar that only says busy once the size is known`() {
        assertEquals(0.38f, UpdatesPage.progressOf(UpdatesPage.State.DOWNLOADING, 38)!!, 0.0001f)
        assertEquals(-1f, UpdatesPage.progressOf(UpdatesPage.State.DOWNLOADING, -1)!!, 0f)
        assertEquals(-1f, UpdatesPage.progressOf(UpdatesPage.State.INSTALLING, 100)!!, 0f)
        assertNull(UpdatesPage.progressOf(UpdatesPage.State.WAITING, 10))
        assertNull(UpdatesPage.progressOf(UpdatesPage.State.AVAILABLE, -1))
    }

    @Test
    fun `the views are All, then each kind with updates, each with its count`() {
        val titles = UpdatesPage.viewTitles(mapOf(UpdatesPage.Kind.PLUGINS to 3, UpdatesPage.Kind.GAMES to 1)).map { it.second }
        assertEquals(listOf("All · 4", "Plugins · 3", "Games · 1"), titles)
        assertEquals(listOf("All · 0"), UpdatesPage.viewTitles(emptyMap()).map { it.second })
    }
}
