package dev.droidtop.runtime.launcherimport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A Nova 8 backup (nova.db v37, nova.xml) as droidtop's plan (NovaImport.kt), from the format document's schema. */
class NovaImportTest {
    private val chrome = "com.android.chrome/com.google.android.apps.chrome.Main"
    private val camera = "com.android.camera2/com.android.camera.CameraLauncher"
    private val maps = "com.google.android.apps.maps/com.google.android.maps.MapsActivity"
    private fun launch(c: String) = "#Intent;action=android.intent.action.MAIN;category=android.intent.category.LAUNCHER;component=$c;end"
    private fun named(title: String, action: String) = "$title\$#Intent;action=com.teslacoilsw.launcher.ACTION;S.LAUNCHER_ACTION=$action;end"

    private fun fav(id: Long, type: Long, container: Long, screen: Long, x: Double, y: Double, vararg extra: Pair<String, Any?>) =
        mapOf<String, Any?>("_id" to id, "itemType" to type, "container" to container, "screen" to screen, "cellX" to x, "cellY" to y, "spanX" to 1.0, "spanY" to 1.0, "novaFlags" to 0L) + extra

    private val prefs = """<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
        <map>
            <string name="desktop_grid">5x4 subgrid</string>
            <boolean name="dock_enable" value="true" />
            <int name="dock_grid_cols" value="4" />
            <string name="theme_icon_pack">Whicons:NOVA:com.whicons.iconpack:false:{a:b}</string>
            <string name="gesture_double_tap">${named("Lock screen", "SCREEN_OFF")}</string>
            <string name="gesture_swipe_down">${named("Notifications", "EXPAND_STATUS_BAR")}</string>
            <string name="gesture_pinch_in">${named("Overview", "SHOW_PREVIEWS")}</string>
            <string name="gesture_swipe_two_up">Camera${'$'}${launch(camera)}</string>
            <string name="gesture_rotate_cw">${named("Drawer", "APP_DRAWER")}</string>
            <string name="gesture_swipe_up">${named("None", "NONE")}</string>
        </map>"""

    private val icon = byteArrayOf(1, 2, 3)

    private val db = DbTables(
        mapOf(
            "favorites" to listOf(
                // The sentinel row b3.onCreate inserts.
                mapOf("_id" to -1L, "itemType" to -1L, "rank" to 37L, "spanX" to 5.0, "spanY" to 6.0, "screen" to 5L),
                fav(1, 0, -100, 0, 0.0, 0.0, "intent" to launch(chrome), "title" to "Web", "novaFlags" to 3L, "icon" to icon,
                    "flingUpIntent" to "Maps\$${launch(maps)}", "flingDownIntent" to named("Weather", "WEATHER")),
                fav(2, 2, -100, 1, 1.5, 0.5, "title" to "Games", "options" to 12L, "novaFlags" to 4L),
                fav(3, 0, 2, 0, 0.0, 0.0, "intent" to launch(camera), "rank" to 1L),
                fav(4, 0, 2, 0, 1.0, 0.0, "intent" to launch(maps), "rank" to 0L),
                fav(5, 4, -100, 0, 0.0, 1.0, "appWidgetProvider" to "com.google.android.deskclock/com.android.alarmclock.DigitalAppWidgetProvider", "spanX" to 2.5, "spanY" to 1.0, "appWidgetId" to 12L),
                fav(6, 5, -100, 0, 2.0, 3.0, "appWidgetProvider" to "com.teslacoilsw.launcher/#custom-widget-1", "title" to ""),
                fav(7, 6, -100, 0, 3.0, 3.0, "title" to "New tab", "intent" to "#Intent;component=$chrome;S.shortcut_id=new-tab;end"),
                fav(8, 1, -100, 0, 3.0, 4.0, "title" to "Drawer", "intent" to named("Drawer", "APP_DRAWER").substringAfter('$')),
                fav(9, 0, -101, 0, 2.0, 0.0, "intent" to launch(camera)),
                fav(10, 0, -101, 1, 0.0, 0.0, "intent" to launch(maps), "title" to "Maps"),
            ),
            "drawer_groups" to listOf(
                mapOf("_id" to 1L, "title" to "All", "groupType" to "TAB_APPS", "tabOrder" to 0L),
                mapOf("_id" to 2L, "title" to "Work", "groupType" to "TAB_APP_GROUP", "tabOrder" to 2L),
                mapOf("_id" to 3L, "title" to "Photo", "groupType" to "FOLDER_APP_GROUP", "tabOrder" to 1L, "hideApps" to 1L),
                mapOf("_id" to 4L, "title" to "Tools", "groupType" to "FOLDER_APP_GROUP", "tabOrder" to 3L, "hideApps" to 0L),
            ),
            "appgroups" to listOf(
                mapOf("groupId" to 2L, "component" to "com.android.chrome/com.google.android.apps.chrome.Main"),
                // A drawer folder inside the tab: FOLDER:<n>, n = -200 - groupId.
                mapOf("groupId" to 2L, "component" to "com.teslacoilsw.launcher/FOLDER:-203"),
                mapOf("groupId" to 3L, "component" to "com.android.camera2/com.android.camera.CameraLauncher"),
                mapOf("groupId" to 4L, "component" to maps),
                mapOf("groupId" to -1L, "component" to "com.facebook.katana/.LoginActivity"),
            ),
            "allapps" to listOf(
                mapOf("componentName" to chrome, "title" to "Browser", "customIconSource" to null),
                mapOf("componentName" to "com.a/.B", "title" to "", "customIconSource" to "iconpack:x"),
            ),
        ),
        userVersion = 37,
    )

