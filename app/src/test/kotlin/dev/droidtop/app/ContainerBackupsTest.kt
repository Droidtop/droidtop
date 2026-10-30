package dev.droidtop.app

import org.junit.Assert.assertEquals
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

    private val names = listOf(
        "Debian-2026-10-01-0200.tar",
        "Debian-2026-10-02-0200-changes.tar", "Debian-2026-10-02-0200-changes.manifest",
        "Debian-2026-10-03-0200-changes.tar",
        "Debian-2026-10-08-0200.tar",
        "Debian-2026-10-09-0200-changes.tar",
        "Debian-2026-10-15-0200.tar",
        "Other-2026-10-01-0200.tar",
        "notes.txt",
    )

    @Test
    fun `the folder's backups of one container, oldest first, manifests and others left out`() {
        val points = ContainerBackups.points("Debian", names)
        assertEquals(6, points.size)
        assertTrue(points.first().full)
        assertFalse(points[1].full)
    }

    @Test
    fun `restoring a point takes the full backup before it and each increment up to it`() {
        val points = ContainerBackups.points("Debian", names)
        val chosen = points.first { it.stamp == "2026-10-03-0200" }
        assertEquals(listOf("2026-10-01-0200", "2026-10-02-0200", "2026-10-03-0200"), ContainerBackups.chain(points, chosen).map { it.stamp })
        val later = points.first { it.stamp == "2026-10-09-0200" }
        assertEquals(listOf("2026-10-08-0200", "2026-10-09-0200"), ContainerBackups.chain(points, later).map { it.stamp })
    }

    @Test
    fun `after a third full backup the oldest chain goes`() {
        val stale = ContainerBackups.stale(ContainerBackups.points("Debian", names)).map { it.stamp }
        assertEquals(listOf("2026-10-01-0200", "2026-10-02-0200", "2026-10-03-0200"), stale)
    }
}
