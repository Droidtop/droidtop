package dev.droidtop.runtime.windows

import android.content.Context
import app.gamenative.data.AmazonGame
import app.gamenative.data.EpicGame
import app.gamenative.data.GOGGame
import app.gamenative.data.GameSource
import app.gamenative.data.LibraryItem
import app.gamenative.data.SteamApp
import app.gamenative.service.SteamService
import app.gamenative.utils.CustomGameScanner
import app.gamenative.utils.GameCompatibilityCache
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.droidtop.library.PcCompatibility
import dev.droidtop.library.PcInfo
import dev.droidtop.library.StoreInstall
import java.io.File

/**
 * droidtop's OWN source-agnostic view of every PC game the vendored
 * gamenative-tux backend knows about — Steam, GOG, Epic, Amazon, and
 * loose folders — behind one shape.
 *
 * The point of this class, and the audit finding that produced it
 * (docs/SPEC.md §7g): droidtop compiled 830 gamenative source files and
 * referenced eight symbols, all of them Steam. Complete GOG, Epic and
 * Amazon services and a loose-Windows-game folder scanner were built,
 * tested, and unreachable, purely because [SteamAccess] wrapped one
 * service and `PcGameProvider` read Wine container shortcuts. Nothing
 * here is new capability; it is the wiring that was missing.
 *
 * Per standing direction this consumes gamenative's SERVICES and data
 * layer only — never its UI. `LibraryViewModel`, `LibraryAppItem` and
 * friends stay entirely out of droidtop's way; droidtop renders its own.
 *
 * A user should not have to care which store a game came from. Source is
 * a fact about a game, worth showing and worth filtering on, but it is
 * never a separate screen.
 */
object PcLibrary {

    /**
     * Where a game came from. Mirrors gamenative's own `GameSource` (the
     * unified model already in the fork) rather than inventing a second
     * vocabulary, but is droidtop's own type so library-core and the
     * shells never import `app.gamenative.*`.
     */
    enum class Source { STEAM, GOG, EPIC, AMAZON, FOLDER }

    /**
     * Community compatibility reports for a title, from gamenative's own
     * `api.gamenative.app/api/game-runs` service.
     *
     * **Reference, never a gate** (directed 2026-09-01). droidtop shows
     * this and lets the user decide; it must never block a download, hide
     * an entry, or reorder a library on its own. A rating is other
     * people's experience on other hardware, which is useful information
     * and a bad decision-maker.
     */
    data class Compatibility(
        val averageRating: Float,
        val playableReports: Int,
        val gpuPlayableReports: Int,
        val hasBeenTried: Boolean,
        val reportedNotWorking: Boolean,
    )

    /** One PC game, whatever it came from. */
    data class Game(
        /** Stable across scans and unique across sources: `"steam:440"`. */
        val id: String,
        val source: Source,
        /** The id this game's own store/scanner uses, unprefixed. */
        val nativeId: String,
        val title: String,
        val installed: Boolean,
        val installPath: String?,
        /** On-disk size when installed, download size when not, 0 when unknown. */
        val sizeBytes: Long,
        val artUrl: String?,
        val compatibility: Compatibility?,
    ) {
        val installDir: File? get() = installPath?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isDirectory }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface StoreDaoEntryPoint {
        fun steamAppDao(): app.gamenative.db.dao.SteamAppDao
        fun gogGameDao(): app.gamenative.db.dao.GOGGameDao
        fun epicGameDao(): app.gamenative.db.dao.EpicGameDao
        fun amazonGameDao(): app.gamenative.db.dao.AmazonGameDao
    }

    private fun daos(context: Context): StoreDaoEntryPoint =
        EntryPointAccessors.fromApplication(context.applicationContext, StoreDaoEntryPoint::class.java)

