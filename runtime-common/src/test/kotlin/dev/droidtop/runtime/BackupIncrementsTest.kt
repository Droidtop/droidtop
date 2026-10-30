package dev.droidtop.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

/** Incremental backups (docs/SPEC.md 3d "Backups on a schedule", Droidtop/tracker#808). */
class BackupIncrementsTest {
    @Test
    fun `find's paths become tar member names, the root left out`() {
        assertEquals(listOf("./etc", "./etc/hosts"), BackupIncrements.relative("/r/rootfs/", "/r/rootfs\n/r/rootfs/etc\n/r/rootfs/etc/hosts\n/elsewhere\n"))
    }

    @Test
    fun `a manifest reads back as written`() {
        val text = BackupIncrements.manifest(listOf("./etc", "./home"), listOf("./etc/hosts"))
        val m = BackupIncrements.parse(text)
        assertEquals(setOf("./etc", "./home"), m.directories)
        assertEquals(setOf("./etc/hosts"), m.entries)
    }

    @Test
    fun `a restore removes what was deleted, deepest first, and makes empty directories that came back`() {
        val present = BackupIncrements.Manifest(setOf("./etc", "./tmp", "./tmp/x"), setOf("./etc/hosts", "./tmp/x/a", "./tmp/x/b"))
        val wanted = BackupIncrements.Manifest(setOf("./etc", "./var", "./var/log"), setOf("./etc/hosts"))
        assertEquals(listOf("./tmp/x/a", "./tmp/x/b", "./tmp/x", "./tmp"), BackupIncrements.toRemove(present, wanted))
        assertEquals(listOf("./var", "./var/log"), BackupIncrements.toMake(present, wanted))
    }

    @Test
    fun `a path that climbs out of the tree is never removed`() {
        val present = BackupIncrements.Manifest(emptySet(), setOf("./../outside"))
        assertEquals(emptyList<String>(), BackupIncrements.toRemove(present, BackupIncrements.Manifest(emptySet(), emptySet())))
    }

    @Test
    fun `changed files carry a minute's margin and archive without recursing`() {
        assertEquals(listOf("-mmin", "-61"), BackupIncrements.changedCommand("/r", 60).takeLast(2))
        assertEquals(true, "--no-recursion" in BackupIncrements.archiveListCommand("/r", "/l"))
    }
}