    private val plan = NovaImport.plan(prefs, db)

    @Test fun `grid, dock and icon pack from nova xml`() {
        // 5 rows x 4 columns, with half cells in the backup: doubled.
        assertEquals(8 to 10, plan.grid)
        assertEquals(4, plan.dockIcons)
        assertEquals("com.whicons.iconpack", plan.iconPack)
    }

    @Test fun `an app keeps its custom title, icon and swipes`() {
        val app = plan.items.filterIsInstance<PlanItem.App>().single { it.container == PlanContainer.HOME }
        assertEquals(chrome, app.component)
        assertEquals("Web", app.customTitle)
        assertArrayEquals(icon, app.customIcon)
        assertEquals(PlanAction.Launch(maps), app.swipeUp)
        assertNull(app.swipeDown)
        assertTrue(plan.notes.any { it.what == "Web: swipe down" && "Weather" in it.reason })
    }

    @Test fun `a swipe-to-open folder becomes a cover folder with its apps in rank order`() {
        val folder = plan.items.filterIsInstance<PlanItem.Folder>().single()
        assertEquals("Games", folder.title)
        assertTrue(folder.cover)
        assertEquals(12, folder.options)
        assertEquals(listOf(maps, camera), folder.children.map { it.component })
        assertEquals(1, folder.page)
    }

    @Test fun `widgets come across by provider with spans rounded up, Nova's own are reported`() {
        val widget = plan.items.filterIsInstance<PlanItem.Widget>().single()
        assertEquals("com.google.android.deskclock/com.android.alarmclock.DigitalAppWidgetProvider", widget.provider)
        assertEquals(5 to 2, widget.spanX to widget.spanY)
        assertTrue(plan.notes.any { "Nova Launcher's own widgets" in it.reason })
        assertTrue(plan.notes.any { it.what == "New tab" && "pinned shortcut" in it.reason })
        assertTrue(plan.notes.any { it.what == "Drawer" && "set it as a gesture" in it.reason })
    }

    @Test fun `only the default dock page comes across`() {
        val dock = plan.items.filter { it.container == PlanContainer.DOCK }
        assertEquals(listOf(camera), dock.map { (it as PlanItem.App).component })
        assertEquals(2, dock.single().x)
        assertTrue(plan.notes.any { it.what == "Maps" && "dock" in it.reason })
    }

