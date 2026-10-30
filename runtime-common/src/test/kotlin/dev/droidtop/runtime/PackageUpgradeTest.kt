package dev.droidtop.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** "Update its software" for a container (docs/SPEC.md 3d; Droidtop/tracker#757 slice 4). */
class PackageUpgradeTest {
    private fun manager(id: String) = PackageUpgrade.byId(id)!!

    @Test
    fun `the probe names a known manager, in a fixed order`() {
        assertEquals("apt", PackageUpgrade.byId("apt-get\n")?.label)
        assertNull(PackageUpgrade.byId("emerge"))
        assertEquals(true, PackageUpgrade.FIND_SCRIPT.startsWith("for m in apk apt-get dnf pacman zypper; do"))
    }

    @Test
    fun `every step upgrades what is installed, never the distribution`() {
        val all = PackageUpgrade.MANAGERS.flatMap { it.steps }.flatMap { it.command }
        for (word in listOf("dist-upgrade", "full-upgrade", "dup", "distro-sync", "install")) assertFalse(word, word in all)
        assertEquals(mapOf("DEBIAN_FRONTEND" to "noninteractive"), manager("apt-get").steps.last().env)
    }

    @Test
    fun `a successful upgrade is summed up from the manager's own words`() {
        assertEquals(
            "3 packages upgraded",
            PackageUpgrade.summary(manager("apt-get"), "Reading package lists...\n3 upgraded, 0 newly installed, 0 to remove and 1 not upgraded.\n"),
        )
        assertEquals("Already up to date", PackageUpgrade.summary(manager("apt-get"), "0 upgraded, 0 newly installed, 0 to remove and 0 not upgraded."))
        assertEquals(
            "2 packages upgraded",
            PackageUpgrade.summary(manager("apk"), "(1/2) Upgrading musl (1.2.4-r1 -> 1.2.4-r2)\n(2/2) Upgrading busybox (1.36-r1 -> 1.36-r2)\nOK: 8 MiB in 15 packages"),
        )
        assertEquals("Already up to date", PackageUpgrade.summary(manager("apk"), "OK: 8 MiB in 15 packages"))
        assertEquals("Already up to date", PackageUpgrade.summary(manager("dnf"), "Dependencies resolved.\nNothing to do.\nComplete!"))
        assertEquals("1 package upgraded", PackageUpgrade.summary(manager("dnf"), "Transaction Summary\n=====\nUpgrade  1 Package\n"))
        assertEquals("Already up to date", PackageUpgrade.summary(manager("pacman"), ":: Starting full system upgrade...\n there is nothing to do"))
        assertEquals("4 packages upgraded", PackageUpgrade.summary(manager("pacman"), "Packages (4) a-1 b-2 c-3 d-4"))
        assertEquals("Already up to date", PackageUpgrade.summary(manager("zypper"), "Loading repository data...\nNothing to do."))
        assertEquals("5 packages upgraded", PackageUpgrade.summary(manager("zypper"), "The following 5 packages are going to be upgraded:"))
        assertNull(PackageUpgrade.summary(manager("dnf"), "something else entirely"))
    }

    @Test
    fun `a failure keeps the manager's last words, not the whole log`() {
        assertEquals("E: Unable to fetch some archives Fix it", PackageUpgrade.failureLine("lots\nof\noutput\nE: Unable to fetch some archives\n\n  Fix it  \n"))
        assertEquals("no output", PackageUpgrade.failureLine("\n\n"))
    }
}
