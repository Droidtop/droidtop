package dev.droidtop.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The user's yardstick item 5 (/root/coordination/DECISIONS.md 2026-09-10
 * 17:18), end to end over the pure halves of the path: a game installed by
 * a STORE whose folder detection classifies as an engine game shows up as
 * ONE entry carrying the store's own facts, runs on the enginehost runner
 * for that engine rather than Wine, says so in the "Runs with" row, and
 * hands enginehost the store's install folder.
 *
 * Every step below is the same function the real providers call, in the
 * order they call it -- `EngineGameProvider.scan` (detection over the
 * store root, then the store merge), `PcRunnerOptions.forEntry`
 * (availability), `RunnerAvailability.resolve` (the row), and
 * `EngineGameProvider.launch` (the folder handed to `EngineHost.launch`).
 * The two that need a real `Context` are represented by the pure function
 * they delegate to, since CI runs no instrumented tests.
 */
class StoreEngineGameTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val defs = EngineRegistryParser.parse(SeedAssets.read("engines-database.json"))

    /**
     * A Steam install of a Ren'Py game, exactly as one looks on disk: the
     * store's own `steamapps/common/<game>` directory, the engine's
     * `renpy/` runtime and `game/` data, and the Windows launcher the
     * store shipped (which is what makes "enginehost, not Wine" a real
     * choice rather than the only option there is).
     */
    private fun steamRenpyInstall(): File {
        val install = File(tmp.root, "steamapps/common/Some Store VN")
        File(install, "renpy").mkdirs()
        File(install, "game").mkdirs()
        File(install, "game/script.rpyc").createNewFile()
        File(install, "SomeStoreVN.exe").createNewFile()
        return install
    }

    private fun storeInstall(install: File) = StoreInstall(
        installDir = install,
        pcInfo = PcInfo(
            source = "Steam",
            storeId = "steam:730",
            installed = true,
            sizeBytes = 2_500_000_000L,
            installPath = install.absolutePath,
            compatibility = PcCompatibility(
                averageRating = 4.0f,
                playableReports = 7,
                gpuPlayableReports = 4,
                hasBeenTried = true,
                reportedNotWorking = false,
            ),
        ),
        artworkUri = "https://cdn.example/steam/730.jpg",
    )

    /** The facts `PcRunnerOptions.forEntry` measures for this install on a console with enginehost on it. */
    private fun factsFor(install: File, engine: GameEngine?) = GameLaunchStrategyResolver.facts(
        engine = engine,
        folder = install,
        engineHostInstalled = true,
        engineHostEngineVersionKnown = true,
        windowsEnvironmentReady = true,
        wineRendererWired = RunnerAvailability.WINE_RENDERER_WIRED,
        linuxContainerAvailable = false,
        preferredOrder = defs.first { it.engine == GameEngine.RENPY }.strategies,
    )

    @Test
    fun `a store-installed engine game is ONE entry with the store's facts on it`() {
        val install = steamRenpyInstall()
        val installs = listOf(storeInstall(install))

        // EngineGameProvider.scan's own roots rule: a store install
        // directory is a child of the root detection walks.
        val root = install.parentFile!!
        val detected = GameEngineDetector.scan(root, emptyMap(), defs)

        assertEquals(1, detected.size)
        val game = detected.single()
        assertEquals(install, game.displayFolder)
        assertEquals(GameEngine.RENPY, game.engine)

        val entry = LibraryEntry(
            id = game.displayFolder.absolutePath,
            title = game.displayFolder.name,
            kind = game.engine.toLibraryEntryKind(),
        ).withStoreInstall(installs.byInstallDir().forFolder(game.displayFolder))

        // One entry, routed by the engine, carrying what only the store knew.
        assertEquals(LibraryEntryKind.RENPY, entry.kind)
        assertEquals("Steam", entry.pcInfo?.source)
        assertEquals("steam:730", entry.pcInfo?.storeId)
        assertTrue(entry.pcInfo!!.installed)
        assertEquals(2_500_000_000L, entry.pcInfo!!.sizeBytes)
        assertEquals(install.absolutePath, entry.pcInfo!!.installPath)
        assertEquals(7, entry.pcInfo!!.compatibility!!.playableReports)
        assertEquals("https://cdn.example/steam/730.jpg", entry.artworkUri)
        // And no second `pc` entry for the same folder.
        assertTrue(GameEngineDetector.engineOwnsInstall(install, defs))
    }

    @Test
    fun `it runs on enginehost, and the Runs-with row says which engine`() {
        val install = steamRenpyInstall()
        val options = RunnerAvailability.evaluate(factsFor(install, GameEngine.RENPY))

        val resolved = RunnerAvailability.resolve(
            options,
            override = null,
            engine = GameEngine.RENPY,
            defaultLabel = LibraryEntryKind.RENPY.displayName(),
        )!!

        assertEquals(GameLaunchStrategy.ENGINEHOST, resolved.option.strategy)
        assertEquals(RunnerState.READY, resolved.option.state)
        assertEquals("enginehost (Ren'Py)", resolved.label)
        // Wine is a real row for this game -- the store shipped an .exe --
        // and it still loses, which is the point of the yardstick.
        val wine = options.first { it.strategy == GameLaunchStrategy.WINE_PREFIX }
        assertEquals(RunnerState.NEEDS_SETUP, wine.state)
        assertTrue(options.indexOf(wine) > options.indexOf(resolved.option))
    }

    @Test
    fun `the folder handed to enginehost's LAUNCH is the store's install folder`() {
        val install = steamRenpyInstall()

        // EngineGameProvider.launch resolves the folder through
        // detectGame (its resolveEntry) and passes gameRoot to
        // EngineHost.launch, which puts it in the intent's "path" extra.
        val resolved = GameEngineDetector.detectGame(File(install.absolutePath), defs)!!

        assertEquals(install, resolved.gameRoot)
        assertEquals(install, resolved.displayFolder)
    }

    @Test
    fun `a store-installed Windows game is untouched by any of this`() {
        val install = File(tmp.root, "steamapps/common/Some Windows Game")
        install.mkdirs()
        File(install, "Game.exe").createNewFile()

        val options = RunnerAvailability.evaluate(factsFor(install, engine = null))
        val resolved = RunnerAvailability.resolve(options, override = null, engine = null)!!

        assertEquals(GameLaunchStrategy.WINE_PREFIX, resolved.option.strategy)
        assertEquals("Wine", resolved.label)
        assertEquals(
            RunnerState.NOT_FOR_THIS_GAME,
            options.first { it.strategy == GameLaunchStrategy.ENGINEHOST }.state,
        )
    }
}