    @Test fun `the sentinel row is not an item`() {
        assertTrue(plan.notes.none { "type -1" in it.reason })
        assertEquals(4, plan.items.size)
    }

    @Test fun `gestures map to droidtop's, an app target included, the rest reported`() {
        assertEquals(
            mapOf(
                ImportActions.DOUBLE_TAP to PlanAction.Droidtop("LOCK_SCREEN"),
                ImportActions.SWIPE_DOWN to PlanAction.Droidtop("OPEN_NOTIFICATIONS"),
                ImportActions.TWO_FINGER_UP to PlanAction.Launch(camera),
            ),
            plan.gestures,
        )
        assertTrue(plan.notes.any { it.what == "Gesture: pinch in" && "Show previews" in it.reason })
        assertTrue(plan.notes.any { it.what == "Gesture: Rotate clockwise" })
        // Swipe up set to nothing is not worth a line.
        assertTrue(plan.notes.none { it.what.startsWith("Gesture: Swipe up") })
    }

    @Test fun `drawer tabs, folders and hidden apps from drawer_groups and appgroups`() {
        assertEquals(
            listOf(
                PlanDrawerGroup("Photo", listOf(camera), folder = true),
                PlanDrawerGroup("Work", listOf(chrome, camera), folder = false),
                PlanDrawerGroup("Tools", listOf(maps), folder = false),
            ),
            plan.drawerGroups,
        )
        assertTrue(plan.notes.any { it.what == "Drawer folder \"Tools\"" && it.imported })
        assertEquals(setOf("com.facebook.katana/com.facebook.katana.LoginActivity"), plan.hiddenApps)
    }

    @Test fun `drawer titles come across, drawer icons are reported`() {
        assertEquals(mapOf(chrome to "Browser"), plan.appTitles)
        assertTrue(plan.notes.any { it.what == "com.a" && "custom icon" in it.reason })
    }

    @Test fun `an old backup reads alike`() {
        val old = """<map><int name="desktop_grid_cols" value="5" /><int name="desktop_grid_rows" value="6" />
            <string name="theme_icon_pack">System:BUILTIN::false</string></map>"""
        val oldDb = DbTables(
            mapOf(
                "workspaceScreens" to listOf(mapOf("_id" to 7L, "screenRank" to 1L), mapOf("_id" to 3L, "screenRank" to 0L)),
                "favorites" to listOf(fav(1, 0, -100, 7, 0.0, 0.0, "intent" to launch(chrome))),
                "appgroups" to listOf(
                    mapOf("groupId" to 0L, "component" to camera),
                    mapOf("groupId" to 5L, "component" to "com.teslacoilsw.launcher/appgroup:6"),
                    mapOf("groupId" to 6L, "component" to maps),
                ),
                "drawer_groups" to listOf(mapOf("_id" to 5L, "title" to "Tab", "groupType" to "TAB_APP_GROUP")),
            ),
            userVersion = 28,
        )
        val p = NovaImport.plan(old, oldDb)
        assertEquals(5 to 6, p.grid)
        assertEquals("", p.iconPack)
        // Screen id 7 (rank 1) is the only page in use, so it is droidtop's first.
        assertEquals(0, p.items.single().page)
        assertEquals(setOf(camera), p.hiddenApps)
        assertEquals(listOf(PlanDrawerGroup("Tab", listOf(maps), folder = false)), p.drawerGroups)
    }

    @Test fun `nothing at all still makes a plan that changes nothing`() {
        val p = NovaImport.plan(null, null)
        assertNull(p.grid)
        assertNull(p.dockIcons)
        assertNull(p.iconPack)
        assertTrue(p.items.isEmpty() && p.gestures.isEmpty() && p.notes.isEmpty())
    }
}
