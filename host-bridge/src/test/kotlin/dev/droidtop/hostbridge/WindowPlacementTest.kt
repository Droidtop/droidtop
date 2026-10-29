package dev.droidtop.hostbridge

import dev.droidtop.hostbridge.DesktopWindows.Placement
import org.junit.Assert.assertEquals
import org.junit.Test

/** The bar's window controls under sway (docs/SPEC.md 2b "Window controls", Droidtop/tracker#686). */
class WindowPlacementTest {
    @Test
    fun `maximize and snap float the window and size it to the workspace`() {
        assertEquals(
            "[con_id=7] floating enable, resize set width 100 ppt height 100 ppt, move position 0 ppt 0 ppt",
            DesktopWindows.placeCommand(7, Placement.MAXIMIZE, null),
        )
        assertEquals(
            "[con_id=7] floating enable, resize set width 50 ppt height 100 ppt, move position 0 ppt 0 ppt",
            DesktopWindows.placeCommand(7, Placement.SNAP_LEFT, null),
        )
        assertEquals(
            "[con_id=7] floating enable, resize set width 50 ppt height 100 ppt, move position 50 ppt 0 ppt",
            DesktopWindows.placeCommand(7, Placement.SNAP_RIGHT, null),
        )
    }

    @Test
    fun `restore puts a floating window back where it was and a tiled one back in the tiling`() {
        val floating = SwayWindow(7, floating = true, focused = true, rect = SwayRect(40, 60, 640, 480))
        assertEquals(
            "[con_id=7] resize set width 640 px height 480 px, move absolute position 40 px 60 px",
            DesktopWindows.placeCommand(7, Placement.RESTORE, floating),
        )
        assertEquals("[con_id=7] floating disable", DesktopWindows.placeCommand(7, Placement.RESTORE, floating.copy(floating = false)))
        assertEquals("[con_id=7] move position center", DesktopWindows.placeCommand(7, Placement.RESTORE, floating.copy(rect = null)))
    }

    @Test
    fun `a window's place is read from the tree`() {
        val tree = org.json.JSONObject(
            """{"id":1,"nodes":[],"floating_nodes":[{"id":9,"name":"t","app_id":"foot","pid":2,"focused":true,""" +
                """"rect":{"x":10,"y":26,"width":300,"height":200},"nodes":[],"floating_nodes":[]}]}""",
        )
        assertEquals(listOf(SwayWindow(9, floating = true, focused = true, rect = SwayRect(10, 26, 300, 200))), SwayIpc.windows(tree, "foot", "t"))
    }
}
