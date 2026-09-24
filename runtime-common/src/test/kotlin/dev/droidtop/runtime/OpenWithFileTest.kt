package dev.droidtop.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenWithFileTest {
    @Test
    fun `kinds are recognised by extension, whatever the case`() {
        assertEquals(OpenWithKind.EXE, OpenWithKind.of("Setup.EXE"))
        assertEquals(OpenWithKind.APPIMAGE, OpenWithKind.of("tool-x86_64.appimage"))
        assertEquals(OpenWithKind.DEB, OpenWithKind.of("pkg_1.0_arm64.deb"))
        assertNull(OpenWithKind.of("notes.txt"))
        assertNull(OpenWithKind.of("exe"))
    }

    @Test
    fun `a container is offered only for packages its manager installs`() {
        assertTrue(ContainerOpenWith.accepts(OpenWithKind.DEB, ContainerPackageManager.APT))
        assertFalse(ContainerOpenWith.accepts(OpenWithKind.RPM, ContainerPackageManager.APT))
        assertTrue(ContainerOpenWith.accepts(OpenWithKind.RPM, ContainerPackageManager.ZYPPER))
        assertFalse(ContainerOpenWith.accepts(OpenWithKind.DEB, null))
        assertTrue(ContainerOpenWith.accepts(OpenWithKind.APPIMAGE, null))
    }

    @Test
    fun `the probe's output names the manager`() {
        assertEquals(
            ContainerPackageManager.DNF,
            ContainerOpenWith.packageManagerFrom(ContainerExecResult(0, "/usr/bin/x\ndnf\n", "")),
        )
        assertNull(ContainerOpenWith.packageManagerFrom(ContainerExecResult(1, "", "")))
    }

    @Test
    fun `installs run from where the file is and answer their own prompts`() {
        val path = "${ContainerLayout.SHARED_STORAGE_DIR}/primary/Download/a.deb"
        assertEquals(listOf("apt-get", "install", "-y", path), ContainerOpenWith.command(OpenWithKind.DEB, ContainerPackageManager.APT, path))
        assertEquals(
            listOf("zypper", "--non-interactive", "install", "/x.rpm"),
            ContainerOpenWith.command(OpenWithKind.RPM, ContainerPackageManager.ZYPPER, "/x.rpm"),
        )
        assertEquals(listOf("/a.AppImage"), ContainerOpenWith.command(OpenWithKind.APPIMAGE, null, "/a.AppImage"))
        assertEquals("1", ContainerOpenWith.environment(OpenWithKind.APPIMAGE)["APPIMAGE_EXTRACT_AND_RUN"])
    }
}
