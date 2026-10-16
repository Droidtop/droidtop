package dev.droidtop.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** droidtop's own components in Standard's launcher: the one icon and every widget, nothing else (docs/SPEC.md 2c). */
class LauncherSelfFilterTest {
    @Test
    fun `every widget provider shows, whatever its class is called`() {
        listOf(
            "dev.droidtop.app.ShelfWidgetProvider",
            "dev.droidtop.app.QuickLaunchWidgetProvider",
            "dev.droidtop.app.SomeWidgetAddedLater",
        ).forEach { assertTrue(it, LauncherSelfFilter.shown(it, isWidgetProvider = true)) }
    }

    @Test
    fun `the one icon shows and droidtop's other activities do not`() {
        assertTrue(LauncherSelfFilter.shown("dev.droidtop.app.LauncherGamesActivity", isWidgetProvider = false))
        assertFalse(LauncherSelfFilter.shown("dev.droidtop.app.MainActivity", isWidgetProvider = false))
        assertFalse(LauncherSelfFilter.shown("dev.droidtop.app.ShelfConfigureActivity", isWidgetProvider = false))
    }
}