    /**
     * Every PC game from every source, owned and installed alike.
     *
     * A store the user never signed into simply contributes nothing — its
     * Room tables exist and are empty — so this needs no "is GOG enabled"
     * configuration and grows a source the moment somebody signs in.
     * Each source is read independently: one store's failure (a corrupt
     * row, a schema drift after a vendor sync) costs that store's games,
     * not the whole library.
     */
    suspend fun allGames(context: Context): List<Game> {
        val dao = daos(context)
        return buildList {
            addAll(runCatching { dao.steamAppDao().getAllOwnedAppsAsList().map { it.toGame() } }.getOrDefault(emptyList()))
            addAll(runCatching { dao.gogGameDao().getAllAsList().map { it.toGame() } }.getOrDefault(emptyList()))
            addAll(runCatching { dao.epicGameDao().getAllAsList().map { it.toGame() } }.getOrDefault(emptyList()))
            addAll(runCatching { dao.amazonGameDao().getAllAsList().map { it.toGame() } }.getOrDefault(emptyList()))
            addAll(
                runCatching {
                    CustomGameScanner.scanAsLibraryItems()
                        // The scanner recognizes a Steam install sitting in a
                        // scanned folder and returns it as a STEAM item; that
                        // game already came from the Steam DAO above, so taking
                        // both would list it twice.
                        .filter { it.gameSource == GameSource.CUSTOM_GAME }
                        .map { it.toGame() }
                }.getOrDefault(emptyList()),
            )
        }.sortedBy { it.title.lowercase() }
            // Recording the installs here, in the one place every
            // source's install directories are already known, is what
            // keeps [knownInstalls]/[knownInstallRoots] answerable
            // synchronously. It used to be a separate
            // `installRoots(context)` entry point that nothing ever
            // called, so the store half of that list stayed empty forever
            // and only Steam's own paths reached engine detection.
            .also { games -> storeInstalls = games.mapNotNull { it.toStoreInstall() } }
    }


    /** Only what is actually on this device — what the library grid shows. */
    suspend fun installedGames(context: Context): List<Game> = allGames(context).filter { it.installed }

    /**
     * Every directory any source installs games under. droidtop's engine
     * detection scans these, so a Ren'Py or RPG Maker game installed from
     * GOG flows through the SAME detection, grouping and launch-strategy
     * resolution as one sitting in a games folder — enginehost included.
     * That is the whole reason this returns roots rather than a store's
     * own launch command.
     */
    /**
     * Install roots discovered by the most recent [allGames] call, plus
     * Steam's own paths (which the service can answer synchronously).
     *
     * Synchronous because engine detection's `extraRoots` hook is, and
     * must not block a scan thread on four Room queries. The store half
     * is therefore one scan behind on a cold start — a GOG-installed
     * Ren'Py game is detected on the second scan, not the first — which
     * is the right trade against stalling every scan.
     */
    fun knownInstallRoots(): List<File> {
        val steam = runCatching { SteamService.allInstallPaths }.getOrDefault(emptyList()).map(::File)
        // The PARENT of each game's install directory, not the directory
        // itself. Engine detection reads a root's children as candidate
        // game folders (see `GameEngineDetector.scan`), so handing it a
        // game's own folder points it one level too deep and it finds
        // nothing there. Steam's own paths already arrive as library
        // roots, which is why a Steam-installed Ren'Py game was detected
        // and a GOG one would not have been even once this was populated.
        val storeRoots = storeInstalls.mapNotNull { it.installDir.parentFile }
        return (steam + storeRoots)
            .filter { it.isDirectory }
            .distinctBy { it.absolutePath }
    }

    /**
     * The installs behind [knownInstallRoots], with the store's own facts
     * attached. Engine detection takes these so that a store-installed
     * engine game keeps its source, size, compatibility and cover art
     * after its duplicate `pc` entry is suppressed.
     *
     * Same one-scan-behind caveat as [knownInstallRoots], and for the
     * same reason: it must answer without blocking a scan thread on four
     * Room queries.
     */
    fun knownInstalls(): List<StoreInstall> = storeInstalls.filter { it.installDir.isDirectory }

    @Volatile
    private var storeInstalls: List<StoreInstall> = emptyList()

    /**
     * Cached community compatibility only — no network call. Scanning a
     * library must not depend on a reachable server or a signed-in
     * account, so an entry simply carries no rating until something else
     * has populated the cache.
     */
    private fun compatibilityFor(title: String): Compatibility? =
        runCatching { GameCompatibilityCache.getCached(title) }.getOrNull()?.let { response ->
            Compatibility(
                averageRating = response.avgRating,
                playableReports = response.totalPlayableCount,
                gpuPlayableReports = response.gpuPlayableCount,
                hasBeenTried = response.hasBeenTried,
                reportedNotWorking = response.isNotWorking,
            )
        }

    private fun SteamApp.toGame(): Game = Game(
        id = "steam:$id",
        source = Source.STEAM,
        nativeId = id.toString(),
        title = name,
        installed = runCatching { SteamService.isAppInstalled(id) }.getOrDefault(false),
        // Steam's own row carries no install path; the service resolves it.
        installPath = runCatching { SteamService.getAppDirPath(id) }.getOrNull(),
        // SteamApp models no size on disk, and walking the install tree on
        // every scan would cost more than the number is worth.
        sizeBytes = 0L,
        artUrl = clientIconUrl.takeIf { clientIconHash.isNotEmpty() },
        compatibility = compatibilityFor(name),
    )

