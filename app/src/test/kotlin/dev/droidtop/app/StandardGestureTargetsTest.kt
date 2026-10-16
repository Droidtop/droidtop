package dev.droidtop.app

import dev.droidtop.shell.standard.DroidtopScreens
import dev.droidtop.shell.standard.LauncherSearch
import dev.droidtop.shell.standard.ModeSwitcher
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where Standard's gesture targets land (docs/SPEC.md, Launcher mode "Gestures"; Droidtop/tracker#564). `:shell-default`
 * cannot depend on `:app`, so it opens droidtop's screens by class name or action; a renamed class would leave a
 * gesture that opens nothing, and the rig cannot inject the pinch, two-finger and Home gestures to notice
 * (2026-10-16). Which gesture is which slot is StandardGesturesTest in :runtime-common.
 */
class StandardGestureTargetsTest {
    private val manifest = File("src/main/AndroidManifest.xml").readText().replace(Regex("(?s)<!--.*?-->"), "")

    @Test
    fun quickMenuGamesAndModeSwitchNameTheRealActivities() {
        assertEquals(QuickMenuActivity::class.java.name, DroidtopScreens.QUICK_MENU_ACTIVITY)
        assertEquals(LauncherGamesActivity::class.java.name, DroidtopScreens.GAMES_ACTIVITY)
        assertEquals(ModeSwitcherActivity::class.java.name, ModeSwitcher.ACTIVITY)
        listOf(DroidtopScreens.QUICK_MENU_ACTIVITY, DroidtopScreens.GAMES_ACTIVITY, ModeSwitcher.ACTIVITY).forEach {
            assertTrue("$it is not declared in the manifest", "android:name=\"$it\"" in manifest)
        }
    }

    @Test
    fun searchActionIsAnsweredByTheOneSearch() {
        val activity = Regex("(?s)<activity\\s+android:name=\"${Regex.escape(LauncherSearchActivity::class.java.name)}\"(.*?)</activity>")
            .find(manifest)?.groupValues?.get(1)
        assertTrue("LauncherSearchActivity is not declared", activity != null)
        assertTrue(
            "LauncherSearchActivity does not answer ${LauncherSearch.ACTION_SEARCH}",
            "<action android:name=\"${LauncherSearch.ACTION_SEARCH}\" />" in activity!!,
        )
    }
}
