package dev.droidtop.shell.gamepad

import org.robolectric.RuntimeEnvironment
import dev.droidtop.library.settings.Words
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * One row id once in the settings tree's root (docs/SPEC.md "One settings tree", mytop-project/tracker#964). A renderer
 * keeps the first of two rows with one id, so a duplicate was silently hidden in Gaming and shown in Mobile: this fails
 * on one instead. The root's own groups, as built on a fresh device; screens `:app` registers are not built here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsTreeIdsTest {
    @Test
    fun `no two rows of the settings root share an id`() {
        val context = RuntimeEnvironment.getApplication()
        Words.init(context)
        val groups = runBlocking { SettingsRoot.screen().groups(context) }
        val ids = groups.flatMap { group -> group.allItems.map { it.id } }
        assertEquals(emptyList<String>(), ids.groupBy { it }.filterValues { it.size > 1 }.keys.toList())
    }
}
