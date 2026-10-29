package dev.droidtop.pluginhost

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Only droidtop's own process drives a plugin process, and plugin processes are told apart from it (docs/plugin-api.md 5.6). */
class DroidtopCallersTest {
    private val app = 10_123

    @Test
    fun `plugin processes are known by the names the manifests give them`() {
        val pkg = "dev.droidtop.app"
        listOf(":pluginhost", ":pluginhost3", ":plugin_open0", ":plugin_open7", ":plugin_sandbox", ":plugin_sandbox5").forEach {
            assertTrue(it, DroidtopCallers.isPluginProcess(pkg, pkg + it))
        }
        listOf("", ":wallpaper_chooser", ":pluginhostx", ":plugin_open", ":plugin_sandboxes").forEach {
            assertFalse(it, DroidtopCallers.isPluginProcess(pkg, pkg + it))
        }
        assertFalse("another app's process of the same name", DroidtopCallers.isPluginProcess(pkg, "other.app:pluginhost"))
        assertTrue(DroidtopCallers.isAppProcess(pkg, pkg))
        assertFalse(DroidtopCallers.isAppProcess(pkg, "$pkg:plugin_open0"))
    }

    @Test
    fun `isolated uids are a contained plugin's in any user`() {
        assertTrue(DroidtopCallers.isIsolatedUid(99_000))
        assertTrue(DroidtopCallers.isIsolatedUid(1_099_123))
        assertTrue("an app zygote's", DroidtopCallers.isIsolatedUid(90_005))
        assertFalse(DroidtopCallers.isIsolatedUid(app))
        assertFalse(DroidtopCallers.isIsolatedUid(1_010_123))
    }

    @Test
    fun `a non-isolated plugin process serves only the pid the system names droidtop's`() {
        val caller = AppCaller(isolated = false, appUid = app) { pid -> pid == 100 }
        assertFalse("a plugin process of droidtop's own uid", caller.allows(app, 200))
        assertTrue(caller.allows(app, 100))
        assertFalse("another uid", caller.allows(10_999, 100))
        assertFalse(caller.allows(app, 200))
    }

    @Test
    fun `when droidtop's process dies its pid is asked about again`() {
        var appPid = 100
        val caller = AppCaller(isolated = false, appUid = app) { pid -> pid == appPid }
        assertTrue(caller.allows(app, 100))
        appPid = 300
        caller.forget(100)
        assertFalse("the old pid, now another process's", caller.allows(app, 100))
        assertTrue(caller.allows(app, 300))
    }

    @Test
    fun `an isolated plugin process keeps the first caller of droidtop's uid`() {
        val caller = AppCaller(isolated = true, appUid = app) { error("an isolated process cannot ask the system") }
        assertFalse("not droidtop's uid: not kept", caller.allows(99_100, 5))
        assertTrue(caller.allows(app, 100))
        assertTrue(caller.allows(app, 100))
        assertFalse("a second process of droidtop's uid", caller.allows(app, 200))
    }

    @Test
    fun `a one-way call has no pid, so it is droidtop's only by its uid and the token (Droidtop tracker 511)`() {
        val caller = AppCaller(isolated = true, appUid = app) { error("an isolated process cannot ask the system") }
        assertTrue(caller.allows(app, 100))
        assertFalse("a one-way call's pid is 0, whoever made it", caller.allows(app, 0))
        val secret = 0x5eed_1234_abcdL
        assertTrue(AppCaller.tokenAllows(app, app, secret, secret))
        assertFalse("droidtop's uid without the token: another plugin of that uid", AppCaller.tokenAllows(app, app, 7L, secret))
        assertFalse("another uid with the token", AppCaller.tokenAllows(10_999, app, secret, secret))
        assertFalse("no token made yet", AppCaller.tokenAllows(app, app, 0L, 0L))
    }

    @Test
    fun `a process reports only about the plugins that run in it`() {
        val first = Any()
        val second = Any()
        assertTrue(PluginProcessOwnership.reports(first, first))
        assertFalse("another plugin's process", PluginProcessOwnership.reports(second, first))
        assertFalse("a plugin this runner never loaded", PluginProcessOwnership.reports(null, first))
    }

    @Test
    fun `only droidtop and full access may have Shizuku's binder`() {
        val pkg = "dev.droidtop.app"
        assertTrue(DroidtopCallers.mayUseShizuku(pkg, pkg))
        assertTrue(DroidtopCallers.mayUseShizuku(pkg, "$pkg:pluginhost"))
        assertTrue(DroidtopCallers.mayUseShizuku(pkg, "$pkg:pluginhost4"))
        assertFalse("an open plugin", DroidtopCallers.mayUseShizuku(pkg, "$pkg:plugin_open0"))
        assertFalse("a contained plugin", DroidtopCallers.mayUseShizuku(pkg, "$pkg:plugin_sandbox2"))
        assertFalse(DroidtopCallers.mayUseShizuku(pkg, "$pkg:wallpaper_chooser"))
    }
}
