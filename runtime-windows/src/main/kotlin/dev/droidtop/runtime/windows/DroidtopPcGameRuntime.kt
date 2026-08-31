package dev.droidtop.runtime.windows

import android.content.Context
import app.gamenative.service.SteamService
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
) : PcGameRuntime {

    override val isAvailable: Boolean
        get() = primarySession() != null

    override val isProvisioned: Boolean
        get() = runCatching { ContainerManager(context).containers.isNotEmpty() }.getOrDefault(false)

    override suspend fun provision(
        gamesRoots: List<File>,
        onStatus: (String) -> Unit,
    ): PcProvisionResult = withContext(Dispatchers.IO) {
        val manager = runCatching { ContainerManager(context) }
            .getOrElse { return@withContext PcProvisionResult(false, it.message ?: "couldn't open container storage") }

        manager.containers.firstOrNull()?.let {
            return@withContext PcProvisionResult(true, "\"${it.name}\" is already set up")
        }

        onStatus("Creating the Windows environment\u2026")
        // A minimal config on purpose. Container fills in every field it
        // owns and loadData only reads keys that are present, so the
        // defaults apply for everything not named here -- which is why
        // upstream's 1400-line Steam-coupled ContainerUtils
        // (deliberately not forked) is not needed to create one.
        val data = JSONObject().apply {
            put("name", CONTAINER_NAME)
            put("drives", drivesFor(gamesRoots))
        }
        val container = runCatching { manager.createContainer(CONTAINER_ID, data) }
            .getOrElse { return@withContext PcProvisionResult(false, it.message ?: "container creation threw") }
            ?: return@withContext PcProvisionResult(
                false,
                "couldn't create the container (the Wine base files may have failed to download)",
            )

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
            imageFs.variant != container.containerVariant
        if (needsImage) {
            val archiveName = if (container.containerVariant == Container.GLIBC) {
                "imagefs_gamenative.txz"
            } else {
                "imagefs_bionic.txz"
            }
            onStatus("Downloading the Windows base system\u2026")
            // gamenative's own downloader, now that the whole tree is
            // compiled in -- the same primary-plus-R2-mirror pair its
            // pre-launch phase uses, writing to the same place
            // ImageFsInstaller looks (ImageFs.getFilesDir() is the
            // imagefs root's parent, i.e. the app files dir).
            val dest = File(context.filesDir, archiveName)
            if (!(dest.isFile && dest.length() > 0)) {
                val downloaded = runCatching {
                    SteamService.fetchFileWithFallback(archiveName, dest, context) { fraction ->
                        onStatus("Downloading the Windows base system\u2026 ${(fraction * 100).toInt()}%")
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

        // Second, not first: installIfNeededFuture reads the wine version
        // and variant off the container, so one has to exist already.
        onStatus("Installing Windows system files\u2026")
        val installed = runCatching {
            ImageFsInstaller.installIfNeededFuture(context, context.assets, container) { percent ->
                onStatus("Installing Windows system files\u2026 $percent%")
            }.get()
        }.getOrElse { return@withContext PcProvisionResult(false, it.message ?: "system file install threw") }

        if (installed != true) {
            return@withContext PcProvisionResult(false, "the Windows system files failed to install")
        }

        // Third and last: this points the environment's own "xuser" home
        // at this container, and anything reading container-relative
        // paths depends on it having happened.
        runCatching { manager.activateContainer(container) }
            .getOrElse { return@withContext PcProvisionResult(false, it.message ?: "couldn't activate the container") }

        PcProvisionResult(true, "Windows environment ready")
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
        val session = primarySession()
            ?: return PcLaunchResult(false, "no live container session")

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

        val wine = WineSession(
            container = session.container,
            runtime = session.runtime,
            prefixPath = session.runtime.hostStorageToContainerPath(prefixHostPath),
        )
        // The executable is handed over as an in-container path, not a host
        // one: a game routinely lives on a games root outside the
        // container's own rootfs, and the runtime is what knows how that
        // maps inside (see ContainerRuntime.hostStorageToContainerPath).
        val result = runCatching {
            wine.launch(session.runtime.hostStorageToContainerPath(executable))
        }.getOrElse { return PcLaunchResult(false, it.message ?: it.toString()) }

        return PcLaunchResult(
            succeeded = result.succeeded,
            detail = if (result.succeeded) "ok" else "exit ${result.exitCode}: ${result.stderr.ifBlank { result.stdout }}",
        )
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
    }
}
