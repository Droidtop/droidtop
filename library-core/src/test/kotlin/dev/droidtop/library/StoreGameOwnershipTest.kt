package dev.droidtop.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The real bug behind these: a store-installed Ren'Py game appeared
 * TWICE in the library, once from [EngineGameProvider] (routed to
 * enginehost) and once from `PcGameProvider` (routed past engine
 * detection entirely, to Wine). docs/SPEC.md 7g.
 *
 * The rule under test is [GameEngineDetector.engineOwnsInstall], which
 * both providers call, plus the merge that keeps the suppressed entry's
 * information alive on the surviving one. Both are deliberately pure and
 * `Context`-free so they are provable here in plain JVM unit tests --
 * the providers themselves need a real `Context` and could only be
 * covered by an instrumented test that CI does not run.
 */
class StoreGameOwnershipTest {
    @get:Rule
    val tmp = TemporaryFolder()

    // The REAL shipped seed registry, same as GameEngineDetectorTest:
    // a registry edit that stops recognising Ren'Py would fail here too.
    private val defs = EngineRegistryParser.parse(
        File("src/main/assets/engines-database.json").readText(),
    )

    private fun dir(vararg segments: String): File =
        segments.fold(tmp.root) { parent, seg -> File(parent, seg) }.also { it.mkdirs() }

    private fun file(dir: File, name: String): File =
        File(dir, name).also { it.parentFile?.mkdirs(); it.createNewFile() }

    /** A store install shaped like a real Ren'Py game: `renpy/` + `game/`. */
    private fun renpyInstall(name: String): File = dir("steamapps", "common", name).also { root ->
        file(root, "renpy/.keep")
        file(root, "game/script.rpyc")
    }

    private fun storeInstall(
        installDir: File,
        storeId: String = "steam:440",
        source: String = "Steam",
        artworkUri: String? = "https://cdn.example/steam/440.jpg",
    ) = StoreInstall(
        installDir = installDir,
        pcInfo = PcInfo(
            source = source,
            storeId = storeId,
            installed = true,
            sizeBytes = 1_234_567L,
            installPath = installDir.absolutePath,
            compatibility = PcCompatibility(
                averageRating = 4.5f,
                playableReports = 3,
                gpuPlayableReports = 2,
                hasBeenTried = true,
                reportedNotWorking = false,
            ),
        ),
        artworkUri = artworkUri,
    )

    @Test
    fun `engine detection owns a store-installed engine game, so the pc entry is suppressed`() {
        val install = renpyInstall("Some VN")

        assertTrue(GameEngineDetector.engineOwnsInstall(install, defs))
        assertEquals(GameEngine.RENPY, GameEngineDetector.detectGame(install, defs)?.engine)
    }

    @Test
    fun `a Windows-only game is not engine-owned, so it keeps its pc entry and its Wine route`() {
        val install = dir("steamapps", "common", "Some Windows Game")
        file(install, "Game.exe")
        file(install, "d3dx9_43.dll")

        assertFalse(GameEngineDetector.engineOwnsInstall(install, defs))
        assertNull(GameEngineDetector.detectGame(install, defs))
        // So the pc entry survives, and the route it takes is the Wine
        // one: PcGameProvider.launchStoreGame resolves the executable
        // through exactly this call before handing it to the runtime.
        assertEquals("Game.exe", GameExecutableResolver.windowsExecutable(install)?.name)
        assertNull(GameExecutableResolver.linuxExecutable(install))
    }

    @Test
    fun `an engine game wrapped in a version folder is still owned by the engine`() {
        // A real, confirmed download shape: the markers sit one folder
        // deeper than the nicely-named folder the library shows.
        val install = dir("steamapps", "common", "Wrapped VN")
        val inner = dir("steamapps", "common", "Wrapped VN", "WrappedVN-1.2-pc")
        file(inner, "renpy/.keep")
        file(inner, "game/script.rpyc")

        assertTrue(GameEngineDetector.engineOwnsInstall(install, defs))
        val detected = GameEngineDetector.detectGame(install, defs)
        assertNotNull(detected)
        // The outer folder stays the entry's identity; only the game root moves.
        assertEquals(install, detected!!.displayFolder)
        assertEquals(inner, detected.gameRoot)
    }

