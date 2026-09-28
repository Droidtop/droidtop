package dev.droidtop.runtime.windows

import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import app.gamenative.data.SteamApp
import app.gamenative.data.GOGGame
import app.gamenative.data.EpicGame
import app.gamenative.data.AmazonGame
import app.gamenative.data.ItchGame
import app.gamenative.data.SteamApp.DepotInfo
import app.gamenative.data.SteamApp.BranchInfo
import app.gamenative.data.SteamApp.LibraryAssetsInfo
import app.gamenative.data.SteamApp.LibraryCapsuleInfo
import app.gamenative.data.SteamApp.LibraryHeroInfo
import app.gamenative.data.SteamApp.LibraryImageInfo
import app.gamenative.data.SteamApp.ELicenseFlags
import app.gamenative.data.SteamApp.AppType
import app.gamenative.data.SteamApp.OS
import app.gamenative.data.SteamApp.ReleaseState
import app.gamenative.data.SteamApp.ControllerSupport
import app.gamenative.data.SteamApp.Language
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for PcLibrary's store-game conversion and merge/filter logic.
 *
 * These tests verify that each store's data model maps correctly to
 * droidtop's source-agnostic [PcLibrary.Game] type, and that the
 * LibraryItem conversions used by the store screens preserve the
 * expected fields.
 */
class PcLibraryTest {

    /** A minimal SteamApp for testing. */
    private fun steamApp(id: Int, name: String): SteamApp {
        return SteamApp(
            id = id,
            name = name,
            clientIconHash = "icon_$id",
            // Other fields use defaults
        )
    }

    /** A minimal GOGGame for testing. */
    private fun gogGame(id: String, title: String): GOGGame {
        return GOGGame(
            id = id,
            title = title,
            isInstalled = false,
            installPath = "",
            installSize = 0,
            downloadSize = 1_000_000,
            verticalCoverUrl = "cover_$id",
            imageUrl = "",
            iconUrl = "",
        )
    }

    /** A minimal EpicGame for testing. */
    private fun epicGame(catalogId: String, title: String): EpicGame {
        return EpicGame(
            id = 1,
            catalogId = catalogId,
            title = title,
            isInstalled = false,
            installPath = "",
            installSize = 2_000_000,
            artCover = "art_cover_$catalogId",
            artSquare = "art_square_$catalogId",
            artPortrait = "art_portrait_$catalogId",
            // Other fields use defaults
        )
    }

    /** A minimal AmazonGame for testing. */
    private fun amazonGame(productId: String, title: String): AmazonGame {
        return AmazonGame(
            appId = 1,
            productId = productId,
            title = title,
            isInstalled = false,
            installPath = "",
            installSize = 3_000_000,
            downloadSize = 3_000_000,
            artUrl = "art_$productId",
            heroUrl = "",
        )
    }

    /** A minimal ItchGame for testing. */
    private fun itchGame(id: String, title: String): ItchGame {
        return ItchGame(
            id = id,
            title = title,
            downloadKeyId = "key_$id",
            coverUrl = "cover_$id",
            url = "https://user.itch.io/$title",
            isInstalled = false,
            installPath = "",
            sizeBytes = 4_000_000,
        )
    }

    @Test
    fun `SteamApp converts to Game with steam prefix and correct fields`() {
        val app = steamApp(440, "Team Fortress 2")
        val game = PcLibrary.SteamApp.toGame(app)

        assertEquals("steam:440", game.id)
        assertEquals(PcLibrary.Source.STEAM, game.source)
        assertEquals("440", game.nativeId)
        assertEquals("Team Fortress 2", game.title)
        assertEquals("https://steamcdn-a.akamaihd.net/steamcommunity/public/images/apps/440/icon_440.ico", game.artUrl)
    }

    @Test
    fun `GOGGame converts to Game with gog prefix and correct fields`() {
        val game_ = gogGame("12345", "The Witcher 3")
        val game = PcLibrary.GOGGame.toGame(game_)

        assertEquals("gog:12345", game.id)
        assertEquals(PcLibrary.Source.GOG, game.source)
        assertEquals("12345", game.nativeId)
        assertEquals("The Witcher 3", game.title)
        assertEquals("https://images.gog.com/cover_12345", game.artUrl)
        assertEquals(1_000_000, game.sizeBytes)
    }

    @Test
    fun `EpicGame converts to Game with epic prefix and catalogId as nativeId`() {
        val game_ = epicGame("catalog_678", "Fortnite")
        val game = PcLibrary.EpicGame.toGame(game_)

        assertEquals("epic:catalog_678", game.id)
        assertEquals(PcLibrary.Source.EPIC, game.source)
        assertEquals("catalog_678", game.nativeId)
        assertEquals("Fortnite", game.title)
        assertEquals("art_cover_catalog_678", game.artUrl)
        assertEquals(2_000_000, game.sizeBytes)
    }

