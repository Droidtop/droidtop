package dev.droidtop.stores

import dev.droidtop.library.stores.StoreContentChoice
import dev.droidtop.stores.steam.BranchInfo
import dev.droidtop.stores.steam.DepotInfo
import dev.droidtop.stores.steam.ManifestInfo
import dev.droidtop.stores.steam.OS
import dev.droidtop.stores.steam.OSArch
import dev.droidtop.stores.steam.SteamApp
import dev.droidtop.stores.steam.SteamBranches
import dev.droidtop.stores.steam.SteamChoice
import dev.droidtop.stores.steam.SteamChoices
import dev.droidtop.stores.steam.SteamContent
import dev.droidtop.stores.steam.SteamDepots
import dev.droidtop.stores.steam.SteamIds
import `in`.dragonbra.javasteam.types.KeyValue
import java.util.Date
import java.util.EnumSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The DLC and branch picker's rules and the choice file (docs/SPEC.md 7g, "Stores", Droidtop/tracker#313). */
class SteamContentTest {
    private fun manifests(vararg branches: String, gid: Long = 1L, size: Long = 100L) =
        branches.associateWith { ManifestInfo(it, gid, size, size / 2) }

    private fun depot(
        id: Int,
        dlcAppId: Int = SteamIds.INVALID_APP_ID,
        manifests: Map<String, ManifestInfo> = manifests("public"),
        encrypted: Map<String, ManifestInfo> = emptyMap(),
    ) = DepotInfo(
        depotId = id,
        dlcAppId = dlcAppId,
        depotFromApp = SteamIds.INVALID_APP_ID,
        sharedInstall = false,
        osList = EnumSet.of(OS.windows),
        osArch = OSArch.Unknown,
        manifests = manifests,
        encryptedManifests = encrypted,
    )

    private fun branch(name: String, locked: Boolean = false, build: Long = 10L) = BranchInfo(name, build, locked, Date(1_000L))

    private fun app(branches: Map<String, BranchInfo>) = SteamApp(id = 10, name = "Game", branches = branches)

    @Test
    fun `DLC inside the game's depots and DLC apps are listed with what each downloads`() {
        val plan = SteamDepots.Plan(
            mainDepots = mapOf(1 to depot(1), 2 to depot(2, dlcAppId = 50, manifests = manifests("public", size = 400L))),
            dlcDepots = mapOf(3 to depot(3, dlcAppId = 60, manifests = manifests("public", size = 900L))),
        )
        val dlc = SteamContent.dlcIn(plan, "public") { id -> mapOf(50 to "Zebra pack", 60 to "Alpha pack")[id].orEmpty() }
        assertEquals(listOf("Alpha pack", "Zebra pack"), dlc.map { it.name })
        // the download is half the installed size in these manifests
        assertEquals(450L, dlc.first { it.appId == 60 }.bytes)
        assertEquals(200L, dlc.first { it.appId == 50 }.bytes)
    }

    @Test
    fun `a DLC with no name is called by its number`() {
        val plan = SteamDepots.Plan(emptyMap(), mapOf(3 to depot(3, dlcAppId = 60)))
        assertEquals("DLC 60", SteamContent.dlcIn(plan, "public") { "" }.single().name)
    }

    @Test
    fun `the picker shows what is on, what is installed and which versions are locked`() {
        val app = app(mapOf("public" to branch("public"), "beta" to branch("beta", locked = true), "alpha" to branch("alpha")))
        val dlc = listOf(SteamContent.Dlc(50, "One", 10L), SteamContent.Dlc(60, "Two", 20L))
        val choice = SteamChoice(branch = "beta", excludedDlc = setOf(60), branchPasswords = mapOf("beta" to "secret"))
        val options = SteamContent.options(app, dlc, choice, installedDlc = setOf(60), installed = true)
        assertEquals(listOf("public", "alpha", "beta"), options.branches.map { it.id })
        val beta = options.branches.first { it.id == "beta" }
        assertTrue(beta.locked && beta.unlocked && beta.selected)
        assertFalse(options.branches.first { it.id == "public" }.selected)
        assertEquals(listOf(true, false), options.extras.map { it.selected })
        assertEquals(listOf(false, true), options.extras.map { it.installed })
    }

    @Test
    fun `a branch Steam no longer lists falls back to public`() {
        val app = app(mapOf("public" to branch("public")))
        val options = SteamContent.options(app, emptyList(), SteamChoice(branch = "gone"), emptySet(), installed = false)
        assertTrue(options.branches.single().selected)
        assertTrue(options.isEmpty)
    }

    @Test
    fun `the picked ids become the DLC left out`() {
        val picked = StoreContentChoice(extraIds = setOf("50"), branchId = "beta")
        val choice = SteamContent.choiceFrom(picked, available = setOf(50, 60), before = SteamChoice(branchPasswords = mapOf("beta" to "x")))
        assertEquals(setOf(60), choice.excludedDlc)
        assertEquals("beta", choice.branch)
        assertEquals("x", choice.password)
        assertEquals("public", SteamContent.choiceFrom(StoreContentChoice(emptySet(), null), setOf(50), SteamChoice()).branch)
    }

