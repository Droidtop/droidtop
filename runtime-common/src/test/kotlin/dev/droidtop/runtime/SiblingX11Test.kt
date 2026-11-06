package dev.droidtop.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A sibling's own X server (docs/SPEC.md 3d "Clipboard per container", the Xwayland bullet; Droidtop/tracker#910). */
class SiblingX11Test {
    private fun manager(id: String) = PackageUpgrade.byId(id)!!

    @Test
    fun `each container has its own display number, clear of the primary's, the same each time`() {
        val ids = listOf("droidtop-primary", "droidtop-sibling-1a2b3c4d", "droidtop-sibling-99887766", "droidtop-sibling-00000000")
        val numbers = ids.map { SiblingX11.displayNumber(it, ids) }
        assertEquals(ids.size, numbers.toSet().size)
        numbers.forEach { assertTrue(it in SiblingX11.FIRST_DISPLAY until SiblingX11.FIRST_DISPLAY + SiblingX11.DISPLAYS) }
        assertEquals(numbers, ids.reversed().let { other -> ids.map { SiblingX11.displayNumber(it, other) } })
        // A container not yet in the list is placed as if it were.
        assertEquals(numbers[1], SiblingX11.displayNumber(ids[1], ids - ids[1]))
    }

    @Test
    fun `two ids on one number are moved apart, the one sorting later to the next free number`() {
        // Many ids, so the 900 numbers see collisions; every one still gets its own.
        val ids = (0 until 600).map { "droidtop-sibling-%08x".format(it * 7919) }
        val numbers = ids.map { SiblingX11.displayNumber(it, ids) }
        assertEquals(ids.size, numbers.toSet().size)
    }

    @Test
    fun `the server runs inside the sibling, on its own display, never on an abstract socket or the shared folder`() {
        val script = SiblingX11.serverScript(123)
        assertTrue(script.endsWith("exec xwayland-satellite :123 -nolisten local"))
        // Already running or on its way: its pid file names satellite (or this script about to become it).
        assertTrue(script, script.contains("</proc/\$pid/cmdline 2>/dev/null | grep -q xwayland-satellite; }; then echo 'droidtop-x11: running'; exit 0; fi"))
        // It says it is starting only once a gone server's socket is removed.
        assertTrue(script.indexOf("rm -f /tmp/.X123-lock") < script.indexOf("echo 'droidtop-x11: starting'"))
        assertTrue(script.contains("rm -f /tmp/.X123-lock /tmp/.X11-unix/X123"))
        // Its own Wayland socket for Xwayland goes in a private folder, the compositor's is named in full.
        assertTrue(script.contains("export WAYLAND_DISPLAY XDG_RUNTIME_DIR=/tmp/.droidtop-x11-123"))
        assertTrue(script.contains("WAYLAND_DISPLAY=\"\$XDG_RUNTIME_DIR/\$WAYLAND_DISPLAY\""))
        assertFalse(script.contains(ContainerLayout.SOCKET_DIR))
    }

    @Test
    fun `the detached start gives DISPLAY only when the package is there and the socket came up`() {
        val prefix = SiblingX11.detachedPrefix(150, "WAYLAND_DISPLAY='wayland-1'")
        assertTrue(prefix.startsWith("if command -v xwayland-satellite >/dev/null 2>&1; then ( export WAYLAND_DISPLAY='wayland-1'; "))
        assertTrue(prefix.contains(SiblingX11.shellQuote(SiblingX11.serverScript(150))))
        // A gone server's socket is removed before the wait, so it is not taken for the new one.
        assertTrue(prefix.indexOf("rm -f /tmp/.X150-lock /tmp/.X11-unix/X150") < prefix.indexOf("while [ ! -S"))
        assertTrue(prefix.contains("while [ ! -S /tmp/.X11-unix/X150 ] && [ \$i -lt 100 ]"))
        assertTrue(prefix.endsWith("if [ -S /tmp/.X11-unix/X150 ]; then export DISPLAY=:150; fi; fi; "))
    }

    @Test
    fun `the env line is DISPLAY, only when a display is given`() {
        assertEquals(mapOf("DISPLAY" to ":150"), SiblingX11.environment(150))
        assertEquals(":150", ContainerLayout.clientEnvironment("wayland-1", x11Display = 150)["DISPLAY"])
        assertEquals(null, ContainerLayout.clientEnvironment("wayland-1")["DISPLAY"])
    }

    @Test
    fun `the page reads ready, a package manager, or none`() {
        assertEquals(SiblingX11.Status.Ready, SiblingX11.status("ready\n"))
        assertEquals(SiblingX11.Status.Installable(manager("apk")), SiblingX11.status("apk\n"))
        assertEquals(SiblingX11.Status.NoManager, SiblingX11.status(""))
        assertTrue(SiblingX11.STATUS_SCRIPT.endsWith(PackageUpgrade.FIND_SCRIPT))
    }

    @Test
    fun `each package manager installs the server and Xwayland`() {
        fun commands(id: String) = SiblingX11.installSteps(manager(id)).map { it.command.joinToString(" ") }
        assertEquals(listOf("apk update", "apk add xwayland-satellite xwayland"), commands("apk"))
        assertEquals(
            listOf("apt-get update", "apt-get install -y --no-install-recommends xwayland-satellite xwayland"),
            commands("apt-get"),
        )
        assertEquals(mapOf("DEBIAN_FRONTEND" to "noninteractive"), SiblingX11.installSteps(manager("apt-get")).last().env)
        assertEquals(
            listOf("dnf install -y --setopt=install_weak_deps=False xwayland-satellite xorg-x11-server-Xwayland"),
            commands("dnf"),
        )
        assertEquals(listOf("pacman -Sy --needed --noconfirm xwayland-satellite xorg-xwayland"), commands("pacman"))
        assertEquals(
            listOf("zypper --non-interactive refresh", "zypper --non-interactive install --no-recommends xwayland-satellite xwayland"),
            commands("zypper"),
        )
        PackageUpgrade.MANAGERS.forEach { assertTrue(SiblingX11.installSteps(it).isNotEmpty()) }
    }

    @Test
    fun `a distro without the package is told from any other failure`() {
        assertTrue(SiblingX11.notPackaged(manager("apt-get"), "E: Unable to locate package xwayland-satellite"))
        assertTrue(SiblingX11.notPackaged(manager("apk"), "ERROR: unable to select packages:\n  xwayland-satellite (no such package)"))
        assertTrue(SiblingX11.notPackaged(manager("dnf"), "No match for argument: xwayland-satellite"))
        assertTrue(SiblingX11.notPackaged(manager("pacman"), "error: target not found: xwayland-satellite"))
        assertTrue(SiblingX11.notPackaged(manager("zypper"), "'xwayland-satellite' not found in package names."))
        assertFalse(SiblingX11.notPackaged(manager("apt-get"), "E: Could not resolve 'deb.debian.org'"))
        assertNotEquals(SiblingX11.Status.Ready, SiblingX11.status("apt-get"))
    }
}
