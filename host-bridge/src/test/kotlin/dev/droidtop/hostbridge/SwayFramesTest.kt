package dev.droidtop.hostbridge

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SwayFramesTest {
    @Test
    fun `each output's current workspace gives its windows, tiled first, then floating in stacking order`() {
        val frames = SwayFrames.parse(JSONObject(TREE))
        assertEquals(mapOf("HEADLESS-1" to SwayRect(0, 0, 960, 540), "HEADLESS-2" to SwayRect(960, 0, 800, 600)), frames.outputs)
        assertEquals(listOf(10L, 11L, 15L), frames.on("HEADLESS-1").map { it.conId })
        assertEquals(listOf(20L), frames.on("HEADLESS-2").map { it.conId })
    }

    @Test
    fun `a title bar sits right above the rect, which sway moved down by its height`() {
        val foot = SwayFrames.parse(JSONObject(TREE)).on("HEADLESS-1").first { it.conId == 11L }
        assertEquals(SwayRect(100, 124, 400, 300), foot.rect)
        assertEquals(SwayRect(100, 100, 400, 24), foot.titleBar)
        assertEquals(SwayRect(100, 100, 400, 324), foot.frame)
        assertEquals(8, foot.border)
        assertTrue(foot.floating)
        assertEquals("foot", foot.appId)
        assertEquals("~", foot.title)
        assertEquals(SwayWindow(11, floating = true, focused = true, rect = SwayRect(100, 124, 400, 300)), foot.window)
    }

    @Test
    fun `no title bar for a pixel border, a tab, or an Xwayland window with none`() {
        val frames = SwayFrames.parse(JSONObject(TREE))
        val pixel = frames.on("HEADLESS-1").first { it.conId == 15L }
        assertNull(pixel.titleBar)
        assertEquals(pixel.rect, pixel.frame)
        assertEquals("", pixel.appId)
        val tab = frames.on("HEADLESS-2").single()
        assertNull(tab.titleBar)
    }

    @Test
    fun `the hidden workspace, the scratchpad and a hidden tab are not read`() {
        val ids = SwayFrames.parse(JSONObject(TREE)).windows.map { it.conId }
        assertTrue(30L !in ids) // workspace 2, not shown
        assertTrue(12L !in ids) // the scratchpad
        assertTrue(21L !in ids) // the tab behind
    }

    @Test
    fun `a fullscreen window empties its output, and a global one every output`() {
        val one = TREE.replace(""""id":10,"type":"con","fullscreen_mode":0""", """"id":10,"type":"con","fullscreen_mode":1""")
        val frames = SwayFrames.parse(JSONObject(one))
        assertEquals(emptyList<WindowFrame>(), frames.on("HEADLESS-1"))
        assertEquals(1, frames.on("HEADLESS-2").size)
        val global = TREE.replace(""""id":10,"type":"con","fullscreen_mode":0""", """"id":10,"type":"con","fullscreen_mode":2""")
        assertEquals(emptyList<WindowFrame>(), SwayFrames.parse(JSONObject(global)).windows)
    }

    @Test
    fun `a surface's output is its name, or the one output when the primary has none yet`() {
        val two = SwayFrames.parse(JSONObject(TREE))
        assertEquals("HEADLESS-2", two.output("HEADLESS-2"))
        assertNull(two.output(""))
        assertNull(two.output("HEADLESS-9"))
        val one = SwayFrames(mapOf("HEADLESS-1" to SwayRect(0, 0, 10, 10)), emptyList())
        assertEquals("HEADLESS-1", one.output(""))
        assertEquals("HEADLESS-1", one.output(null))
        assertNull(SwayFrames.NONE.output(null))
    }

    private companion object {
        // A trimmed GET_TREE with two outputs. HEADLESS-1 shows workspace 1: a tiled pcmanfm, a floating foot over it,
        // then a floating xclock with a pixel border on top; workspace 2 there is not shown. HEADLESS-2 shows a tabbed
        // container with two tabs, one hidden.
        val TREE = """
            {"id":1,"type":"root","name":"root","rect":{"x":0,"y":0,"width":1760,"height":600},"nodes":[
              {"id":2,"type":"output","name":"__i3","rect":{"x":0,"y":0,"width":1760,"height":600},"nodes":[
                {"id":3,"type":"workspace","name":"__i3_scratch","nodes":[],"floating_nodes":[
                  {"id":12,"type":"floating_con","name":"calc","app_id":"calc","pid":40,"visible":false,"border":"normal",
                   "rect":{"x":0,"y":24,"width":100,"height":100},"deco_rect":{"x":0,"y":0,"width":100,"height":24},"nodes":[],"floating_nodes":[]}
                ]}
              ],"floating_nodes":[]},
              {"id":4,"type":"output","name":"HEADLESS-1","current_workspace":"1","rect":{"x":0,"y":0,"width":960,"height":540},"nodes":[
                {"id":5,"type":"workspace","name":"1","fullscreen_mode":1,"layout":"splith","rect":{"x":0,"y":0,"width":960,"height":540},"nodes":[
                  {"id":10,"type":"con","fullscreen_mode":0,"name":"Home","app_id":"pcmanfm","pid":41,"visible":true,"focused":false,
                   "border":"normal","current_border_width":2,
                   "rect":{"x":0,"y":24,"width":960,"height":516},"deco_rect":{"x":0,"y":0,"width":960,"height":24},"nodes":[],"floating_nodes":[]}
                ],"floating_nodes":[
                  {"id":11,"type":"floating_con","fullscreen_mode":0,"name":"~","app_id":"foot","pid":42,"visible":true,"focused":true,
                   "border":"normal","current_border_width":8,
                   "rect":{"x":100,"y":124,"width":400,"height":300},"deco_rect":{"x":100,"y":100,"width":400,"height":24},"nodes":[],"floating_nodes":[]},
                  {"id":15,"type":"floating_con","fullscreen_mode":0,"name":"xclock","app_id":null,"pid":43,"visible":true,"focused":false,
                   "border":"pixel","current_border_width":2,
                   "rect":{"x":600,"y":50,"width":200,"height":200},"deco_rect":{"x":0,"y":0,"width":0,"height":0},"nodes":[],"floating_nodes":[]}
                ]},
                {"id":6,"type":"workspace","name":"2","fullscreen_mode":1,"nodes":[
                  {"id":30,"type":"con","fullscreen_mode":0,"name":"x","app_id":"x","pid":44,"visible":false,"border":"normal",
                   "rect":{"x":0,"y":24,"width":960,"height":516},"deco_rect":{"x":0,"y":0,"width":960,"height":24},"nodes":[],"floating_nodes":[]}
                ],"floating_nodes":[]}
              ],"floating_nodes":[]},
              {"id":7,"type":"output","name":"HEADLESS-2","current_workspace":"3","rect":{"x":960,"y":0,"width":800,"height":600},"nodes":[
                {"id":8,"type":"workspace","name":"3","fullscreen_mode":1,"layout":"splith","nodes":[
                  {"id":9,"type":"con","fullscreen_mode":0,"name":null,"layout":"tabbed","border":"normal",
                   "rect":{"x":960,"y":24,"width":800,"height":576},"nodes":[
                    {"id":20,"type":"con","fullscreen_mode":0,"name":"a","app_id":"a","pid":45,"visible":true,"border":"normal","current_border_width":2,
                     "rect":{"x":960,"y":48,"width":800,"height":552},"deco_rect":{"x":0,"y":0,"width":400,"height":24},"nodes":[],"floating_nodes":[]},
                    {"id":21,"type":"con","fullscreen_mode":0,"name":"b","app_id":"b","pid":46,"visible":false,"border":"normal","current_border_width":2,
                     "rect":{"x":960,"y":48,"width":800,"height":552},"deco_rect":{"x":400,"y":0,"width":400,"height":24},"nodes":[],"floating_nodes":[]}
                  ],"floating_nodes":[]}
                ],"floating_nodes":[]}
              ],"floating_nodes":[]}
            ]}
        """.trimIndent()
    }
}
