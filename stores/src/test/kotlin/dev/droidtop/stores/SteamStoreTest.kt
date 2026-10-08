package dev.droidtop.stores

import dev.droidtop.stores.amazon.AmazonStore
import dev.droidtop.stores.steam.AppType
import dev.droidtop.stores.steam.ConfigInfo
import dev.droidtop.stores.steam.DepotInfo
import dev.droidtop.stores.steam.LaunchInfo
import dev.droidtop.stores.steam.ManifestInfo
import dev.droidtop.stores.steam.OS
import dev.droidtop.stores.steam.OSArch
import dev.droidtop.stores.steam.SteamApp
import dev.droidtop.stores.steam.SteamAppKind
import dev.droidtop.stores.steam.SteamLicense
import dev.droidtop.stores.steam.SteamOwnership
import `in`.dragonbra.javasteam.enums.ELicenseFlags
import `in`.dragonbra.javasteam.enums.ELicenseType
import dev.droidtop.stores.steam.SteamDatabase
import dev.droidtop.stores.steam.SteamConverters
import dev.droidtop.stores.steam.SteamDepots
import dev.droidtop.stores.steam.SteamExecutables
import dev.droidtop.stores.steam.SteamIds
import dev.droidtop.stores.steam.SteamLibrarySync
import dev.droidtop.stores.steam.SteamInstalls
import java.io.File
import java.util.EnumSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Steam's depot choice, program choice, install reading and GameNative's stored rows (docs/SPEC.md 7g, "Stores"). */
class SteamStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun manifest(gid: Long = 1L, size: Long = 100L) = mapOf("public" to ManifestInfo("public", gid, size, size / 2))

    private fun depot(
        id: Int,
        os: OS = OS.windows,
        arch: OSArch = OSArch.Unknown,
        language: String = "",
        dlcAppId: Int = SteamIds.INVALID_APP_ID,
        manifests: Map<String, ManifestInfo> = manifest(),
        steamDeck: Boolean = false,
    ) = DepotInfo(
        depotId = id,
        dlcAppId = dlcAppId,
        depotFromApp = SteamIds.INVALID_APP_ID,
        sharedInstall = false,
        osList = EnumSet.of(os),
        osArch = arch,
        manifests = manifests,
        encryptedManifests = emptyMap(),
        language = language,
        steamDeck = steamDeck,
    )

    @Test
    fun `the device's language is taken when the game has it, English otherwise`() {
        val depots = mapOf(1 to depot(1, language = "english"), 2 to depot(2, language = "german"))
        assertEquals("german", SteamDepots.effectiveLanguage(depots, "german", null, null))
        assertEquals("english", SteamDepots.effectiveLanguage(depots, "french", null, null))
        val chosen = SteamDepots.resolve(depots, "french", null, null)
        assertEquals(setOf(1), chosen.keys)
    }

    @Test
    fun `64-bit wins over 32-bit, Windows over other systems, the plain build over the Deck's`() {
        val depots = mapOf(
            1 to depot(1, arch = OSArch.Arch64),
            2 to depot(2, arch = OSArch.Arch32),
            3 to depot(3, os = OS.linux),
            4 to depot(4, steamDeck = true),
        )
        assertEquals(setOf(1), SteamDepots.resolve(depots, "english", null, null).keys)
    }

    @Test
    fun `a depot the package does not grant is left out, unless it is Steam's own`() {
        val depots = mapOf(1 to depot(1), 2 to depot(2), 3 to depot(3).copy(systemDefined = true))
        assertEquals(setOf(1, 3), SteamDepots.resolve(depots, "english", null, setOf(1)).keys)
    }

    @Test
    fun `a plan takes owned DLC only, and files a DLC package's depot under that DLC`() {
        val app = SteamApp(
            id = 10,
            depots = mapOf(
                1 to depot(1),
                2 to depot(2, dlcAppId = 20),
                3 to depot(3, dlcAppId = 30),
                4 to depot(4),
            ),
        )
        val dlcApp = SteamApp(id = 40, dlcForAppId = 10, depots = mapOf(41 to depot(41)))
        val plan = SteamDepots.plan(
            app = app,
            language = "english",
            ownedDlcAppIds = setOf(20, 40),
            licensedDepotIds = null,
            mainPackageDepots = setOf(1, 2, 3),
            dlcPackageDepots = mapOf(40 to listOf(4)),
            dlcApps = listOf(SteamDepots.DlcApp(dlcApp, null)),
            alreadyDownloaded = null,
        )
        assertEquals(setOf(1, 2, 4), plan.mainDepots.keys)
        assertEquals(40, plan.mainDepots.getValue(4).dlcAppId)
        assertEquals(setOf(41), plan.dlcDepots.keys)
        assertEquals(40, plan.dlcDepots.getValue(41).dlcAppId)
        assertEquals(listOf(40), plan.dlcAppIds)
    }

    @Test
    fun `a download moves the download size, never more than the install`() {
        assertEquals(50L, ManifestInfo("public", 1, 100, 50).downloadBytes)
        assertEquals(100L, ManifestInfo("public", 1, 100, 400).downloadBytes)
        assertEquals(100L, ManifestInfo("public", 1, 100, 0).downloadBytes)
    }

    @Test
    fun `the developer's launch entry is the program unless it is a stub`() {
        val files = listOf(
            SteamExecutables.Candidate("Game.exe", true, 50_000_000, 0),
            SteamExecutables.Candidate("Launcher.exe", true, 3_000_000, 0),
        )
        assertEquals("Game.exe", SteamExecutables.choose(files, listOf("game.exe"), "Game"))
        assertEquals("Game.exe", SteamExecutables.choose(files, listOf("Launcher.exe"), "Game"))
    }

    @Test
    fun `an Unreal shipping program beats a helper, and with no files the launch entry answers`() {
        val files = listOf(
            SteamExecutables.Candidate("Stray/Binaries/Win64/Stray-Win64-Shipping.exe", true, 80_000_000, 0),
            SteamExecutables.Candidate("Engine/Binaries/Win64/CrashReportClient.exe", true, 20_000_000, 0),
            SteamExecutables.Candidate("Stray.exe", false, 300_000, 0),
        )
        assertEquals("Stray/Binaries/Win64/Stray-Win64-Shipping.exe", SteamExecutables.choose(files, emptyList(), "Stray"))
        assertEquals("bin/game.exe", SteamExecutables.choose(emptyList(), listOf("bin/game.exe"), "Game"))
        assertNull(SteamExecutables.choose(emptyList(), emptyList(), "Game"))
    }

    @Test
    fun `only launch entries for an exe are Windows ones`() {
        val app = SteamApp(
            id = 1,
            config = ConfigInfo(
                launch = listOf(
                    LaunchInfo("game.sh", "", "", "", EnumSet.of(OS.linux), OSArch.Unknown),
                    LaunchInfo("Game.EXE", "", "", "", EnumSet.of(OS.none), OSArch.Unknown, "-dx11"),
                ),
            ),
        )
        assertEquals(listOf("Game.EXE"), SteamExecutables.windowsLaunchEntries(app).map { it.executable })
    }

    @Test
    fun `the installed build of each depot is read off the depot downloader's manifest names`() {
        assertEquals(228981 to 7613356809904826842L, SteamInstalls.parse("228981_7613356809904826842.manifest"))
        // An unsigned build id above Long.MAX_VALUE reads as the same Long either way it is written.
        assertEquals(SteamInstalls.parse("5_-1.manifest"), SteamInstalls.parse("5_18446744073709551615.manifest"))
        assertNull(SteamInstalls.parse("depot.config"))
        val builds = SteamInstalls.installedBuilds(listOf("1_10.manifest" to 100L, "1_11.manifest" to 200L, "2_7.manifest" to 50L))
        assertEquals(mapOf(1 to 11L, 2 to 7L), builds)
    }

    @Test
    fun `a game is behind when a depot it has is served at another build`() {
        val live = mapOf(1 to depot(1, manifests = manifest(gid = 11)), 2 to depot(2, manifests = manifest(gid = 7)))
        assertEquals(false, SteamInstalls.isBehind(mapOf(1 to 11L, 2 to 7L), live, "public"))
        assertEquals(true, SteamInstalls.isBehind(mapOf(1 to 10L, 2 to 7L), live, "public"))
        // A depot Steam no longer names on the branch says nothing.
        assertNull(SteamInstalls.isBehind(mapOf(9 to 1L), live, "public"))
    }

    @Test
    fun `a GameNative install is found by its folder name, a finished one first`() {
        val internal = temp.newFolder("internal")
        val card = temp.newFolder("card")
        File(internal, "Half-Life 2").mkdirs()
        File(card, "Half-Life 2").mkdirs()
        File(card, "Half-Life 2/.download_complete").createNewFile()
        val found = SteamInstalls.findInstall("", listOf(internal, card), listOf("Half-Life 2")) { File(it, ".download_complete").exists() }
        assertEquals(File(card, "Half-Life 2"), found)
        val custom = temp.newFolder("picked")
        assertEquals(custom, SteamInstalls.findInstall(custom.path, listOf(internal), listOf("x")) { false })
        assertNull(SteamInstalls.findInstall("", listOf(internal), listOf("Portal")) { true })
    }

    @Test
    fun `GameNative's stored rows read back, its extra fields skipped`() {
        val converters = SteamConverters()
        val depots = converters.toDepots(
            """{"228981":{"depotId":228981,"dlcAppId":2147483647,"depotFromApp":2147483647,"sharedInstall":true,""" +
                """"osList":1,"osArch":"Arch64","manifests":{"public":{"name":"public","gid":"7613356809904826842",""" +
                """"size":"63374025","download":"29842592"}},"encryptedManifests":{},"realm":"steamglobal","futureField":3}}""",
        )
        val read = depots.getValue(228981)
        assertTrue(read.sharedInstall)
        // GameNative's OS.from(code) always adds `none` (0 matches every code); the set reads as Windows.
        assertTrue(OS.windows in read.osList && read.isWindowsCompatible)
        assertEquals(OSArch.Arch64, read.osArch)
        assertEquals(7613356809904826842L, read.manifests.getValue("public").gid)
        val config = converters.toConfig(
            """{"installDir":"Half-Life 2","launch":[{"executable":"hl2.exe","workingDir":"","description":"",""" +
                """"type":"default","configOS":1,"configArch":"Unknown"}],"steamControllerTemplateIndex":1}""",
        )
        assertEquals("Half-Life 2", config.installDir)
        assertEquals("", config.launch.single().arguments)
        assertEquals(depots, converters.toDepots(converters.fromDepots(depots)))
        assertEquals(AppType.game, converters.toAppType(1))
        assertEquals(emptyList<Int>(), converters.toIntList(""))
        assertEquals(listOf(1, 2), converters.toIntList(converters.fromIntList(listOf(1, 2))))
        assertFalse(SteamApp(id = 1).receivedPICS)
    }

    @Test
    fun `only games are library entries, never DLC, tools, music or demos`() {
        fun app(type: AppType, dlcFor: Int = SteamIds.INVALID_APP_ID) = SteamApp(id = 7, type = type, dlcForAppId = dlcFor)
        assertTrue(SteamLibrarySync.isLibraryGame(app(AppType.game)))
        assertEquals(listOf(AppType.game.code), SteamLibrarySync.PLAYABLE_TYPES)
        for (type in listOf(AppType.dlc, AppType.tool, AppType.music, AppType.demo, AppType.application, AppType.beta, AppType.config)) {
            assertFalse("$type", SteamLibrarySync.isLibraryGame(app(type)))
        }
        // A DLC whose product info types it as a game is still DLC when it names a base game.
        assertFalse(SteamLibrarySync.isLibraryGame(app(AppType.game, dlcFor = 220)))
        assertFalse(SteamLibrarySync.isLibraryGame(app(AppType.invalid, dlcFor = 220), keepInstalledKinds = true))
        // An installed demo or application stays listed with its files; installed tools and music do not.
        assertTrue(SteamLibrarySync.isLibraryGame(app(AppType.demo), keepInstalledKinds = true))
        assertFalse(SteamLibrarySync.isLibraryGame(app(AppType.music), keepInstalledKinds = true))
    }

    private fun licence(packageId: Int, owner: Int, apps: List<Int>, vararg flags: ELicenseFlags) = SteamLicense(
        packageId = packageId,
        lastChangeNumber = 1,
        licenseFlags = if (flags.isEmpty()) EnumSet.noneOf(ELicenseFlags::class.java) else EnumSet.copyOf(flags.toList()),
        licenseType = ELicenseType.SinglePurchase,
        accessToken = 0L,
        ownerAccountId = listOf(owner),
        appIds = apps,
    )

    // The account is 7; 9 is a family member who lends games.
    private val licences = listOf(
        licence(SteamOwnership.FREE_SUB, 7, listOf(100, 101, 102)),
        licence(10, 7, listOf(1, 2)),
        licence(11, 9, listOf(2, 3)),
        licence(12, 7, listOf(4), ELicenseFlags.Expired),
        licence(13, 7, listOf(5), ELicenseFlags.CancelledByUser),
        licence(14, 7, listOf(60)),
    )

    @Test
    fun `own licences own, a family member's lend, and the free sub and ended licences grant nothing`() {
        val ownership = SteamOwnership.of(licences, accountId = 7)
        assertEquals(setOf(1, 2, 60), ownership.own)
        // A game both own and lent is the account's own.
        assertEquals(setOf(3), ownership.family)
        assertEquals(SteamOwnership.Status.OWN, ownership.statusOf(1))
        assertEquals(SteamOwnership.Status.FAMILY, ownership.statusOf(3))
        for (app in listOf(4, 5, 100, 101)) assertEquals("$app", SteamOwnership.Status.NONE, ownership.statusOf(app))
        // A free-to-start game is owned through its DLC.
        assertEquals(SteamOwnership.Status.OWN, ownership.statusOf(6, dlc = listOf(60)))
        // With no account id known, every live licence is the account's own.
        assertEquals(setOf(1, 2, 3, 60), SteamOwnership.of(licences, accountId = null).own)
    }

    @Test
    fun `a sync summary counts licences, the free sub, and apps by whose licence grants them`() {
        val kinds = listOf(
            SteamAppKind(1, AppType.game.code, SteamIds.INVALID_APP_ID),
            SteamAppKind(2, AppType.game.code, SteamIds.INVALID_APP_ID),
            SteamAppKind(3, AppType.game.code, SteamIds.INVALID_APP_ID),
            SteamAppKind(4, AppType.game.code, SteamIds.INVALID_APP_ID),
            SteamAppKind(5, AppType.dlc.code, 1),
            SteamAppKind(60, AppType.dlc.code, 6),
            SteamAppKind(6, AppType.game.code, SteamIds.INVALID_APP_ID),
            SteamAppKind(100, AppType.game.code, SteamIds.INVALID_APP_ID),
            SteamAppKind(101, AppType.demo.code, SteamIds.INVALID_APP_ID),
            SteamAppKind(102, AppType.invalid.code, SteamIds.INVALID_APP_ID),
        )
        val line = SteamLibrarySync.summary(
            licences = emptyList(),
            stored = licences,
            accountId = 7,
            billing = mapOf(12 to 2, 1 to 4),
            kinds = kinds,
            ownership = SteamOwnership.of(licences, accountId = 7),
        )
        assertEquals(
            "steam sync: 0 licences (payment: none; flags: CancelledByUser 1, Expired 1; another account's 1; " +
                "free sub held, 3 apps); packages by billing type: 1 4, 12 2; " +
                "own apps by type: game 2, dlc 1; family apps by type: game 1; " +
                "only in the free sub or ended licences: game 2, demo 1, dlc 1, no product info 1; " +
                "library games: own 3, family 1",
            line,
        )
    }

    @Test
    fun `GameNative's reading of a missing dlcforappid, 0, is turned into none`() {
        assertEquals(
            "UPDATE steam_app SET dlc_for_app_id = ${SteamIds.INVALID_APP_ID} WHERE dlc_for_app_id = 0",
            SteamDatabase.NO_BASE_GAME_FROM_GAMENATIVE,
        )
    }

    @Test
    fun `an Amazon entitlement is a game unless its product line names an entitlement`() {
        assertTrue(AmazonStore.isGame("""{"id":"a","productLine":"Twitch:FuelGame"}"""))
        assertTrue(AmazonStore.isGame("""{"id":"a"}"""))
        assertFalse(AmazonStore.isGame("""{"id":"a","productLine":"Twitch:FuelEntitlement"}"""))
    }
}
