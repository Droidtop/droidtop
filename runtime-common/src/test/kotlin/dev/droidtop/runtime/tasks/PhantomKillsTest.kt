package dev.droidtop.runtime.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Recognising Android's phantom process kills (docs/SPEC.md "Child processes and Android's limit", Droidtop/tracker#816). */
class PhantomKillsTest {
    private val log = """
        1761800000.123  1000  1500 I ActivityManager: Killing PhantomProcessRecord {d4b8d5c 21391:10174/u0a174 proot}: Trimming phantom processes
        1761800001.5  1000  1500 I ActivityManager: Killing PhantomProcessRecord {a1b2c3d 22000:10200/u0a200 node}: Trimming phantom processes
        1761800002.000  1000  1500 I ActivityManager: Start proc 23000:dev.droidtop.app/u0a174
    """.trimIndent()

    @Test
    fun `a kill line gives its time, process, user and name`() {
        val kill = PhantomKills.parse(log.lines().first())!!
        assertEquals(1761800000123L, kill.atMs)
        assertEquals(21391, kill.pid)
        assertEquals(10174, kill.uid)
        assertEquals("proot", kill.name)
        assertNull(PhantomKills.parse(log.lines().last()))
    }

    @Test
    fun `only droidtop's own kills since the program started count`() {
        assertEquals(listOf(21391), PhantomKills.among(log, 10174, 1761799999000L).map { it.pid })
        assertEquals(emptyList<Int>(), PhantomKills.among(log, 10174, 1761800000500L).map { it.pid })
        assertEquals(1761800001500L, PhantomKills.among(log, 10200, 0L).single().atMs)
    }

    @Test
    fun `exit 137 is explained when the log shows the limit, hinted when it can't be read`() {
        val where = "Settings > Desktop mode > Child processes"
        assertEquals(true, PhantomKills.explain(137, true, 33, where)!!.startsWith("Android ended it"))
        assertEquals(true, PhantomKills.explain(137, null, 33, where)!!.startsWith("Something ended it"))
        assertNull(PhantomKills.explain(137, false, 33, where))
        assertNull(PhantomKills.explain(137, null, 30, where))
        assertNull(PhantomKills.explain(1, true, 33, where))
    }
}
