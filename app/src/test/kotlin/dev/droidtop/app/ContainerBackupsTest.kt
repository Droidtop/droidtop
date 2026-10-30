package dev.droidtop.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Backups on a schedule (docs/SPEC.md 3d "Backups on a schedule", Droidtop/tracker#808). */
class ContainerBackupsTest {
    private val day = 86_400_000L

    @Test
    fun `a backup is due once its interval has passed, a tenth early counting, and never when off`() {
        assertTrue(ContainerBackups.due(ContainerBackups.Frequency.DAILY, 0L, day))
        assertTrue(ContainerBackups.due(ContainerBackups.Frequency.DAILY, 0L, day - day / 10))
        assertFalse(ContainerBackups.due(ContainerBackups.Frequency.DAILY, 0L, day / 2))
        assertFalse(ContainerBackups.due(ContainerBackups.Frequency.WEEKLY, 0L, 3 * day))
        assertTrue(ContainerBackups.due(ContainerBackups.Frequency.WEEKLY, 0L, 7 * day))
        assertFalse(ContainerBackups.due(ContainerBackups.Frequency.OFF, 0L, 100 * day))
    }

    @Test
    fun `the file is named for the container and the time, with nothing a folder can't hold`() {
        val name = ContainerBackups.fileName("My Debian!", 0L)
        assertTrue(name.startsWith("MyDebian-"))
        assertTrue(name.endsWith(".tar"))
        assertTrue(ContainerBackups.fileName("!!", 0L).startsWith("container-"))
    }
}