    @Test
    fun `an install differs when a DLC or the branch is not what is on the device`() {
        val all = setOf(50, 60)
        assertFalse(SteamContent.installDiffers(SteamChoice(), all, installedDlc = all, installedBranch = "public"))
        assertTrue(SteamContent.installDiffers(SteamChoice(), all, installedDlc = setOf(50), installedBranch = "public"))
        assertTrue(SteamContent.installDiffers(SteamChoice(excludedDlc = setOf(60)), all, installedDlc = all, installedBranch = "public"))
        assertTrue(SteamContent.installDiffers(SteamChoice(branch = "beta"), all, installedDlc = all, installedBranch = "public"))
        // an install row from before branches were kept names none
        assertFalse(SteamContent.installDiffers(SteamChoice(), all, installedDlc = all, installedBranch = ""))
        // a DLC that is gone from the account does not count
        assertFalse(SteamContent.installDiffers(SteamChoice(), all, installedDlc = all + 70, installedBranch = "public"))
    }

    @Test
    fun `only installed DLC that were turned off are removed`() {
        val removed = SteamContent.removedDlc(SteamChoice(excludedDlc = setOf(60, 70)), available = setOf(50, 60, 70), installedDlc = setOf(50, 60))
        assertEquals(setOf(60), removed)
    }

    @Test
    fun `the depots of a removed DLC are those carrying its id and those its own install downloaded`() {
        val depots = mapOf(1 to depot(1), 2 to depot(2, dlcAppId = 50), 3 to depot(3, dlcAppId = 70))
        assertEquals(setOf(2, 9), SteamContent.depotsOf(setOf(50, 60), depots, mapOf(60 to listOf(9))))
    }

    @Test
    fun `a file two depots list stays when one of them goes`() {
        val gone = SteamContent.filesToDelete(
            removedFiles = listOf("data\\dlc.pak", "Shared\\Common.DLL", "data/dlc.pak"),
            keptFiles = listOf("shared/common.dll", "game.exe"),
        )
        assertEquals(listOf("data/dlc.pak"), gone)
    }

    @Test
    fun `the depot downloader's record forgets removed depots and keeps the others`() {
        val config = """{"installedManifestIDs":{"1":111,"2":222,"3":333}}"""
        val after = SteamContent.withoutDepots(config, setOf(2))
        assertTrue("\"1\"" in after && "\"3\"" in after && "\"2\"" !in after)
        assertEquals("not json", SteamContent.withoutDepots("not json", setOf(2)))
    }

    @Test
    fun `the public branch leads the list and the rest are sorted`() {
        val listed = SteamBranches.listed(mapOf("zeta" to branch("zeta"), "Beta" to branch("Beta"), "public" to branch("public")))
        assertEquals(listOf("public", "Beta", "zeta"), listed.map { it.name })
        assertEquals(listOf("public"), SteamBranches.listed(emptyMap()).map { it.name })
    }

    @Test
    fun `a branch has the depots that list it and the ones with no manifests at all`() {
        val depots = mapOf(
            1 to depot(1, manifests = manifests("public", "beta")),
            2 to depot(2, manifests = manifests("public")),
            3 to depot(3, manifests = emptyMap(), encrypted = manifests("beta")),
            4 to depot(4, manifests = emptyMap()),
        )
        assertEquals(setOf(1, 3, 4), SteamBranches.forBranch(depots, "beta").keys)
        assertEquals(depots.keys, SteamBranches.forBranch(depots, "public").keys)
    }

    @Test
    fun `an opened branch's manifests replace the encrypted ones`() {
        val depots = mapOf(3 to depot(3, manifests = emptyMap(), encrypted = manifests("beta", gid = 0L, size = 0L)))
        val opened = SteamBranches.overlay(depots, mapOf(3 to ManifestInfo("beta", 77L, 500L, 250L)), "beta")
        val depot = opened.getValue(3)
        assertEquals(77L, depot.manifests.getValue("beta").gid)
        assertTrue(depot.encryptedManifests.isEmpty())
        assertEquals(250L, SteamDepots.downloadBytes(depot, "beta"))
    }

    @Test
    fun `a private depot section is read per depot for the branch asked`() {
        fun manifest(branch: String, gid: String, size: String, download: String) = KeyValue("manifests").apply {
            children.add(
                KeyValue(branch).apply {
                    children.add(KeyValue("gid", gid))
                    children.add(KeyValue("size", size))
                    children.add(KeyValue("download", download))
                },
            )
        }
        val section = KeyValue("depots").apply {
            children.add(KeyValue("5").apply { children.add(manifest("beta", "123", "1000", "400")) })
            children.add(KeyValue("6").apply { children.add(manifest("other", "9", "9", "9")) })
            children.add(KeyValue("notadepot", "x"))
        }
        val parsed = SteamBranches.parseSection(section, "beta")
        assertEquals(setOf(5), parsed.keys)
        assertEquals(ManifestInfo("beta", 123L, 1000L, 400L), parsed.getValue(5))
    }

    @Test
    fun `a password opens a branch only when Steam gave its key`() {
        assertTrue(SteamBranches.unlocked(mapOf("beta" to byteArrayOf(1)), "beta"))
        assertFalse(SteamBranches.unlocked(emptyMap(), "beta"))
        assertFalse(SteamBranches.unlocked(mapOf("alpha" to byteArrayOf(1)), "beta"))
    }

    @Test
    fun `the choices file round trips and a damaged one is no choices`() {
        val all = mapOf(
            10 to SteamChoice(branch = "beta", excludedDlc = setOf(50), branchPasswords = mapOf("beta" to "pw")),
            20 to SteamChoice(excludedDlc = setOf(1, 2)),
        )
        assertEquals(all, SteamChoices.decode(SteamChoices.encode(all)))
        assertEquals(emptyMap<Int, SteamChoice>(), SteamChoices.decode("{ broken"))
        assertEquals("pw", all.getValue(10).password)
        assertNull(all.getValue(20).password)
    }
}
