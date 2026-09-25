package dev.droidtop.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContainerApplicationsTest {
    // foot's own entry as Debian ships it (foot 1.21, foot.desktop).
    private val foot = """
        [Desktop Entry]
        Type=Application
        Exec=foot
        Icon=foot
        Terminal=false
        Categories=System;TerminalEmulator;
        Keywords=shell;prompt;command;commandline;
        Name=Foot
        GenericName=Terminal
        Comment=A wayland native terminal emulator
        StartupWMClass=foot
    """.trimIndent()

    @Test
    fun `a plain application entry`() {
        val app = ContainerApplications.parseEntry("foot.desktop", foot)!!
        assertEquals(
            ContainerApp("foot.desktop", "Foot", listOf("foot"), terminal = false, genericName = "Terminal", icon = "foot"),
            app,
        )
    }

    @Test
    fun `a client and a server of a program fold into the program`() {
        val listing = buildString {
            append("\n@@droidtop-desktop-file /usr/share/applications/foot.desktop\n").append(foot).append('\n')
            append("\n@@droidtop-desktop-file /usr/share/applications/footclient.desktop\n")
            append(foot.replace("Exec=foot", "Exec=footclient").replace("Name=Foot", "Name=Foot Client")).append('\n')
            append("\n@@droidtop-desktop-file /usr/share/applications/foot-server.desktop\n")
            append(foot.replace("Exec=foot", "Exec=foot --server").replace("Name=Foot", "Name=Foot Server")).append('\n')
        }
        assertEquals(listOf("foot.desktop"), ContainerApplications.parseListing(listing).map { it.id })
    }

    @Test
    fun `a link that opens a page is not an app`() {
        val cups = "[Desktop Entry]\nType=Application\nName=Manage Printing\nExec=xdg-open http://localhost:631/\nIcon=cups\n"
        assertNull(ContainerApplications.parseEntry("cups.desktop", cups))
    }

    @Test
    fun `entries for other desktops are not this desktop's`() {
        assertNull(ContainerApplications.parseEntry("g.desktop", foot + "\nOnlyShowIn=GNOME;"))
        assertNull(ContainerApplications.parseEntry("s.desktop", foot + "\nNotShowIn=sway;"))
        assertEquals("Foot", ContainerApplications.parseEntry("w.desktop", foot + "\nOnlyShowIn=GNOME;sway;")?.name)
    }

    @Test
    fun `hidden, no-display and non-application entries are not listed`() {
        assertNull(ContainerApplications.parseEntry("a.desktop", foot + "\nNoDisplay=true"))
        assertNull(ContainerApplications.parseEntry("b.desktop", foot + "\nHidden=true"))
        assertNull(ContainerApplications.parseEntry("c.desktop", foot.replace("Type=Application", "Type=Link")))
    }

    @Test
    fun `only the Desktop Entry group counts, and localized keys are not the name`() {
        val text = """
            [Desktop Entry]
            Name[de]=Fuß
            Name=Foot
            Type=Application
            Exec=foot
            [Desktop Action new-window]
            Name=New Window
            Exec=foot --new
        """.trimIndent()
        val app = ContainerApplications.parseEntry("foot.desktop", text)!!
        assertEquals("Foot", app.name)
        assertEquals(listOf("foot"), app.command)
    }

    @Test
    fun `field codes are dropped and a double percent is a percent`() {
        assertEquals(listOf("mousepad"), ContainerApplications.splitExec("mousepad %U"))
        assertEquals(listOf("app", "--x=50%"), ContainerApplications.splitExec("app --x=50%% %f"))
    }

    @Test
    fun `quoted arguments follow the spec's escaping`() {
        // In the file: Exec=sh -c "echo \\$HOME is \"\\\\\""
        val exec = "sh -c \"echo \\\\\$HOME is \\\"\\\\\\\\\\\"\""
        assertEquals(listOf("sh", "-c", "echo \$HOME is \"\\\""), ContainerApplications.splitExec(exec))
    }

    @Test
    fun `a terminal program runs inside the provisioned terminal`() {
        val htop = ContainerApp("htop.desktop", "Htop", listOf("htop"), terminal = true)
        assertEquals(listOf(ContainerTerminal.PACKAGE, "-e", "htop"), ContainerApplications.launchCommand(htop))
    }

    @Test
    fun `a listing splits into files, sorts by name, and later directories win`() {
        val listing = buildString {
            append("\n@@droidtop-desktop-file /usr/share/applications/foot.desktop\n")
            append(foot).append('\n')
            append("\n@@droidtop-desktop-file /usr/share/applications/alpha.desktop\n")
            append("[Desktop Entry]\nType=Application\nName=alpha\nExec=alpha\n")
            append("\n@@droidtop-desktop-file /usr/local/share/applications/foot.desktop\n")
            append(foot.replace("Name=Foot", "Name=Foot (local)")).append('\n')
        }
        val apps = ContainerApplications.parseListing(listing)
        assertEquals(listOf("alpha", "Foot (local)"), apps.map { it.name })
        assertTrue(apps.all { it.command.isNotEmpty() })
    }
}
