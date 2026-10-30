package dev.droidtop.hostbridge

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SwayIpcTest {
    @Test
    fun `a message is the magic, length and type in host order, then the payload`() {
        val frame = SwayIpc.frame(SwayIpc.RUN_COMMAND, "move scratchpad")
        val buffer = ByteBuffer.wrap(frame).order(ByteOrder.nativeOrder())
        val magic = ByteArray(6).also { buffer.get(it) }
        assertArrayEquals("i3-ipc".toByteArray(), magic)
        assertEquals(15, buffer.int)
        assertEquals(0, buffer.int)
        assertEquals("move scratchpad", String(frame, 14, frame.size - 14))
    }

    @Test
    fun `a reply header gives its length and must answer the type asked`() {
        val header = SwayIpc.frame(SwayIpc.GET_TREE, "x".repeat(42)).copyOf(14)
        assertEquals(42, SwayIpc.replyLength(header, SwayIpc.GET_TREE))
        assertTrue(runCatching { SwayIpc.replyLength(header, SwayIpc.RUN_COMMAND) }.isFailure)
    }

    @Test
    fun `a command chain succeeds only when every part did`() {
        assertTrue(SwayIpc.commandSucceeded("""[{"success":true},{"success":true}]"""))
        assertFalse(SwayIpc.commandSucceeded("""[{"success":true},{"success":false,"error":"Can't change floating"}]"""))
        assertFalse(SwayIpc.commandSucceeded("[]"))
    }

    @Test
    fun `windows are found by app id and title, tiled, floating and in the scratchpad`() {
        val tree = JSONObject(TREE)
        assertEquals(listOf(SwayWindow(10, floating = false, focused = true)), SwayIpc.windows(tree, "foot", "~"))
        assertEquals(listOf(SwayWindow(11, floating = true, focused = false)), SwayIpc.windows(tree, "org.gnome.Calculator", "Calculator"))
        assertEquals(listOf(SwayWindow(12, floating = true, focused = false)), SwayIpc.windows(tree, "", "xclock"))
        assertEquals(2, SwayIpc.windows(tree, "pcmanfm", "Home").size)
        assertEquals(emptyList<SwayWindow>(), SwayIpc.windows(tree, "foot", "vim"))
    }

    private companion object {
        // A trimmed GET_TREE: one output with a tiled foot and a floating calculator, two file managers alike,
        // and an Xwayland window (app_id null) hidden in the scratchpad.
        val TREE = """
            {"id":1,"type":"root","name":"root","nodes":[
              {"id":2,"type":"output","name":"__i3","nodes":[
                {"id":3,"type":"workspace","name":"__i3_scratch","nodes":[],"floating_nodes":[
                  {"id":12,"type":"floating_con","name":"xclock","app_id":null,"pid":40,"focused":false,"nodes":[],"floating_nodes":[]}
                ]}
              ],"floating_nodes":[]},
              {"id":4,"type":"output","name":"HEADLESS-1","nodes":[
                {"id":5,"type":"workspace","name":"1","nodes":[
                  {"id":10,"type":"con","name":"~","app_id":"foot","pid":41,"focused":true,"nodes":[],"floating_nodes":[]},
                  {"id":6,"type":"con","name":null,"focused":false,"nodes":[
                    {"id":13,"type":"con","name":"Home","app_id":"pcmanfm","pid":42,"focused":false,"nodes":[],"floating_nodes":[]},
                    {"id":14,"type":"con","name":"Home","app_id":"pcmanfm","pid":43,"focused":false,"nodes":[],"floating_nodes":[]}
                  ],"floating_nodes":[]}
                ],"floating_nodes":[
                  {"id":11,"type":"floating_con","name":"Calculator","app_id":"org.gnome.Calculator","pid":44,"focused":false,"nodes":[],"floating_nodes":[]}
                ]}
              ],"floating_nodes":[]}
            ]}
        """.trimIndent()
    }

    @Test
    fun `the focused window's process is found among tiled and floating windows`() {
        val tiled = JSONObject("""{"nodes":[{"nodes":[{"id":4,"pid":120,"app_id":"foot","focused":false},{"id":5,"pid":130,"app_id":"gedit","focused":true}]}]}""")
        assertEquals(130, SwayIpc.focusedPid(tiled))
        val floating = JSONObject("""{"nodes":[{"floating_nodes":[{"id":6,"pid":0,"app_id":"xterm","focused":true}]}]}""")
        assertEquals(0, SwayIpc.focusedPid(floating))
        assertEquals(null, SwayIpc.focusedPid(JSONObject("""{"nodes":[{"id":4,"pid":120,"focused":false}]}""")))
    }
}
