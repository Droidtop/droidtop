package dev.droidtop.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ContainerLayoutTest {
    private val plan = PrimaryProvisioning(installCommand = "false && true", compositorCommand = "sway")

    @Test
    fun `the boot script checks the install itself instead of trusting set -e`() {
        // `set -e` ignores a failure anywhere in an && list but the last
        // command; the script must not reach the marker after a failed
        // `apt-get update`.
        val script = ContainerLayout.primaryInitScript(plan)
        val guard = script.indexOf("if ! { false && true; }; then")
        val marker = script.indexOf("echo ${ContainerLayout.planId(plan)} >> ${ContainerLayout.PROVISIONED_MARKER}")
        assertTrue(guard >= 0)
        assertTrue(marker > guard)
        assertTrue(script.substring(guard, marker).contains("exit 1"))
    }

    @Test
    fun `a plan already installed is not installed again, and only a first install says first boot`() {
        val script = ContainerLayout.primaryInitScript(plan)
        assertTrue(script.contains("if ! grep -qx ${ContainerLayout.planId(plan)} ${ContainerLayout.PROVISIONED_MARKER} 2>/dev/null; then"))
        val changed = script.indexOf("the desktop setup changed")
        val firstBoot = script.indexOf("(first boot)")
        assertTrue(changed in 0 until firstBoot)
        assertTrue(script.substring(changed, firstBoot).contains("else"))
    }

    @Test
    fun `the compositor runs headless with software rendering, as the last thing the script does`() {
        val script = ContainerLayout.primaryInitScript(plan)
        assertTrue(script.contains("export WLR_BACKENDS=headless"))
        assertTrue(script.contains("export WLR_HEADLESS_OUTPUTS=1"))
        assertTrue(script.contains("export WLR_RENDERER=pixman"))
        assertTrue(script.contains("export XDG_RUNTIME_DIR=${ContainerLayout.SOCKET_DIR}"))
        assertEquals("exec sway", script.trimEnd().lines().last())
    }

    @Test
    fun `clients get the socket directory and the name the compositor chose`() {
        val env = ContainerLayout.clientEnvironment("wayland-1")
        assertEquals(ContainerLayout.SOCKET_DIR, env["XDG_RUNTIME_DIR"])
        assertEquals("wayland-1", env["WAYLAND_DISPLAY"])
        assertEquals(null, ContainerLayout.clientEnvironment(null)["WAYLAND_DISPLAY"])
    }

    @Test
    fun `the time zone is a POSIX rule a stock image reads without tzdata`() {
        val summer = java.time.Instant.parse("2026-07-01T12:00:00Z")
        assertEquals("<-05>5<-04>,M3.2.0,M11.1.0", ContainerLayout.posixTimeZone(java.time.ZoneId.of("America/New_York"), summer))
        assertEquals("<+01>-1<+02>,M3.5.0,M10.5.0/3", ContainerLayout.posixTimeZone(java.time.ZoneId.of("Europe/Berlin"), summer))
        assertEquals("<+10>-10<+11>,M10.1.0,M4.1.0/3", ContainerLayout.posixTimeZone(java.time.ZoneId.of("Australia/Sydney"), summer))
        assertEquals("<+0530>-5:30", ContainerLayout.posixTimeZone(java.time.ZoneId.of("Asia/Kolkata"), summer))
        assertEquals("<+00>0", ContainerLayout.posixTimeZone(java.time.ZoneOffset.UTC, summer))
        assertEquals("<+09>-9", ContainerLayout.posixTimeZone(java.time.ZoneId.of("Asia/Tokyo"), summer))
    }

    @Test
    fun `every container gets a machine id once, and clients the shared session bus`() {
        val script = ContainerLayout.primaryInitScript(CompositorProvisioning.plan("alpine", "sway")!!)
        assertTrue(script.lines().contains("[ -s /etc/machine-id ] || tr -d '-' < /proc/sys/kernel/random/uuid > /etc/machine-id"))
        assertEquals("unix:path=/run/droidtop-sockets/bus", ContainerLayout.clientEnvironment(null)["DBUS_SESSION_BUS_ADDRESS"])
    }

    @Test
    fun `menus find the distro's entries first, then the library's`() {
        assertEquals(
            "/usr/local/share:/usr/share:/run/droidtop-app-storage/desktop-launcher/share",
            ContainerLayout.clientEnvironment(null)["XDG_DATA_DIRS"],
        )
    }

    @Test
    fun `PULSE_SERVER is set by default and dropped when audio sharing is off`() {
        val shared = ContainerLayout.clientEnvironment(null)
        assertEquals("${ContainerLayout.SOCKET_DIR}/${ContainerLayout.AUDIO_SOCKET}", shared["PULSE_SERVER"])
        val notShared = ContainerLayout.clientEnvironment(null, audioShared = false)
        assertEquals(null, notShared["PULSE_SERVER"])
    }

    @Test
    fun `CUPS_SERVER is unaffected by the audio toggle`() {
        assertEquals(
            "${ContainerLayout.SOCKET_DIR}/${ContainerLayout.CUPS_SOCKET}",
            ContainerLayout.clientEnvironment(null, audioShared = false)["CUPS_SERVER"],
        )
    }

    @Test
    fun `the compositor socket is found, whatever it is called`() {
        // sway starts at wayland-1 and never uses wayland-0.
        val dir = java.nio.file.Files.createTempDirectory("sockets").toFile()
        try {
            assertEquals(null, ContainerLayout.findWaylandSocket(dir))
            listOf("sway-ipc.0.12.sock", "wayland-1.lock", "wayland-10", "wayland-1").forEach { File(dir, it).writeText("") }
            assertEquals("wayland-1", ContainerLayout.findWaylandSocket(dir)!!.name)
        } finally {
            dir.listFiles()?.forEach { it.delete() }
            dir.delete()
        }
    }

    @Test
    fun `app storage paths map under the fixed in-container directory`() {
        val storage = File("/data/user/0/dev.droidtop.app/files")
        assertEquals(
            "${ContainerLayout.APP_STORAGE_DIR}/imagefs/home",
            ContainerLayout.hostStorageToContainerPath(storage, File(storage, "imagefs/home")),
        )
        assertEquals(ContainerLayout.APP_STORAGE_DIR, ContainerLayout.hostStorageToContainerPath(storage, storage))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a path outside app storage is a caller bug`() {
        ContainerLayout.hostStorageToContainerPath(File("/data/user/0/dev.droidtop.app/files"), File("/sdcard/x"))
    }

    @Test
    fun `a changed plan provisions again, the same plan does not`() {
        val a = PrimaryProvisioning("apk add sway foot", "sway")
        val b = PrimaryProvisioning("apk add sway font-dejavu foot", "sway")
        val script = ContainerLayout.primaryInitScript(a)
        assertTrue(script.contains("grep -qx ${ContainerLayout.planId(a)} "))
        assertTrue(ContainerLayout.planId(a) != ContainerLayout.planId(b))
        assertEquals(ContainerLayout.planId(a), ContainerLayout.planId(a.copy(compositorCommand = "labwc")))
    }
}
