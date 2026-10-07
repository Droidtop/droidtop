package dev.droidtop.stores.steam

import android.content.Context
import dev.droidtop.library.PcStoreNames
import dev.droidtop.library.StoreUpdate
import dev.droidtop.library.stores.StoreGame
import dev.droidtop.library.stores.StoreLaunch
import dev.droidtop.library.stores.StoreLibrary
import dev.droidtop.library.stores.StoreProgress
import dev.droidtop.library.stores.StoreSignIn
import dev.droidtop.library.stores.StoreSignInKind
import dev.droidtop.library.stores.StoreUpdateCheck
import dev.droidtop.runtime.SafeDelete
import dev.droidtop.stores.epic.EpicGameLauncher
import dev.droidtop.stores.util.StoreFiles
import dev.droidtop.stores.util.StoreLanguage
import `in`.dragonbra.javasteam.types.DepotManifest
import `in`.dragonbra.javasteam.types.FileData
import java.io.File
import java.security.MessageDigest
import java.util.EnumSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Steam as a [StoreLibrary] (docs/SPEC.md 7g, "Stores"): droidtop's own
 * Steam client, the one GameNative ran (JavaSteam: sign-in by QR code or by
 * password with Steam Guard, the licence and product-info reads, depot
 * downloads), lifted into droidtop with droidtop's screens and jobs. It is
 * the default way droidtop reaches Steam; nothing of it needs Valve's own
 * client on the device.
 *
 * A game's id is its Steam app id ("steam:440"), the id the PC library has
 * always used for Steam, so play history, favourites and Wine prefixes made
 * for a Steam game stay with it.
 */
class SteamStore : StoreLibrary {
    override val id = "steam"
    override val label = PcStoreNames.STEAM
    override val signInKind = StoreSignInKind.ACCOUNT
    override val canVerify = true

    private fun db(context: Context) = SteamDatabase.get(context)

    override fun signedIn(context: Context): Boolean = SteamCredentials.exists(context)

    override fun accountName(context: Context): String? = SteamCredentials.load(context)?.accountName

    override fun signIn(context: Context): StoreSignIn = StoreSignIn.Account(SteamSignIn(context, this))

    /** Steam signs in step by step on its own screen ([signIn]); there is no code to finish with. */
    override suspend fun completeSignIn(context: Context, secret: String): Result<String?> =
        Result.failure(UnsupportedOperationException("Steam signs in on its own screen"))

