package dev.droidtop.runtime.tasks

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class OwnScreensTest {
    @After
    fun reset() {
        OwnScreens.open.value.forEach { OwnScreens.closed(it.taskId) }
    }

    @Test
    fun `a screen is listed while open, once per task, and gone when closed`() {
        OwnScreens.opened(OwnScreen(142, "Settings", 0))
        OwnScreens.opened(OwnScreen(143, "Containers", 2))
        OwnScreens.opened(OwnScreen(142, "Settings", 0))

        assertEquals(listOf(143, 142), OwnScreens.open.value.map { it.taskId })

        OwnScreens.closed(142)
        assertEquals(listOf("Containers"), OwnScreens.open.value.map { it.title })
    }
}
