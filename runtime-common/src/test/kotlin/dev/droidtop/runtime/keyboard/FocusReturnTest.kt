package dev.droidtop.runtime.keyboard

import dev.droidtop.runtime.tasks.Fidelity
import dev.droidtop.runtime.tasks.RunningApp
import dev.droidtop.runtime.tasks.RunningSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Handing the system's focus back to the app on the other screen (Droidtop/tracker#314). */
class FocusReturnTest {
    private fun app(pkg: String, display: Int, visible: Boolean = true) = RunningApp(pkg, pkg, display, taskId = 1, visible = visible)

    @Test
    fun `the visible app on the other screen is chosen, not one on the companion's screen or droidtop itself`() {
        val snapshot = RunningSnapshot(
            listOf(app("dev.droidtop", 15), app("org.hidden", 15, visible = false), app("org.near", 0), app("org.far", 15)),
            Fidelity.EXACT,
        )
        assertEquals("org.far", FocusReturn.appToRefocus(snapshot, 0, "dev.droidtop")?.packageName)
    }

    @Test
    fun `a list of only what droidtop launched moves nothing`() {
        val snapshot = RunningSnapshot(listOf(app("org.far", 15)), Fidelity.LAUNCHED_ONLY)
        assertNull(FocusReturn.appToRefocus(snapshot, 0, "dev.droidtop"))
    }
}
