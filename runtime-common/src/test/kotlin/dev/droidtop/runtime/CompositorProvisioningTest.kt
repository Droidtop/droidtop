package dev.droidtop.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompositorProvisioningTest {
    @Test
    fun `debian plus sway installs sway with apt and starts sway`() {
        val plan = CompositorProvisioning.plan("debian", "sway")!!
        assertTrue(plan.installCommand.contains("apt-get install") && plan.installCommand.contains(" sway "))
        assertEquals("sway", plan.compositorCommand)
    }

    @Test
    fun `alpine plus sway installs sway with apk and starts sway`() {
        val plan = CompositorProvisioning.plan("alpine", "sway")!!
        assertTrue(plan.installCommand.contains("apk add") && plan.installCommand.contains(" sway "))
        assertEquals("sway", plan.compositorCommand)
    }

    @Test
    fun `the Waybar panel installs waybar and wofi and starts sway with droidtop's config`() {
        val plan = CompositorProvisioning.plan("alpine", "sway", panel = DesktopPanel.WAYBAR)!!
        assertTrue(plan.installCommand.endsWith("&& apk add --no-cache waybar wofi"))
        assertEquals("sway -c /run/droidtop-app-storage/desktop-launcher/panel/sway.config", plan.compositorCommand)
        val debian = CompositorProvisioning.plan("debian", "sway", panel = DesktopPanel.WAYBAR)!!
        assertTrue(debian.installCommand.endsWith("&& apt-get install -y --no-install-recommends waybar wofi"))
        // Sway's own bar is the plain plan; labwc takes no panel.
        assertEquals("sway", CompositorProvisioning.plan("alpine", "sway", panel = DesktopPanel.SWAYBAR)!!.compositorCommand)
        assertEquals("labwc", CompositorProvisioning.plan("alpine", "labwc", panel = DesktopPanel.WAYBAR)!!.compositorCommand)
    }

    @Test
    fun `droidtop's sway config keeps the distro's and swaps only the bar and the launcher`() {
        val lines = DesktopPanel.swayConfig().lines()
        assertTrue("include /etc/sway/config" in lines)
        assertTrue("bar bar-0 swaybar_command true" in lines)
        assertTrue("exec waybar -c /run/droidtop-app-storage/desktop-launcher/panel/waybar.json" in lines)
        assertTrue("bindsym --no-warn \$mod+d exec wofi --show drun" in lines)
        assertTrue(DesktopPanel.waybarConfig().contains("\"custom/apps\": { \"format\": \"Apps\", \"tooltip\": false, \"on-click\": \"wofi --show drun\" }"))
        assertEquals(DesktopPanel.WAYBAR, DesktopPanel.fromId(null))
        assertEquals(DesktopPanel.SWAYBAR, DesktopPanel.fromId("swaybar"))
    }

    @Test
    fun `alpine plus labwc starts the labwc it installed, not sway`() {
        val plan = CompositorProvisioning.plan("alpine", "labwc")!!
        assertTrue(plan.installCommand.contains("apk add") && plan.installCommand.contains(" labwc "))
        assertEquals("labwc", plan.compositorCommand)
    }

    @Test
    fun `every plan installs the distro's xkbcommon tool for the keyboard layout`() {
        assertTrue(CompositorProvisioning.plan("alpine", "sway")!!.installCommand.split(" ").contains("xkbcli"))
        assertTrue(CompositorProvisioning.plan("alpine", "labwc")!!.installCommand.split(" ").contains("xkbcli"))
        assertTrue(CompositorProvisioning.plan("debian", "sway")!!.installCommand.split(" ").contains("libxkbcommon-tools"))
    }

    @Test
    fun `the desktop has a session bus, and sway its wallpaper program`() {
        for ((os, de) in listOf("debian" to "sway", "alpine" to "sway", "alpine" to "labwc")) {
            val plan = CompositorProvisioning.plan(os, de)!!
            assertTrue(plan.installCommand.split(" ").contains("dbus"))
            assertEquals(
                "dbus-daemon --session --nofork --nopidfile --address=unix:path=/run/droidtop-sockets/bus",
                plan.daemons.first(),
            )
            assertEquals(de == "sway", plan.installCommand.split(" ").contains("swaybg"))
        }
    }

    @Test
    fun `unsupported combinations return null instead of a guessed plan`() {
        assertNull(CompositorProvisioning.plan("alpine", "hyprland"))
        assertNull(CompositorProvisioning.plan("fedora", "sway"))
        assertNull(CompositorProvisioning.plan("debian", "labwc"))
    }

    @Test
    fun `a headless compositor needs no seat daemon, so none is installed`() {
        // wlroots only opens a session for its drm and libinput backends;
        // the compositor runs headless (ContainerLayout.compositorEnvironment).
        for ((os, de) in listOf("debian" to "sway", "alpine" to "sway", "alpine" to "labwc")) {
            assertFalse(CompositorProvisioning.plan(os, de)!!.installCommand.contains("seatd"))
        }
    }

    @Test
    fun `debian refuses service starts before installing`() {
        // Debian's policy-rc.d contract: exit 101 means "do not start".
        val install = CompositorProvisioning.plan("debian", "sway")!!.installCommand
        assertTrue(install.indexOf("policy-rc.d") in 0 until install.indexOf("apt-get"))
        assertTrue(install.contains("exit 101"))
    }

    @Test
    fun `every provisionable combination installs the terminal ContainerTerminal launches`() {
        // One constant names both, so a primary container never provisions
        // one terminal and tries to run another (docs/SPEC.md 3d).
        for ((os, de) in listOf("debian" to "sway", "alpine" to "sway", "alpine" to "labwc")) {
            val words = CompositorProvisioning.plan(os, de)!!.installCommand.split(" ")
            assertTrue("$os/$de must install ${ContainerTerminal.PACKAGE}", words.contains(ContainerTerminal.PACKAGE))
        }
    }

    @Test
    fun `every provisionable combination installs the graphical file manager`() {
        for ((os, de) in listOf("debian" to "sway", "alpine" to "sway", "alpine" to "labwc")) {
            val words = CompositorProvisioning.plan(os, de)!!.installCommand.split(" ")
            assertTrue("$os/$de must install a file manager", words.contains(CompositorProvisioning.FILE_MANAGER_PACKAGE))
        }
    }

    @Test
    fun `every provisionable combination installs an icon theme for the file manager`() {
        for ((os, de) in listOf("debian" to "sway", "alpine" to "sway", "alpine" to "labwc")) {
            val words = CompositorProvisioning.plan(os, de)!!.installCommand.split(" ")
            assertTrue("$os/$de must install an icon theme", words.contains(CompositorProvisioning.ICON_THEME_PACKAGE))
        }
    }

    @Test
    fun `printing adds CUPS, points it at the shared socket, and starts it`() {
        val off = CompositorProvisioning.plan("debian", "sway")!!
        val on = CompositorProvisioning.plan("debian", "sway", printing = true)!!
        assertTrue(on.installCommand.startsWith(off.installCommand))
        assertTrue(on.installCommand.contains("apt-get install -y --no-install-recommends cups"))
        assertTrue(on.installCommand.contains("Listen ${ContainerLayout.SOCKET_DIR}/${ContainerLayout.CUPS_SOCKET}"))
        assertEquals(listOf(CompositorProvisioning.SESSION_BUS_DAEMON, "cupsd -f"), on.daemons)
        assertEquals(listOf(CompositorProvisioning.SESSION_BUS_DAEMON), off.daemons)
        // A different plan, so a container provisioned without it re-runs.
        assertFalse(ContainerLayout.planId(on) == ContainerLayout.planId(off))
        assertTrue(CompositorProvisioning.plan("alpine", "labwc", printing = true)!!.installCommand.contains("apk add --no-cache cups"))
    }

    @Test
    fun `the boot script starts each daemon before the compositor`() {
        val script = ContainerLayout.primaryInitScript(CompositorProvisioning.plan("alpine", "sway", printing = true)!!)
        val cupsd = script.indexOf("cupsd -f </dev/null >/var/log/droidtop/cupsd.log 2>&1 &")
        assertTrue(script, cupsd >= 0)
        assertTrue(cupsd < script.indexOf("exec sway"))
        assertEquals("exec sway", script.trimEnd().lines().last())
    }

    @Test
    fun `no daemon is waited for before the compositor starts`() {
        val script = ContainerLayout.primaryInitScript(CompositorProvisioning.plan("alpine", "sway", printing = true)!!)
        // Every line that runs a daemon (the session bus, cupsd), or watches one, is a background job.
        val jobs = script.substringBefore("exec sway").lines()
            .filter { it.contains("cupsd -f") || it.contains("dbus-daemon --session") || it.contains("kill -0") }
        assertEquals(4, jobs.size)
        jobs.forEach { line -> assertTrue(line, line.trimEnd().endsWith("&")) }
        assertTrue(script.contains("droidtop: cupsd is running"))
        assertTrue(script.contains("droidtop: cupsd stopped"))
    }
}
