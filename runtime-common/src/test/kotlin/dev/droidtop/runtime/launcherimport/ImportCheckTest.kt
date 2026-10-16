package dev.droidtop.runtime.launcherimport

import dev.droidtop.runtime.HomeAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fitting a plan to what this device has (ImportCheck.kt). */
class ImportCheckTest {
    private val chrome = "com.android.chrome/com.google.android.apps.chrome.Main"
    private val camera = "com.android.camera2/com.android.camera.CameraLauncher"
    private val gone = "com.gone/com.gone.Main"
    private val clock = "com.google.android.deskclock/com.android.alarmclock.DigitalAppWidgetProvider"
    private val device = ImportCheck.Device(mapOf(chrome to "Chrome", camera to "Camera"), setOf(clock), setOf("com.pack"))
    private val home = PlanContainer.HOME

    private fun plan(items: List<PlanItem>, iconPack: String? = null, gestures: Map<String, HomeAction> = emptyMap()) = ImportPlan(
        SourceLauncher.APEX, 4 to 5, 5, items,
        hiddenApps = setOf(camera, gone),
        drawerGroups = listOf(PlanDrawerGroup("Tab", listOf(gone, chrome), false), PlanDrawerGroup("Empty", listOf(gone), true)),
        iconPack = iconPack, labels = null, gestures = gestures,
        appTitles = mapOf(chrome to "Browser", camera to "Camera", gone to "Gone"), notes = emptyList(),
    )

    @Test fun `apps that are not installed are left out and listed once`() {
        val fitted = ImportCheck.fit(plan(listOf(PlanItem.App(home, 0, 0, 0, chrome), PlanItem.App(home, 0, 1, 0, gone))), device)
        assertEquals(listOf(chrome), fitted.items.map { (it as PlanItem.App).component })
        assertEquals(setOf(camera), fitted.hiddenApps)
        assertEquals(listOf(PlanDrawerGroup("Tab", listOf(chrome), false)), fitted.drawerGroups)
        // A drawer title equal to the app's own is not a rename.
        assertEquals(mapOf(chrome to "Browser"), fitted.appTitles)
        val note = fitted.notes.single()
        assertEquals("1 app", note.what)
        assertTrue("com.gone" in note.reason)
    }

    @Test fun `a title equal to the app's own is no title of its own`() {
        val fitted = ImportCheck.fit(plan(listOf(PlanItem.App(home, 0, 0, 0, chrome, customTitle = "Chrome"), PlanItem.App(home, 0, 1, 0, camera, customTitle = "Photos"))), device)
        assertEquals(listOf(null, "Photos"), fitted.items.map { (it as PlanItem.App).customTitle })
    }

    @Test fun `swipes and gestures to a missing app are dropped with a note`() {
        val fitted = ImportCheck.fit(
            plan(
                listOf(PlanItem.App(home, 0, 0, 0, chrome, swipeUp = HomeAction.Launch(gone), swipeDown = HomeAction.Launch(camera))),
                gestures = mapOf("DOUBLE_TAP" to HomeAction.Launch(gone), "PINCH_IN" to HomeAction.Droidtop("OPEN_DRAWER")),
            ),
            device,
        )
        val app = fitted.items.single() as PlanItem.App
        assertNull(app.swipeUp)
        assertEquals(HomeAction.Launch(camera), app.swipeDown)
        assertEquals(mapOf("PINCH_IN" to HomeAction.Droidtop("OPEN_DRAWER")), fitted.gestures)
        assertTrue(fitted.notes.any { it.what == "Chrome: swipe up" })
        assertTrue(fitted.notes.any { it.what == "Gesture: double tap" })
    }

    @Test fun `a folder of one becomes the app, of none is noted`() {
        val one = PlanItem.Folder(home, 0, 2, 2, "One", 0, false, null, listOf(FolderChild(gone), FolderChild(camera, "Cam")))
        val none = PlanItem.Folder(home, 0, 3, 3, "None", 0, false, null, listOf(FolderChild(gone)))
        val fitted = ImportCheck.fit(plan(listOf(one, none)), device)
        assertEquals(listOf(PlanItem.App(home, 0, 2, 2, camera, "Cam")), fitted.items)
        assertTrue(fitted.notes.any { it.what == "Folder \"One\"" && it.imported })
        assertTrue(fitted.notes.any { it.what == "Folder \"None\"" && !it.imported })
    }

    @Test fun `widgets need their provider, icon packs need to be installed`() {
        val fitted = ImportCheck.fit(plan(listOf(PlanItem.Widget(home, 0, 0, 0, 2, 1, clock), PlanItem.Widget(home, 0, 0, 1, 2, 1, "com.gone/.W")), iconPack = "com.other"), device)
        assertEquals(listOf(clock), fitted.items.map { (it as PlanItem.Widget).provider })
        assertNull(fitted.iconPack)
        assertTrue(fitted.notes.any { it.what == "Icon pack" })
        assertEquals("com.pack", ImportCheck.fit(plan(emptyList(), iconPack = "com.pack"), device).iconPack)
        assertEquals("", ImportCheck.fit(plan(emptyList(), iconPack = ""), device).iconPack)
    }

    @Test fun `the summary says what comes across`() {
        val fitted = ImportCheck.fit(
            plan(
                listOf(
                    PlanItem.App(home, 0, 0, 0, chrome, customTitle = "Web"),
                    PlanItem.App(home, 1, 0, 0, camera),
                    PlanItem.Widget(home, 1, 1, 1, 2, 1, clock),
                    PlanItem.App(PlanContainer.DOCK, 0, 0, 0, camera),
                ),
                iconPack = "",
            ),
            device,
        )
        assertEquals(
            listOf(
                "2 apps, 0 folders and 1 widget on 2 home pages",
                "1 icon in the dock",
                "Widgets come as \"tap to set up\": tap each once to bind it",
                "Home grid 4x5",
                "Dock of 5 icons",
                "1 icon title, picture or swipe of your own",
                "1 drawer tab and 0 drawer folders",
                "1 hidden app",
                "1 renamed app",
                "System icons",
            ),
            ImportCheck.brought(fitted),
        )
    }
}
