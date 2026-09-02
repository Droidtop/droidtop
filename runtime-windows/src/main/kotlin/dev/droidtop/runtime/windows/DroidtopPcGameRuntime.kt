package dev.droidtop.runtime.windows

import android.content.Context
import app.gamenative.data.GameSource
import app.gamenative.service.SteamService
import app.gamenative.utils.LaunchDependencies
import com.winlator.container.Container
import com.winlator.container.ContainerManager
import com.winlator.xenvironment.ImageFs
import com.winlator.xenvironment.ImageFsInstaller
import dev.droidtop.library.PcGameRuntime
import dev.droidtop.library.PcLaunchResult
import dev.droidtop.library.PcProvisionResult
import dev.droidtop.library.WineDriveMapping
import dev.droidtop.runtime.NativeLinuxGameSession
import dev.droidtop.runtime.PrimaryContainerSession
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * droidtop's real implementation of `library-core`'s [PcGameRuntime]
 * seam -- what turns the `WINE_PREFIX` and `LINUX_CONTAINER` launch
 * strategies from dead `error()` stubs into actual launches.
 *
 * Lives in `:runtime-windows` rather than `:app` for a concrete reason:
 * this is the module that compiles the vendored `com.winlator.*` tree
 * (see this module's own build script), so [ContainerManager] -- the real
 * owner of Wine-prefix state -- is only visible from here. `:app` depends
 * on this module with `implementation`, which does not re-export those
 * types, so the same code in `:app` would not compile.
 *
 * The two halves reach their environment differently, and deliberately:
 *
 *  - Windows software goes through the [WineEngine] seam, which needs no
 *    root and no droidspaces container. It used to demand a live
 *    [PrimaryContainerSession] as well, which made Windows games
 *    root-only by accident (docs/SPEC.md 5b) -- the prefix was
 *    provisioned into an ImageFs that the launch path never entered.
 *  - A native Linux build genuinely does need a Linux rootfs to run in,
 *    so [launchLinux] still asks for the primary container. That is a
 *    real requirement of the job, not an assumption inherited from the
 *    Wine path.
 *
 * [primarySession] is a supplier rather than a value for exactly the
 * reason [PcGameProvider]'s constructor already documents: the desktop
 * session may still be connecting, or not be started at all, when this
 * is constructed.
 *
 * droidtop reuses an existing container rather than creating one per
 * game -- a user's engine game should run in the Wine environment they
 * already configured, and silently spawning prefixes per title would
 * multiply multi-hundred-megabyte state without being asked.
 */
class DroidtopPcGameRuntime(
    private val context: Context,
    private val primarySession: () -> PrimaryContainerSession?,
    private val wineEngine: WineEngine = BionicWineEngine(context),
) : PcGameRuntime {

    override val isAvailable: Boolean
        get() = isProvisioned || primarySession() != null

    override val isProvisioned: Boolean
        get() = runCatching { ContainerManager(context).containers.isNotEmpty() }.getOrDefault(false)

    override suspend fun provision(
        gamesRoots: List<File>,
        onStatus: (String) -> Unit,
    ): PcProvisionResult = withContext(Dispatchers.IO) {
        val manager = runCatching { ContainerManager(context) }
            .getOrElse { return@withContext PcProvisionResult(false, it.message ?: "couldn't open container storage") }

        val existing = manager.containers.firstOrNull()

        // What this device's Wine environment should be, stated before
        // any of it exists. A container object is the only way gamenative
        // expresses that -- its installer and its launch dependencies
        // both read the wine version and the variant off one -- so an
        // unsaved instance carries the answer through the steps that run
        // before there is anything on disk to save.
        //
        // Those two fields are deliberately not left to the defaults.
        // `Container.DEFAULT_VARIANT` reads `DefaultVersion.VARIANT`,
        // which is `glibc` at class-load time and only becomes bionic
        // once gamenative's own startup code has mutated the static -- so
        // an environment built from the defaults would provision the
        // glibc rootfs, whose execution model is proot, which does not
        // exist on arm64 (docs/SPEC.md 5b).
        val wanted = existing ?: Container(CONTAINER_ID).apply {
            containerVariant = Container.BIONIC
            wineVersion = WINE_VERSION
        }
        // A container made before droidtop asked for bionic by name is a
        // glibc one. Repointing it is the repair; deleting the user's
        // prefix and starting again is not.
        if (existing != null && existing.containerVariant != Container.BIONIC) {
            onStatus("Switching the Windows environment to the no-root runtime…")
            existing.containerVariant = Container.BIONIC
            existing.wineVersion = WINE_VERSION
            runCatching { existing.saveData() }
        }

        // Wine first, and the order is not arbitrary. Creating a
        // container copies Wine's own DLLs out of the installed build
        // into the new prefix (ContainerManager.extractCommonDlls), so a
        // container created before Wine exists dies on a null directory
        // listing -- confirmed on hardware, where it surfaced as "Setup
        // failed: Attempt to get length of null array". Upstream never
        // hits it because its system files are installed at startup and
        // its containers are created later; droidtop does both here, so
        // it has to do them in that order.
        //
        // This is gamenative's own launch dependency set
        // (BionicDefaultProtonDependency and friends), used rather than
        // reimplemented: it is what knows which archive a given wine
        // version needs and where it unpacks to.
        // The one piece of that dependency droidtop cannot use: it
        // fetches the archive through SteamService.downloadFile, which
        // dereferences the running SteamService instance, and droidtop
        // never starts that service. Confirmed on hardware -- the whole
        // step failed with a KotlinNullPointerException carrying no
        // message at all ("Setup failed: couldn't install Wine").
        //
        // Fetching it here with the instance-free downloader (the same
        // one the base system uses below) makes the dependency's own
        // isFileInstallable check short-circuit, so it still owns the
        // extraction and the on-disk layout. The archive is named after
        // the wine version, exactly as ImageFsInstaller's own
        // installWineFromDownloads assumes.
        val wineArchive = File(context.filesDir, "$WINE_VERSION.txz")
        if (!(wineArchive.isFile && wineArchive.length() > 0)) {
            onStatus("Downloading Wine…")
            val fetched = runCatching {
                SteamService.fetchFileWithFallback(wineArchive.name, wineArchive, context) { fraction ->
                    onStatus("Downloading Wine… ${(fraction * 100).toInt()}%")
                }
            }
            if (fetched.isFailure) {
                return@withContext PcProvisionResult(
                    false,
                    fetched.exceptionOrNull()?.message
                        ?: "couldn't download Wine -- check the network and retry",
                )
            }
        }

        val dependencies = runCatching {
            LaunchDependencies().ensureLaunchDependencies(
                context = context,
                container = wanted,
                gameSource = GameSource.CUSTOM_GAME,
                gameId = 0,
                setLoadingMessage = { message -> onStatus(message) },
                setLoadingProgress = { fraction ->
                    if (fraction >= 0f) onStatus("Installing Wine… ${(fraction * 100).toInt()}%")
                },
            )
        }
        if (dependencies.isFailure) {
            return@withContext PcProvisionResult(
                false,
                dependencies.exceptionOrNull()?.message ?: "couldn't install Wine",
            )
        }

        // The installer only EXTRACTS the base-system archive -- from the
        // bundled assets (where it has never shipped, upstream included)
        // or from a file already sitting in the files dir. Upstream puts
        // it there in its own pre-launch phase through SteamService,
        // which droidtop does not fork -- so it is downloaded here, and
        // only when the installer's own condition says it would actually
        // install (valid + current + same variant means it will skip).
        val imageFs = ImageFs.find(context)
        val needsImage = !imageFs.isValid ||
            imageFs.version < ImageFsInstaller.LATEST_VERSION ||
            imageFs.variant != wanted.containerVariant
        if (needsImage) {
            val archiveName = if (wanted.containerVariant == Container.GLIBC) {
                "imagefs_gamenative.txz"
            } else {
                "imagefs_bionic.txz"
            }
            onStatus("Downloading the Windows base system…")
            // gamenative's own downloader, now that the whole tree is
            // compiled in -- the same primary-plus-R2-mirror pair its
            // pre-launch phase uses, writing to the same place
            // ImageFsInstaller looks (ImageFs.getFilesDir() is the
            // imagefs root's parent, i.e. the app files dir).
            val dest = File(context.filesDir, archiveName)
            if (!(dest.isFile && dest.length() > 0)) {
                val downloaded = runCatching {
                    SteamService.fetchFileWithFallback(archiveName, dest, context) { fraction ->
                        onStatus("Downloading the Windows base system… ${(fraction * 100).toInt()}%")
                    }
                }
                if (downloaded.isFailure) {
                    return@withContext PcProvisionResult(
                        false,
                        downloaded.exceptionOrNull()?.message
                            ?: "couldn't download the Windows base system -- check the network and retry",
                    )
                }
            }
        }

        // installIfNeededFuture reads the wine version and variant off
        // the container it is handed, which is why it takes the wanted
        // one rather than a created one. It also links opt/<wineVersion>
        // into the shared Proton store and skips that link when the store
        // is still empty, so it has to run after the step above.
        onStatus("Installing Windows system files…")
        val installed = runCatching {
            ImageFsInstaller.installIfNeededFuture(context, context.assets, wanted) { percent ->
                onStatus("Installing Windows system files… $percent%")
            }.get()
        }.getOrElse { return@withContext PcProvisionResult(false, it.message ?: "system file install threw") }

        if (installed != true) {
            return@withContext PcProvisionResult(false, "the Windows system files failed to install")
        }

        // Only now: the prefix is stamped out of the Wine build that is
        // by this point actually on disk.
        onStatus("Creating the Windows environment…")
        // A minimal config on purpose. Container fills in every field it
        // owns and loadData only reads keys that are present, so the
        // defaults apply for everything not named here -- which is why
        // upstream's 1400-line Steam-coupled ContainerUtils (deliberately
        // not forked) is not needed to create one.
        // A previous attempt that died part-way leaves the directory
        // behind, and createContainer refuses to touch one that already
        // exists (its mkdirs returns false, and it returns null), so
        // without this a single failure would make every retry fail too.
        // Nothing in it is the user's: no container is registered, so
        // nothing has ever been launched in it.
        if (existing == null) {
            val halfMade = File(imageFs.rootDir, "home/${ImageFs.USER}-$CONTAINER_ID")
            if (halfMade.isDirectory) {
                onStatus("Clearing an unfinished setup…")
                halfMade.deleteRecursively()
            }
        }

        val container = existing ?: runCatching {
            manager.createContainer(
                CONTAINER_ID,
                JSONObject().apply {
                    put("name", CONTAINER_NAME)
                    put("containerVariant", Container.BIONIC)
                    put("wineVersion", WINE_VERSION)
                    put("drives", drivesFor(gamesRoots))
                },
            )
        }
            .getOrElse { return@withContext PcProvisionResult(false, it.message ?: "container creation threw") }
            ?: return@withContext PcProvisionResult(
                false,
                "couldn't create the Wine prefix (the container pattern may have failed to download)",
            )

        // Last: this points the environment's own "xuser" home at this
        // container, and anything reading container-relative paths
        // depends on it having happened.
        runCatching { manager.activateContainer(container) }
            .getOrElse { return@withContext PcProvisionResult(false, it.message ?: "couldn't activate the container") }

        when (val readiness = wineEngine.readiness(container)) {
            is WineEngineReadiness.Ready -> PcProvisionResult(true, "Windows environment ready")
            // Deliberately reported as a failure: everything downloaded
            // and the user would otherwise be told they are set up, then
            // hit the same missing piece on their first launch.
            is WineEngineReadiness.Missing -> PcProvisionResult(false, readiness.reason)
        }
    }

    /**
     * Wine drive mappings, as `<letter>:<path>` entries concatenated --
     * the format [Container.drivesIterator] parses. The assignment
     * itself lives in [WineDriveMapping] so the settings screen previews
     * the same letters this actually writes.
     *
     * Deliberately not [Container.DEFAULT_DRIVES]: that hardcodes
     * `/data/data/app.gamenative/storage`, which is the wrong package for
     * droidtop and private to an app that is not installed. Mapping the
     * user's real games roots instead is the point -- a container that
     * cannot see the folder the games are in is useless, and games on an
     * SD card were exactly the case upstream never handled.
     */
    private fun drivesFor(gamesRoots: List<File>): String =
        WineDriveMapping.assign(gamesRoots.map { it.absolutePath })
            .joinToString("") { (letter, path) -> "$letter:$path" }

    override suspend fun launchWindows(executable: File, gameRoot: File): PcLaunchResult {
        val container = runCatching { ContainerManager(context).containers.firstOrNull() }.getOrNull()
            ?: return PcLaunchResult(
                false,
                // Names the real action that now exists. This used to say
                // "create one under Desktop mode > Containers", a screen
                // that had never been built -- pointing someone at a
                // place they cannot reach is worse than saying nothing.
                "the Windows environment isn't set up yet -- run \"Set up Windows games\" in Settings",
            )

        val prefixHostPath = File(container.rootDir, ".wine")
        if (!prefixHostPath.isDirectory) {
            return PcLaunchResult(
                false,
                "container \"${container.name}\" has no Wine prefix at ${prefixHostPath.absolutePath}",
            )
        }

        return runCatching { wineEngine.launch(container, executable.absolutePath, gameRoot) }
            .getOrElse { PcLaunchResult(false, it.message ?: it.toString()) }
    }

    override suspend fun launchLinux(executable: File, gameRoot: File): PcLaunchResult {
        val session = primarySession()
            ?: return PcLaunchResult(false, "no live container session")

        val result = runCatching {
            NativeLinuxGameSession(session.container, session.runtime)
                .launch(session.runtime.hostStorageToContainerPath(executable))
        }.getOrElse { return PcLaunchResult(false, it.message ?: it.toString()) }

        return PcLaunchResult(
            succeeded = result.succeeded,
            detail = if (result.succeeded) "ok" else "exit ${result.exitCode}: ${result.stderr.ifBlank { result.stdout }}",
        )
    }

    private companion object {
        // Trailing digit on purpose: ContainerUtils.extractGameIdFromContainerId
        // parses a trailing numeric run out of the id, and returns 0 for
        // anything that does not parse.
        const val CONTAINER_ID = "1"
        const val CONTAINER_NAME = "droidtop"

        /**
         * The Wine build droidtop provisions.
         *
         * One of `R.array.bionic_wine_entries`, which is what makes it
         * installable without anyone touching a UI: gamenative's own
         * `BionicDefaultProtonDependency` knows how to fetch exactly
         * those two, whereas its newer default (`proton-10.0-arm64ec-2`)
         * is a `.wcp` a user installs by hand through the Wine/Proton
         * manager dialog, and provisioning cannot assume that happened.
         *
         * x86_64 rather than arm64ec: it runs entirely under box64, whose
         * payload ships in this module's assets, so it depends on nothing
         * else being fetched or configured. arm64ec is the faster of the
         * two and is where this should end up, but it additionally needs
         * the emulator DLL set and a chosen emulator backend -- worth
         * doing deliberately, not as a side effect of picking a default.
         */
        const val WINE_VERSION = "proton-9.0-x86_64"
    }
}
