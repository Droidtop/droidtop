package dev.droidtop.runtime.launcherimport

import dev.droidtop.runtime.HomeAction
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** An Apex 4.9 backup (launcher.db v27, preferences, Pro's DrawerGroups and SwipeActions) as droidtop's plan (ApexImport.kt). */
class ApexImportTest {
    private val chrome = "com.android.chrome/com.google.android.apps.chrome.Main"
    private val camera = "com.android.camera2/com.android.camera.CameraLauncher"
    private val maps = "com.google.android.apps.maps/com.google.android.maps.MapsActivity"
    private fun launch(c: String) = "#Intent;action=android.intent.action.MAIN;category=android.intent.category.LAUNCHER;launchFlags=0x10200000;component=$c;end"
    private fun apexAction(name: String) = "#Intent;action=com.anddoes.launcher.ACTION;component=com.anddoes.launcher/.Launcher;S.LAUNCHER_ACTION=$name;end"

    private fun fav(id: Long, type: Long, container: Long, screen: Long, x: Long, y: Long, vararg extra: Pair<String, Any?>) =
        mapOf<String, Any?>("_id" to id, "itemType" to type, "container" to container, "screen" to screen, "cellX" to x, "cellY" to y, "spanX" to 1L, "spanY" to 1L, "rank" to 0L, "options" to 0L) + extra

    private val prefs = """<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
        <map>
            <int name="home_screen_grid_columns" value="5" />
            <int name="home_screen_grid_rows" value="6" />
            <boolean name="show_dock" value="true" />
            <int name="number_of_dock_icons" value="5" />
            <int name="number_of_dock_pages" value="2" />
            <string name="theme_iconpack_pkg">default</string>
            <boolean name="home_screen_show_labels" value="false" />
            <boolean name="drawer_label_icons" value="true" />
            <set name="hidden_apps"><string>com.facebook.katana/.LoginActivity</string></set>
            <string name="desktop_double_tap_action">SCREEN_LOCK</string>
            <string name="pinch_in_action">SHOW_PREVIEWS</string>
            <string name="two_finger_swipe_up_action">APP</string>
            <string name="two_finger_swipe_up_action_app">$camera</string>
            <string name="home_key_action">GOTO_DEFAULT_SCREEN</string>
            <string name="swipe_up_action">NONE</string>
            <string name="double_tap_swipe_down">SHOW_NOTIFICATIONS</string>
        </map>"""

    private val folderIcon = byteArrayOf(9, 8, 7)

    private val db = DbTables(
        mapOf(
            "workspaceScreens" to listOf(
                mapOf("_id" to 10L, "screenRank" to 1L),
                mapOf("_id" to 20L, "screenRank" to 0L),
            ),
            "favorites" to listOf(
                fav(1, 0, -100, 20, 0, 0, "intent" to launch(chrome), "title" to "Web"),
                fav(2, 2, -100, 10, 1, 1, "title" to "Social", "options" to 6L, "icon" to folderIcon),
                fav(3, 0, 2, 0, 0, 0, "intent" to launch(maps), "rank" to 1L, "title" to "Maps"),
                fav(4, 0, 2, 0, 0, 0, "intent" to launch(camera), "rank" to 0L, "title" to "Camera"),
                fav(5, 6, 2, 0, 0, 0, "title" to "Compose", "rank" to 2L),
                fav(6, 1, -100, 20, 4, 5, "intent" to apexAction("APP_DRAWER"), "title" to "Apps"),
                fav(7, 5, -100, 20, 0, 3, "title" to "Apex clock"),
                fav(8, 4, -100, 20, 0, 1, "appWidgetProvider" to "com.google.android.deskclock/com.android.alarmclock.DigitalAppWidgetProvider", "spanX" to 4L, "spanY" to 2L),
                fav(9, 0, -101, 3, 0, 0, "intent" to launch(camera), "title" to "Camera"),
                fav(10, 0, -101, 101, 0, 0, "intent" to launch(maps), "title" to "Maps"),
                fav(11, 4, -101, 4, 0, 0, "appWidgetProvider" to "com.a/.W"),
            ),
        ),
        userVersion = 27,
    )

    private val swipes = """<map>
        <string name="swipe_up_1">APP</string>
        <string name="swipe_up_app_1">$maps</string>
        <string name="swipe_down_1">SHOW_NOTIFICATIONS</string>
        <string name="swipe_up_9">TOGGLE_DOCK</string>
        <string name="swipe_down_9">NONE</string>
    </map>"""

    private val groups = """<map>
        <string name="all_folders">1;2;3</string>
        <string name="folder_name_1">Media</string>
        <string name="folder_items_1">$camera;$maps</string>
        <string name="folder_name_2">Keep</string>
        <string name="folder_items_2">$chrome</string>
        <boolean name="folder_keep_in_tab_2" value="true" />
        <string name="folder_name_3">Empty</string>
    </map>"""

    private val plan = ApexImport.plan(prefs, db, groups, swipes)

    @Test fun `grid, dock, labels and icon pack from the preferences`() {
        assertEquals(5 to 6, plan.grid)
        assertEquals(5, plan.dockIcons)
        assertEquals("", plan.iconPack)
        assertEquals("NEVER", plan.labels)
        assertTrue(plan.notes.any { it.what == "Dock pages" })
    }

    @Test fun `pages follow screenRank`() {
        val web = plan.items.filterIsInstance<PlanItem.App>().single { it.component == chrome }
        assertEquals(0, web.page)
        assertEquals(1, plan.items.filterIsInstance<PlanItem.Folder>().single().page)
    }

    @Test fun `per-icon swipes from SwipeActions`() {
        val web = plan.items.filterIsInstance<PlanItem.App>().single { it.component == chrome }
        assertEquals(HomeAction.Launch(maps), web.swipeUp)
        assertEquals(HomeAction.Droidtop("OPEN_NOTIFICATIONS"), web.swipeDown)
        assertEquals("Web", web.customTitle)
        assertNull(web.customIcon)
        val dockCamera = plan.items.filterIsInstance<PlanItem.App>().single { it.container == PlanContainer.DOCK }
        assertNull(dockCamera.swipeUp)
        assertTrue(plan.notes.any { it.what == "Camera: swipe up" && "Toggle dock" in it.reason })
    }

    @Test fun `a folder keeps its picture and its apps by rank, a pinned shortcut in it is reported`() {
        val folder = plan.items.filterIsInstance<PlanItem.Folder>().single()
        assertEquals("Social", folder.title)
        assertArrayEquals(folderIcon, folder.customIcon)
        // Apex's work-folder bit (2) is not one droidtop keeps.
        assertEquals(4, folder.options)
        assertEquals(listOf(camera, maps), folder.children.map { it.component })
        assertTrue(plan.notes.any { it.what == "Social: Compose" })
    }

    @Test fun `the dock keeps the default page, by position`() {
        val dock = plan.items.filter { it.container == PlanContainer.DOCK }
        assertEquals(listOf(3), dock.map { it.x })
        assertTrue(plan.notes.any { it.what == "Maps" && "dock page" in it.reason })
        assertTrue(plan.notes.any { "dock holds icons only" in it.reason })
    }

    @Test fun `widgets, Apex's own widgets and actions`() {
        val widget = plan.items.filterIsInstance<PlanItem.Widget>().single()
        assertEquals(4 to 2, widget.spanX to widget.spanY)
        assertTrue(plan.notes.any { it.what == "Apex clock" && "Apex Launcher's own widgets" in it.reason })
        assertTrue(plan.notes.any { it.what == "Apps" && "App drawer" in it.reason })
    }

    @Test fun `gestures, hidden apps and drawer folders`() {
        assertEquals(
            mapOf(
                ImportActions.DOUBLE_TAP to HomeAction.Droidtop("LOCK_SCREEN"),
                ImportActions.TWO_FINGER_UP to HomeAction.Launch(camera),
            ),
            plan.gestures,
        )
        assertTrue(plan.notes.any { it.what == "Gesture: pinch in" })
        assertTrue(plan.notes.any { it.what == "Gesture: home button" && "Goto default screen" in it.reason })
        assertTrue(plan.notes.any { it.what == "Gesture: Double tap and swipe down" })
        assertTrue(plan.notes.none { it.what.startsWith("Gesture: Swipe up") })
        assertEquals(setOf("com.facebook.katana/com.facebook.katana.LoginActivity"), plan.hiddenApps)
        assertEquals(
            listOf(PlanDrawerGroup("Media", listOf(camera, maps), folder = true), PlanDrawerGroup("Keep", listOf(chrome), folder = false)),
            plan.drawerGroups,
        )
    }

    @Test fun `a loose settings file alone sets only settings`() {
        val p = ApexImport.plan(prefs, null)
        assertTrue(p.items.isEmpty())
        assertEquals(5 to 6, p.grid)
        assertTrue(p.drawerGroups.isEmpty())
    }
}