    private fun GOGGame.toGame(): Game = Game(
        id = "gog:$id",
        source = Source.GOG,
        nativeId = id,
        title = title,
        installed = isInstalled,
        installPath = installPath,
        sizeBytes = if (isInstalled) installSize else downloadSize,
        artUrl = verticalCoverUrl.ifEmpty { imageUrl }.ifEmpty { iconUrl }.takeIf { it.isNotEmpty() },
        compatibility = compatibilityFor(title),
    )

    private fun EpicGame.toGame(): Game = Game(
        // Epic's own primary key is catalogId; the integer id is a local
        // tracking number, so the stable identity is the catalog one.
        id = "epic:$catalogId",
        source = Source.EPIC,
        nativeId = catalogId,
        title = title,
        installed = isInstalled,
        installPath = installPath,
        sizeBytes = installSize,
        artUrl = iconUrl.takeIf { it.isNotEmpty() },
        compatibility = compatibilityFor(title),
    )

    private fun AmazonGame.toGame(): Game = Game(
        id = "amazon:$productId",
        source = Source.AMAZON,
        nativeId = productId,
        title = title,
        installed = isInstalled,
        installPath = installPath,
        sizeBytes = if (isInstalled) installSize else downloadSize,
        artUrl = artUrl.takeIf { it.isNotEmpty() },
        compatibility = compatibilityFor(title),
    )

    /**
     * A loose folder of files the user pointed droidtop at — a GOG
     * offline installer's output, an itch download, a portable game.
     * These are "installed" by definition: the files are already there.
     */
    private fun LibraryItem.toGame(): Game {
        // The scanner's appId is "CUSTOM_GAME_<numeric id>"; the numeric
        // half is what resolves back to a folder.
        val numericId = appId.substringAfterLast('_').toIntOrNull()
        val folderPath = numericId?.let { id -> runCatching { CustomGameScanner.findCustomGameById(id) }.getOrNull() }
        // A custom game has no store CDN behind it, so its art is whatever
        // image sits in the folder rather than a remote URL.
        val localArt = runCatching {
            CustomGameScanner.findCapsuleCoverForCustomGame(appId)
                ?: CustomGameScanner.findIconFileForCustomGame(appId)
        }.getOrNull()
        return Game(
            id = "folder:$appId",
            source = Source.FOLDER,
            nativeId = appId,
            title = name,
            installed = true,
            installPath = folderPath,
            sizeBytes = sizeBytes,
            artUrl = localArt,
            compatibility = compatibilityFor(name),
        )
    }
}

/**
 * This game as the shape engine detection consumes, or null when it
 * is not actually installed anywhere on this device.
 *
 * Detection needs the directory; the rest of it is what keeps the
 * game's store-side information alive once the engine entry claims
 * that directory and the `pc` entry is suppressed (see
 * `GameEngineDetector.engineOwnsInstall`).
 */
fun PcLibrary.Game.toStoreInstall(): StoreInstall? = installDir?.let { dir ->
    StoreInstall(installDir = dir, pcInfo = toPcInfo(), artworkUri = artUrl)
}

/**
 * The store-side facts a [dev.droidtop.library.LibraryEntry] carries.
 *
 * Built here rather than in `PcGameProvider` because both an entry it
 * returns itself and a [StoreInstall] handed to engine detection need
 * exactly the same values; two mappings would be two chances for a
 * merged entry to disagree with the one it replaced.
 */
fun PcLibrary.Game.toPcInfo(): PcInfo = PcInfo(
    source = source.displayName(),
    storeId = id,
    installed = installed,
    sizeBytes = sizeBytes,
    installPath = installPath,
    compatibility = compatibility?.let {
        PcCompatibility(
            averageRating = it.averageRating,
            playableReports = it.playableReports,
            gpuPlayableReports = it.gpuPlayableReports,
            hasBeenTried = it.hasBeenTried,
            reportedNotWorking = it.reportedNotWorking,
        )
    },
)

fun PcLibrary.Source.displayName(): String = when (this) {
    PcLibrary.Source.STEAM -> "Steam"
    PcLibrary.Source.GOG -> "GOG"
    PcLibrary.Source.EPIC -> "Epic"
    PcLibrary.Source.AMAZON -> "Amazon"
    PcLibrary.Source.FOLDER -> "Folder"
}