    @Test
    fun `a nested search picks the same folder every scan regardless of listFiles order`() {
        val install = dir("steamapps", "common", "Two Inners")
        for (name in listOf("zzz-build", "aaa-build")) {
            val inner = dir("steamapps", "common", "Two Inners", name)
            file(inner, "renpy/.keep")
            file(inner, "game/script.rpyc")
        }

        // Name-ordered, not filesystem-ordered: the lowest name wins, and
        // keeps winning. Discovery-order non-determinism has bitten this
        // project before, so this is asserted rather than assumed.
        assertEquals("aaa-build", GameEngineDetector.detectGame(install, defs)?.gameRoot?.name)
    }

    @Test
    fun `a non-store engine game is untouched by any of this`() {
        val sdCard = dir("sdcard", "Games", "Some Folder Game")
        file(sdCard, "renpy/.keep")
        file(sdCard, "game/script.rpyc")

        val entry = LibraryEntry(
            id = sdCard.absolutePath,
            title = sdCard.name,
            kind = LibraryEntryKind.RENPY,
        )
        // No store knows about it, so byInstallDir has nothing for it.
        val merged = entry.withStoreInstall(emptyList<StoreInstall>().byInstallDir().forFolder(sdCard))

        assertEquals(entry, merged)
        assertNull(merged.pcInfo)
        assertEquals(LibraryEntryKind.RENPY, merged.kind)
    }

    @Test
    fun `the surviving engine entry keeps everything the suppressed pc entry knew`() {
        val install = renpyInstall("Some VN")
        val entry = LibraryEntry(
            id = install.absolutePath,
            title = install.name,
            kind = LibraryEntryKind.RENPY,
        )

        val merged = entry.withStoreInstall(
            listOf(storeInstall(install)).byInstallDir().forFolder(install),
        )

        // Identity and routing stay the engine entry's.
        assertEquals(entry.id, merged.id)
        assertEquals(entry.title, merged.title)
        assertEquals(LibraryEntryKind.RENPY, merged.kind)
        assertNull(merged.systemId)
        // Everything only the pc entry knew comes with it.
        val pc = merged.pcInfo!!
        assertEquals("Steam", pc.source)
        assertEquals("steam:440", pc.storeId)
        assertTrue(pc.installed)
        assertEquals(1_234_567L, pc.sizeBytes)
        assertEquals(install.absolutePath, pc.installPath)
        assertEquals(3, pc.compatibility!!.playableReports)
        // No local artwork, so the store's cover is used rather than lost.
        assertEquals("https://cdn.example/steam/440.jpg", merged.artworkUri)
    }

    @Test
    fun `local artwork already resolved on disk wins over the store cover`() {
        val install = renpyInstall("Some VN")
        val local = file(install, "cover.png").absolutePath
        val entry = LibraryEntry(
            id = install.absolutePath,
            title = install.name,
            kind = LibraryEntryKind.RENPY,
            artworkUri = local,
        )

        val merged = entry.withStoreInstall(storeInstall(install))

        assertEquals(local, merged.artworkUri)
        assertEquals("steam:440", merged.pcInfo?.storeId)
    }

    @Test
    fun `two stores naming the same install directory resolve identically either way round`() {
        val install = renpyInstall("Owned Twice")
        val steam = storeInstall(install, storeId = "steam:440", source = "Steam")
        val gog = storeInstall(install, storeId = "gog:12345", source = "GOG")
        // Same winner whichever order the DAOs happened to return them in.
        assertEquals("gog:12345", listOf(steam, gog).byInstallDir().forFolder(install)?.pcInfo?.storeId)
        assertEquals("gog:12345", listOf(gog, steam).byInstallDir().forFolder(install)?.pcInfo?.storeId)
    }

    @Test
    fun `a trailing separator on a store install path still matches the scanned folder`() {
        // A store row's installPath may carry one; a scanned directory
        // never does, and the two have to land on the same key.
        val install = renpyInstall("Some VN")

        assertEquals(
            install.absolutePath.asInstallKey(),
            (install.absolutePath + File.separator).asInstallKey(),
        )
        assertNull("".asInstallKey())
        assertNull("   ".asInstallKey())
    }
}