    @Test
    fun `AmazonGame converts to Game with amazon prefix and productId as nativeId`() {
        val game_ = amazonGame("prod_999", "New World")
        val game = PcLibrary.AmazonGame.toGame(game_)

        assertEquals("amazon:prod_999", game.id)
        assertEquals(PcLibrary.Source.AMAZON, game.source)
        assertEquals("prod_999", game.nativeId)
        assertEquals("New World", game.title)
        assertEquals("art_prod_999", game.artUrl)
        assertEquals(3_000_000, game.sizeBytes)
    }

    @Test
    fun `ItchGame converts to Game with itch prefix and correct fields`() {
        val game_ = itchGame("itch_111", "Celeste")
        val game = PcLibrary.ItchGame.toGame(game_)

        assertEquals("itch:itch_111", game.id)
        assertEquals(PcLibrary.Source.ITCH, game.source)
        assertEquals("itch_111", game.nativeId)
        assertEquals("Celeste", game.title)
        assertEquals("cover_itch_111", game.artUrl)
        assertEquals(4_000_000, game.sizeBytes)
    }

    @Test
    fun `SteamApp converts to LibraryItem with STEAM gameSource`() {
        val app = steamApp(440, "Team Fortress 2")
        val item = PcLibrary.SteamApp.toLibraryItem(app)

        assertEquals("STEAM_440", item.appId)
        assertEquals("Team Fortress 2", item.name)
        assertEquals(GameSource.STEAM, item.gameSource)
        assertTrue(item.iconHash.startsWith("icon_"))
    }

    @Test
    fun `GOGGame converts to LibraryItem with GOG gameSource`() {
        val game_ = gogGame("12345", "The Witcher 3")
        val item = PcLibrary.GOGGame.toLibraryItem(game_)

        assertEquals("GOG_12345", item.appId)
        assertEquals("The Witcher 3", item.name)
        assertEquals(GameSource.GOG, item.gameSource)
        assertEquals("https://images.gog.com/cover_12345", item.capsuleImageUrl)
    }

    @Test
    fun `EpicGame converts to LibraryItem with EPIC gameSource`() {
        val game_ = epicGame("catalog_678", "Fortnite")
        val item = PcLibrary.EpicGame.toLibraryItem(game_)

        assertEquals("EPIC_1", item.appId) // Uses integer row id, not catalogId
        assertEquals("Fortnite", item.name)
        assertEquals(GameSource.EPIC, item.gameSource)
        assertEquals("art_square_catalog_678", item.iconHash)
    }

    @Test
    fun `AmazonGame converts to LibraryItem with AMAZON gameSource`() {
        val game_ = amazonGame("prod_999", "New World")
        val item = PcLibrary.AmazonGame.toLibraryItem(game_)

        assertEquals("AMAZON_1", item.appId) // Uses integer row id
        assertEquals("New World", item.name)
        assertEquals(GameSource.AMAZON, item.gameSource)
        assertEquals("art_prod_999", item.iconHash)
    }

    @Test
    fun `ItchGame converts to LibraryItem with ITCH gameSource`() {
        val game_ = itchGame("itch_111", "Celeste")
        val item = PcLibrary.ItchGame.toLibraryItem(game_)

        assertEquals("ITCH_itch_111", item.appId)
        assertEquals("Celeste", item.name)
        assertEquals(GameSource.ITCH, item.gameSource)
        assertEquals("cover_itch_111", item.iconHash)
    }

    @Test
    fun `Source.displayName returns correct labels for all sources`() {
        assertEquals("Steam", PcLibrary.Source.STEAM.displayName())
        assertEquals("GOG", PcLibrary.Source.GOG.displayName())
        assertEquals("Epic", PcLibrary.Source.EPIC.displayName())
        assertEquals("Amazon", PcLibrary.Source.AMAZON.displayName())
        assertEquals("itch.io", PcLibrary.Source.ITCH.displayName())
        assertEquals("Folder", PcLibrary.Source.FOLDER.displayName())
    }

    @Test
    fun `STORE_ID_PREFIXES contains all expected store prefixes`() {
        val prefixes = PcGameProvider.STORE_ID_PREFIXES
        assertTrue("steam" in prefixes)
        assertTrue("gog" in prefixes)
        assertTrue("epic" in prefixes)
        assertTrue("amazon" in prefixes)
        assertTrue("itch" in prefixes)
        assertTrue("folder" in prefixes)
        assertEquals(6, prefixes.size)
    }

    @Test
    fun `Game.toPcInfo preserves source and storeId`() {
        val steamGame = PcLibrary.SteamApp.toGame(steamApp(440, "TF2"))
        val pcInfo = steamGame.toPcInfo()

        assertEquals("Steam", pcInfo.source)
        assertEquals("steam:440", pcInfo.storeId)
        assertFalse(pcInfo.installed)
        assertEquals(0, pcInfo.sizeBytes)
    }

    @Test
    fun `ItchGame.toPcInfo preserves itch.io fields`() {
        val itchGame_ = itchGame("itch_111", "Celeste")
        val game = PcLibrary.ItchGame.toGame(itchGame_)
        val pcInfo = game.toPcInfo()

        assertEquals("itch.io", pcInfo.source)
        assertEquals("itch:itch_111", pcInfo.storeId)
        assertEquals(4_000_000, pcInfo.sizeBytes)
    }
}