    override suspend fun signOut(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            runCatching { SteamSession.logOff() }
            SteamCredentials.clear(context)
            val db = db(context)
            val installed = db.installs().all().filter { it.isDownloaded }.map { it.id }.toSet()
            db.licenses().deleteAll()
            db.cachedLicenses().deleteAll()
            // Installed games stay listed: their rows are kept, everything else goes.
            val keep = db.apps().knownIds(installed.toList().ifEmpty { listOf(-1) }).mapNotNull { db.apps().find(it) }
            db.apps().deleteAll()
            if (keep.isNotEmpty()) db.apps().insertAll(keep)
        }
    }

    override suspend fun sync(context: Context): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            SteamSession.use(context) {
                SteamSession.logOn(context).getOrThrow()
                val apps = SteamSession.apps ?: error("Steam is not connected")
                SteamLibrarySync.run(context, apps, SteamSession.licences())
            }
        }
    }

    override suspend fun games(context: Context): List<StoreGame> = withContext(Dispatchers.IO) {
        val db = db(context)
        val installs = db.installs().all().filter { it.isDownloaded && it.installPath.isNotBlank() }.associateBy { it.id }
        val owned = db.apps().owned(SteamLibrarySync.PLAYABLE_TYPES)
        val ownedIds = owned.mapTo(HashSet()) { it.id }
        // Installed and no longer owned (signed out, a licence gone) still shows, with its files.
        val installedOnly = installs.keys.filter { it !in ownedIds }
            .mapNotNull { db.apps().find(it) }
            // A DLC's own install row is not a game of its own.
            .filter { it.type.code in SteamLibrarySync.PLAYABLE_TYPES || (it.type == AppType.invalid && it.dlcForAppId == SteamIds.INVALID_APP_ID) }
        val language = StoreLanguage.current()
        (owned + installedOnly).filter { it.name.isNotBlank() }.map { app ->
            val install = installs[app.id]
            StoreGame(
                store = id,
                gameId = app.id.toString(),
                title = app.name,
                installed = install != null,
                installPath = install?.installPath,
                sizeBytes = baseSize(app, language),
                artUrl = app.coverUrl,
            )
        }
    }

    /** The size of the base game's files in [language], from product info alone (no disk). */
    private fun baseSize(app: SteamApp, language: String): Long {
        val base = app.depots.filterValues { it.dlcAppId == SteamIds.INVALID_APP_ID }
        return SteamDepots.installBytes(SteamDepots.resolve(base, language, base, null).values, SteamDownload.BRANCH)
    }

    override suspend fun install(context: Context, gameId: String, root: File, progress: StoreProgress): String {
        val appId = gameId.toIntOrNull() ?: error("$gameId is not a Steam app id")
        return SteamSession.use(context) {
            SteamSession.logOn(context).getOrThrow()
            val apps = SteamSession.apps ?: error("Steam is not connected")
            val db = db(context)
            val app = withContext(Dispatchers.IO) { SteamLibrarySync.refreshApp(db, apps, appId) } ?: error("Steam no longer lists this game")
            SteamDownload.run(context, app, root, progress)
        }
    }

    override suspend fun discardPartial(context: Context, gameId: String) {
        val appId = gameId.toIntOrNull() ?: return
        withContext(Dispatchers.IO) {
            val db = db(context)
            val install = db.installs().find(appId) ?: return@withContext
            File(context.cacheDir, "steam_chunks/$appId").let { staging -> SafeDelete.deleteWithin(staging.parentFile ?: context.cacheDir, staging) }
            if (install.isDownloaded || install.installPath.isBlank()) return@withContext
            val dir = File(install.installPath)
            dir.parentFile?.let { SafeDelete.deleteWithin(it, dir) }
            db.installs().delete(listOf(appId))
        }
    }

    override suspend fun uninstall(context: Context, gameId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val appId = gameId.toIntOrNull() ?: return@withContext Result.failure(IllegalArgumentException("$gameId is not a Steam app id"))
        val db = db(context)
        val install = db.installs().find(appId) ?: return@withContext Result.failure(IllegalStateException("This game is not installed"))
        runCatching {
            if (install.installPath.isNotBlank()) {
                val dir = File(install.installPath)
                val parent = dir.parentFile ?: error("${install.installPath} is not a game folder")
                check(SafeDelete.deleteWithin(parent, dir)) { "Could not remove ${install.installPath}" }
            }
            db.installs().delete(listOf(appId) + install.dlcDepots)
        }.onFailure { Timber.tag(TAG).e(it, "Failed to uninstall Steam app $appId") }
    }

    /**
     * Checks every file the installed depots' manifests list by size and
     * SHA-1. The manifests are the ones the download kept in the game's
     * folder, so this needs no network.
     */
    override suspend fun verify(context: Context, gameId: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val (install, dir) = installOf(context, gameId)
            val builds = SteamInstalls.installedBuilds(dir)
            check(builds.isNotEmpty()) { "Steam's file lists are not in this game's folder. Update or install again to repair it" }
            var checked = 0
            var bad = 0
            for ((depot, gid) in builds) {
                if (depot !in install.downloadedDepots && install.downloadedDepots.isNotEmpty()) continue
                val manifest = DepotManifest.loadFromFile(SteamInstalls.manifestFile(dir, depot, gid).absolutePath) ?: continue
                for (file in manifest.files.orEmpty()) {
                    if (isDirectory(file)) continue
                    checked++
                    val onDisk = StoreFiles.findCaseInsensitive(dir, file.fileName.replace('\\', '/'))
                    if (onDisk == null || !onDisk.isFile || onDisk.length() != file.totalSize || !sha1Matches(onDisk, file.fileHash)) bad++
                }
            }
            if (bad == 0) "All $checked files match" else "$bad of $checked files are missing or changed. Update or install again to repair them"
        }
    }

    /** The installed build of each depot against the one Steam serves on the branch now. */
    override suspend fun checkUpdate(context: Context, gameId: String): StoreUpdateCheck? {
        val appId = gameId.toIntOrNull() ?: return null
        if (!signedIn(context)) return null
        val install = withContext(Dispatchers.IO) { db(context).installs().find(appId) } ?: return null
        if (!install.isDownloaded || install.installPath.isBlank()) return null
        val builds = withContext(Dispatchers.IO) { SteamInstalls.installedBuilds(File(install.installPath)) }
        if (builds.isEmpty()) return null
        return runCatching {
            SteamSession.use(context) {
                SteamSession.logOn(context).getOrThrow()
                val apps = SteamSession.apps ?: return@use null
                val live = withContext(Dispatchers.IO) { SteamLibrarySync.refreshApp(db(context), apps, appId) } ?: return@use null
                val branch = install.branch.ifBlank { SteamDownload.BRANCH }
                val behind = SteamInstalls.isBehind(builds, live.depots, branch) ?: return@use null
                StoreUpdateCheck(
                    if (behind) StoreUpdate.AVAILABLE else StoreUpdate.CURRENT,
                    live.branches[branch]?.buildId?.takeIf { it > 0 }?.let { "build $it" },
                )
            }
        }.onFailure { Timber.tag(TAG).w(it, "Steam update check failed for $appId") }.getOrNull()
    }

    /**
     * The game's program (GameNative's choice: the developer's launch entry
     * unless it is a stub, else the best-scoring program the manifests flag),
     * its launch entry's working folder and arguments.
     */
    override suspend fun launch(context: Context, gameId: String): StoreLaunch? = withContext(Dispatchers.IO) {
        val (_, dir) = runCatching { installOf(context, gameId) }.getOrNull() ?: return@withContext null
        val app = db(context).apps().find(gameId.toInt()) ?: return@withContext null
        val entries = SteamExecutables.windowsLaunchEntries(app)
        val candidates = SteamInstalls.installedBuilds(dir).flatMap { (depot, gid) ->
            val depotInfo = app.depots[depot]
            if (depotInfo != null && (depotInfo.sharedInstall || !depotInfo.isWindowsCompatible)) return@flatMap emptyList()
            val manifest = runCatching { DepotManifest.loadFromFile(SteamInstalls.manifestFile(dir, depot, gid).absolutePath) }.getOrNull()
                ?: return@flatMap emptyList()
            val files = manifest.files.orEmpty()
            val depotSize = files.sumOf { it.totalSize }
            files.filterNot(::isDirectory).map { file ->
                SteamExecutables.Candidate(file.fileName.replace('\\', '/'), isExecutable(file), file.totalSize, depotSize)
            }
        }
        val chosen = SteamExecutables.choose(candidates, entries.map { it.executable }, app.folderName) ?: return@withContext null
        val exe = StoreFiles.findCaseInsensitive(dir, chosen) ?: return@withContext null
        val entry = entries.firstOrNull { it.executable.equals(chosen, ignoreCase = true) }
        val workingDir = entry?.workingDir?.takeIf { it.isNotBlank() }?.let { StoreFiles.findCaseInsensitive(dir, it) }
            ?: exe.parentFile ?: dir
        StoreLaunch(exe, workingDir, entry?.arguments?.let(EpicGameLauncher::tokenizeArgs).orEmpty())
    }

    override fun changeStamp(context: Context): Long =
        StoreFiles.stamp(SteamDatabase.files(context) + SteamCredentials.file(context))

    private suspend fun installOf(context: Context, gameId: String): Pair<AppInfo, File> {
        val appId = gameId.toIntOrNull() ?: error("$gameId is not a Steam app id")
        val install = db(context).installs().find(appId)
        check(install != null && install.isDownloaded && install.installPath.isNotBlank()) { "This game is not installed" }
        val dir = File(install.installPath)
        check(dir.isDirectory) { "${install.installPath} is not there" }
        return install to dir
    }

    private companion object {
        const val TAG = "SteamStore"

        /**
         * Whether a depot file carries one of Steam's file flags
         * (EDepotFileFlag), given by name and by bit, however this JavaSteam
         * build hands the flags over (GameNative's isExecutable reads both).
         */
        fun hasFlag(file: FileData, names: Set<String>, bits: Int): Boolean = when (val flags: Any? = file.flags) {
            is EnumSet<*> -> flags.any { (it as? Enum<*>)?.name in names }
            is Number -> flags.toLong() and bits.toLong() != 0L
            else -> false
        }

        fun isDirectory(file: FileData): Boolean = hasFlag(file, setOf("Directory"), 0x40)

        fun isExecutable(file: FileData): Boolean = hasFlag(file, setOf("Executable", "CustomExecutable"), 0x20 or 0x80)

        fun sha1Matches(file: File, expected: ByteArray?): Boolean {
            if (expected == null || expected.isEmpty()) return true
            val digest = MessageDigest.getInstance("SHA-1")
            file.inputStream().use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().contentEquals(expected)
        }
    }
}
