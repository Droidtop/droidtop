package dev.droidtop.stores.steam

import android.content.Context
import dev.droidtop.library.PcStoreNames
import dev.droidtop.library.StoreUpdate
import dev.droidtop.library.settings.CatalogItem
import dev.droidtop.library.social.SocialProvider
import dev.droidtop.library.stores.SaveConflictResolver
import dev.droidtop.library.stores.SaveSyncPhase
import dev.droidtop.library.stores.SaveSyncResult
import dev.droidtop.library.stores.StoreContentChoice
import dev.droidtop.library.stores.StoreContentOptions
import dev.droidtop.library.stores.StoreGame
import dev.droidtop.library.stores.StoreLaunch
import dev.droidtop.library.stores.StoreLibrary
import dev.droidtop.library.stores.StorePlayer
import dev.droidtop.library.stores.StoreProgress
import dev.droidtop.library.stores.StoreSignIn
import dev.droidtop.library.stores.StoreSignInKind
import dev.droidtop.library.stores.StoreUpdateCheck
import dev.droidtop.library.stores.WinePrefixLocation
import dev.droidtop.runtime.SafeDelete
import dev.droidtop.stores.epic.EpicGameLauncher
import dev.droidtop.stores.util.StoreFiles
import dev.droidtop.stores.util.StoreLanguage
import `in`.dragonbra.javasteam.enums.EOSType
import `in`.dragonbra.javasteam.types.DepotManifest
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
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

    /** The signed-in account and the DLC droidtop installed with [gameId] (the Steamworks shim's player). */
    override suspend fun player(context: Context, gameId: String): StorePlayer? = withContext(Dispatchers.IO) {
        val credentials = SteamCredentials.load(context)?.takeIf { it.steamId64 != 0L } ?: return@withContext null
        val dlc = gameId.toIntOrNull()?.let { db(context).installs().find(it) }?.dlcDepots.orEmpty().map(Int::toString).toSet()
        StorePlayer(credentials.steamId64, credentials.accountName, dlc)
    }

    override fun signIn(context: Context): StoreSignIn = StoreSignIn.Account(SteamSignIn(context, this))

    /** Steam signs in step by step on its own screen ([signIn]); there is no code to finish with. */
    override suspend fun completeSignIn(context: Context, secret: String): Result<String?> =
        Result.failure(UnsupportedOperationException("Steam signs in on its own screen"))

    override val social: SocialProvider get() = SteamFriendsHub

    override fun settingsItems(context: Context): List<CatalogItem> = SteamSettings.items(context)

    override suspend fun signOut(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            SteamConnection.stop(context)
            SteamFriendsHub.clear()
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
        // Whose licence grants each game (SteamOwnership): the account's own, a
        // family member's (listed, marked), or only the free sub or an ended
        // licence (not listed).
        val accountId = SteamCredentials.load(context)?.steamId64?.takeIf { it != 0L }?.let { (it and 0xFFFFFFFFL).toInt() }
        val ownership = SteamOwnership.of(db.licenses().all(), accountId)
        val dlcByBase = db.apps().dlcKinds().groupBy({ it.base }, { it.id })
        val status = HashMap<Int, SteamOwnership.Status>()
        val owned = db.apps().owned(SteamLibrarySync.PLAYABLE_TYPES).filter { app ->
            val standing = ownership.statusOf(app.id, dlcByBase[app.id].orEmpty())
            status[app.id] = standing
            standing != SteamOwnership.Status.NONE
        }
        val ownedIds = owned.mapTo(HashSet()) { it.id }
        // Installed and no longer owned (signed out, a licence gone) still shows, with its files.
        val installedOnly = installs.keys.filter { it !in ownedIds }
            .mapNotNull { db.apps().find(it) }
            // A DLC's own install row is not a game of its own.
            .filter { SteamLibrarySync.isLibraryGame(it, keepInstalledKinds = true) }
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
                familyShared = status[app.id] == SteamOwnership.Status.FAMILY,
            )
        }
    }

    override suspend fun installedPath(context: Context, gameId: String): String? = withContext(Dispatchers.IO) {
        val install = gameId.toIntOrNull()?.let { db(context).installs().find(it) }
        install?.takeIf { it.isDownloaded && it.installPath.isNotBlank() }?.installPath
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
            val fresh = withContext(Dispatchers.IO) { SteamLibrarySync.refreshApp(db, apps, appId) } ?: error("Steam no longer lists this game")
            // The branch and the DLC the person chose for this game (DLC and versions); the default is the public branch with every owned DLC.
            val choice = withContext(Dispatchers.IO) { SteamChoices.get(context, appId) }
            val app = SteamBranches.resolve(apps, fresh, choice.branch, choice.password)
            SteamDownload.run(context, app, root, progress, choice)
        }
    }

    override val hasCloudSaves = true

    /**
     * Steam Cloud saves of [gameId], synced into the game's Wine prefix
     * (docs/SPEC.md 7g, "Stores"). Needs the sign-in and the game's folder;
     * null when either is missing or the prefix has no `drive_c` yet (a prefix
     * that Wine has not made is not made here). Steam is told the game starts
     * or ends the way its own client tells it.
     */
    override suspend fun syncSaves(
        context: Context,
        gameId: String,
        phase: SaveSyncPhase,
        prefix: WinePrefixLocation,
        title: String,
        onConflict: SaveConflictResolver?,
    ): SaveSyncResult? {
        val appId = gameId.toIntOrNull() ?: return null
        if (!signedIn(context)) return null
        // A person who turned cloud saves off is only asked again by "Sync cloud saves" on the game's menu.
        if (phase != SaveSyncPhase.MANUAL && !withContext(Dispatchers.IO) { SteamPrefs.cloudSaves(context) }) return null
        val found = withContext(Dispatchers.IO) {
            if (!File(prefix.prefixDir, "drive_c").isDirectory) null else runCatching { installOf(context, gameId) }.getOrNull()
        } ?: return null
        val (install, dir) = found
        return runCatching {
            SteamSession.use(context) {
                SteamSession.logOn(context).getOrThrow()
                val apps = SteamSession.apps ?: error("Steam is not connected")
                val cloud = SteamSession.cloud ?: error("Steam is not connected")
                val http = SteamSession.httpClient ?: error("Steam is not connected")
                val db = db(context)
                var fresh: SteamUfs? = null
                val app = withContext(Dispatchers.IO) {
                    SteamLibrarySync.refreshApp(db, apps, appId) { fresh = it; SteamUfsCache.save(context, appId, it) }
                } ?: error("Steam no longer lists this game")
                val ufs = fresh ?: withContext(Dispatchers.IO) { SteamUfsCache.load(context, appId) } ?: SteamUfs()
                val accountId = (SteamSession.accountId ?: 0).toLong() and 0xFFFFFFFFL
                val steamId64 = SteamCredentials.load(context)?.steamId64 ?: 0L
                val layout = SaveLayout(SaveLayout.windowsDirs(prefix.prefixDir, prefix.user, dir, accountId, appId), steamId64, accountId, ufs)
                val clientId = SteamCredentials.clientId(context)
                val outcome = SteamCloudSync.run(
                    filesDir = context.filesDir,
                    http = http,
                    cloud = cloud,
                    app = app,
                    layout = layout,
                    clientId = clientId,
                    machineName = SteamSession.machineName(context),
                    buildId = app.branches[install.branch.ifBlank { SteamDownload.BRANCH }]?.buildId ?: 0L,
                    title = title,
                    onConflict = onConflict,
                    progress = {},
                )
                // Steam's own client tells Steam a game starts after the sync and ends after the sync.
                when (phase) {
                    SaveSyncPhase.BEFORE_LAUNCH -> if (outcome.settled) {
                        runCatching { cloud.signalAppLaunchIntent(appId, clientId, SteamSession.machineName(context), true, EOSType.WinUnknown).await() }
                    }
                    SaveSyncPhase.AFTER_EXIT -> runCatching { cloud.signalAppExitSyncDone(appId, clientId, outcome.uploadsCompleted, outcome.uploadsRequired) }
                    SaveSyncPhase.MANUAL -> Unit
                }
                outcome.result
            }
        }.getOrElse {
            Timber.tag(TAG).w(it, "Steam Cloud sync failed for $appId")
            SaveSyncResult("Cloud saves: ${userFacing(it)}", failed = true)
        }
    }

    private fun userFacing(error: Throwable): String = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName

    override suspend fun contentOptions(context: Context, gameId: String): StoreContentOptions? = withContext(Dispatchers.IO) {
        val appId = gameId.toIntOrNull() ?: return@withContext null
        val db = db(context)
        val app = db.apps().find(appId)?.takeIf { it.receivedPICS } ?: return@withContext null
        val install = db.installs().find(appId)
        val choice = SteamChoices.get(context, appId)
        val (dlc, available) = dlcOf(db, app, choice)
        SteamContent.options(
            app = app,
            dlc = dlc,
            choice = choice,
            installedDlc = install?.dlcDepots.orEmpty().toSet().intersect(available),
            installed = install?.isDownloaded == true,
        ).takeUnless { it.isEmpty }
    }

    override suspend fun chooseContent(context: Context, gameId: String, choice: StoreContentChoice): Result<Boolean> =
        withContext(Dispatchers.IO) {
            runCatching {
                val appId = gameId.toIntOrNull() ?: error("$gameId is not a Steam app id")
                val db = db(context)
                val app = db.apps().find(appId) ?: error("Steam no longer lists this game")
                val before = SteamChoices.get(context, appId)
                val (_, available) = dlcOf(db, app, before)
                val after = SteamContent.choiceFrom(choice, available, before)
                val branch = app.branches[after.branch]
                check(branch?.pwdRequired != true || after.password != null) { "The ${after.branch} version needs its password first" }
                SteamChoices.put(context, appId, after)
                val install = db.installs().find(appId)
                install?.isDownloaded == true && SteamContent.installDiffers(after, available, install.dlcDepots.toSet(), install.branch)
            }
        }

    override suspend fun unlockBranch(context: Context, gameId: String, branchId: String, password: String): Result<Unit> =
        runCatching {
            val appId = gameId.toIntOrNull() ?: error("$gameId is not a Steam app id")
            val typed = password.trim()
            require(typed.isNotEmpty()) { "Enter the password for the $branchId version" }
            val keys = SteamSession.use(context) {
                SteamSession.logOn(context).getOrThrow()
                SteamBranches.check(SteamSession.apps ?: error("Steam is not connected"), appId, typed)
            }
            check(SteamBranches.unlocked(keys, branchId)) { "Steam does not accept that password for $branchId" }
            withContext(Dispatchers.IO) {
                val before = SteamChoices.get(context, appId)
                SteamChoices.put(context, appId, before.copy(branchPasswords = before.branchPasswords + (branchId to typed)))
            }
        }

    /**
     * The owned DLC of [app] with content on the branch the choice follows
     * (the public one while a locked branch's manifests are not opened), with
     * their names, and their ids.
     */
    private suspend fun dlcOf(db: SteamDatabase, app: SteamApp, choice: SteamChoice): Pair<List<SteamContent.Dlc>, Set<Int>> {
        val branch = choice.branch.takeIf { app.branches[it]?.pwdRequired != true } ?: SteamBranches.PUBLIC
        val everything = SteamDownload.planFor(db, app.copy(depots = SteamBranches.forBranch(app.depots, branch)), SteamChoice(branch = branch))
        val ids = SteamContent.dlcIn(everything, branch) { "" }.map { it.appId }
        val names = ids.associateWith { db.apps().find(it)?.name.orEmpty() }
        val dlc = SteamContent.dlcIn(everything, branch) { names[it].orEmpty() }
        return dlc to ids.toSet()
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
                    if (SteamManifests.isDirectory(file)) continue
                    checked++
                    val onDisk = StoreFiles.findCaseInsensitive(dir, file.fileName.replace('\\', '/'))
                    if (onDisk == null || !onDisk.isFile || onDisk.length() != file.totalSize || !SteamManifests.sha1Matches(onDisk, file.fileHash)) bad++
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
                val fresh = withContext(Dispatchers.IO) { SteamLibrarySync.refreshApp(db(context), apps, appId) } ?: return@use null
                val branch = install.branch.ifBlank { SteamDownload.BRANCH }
                // A locked branch's manifests are encrypted until its password has opened them.
                val password = withContext(Dispatchers.IO) { SteamChoices.get(context, appId).branchPasswords[branch] }
                val live = runCatching { SteamBranches.resolve(apps, fresh, branch, password) }.getOrDefault(fresh)
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
            files.filterNot(SteamManifests::isDirectory).map { file ->
                SteamExecutables.Candidate(file.fileName.replace('\\', '/'), SteamManifests.isExecutable(file), file.totalSize, depotSize)
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
    }
}
