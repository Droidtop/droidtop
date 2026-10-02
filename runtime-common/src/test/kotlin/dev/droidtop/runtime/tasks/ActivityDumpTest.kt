package dev.droidtop.runtime.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The input text is written by hand from the shape of `Task.toString()` in AOSP; it is not a capture from
 * the console, whose real output is the rig check on the commit that added this.
 */
class ActivityDumpTest {
    private val twoDisplays = """
        Display #0 (activities from top to bottom):
          * Task{4a1b2c3 #1743 type=standard A=10123:com.android.calendar U=0 visible=true mode=fullscreen translucent=false sz=1}
          * Task{9d8e7f6 #1700 type=standard A=10150:org.example.browser U=0 visible=false mode=fullscreen translucent=false sz=2}
          * Task{1111111 #1 type=home U=0 visible=false mode=fullscreen translucent=false sz=1}
        Display #2 (activities from top to bottom):
          * Task{2222222 #1801 type=standard A=10200:dev.enginehost U=0 visible=true mode=fullscreen translucent=false sz=1}
    """.trimIndent()

    @Test
    fun `tasks are read with their package, display and visibility, in dump order`() {
        assertEquals(
            listOf(
                DumpedTask(1743, "com.android.calendar", 0, true),
                DumpedTask(1700, "org.example.browser", 0, false),
                DumpedTask(1801, "dev.enginehost", 2, true),
            ),
            ActivityDump.parse(twoDisplays),
        )
    }

    @Test
    fun `home and other non-standard task types are not apps`() {
        assertTrue(ActivityDump.parse(twoDisplays).none { it.taskId == 1 })
    }

    @Test
    fun `the older affinity form without a uid is read too`() {
        val old = "Display #0 (activities from top to bottom):\n  * Task{abc1234 #12 type=standard A=com.android.calendar U=0 visible=true sz=1}"
        assertEquals(listOf(DumpedTask(12, "com.android.calendar", 0, true)), ActivityDump.parse(old))
    }

    @Test
    fun `a task listed twice, as a parent and a child, counts once`() {
        val dup = """
            Display #0 (activities from top to bottom):
              * Task{aaa #5 type=standard A=10123:com.android.calendar U=0 visible=true sz=1}
                * Task{aaa #5 type=standard A=10123:com.android.calendar U=0 visible=true sz=1}
        """.trimIndent()
        assertEquals(1, ActivityDump.parse(dup).size)
    }

    @Test
    fun `lines that are not tasks, and tasks without an affinity, are skipped`() {
        val noise = "garbage\nTask{zzz not a task\nDisplay #0 (x):\n  * Task{abc #9 type=standard U=0 visible=true}\n"
        assertEquals(emptyList<DumpedTask>(), ActivityDump.parse(noise))
        assertEquals(emptyList<DumpedTask>(), ActivityDump.parse(""))
    }
}
