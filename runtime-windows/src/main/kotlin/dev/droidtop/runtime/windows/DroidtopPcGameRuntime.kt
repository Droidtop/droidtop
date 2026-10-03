package dev.droidtop.runtime.windows

import android.content.Context
import app.gamenative.service.SteamService
import app.gamenative.utils.ContainerUtils
import app.gamenative.utils.X86_64GuestLibs
import com.winlator.container.Container
import com.winlator.container.ContainerData
import com.winlator.container.ContainerManager
import com.winlator.core.KeyValueSet
import com.winlator.core.envvars.EnvVars
import com.winlator.xenvironment.ImageFs
import com.winlator.xenvironment.ImageFsInstaller
import dev.droidtop.library.PcGameRuntime
import dev.droidtop.library.PcLaunchResult
import dev.droidtop.library.PcPrefixState
import dev.droidtop.library.PcProvisionResult
import dev.droidtop.library.WineDriveMapping
import dev.droidtop.library.lutris.DllOverrides
import dev.droidtop.library.lutris.WinePrefixChanges
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
 *    root and no droidspaces container -- and, stronger, MUST NEVER be
 *    given either: Wine runs downloaded third-party binaries, and the
 *    guest process must not execute in a root-capable context on any
 *    device (the sealed [WineEngine]'s own doc states the boundary;
 *    docs/SPEC.md 5b records it). It used to demand a live
 *    [PrimaryContainerSession] as well, which made Windows games
 *    root-only by accident -- the prefix was provisioned into an
 *    ImageFs that the launch path never entered.
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

    // On an x86_64 device the environment also needs the x86_64 guest
    // libraries; a device set up before they existed is offered Set up again,
    // which fetches only them (Droidtop/tracker#242).
    override val isProvisioned: Boolean
        get() = runCatching { ContainerManager(context).containers.isNotEmpty() }.getOrDefault(false) &&
            (!X86_64GuestLibs.isX86_64Host() || X86_64GuestLibs.isInstalled(context))

    /**
     * A failed setup step: the whole exception goes to the log (the screen
     * gets [line] only), because "Attempt to get length of null array" with
     * no frame was all the console ever reported (Droidtop/tracker#249).
     */
    private fun failed(step: String, error: Throwable?, line: String): PcProvisionResult {
        android.util.Log.w(TAG, "Windows setup: $step failed", error)
        return PcProvisionResult(false, line)
    }

    // The primary container session IS the Linux runtime: launchLinux
    // runs inside it and nothing else can. It exists only while Desktop
    // mode's container is up (droidspaces with root, proot without), which
    // is why the PC surface shows a Linux row as "not on this device"
    // otherwise rather than offering a launch that cannot work.
    override val isLinuxContainerAvailable: Boolean
        get() = primarySession() != null

    override suspend fun provision(
        gamesRoots: List<File>,
        onStatus: (String) -> Unit,
    ): PcProvisionResult = withContext(Dispatchers.IO) {
        val manager = runCatching { ContainerManager(context) }
            .getOrElse { return@withContext failed("opening container storage", it, it.message ?: "couldn't open container storage") }

        // droidtop's own environment, the one every game without Wine
        // settings of its own runs in; a game's own container (made from
        // its Wine settings) is not this.
        val existing = manager.containers.let { all -> all.firstOrNull { it.id == CONTAINER_ID } ?: all.firstOrNull() }

        // What this device's Wine environment should be: upstream
        // GameNative's own per-device defaults (ContainerUtils.
        // setContainerDefaults, which only GameNative's MainActivity ever
        // ran): on arm64 the arm64ec Proton with FEXCore, the Wrapper
        // driver and the DXVK picked for the GPU; on x86_64 the x86_64
        // Proton run directly with software Vulkan. Every one of them is a
        // choice under Settings > Windows games and each game's Wine
        // settings (docs/SPEC.md 5a); these are only where they start.
        val defaults = runCatching { ContainerUtils.deviceDefaultContainerData(context) }
            .getOrElse { return@withContext failed("reading this device's defaults", it, it.message ?: "couldn't read this device's defaults") }

        // Stated before any of it exists. A container object is the only
        // way gamenative expresses that -- its installer and its launch
        // dependencies both read the wine version and the variant off one
        // -- so an unsaved instance carries the answer through the steps
        // that run before there is anything on disk to save. The variant
        // is bionic by name: the glibc variant's execution model is proot,
        // which does not exist on arm64 (docs/SPEC.md 5b).
        val wanted = existing ?: Container(CONTAINER_ID).apply {
            containerVariant = Container.BIONIC
            wineVersion = defaults.wineVersion
        }
        // A container made before droidtop asked for bionic by name is a
        // glibc one. Repointing it is the repair; deleting the user's
        // prefix and starting again is not. An existing bionic
        // environment keeps the Wine it has: switching a prefix's Wine is
        // the person's choice, made in its settings.
        if (existing != null && existing.containerVariant != Container.BIONIC) {
            onStatus("Switching the Windows environment to the no-root runtime…")
            existing.containerVariant = Container.BIONIC
            existing.wineVersion = defaults.wineVersion
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
        // it has to do them in that order. WineComponents.ensureWine owns
        // where a build comes from (gamenative's launch dependency for the
        // two Proton 9 builds, upstream's component list for the rest).
        runCatching { WineComponents.ensureWine(context, wanted, onStatus) }
            .onFailure { return@withContext failed("installing Wine", it, it.message ?: "couldn't install Wine -- check the network and retry") }

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
        }.getOrElse { return@withContext failed("installing the system files", it, it.message ?: "system file install threw") }

        if (installed != true) {
            return@withContext failed("installing the system files", null, "the Windows system files failed to install")
        }
        // The image is now the variant it was installed as, and says so.
        // gamenative writes this marker in its own pre-launch screen
        // (XServerScreen.setImagefsContainerVariant), which droidtop never
        // runs; without it ImageFs.variant reads "" and every later setup,
        // here and in installIfNeededFuture, sees a mismatch and installs
        // the whole image again (Droidtop/tracker#249: the second
        // "Download now" ran the full install a second time).
        imageFs.createVariantFile(wanted.containerVariant)

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
                // NEVER a plain recursive walk here. A Wine prefix
                // contains dosdevices/z:, a symlink to the filesystem
                // root, and one symlink per drive letter; Kotlin's
                // deleteRecursively follows symlinked directories, and
                // on the test device the walk left the prefix and
                // emptied internal storage. SafeDelete proves the
                // target is inside the ImageFs before deleting and its
                // walk cannot follow a symlink at all -- containment is
                // verified, not delegated to a helper that refuses.
                if (!SafeDelete.deleteWithin(imageFs.rootDir, halfMade)) {
                    return@withContext PcProvisionResult(
                        false,
                        "couldn't clear the unfinished setup at ${halfMade.absolutePath} -- nothing was deleted",
                    )
                }
            }
        }

        val container = existing ?: runCatching {
            manager.createContainer(
                CONTAINER_ID,
                JSONObject().apply {
                    put("name", CONTAINER_NAME)
                    put("containerVariant", Container.BIONIC)
                    put("wineVersion", defaults.wineVersion)
                    put("drives", drivesFor(gamesRoots))
                },
            )?.also { created ->
                // The rest of the device's defaults (emulator, its
                // versions, graphics driver, DXVK/VKD3D), through
                // gamenative's own save path; every other field stays
                // the container's own default, as before.
                ContainerUtils.applyToContainer(
                    context,
                    created,
                    ContainerUtils.toContainerData(created).copy(
                        emulator = defaults.emulator,
                        box64Version = defaults.box64Version,
                        fexcoreVersion = defaults.fexcoreVersion,
                        graphicsDriver = defaults.graphicsDriver,
                        graphicsDriverConfig = defaults.graphicsDriverConfig,
                        dxwrapper = defaults.dxwrapper,
                        dxwrapperConfig = defaults.dxwrapperConfig,
                    ),
                )
            }
        }
            .getOrElse { return@withContext failed("creating the container", it, it.message ?: "container creation threw") }
            ?: return@withContext PcProvisionResult(
                false,
                "couldn't create the Wine prefix (the container pattern may have failed to download)",
            )

        // This points the environment's own "xuser" home at this
        // container, and anything reading container-relative paths
        // depends on it having happened.
        runCatching { manager.activateContainer(container) }
            .getOrElse { return@withContext failed("activating the container", it, it.message ?: "couldn't activate the container") }

        // Last: everything the environment's settings name beyond Wine
        // itself -- on x86_64 the guest libraries and the graphics driver,
        // anywhere the DXVK, VKD3D, FEXCore or Box64 version and the driver
        // build -- the same step every launch runs (WineComponents).
        runCatching { WineComponents.ensure(context, container, onStatus) }
            .onFailure {
                return@withContext failed(
                    "downloading the Windows components",
                    it,
                    it.message ?: "couldn't download the Windows components -- check the network and retry",
                )
            }

        when (val readiness = wineEngine.readiness(container)) {
            // Ready only if the container reads back the way every later
            // decision reads it ([isProvisioned], the game page's Set up
            // row): a prefix whose config did not land on disk loads as
            // nothing, and reporting success then is the silent "Set up"
            // loop of Droidtop/tracker#249.
            is WineEngineReadiness.Ready -> if (isProvisioned) {
                PcProvisionResult(true, "Windows environment ready")
            } else {
                failed(
                    "reading the new container back",
                    null,
                    "Windows setup finished, but its environment could not be read back. Try Set up again.",
                )
            }
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

    override suspend fun launchWindows(
        executable: File,
        gameRoot: File,
        workingDir: File,
        arguments: List<String>,
        entryId: String?,
    ): PcLaunchResult {
        // The same rule the game's Wine settings resolve with, so the
        // prefix somebody edited is the prefix this starts in: the game's
        // own when it has one, droidtop's shared one otherwise. (This used
        // to ask for the shared one always, so a game's own prefix was
        // configurable but never launched into.)
        val container = PcContainers.forGame(context, entryId)
            ?: return PcLaunchResult(
                false,
                // Names the real action that now exists. This used to say
                // "create one under Desktop mode > Containers", a screen
                // that had never been built -- pointing someone at a
                // place they cannot reach is worse than saying nothing.
                "the Windows environment isn't set up yet -- run \"Set up Windows games\" in Settings",
            )
        // A game sharing the prefix runs with its own Wine choices laid over
        // it for this launch only (docs/SPEC.md 5a); the shared prefix's
        // saved settings are not touched.
        withContext(Dispatchers.IO) { container.setLaunchOverrides(WineOptions.launchOverrides(context, entryId, container)) }

        return launchInPrefix(wineEngine, container, executable.absolutePath, workingDir, arguments)
    }

    override fun prefixState(entryId: String?): PcPrefixState? {
        val container = PcContainers.forGame(context, entryId) ?: return null
        val env = EnvVars(container.envVars)
        return PcPrefixState(
            name = container.name,
            shared = !PcContainers.isOwnPrefix(entryId, container),
            dxvk = !container.dxWrapper.orEmpty().startsWith(WINED3D, ignoreCase = true),
            // The launch puts WINEESYNC=1 when the prefix says nothing.
            esync = env.get("WINEESYNC") != "0",
            components = KeyValueSet(container.winComponents).mapNotNull { (id, on) -> id.takeIf { on == "1" } }.toSet(),
            env = env.associateWith { env.get(it) },
            dllOverrides = DllOverrides.parse(env.get("WINEDLLOVERRIDES")),
        )
    }

    /**
     * gamenative's own read-modify-write of a container
     * ([ContainerUtils.toContainerData] then [ContainerUtils.applyToContainer]),
     * the path its configuration dialog saves through, so an import and a
     * hand edit land in the prefix identically.
     */
    override suspend fun applyPrefixChanges(entryId: String?, changes: WinePrefixChanges): PcProvisionResult =
        withContext(Dispatchers.IO) {
            val container = PcContainers.forGame(context, entryId)
                ?: return@withContext PcProvisionResult(false, "There is no Windows prefix yet. Set up Windows games first.")
            runCatching {
                val data = ContainerUtils.toContainerData(container)
                val env = EnvVars(data.envVars)
                changes.esync?.let { env.put("WINEESYNC", if (it) "1" else "0") }
                changes.env.forEach { (name, value) -> env.put(name, value) }
                if (changes.dllOverrides.isNotEmpty()) {
                    env.put(
                        "WINEDLLOVERRIDES",
                        DllOverrides.format(DllOverrides.parse(env.get("WINEDLLOVERRIDES")) + changes.dllOverrides),
                    )
                }
                val components = KeyValueSet(data.wincomponents)
                changes.components.forEach { components.put(it, "1") }
                val dxwrapper = when (changes.dxvk) {
                    false -> WINED3D
                    // "On" leaves a DXVK-based wrapper (DXVK, VKD3D) as it is.
                    true -> if (data.dxwrapper.startsWith(WINED3D, ignoreCase = true)) "dxvk" else data.dxwrapper
                    null -> data.dxwrapper
                }
                ContainerUtils.applyToContainer(
                    context,
                    container,
                    data.copy(dxwrapper = dxwrapper, envVars = env.toString(), wincomponents = components.toString()),
                )
                PcProvisionResult(true, "Saved to ${container.name}")
            }.getOrElse { PcProvisionResult(false, "The prefix could not be saved: ${it.message ?: it}") }
        }

    override suspend fun launchLinux(executable: File, gameRoot: File): PcLaunchResult {
        val session = primarySession()
            ?: return PcLaunchResult(false, "a native Linux build runs inside Desktop mode's container, and it isn't running")

        val result = runCatching {
            NativeLinuxGameSession(session.container, session.runtime)
                .launch(session.runtime.hostStorageToContainerPath(executable))
        }.getOrElse { return PcLaunchResult(false, it.message ?: it.toString()) }

        return PcLaunchResult(
            succeeded = result.succeeded,
            detail = if (result.succeeded) "ok" else "exit ${result.exitCode}: ${result.stderr.ifBlank { result.stdout }}",
        )
    }

    internal companion object {
        // Trailing digit on purpose: ContainerUtils.extractGameIdFromContainerId
        // parses a trailing numeric run out of the id, and returns 0 for
        // anything that does not parse.
        const val CONTAINER_ID = "1"

        private const val TAG = "droidtop.WineSetup"

        /** gamenative's id for Wine's own Direct3D, the one wrapper that is not DXVK-based. */
        private const val WINED3D = "wined3d"
        const val CONTAINER_NAME = "droidtop"
    }
}

/**
 * WHICH Wine container a PC game runs in, in one place.
 *
 * Two shapes exist and both are real. gamenative keys a container by the
 * store's own app id, so a game somebody configured under GameNative, or
 * gave Wine settings of its own in droidtop ([createOwn]), has a prefix of
 * its own; droidtop provisions ONE container (id [DroidtopPcGameRuntime]
 * writes) for everything else, because a person setting up Windows games
 * once should not be asked to set one up per game. This answers "the
 * game's own prefix if it has one, droidtop's otherwise" for both the
 * launch path and the configuration screen, so the prefix a person edits
 * is provably the prefix the game starts in (docs/SPEC.md 7i, build-plan
 * step 7).
 */
object PcContainers {

    /**
     * [entryId] is droidtop's own PC entry id ("steam:440"); null, or an
     * entry no store knows, means droidtop's own container. Null comes
     * back only when there is no container at all yet, which is the
     * "Set up Windows games" step rather than an error.
     */
    fun forGame(context: Context, entryId: String?): Container? {
        val manager = runCatching { ContainerManager(context) }.getOrNull() ?: return null
        val perGame = entryId?.let { ownId(it) }
        if (perGame != null && runCatching { manager.hasContainer(perGame) }.getOrDefault(false)) {
            return runCatching { manager.getContainerById(perGame) }.getOrNull()
        }
        val containers = runCatching { manager.containers }.getOrNull().orEmpty()
        return containers.firstOrNull { it.id == DroidtopPcGameRuntime.CONTAINER_ID } ?: containers.firstOrNull()
    }

    /** Whether [container] is [entryId]'s own prefix rather than the one every other game shares. */
    fun isOwnPrefix(entryId: String?, container: Container): Boolean =
        entryId?.let { ownId(it) } == container.id

    /**
     * Gives [entryId] a container of its own, named [title], made with
     * [settings] (the shared environment's, with the game's own choices and
     * the Wine build it picked). Only a different Wine build needs this: a
     * Wine build belongs to the prefix it boots, while a game's other choices
     * are laid over the shared prefix at launch (docs/SPEC.md 5a). From then
     * on the game starts in it and its Wine settings edit it. Returns the
     * existing one if the game already has its own. Downloads the Wine build
     * first when it is not on the device, then makes a whole new prefix:
     * never on the main thread.
     */
    suspend fun createOwn(context: Context, entryId: String, title: String, settings: ContainerData): Container {
        val manager = ContainerManager(context)
        val id = ownId(entryId)
        if (manager.hasContainer(id)) return manager.getContainerById(id)
        // Creating a prefix copies Wine's own DLLs out of the installed build,
        // so the build has to be on disk first (the order setup keeps too).
        WineComponents.ensureWine(
            context,
            Container(id).apply {
                containerVariant = settings.containerVariant
                wineVersion = settings.wineVersion
            },
        ) {}
        val created = manager.createContainer(
            id,
            JSONObject().apply {
                put("name", title)
                put("containerVariant", settings.containerVariant)
                put("wineVersion", settings.wineVersion)
                put("drives", settings.drives)
            },
        ) ?: error("couldn't create a prefix for $title")
        ContainerUtils.applyToContainer(context, created, settings.copy(name = title))
        return created
    }

    /**
     * The id of [entryId]'s own container: the one gamenative would have
     * given it ("STEAM_440" for a store entry, the scanner's own app id for
     * a folder game), so a prefix GameNative made for the game is the one
     * it uses; for anything gamenative never had an identity for, one
     * derived from droidtop's own entry id.
     */
    private fun ownId(entryId: String): String = gamenativeAppId(entryId)
        ?: "DROIDTOP_" + java.util.zip.CRC32().apply { update(entryId.toByteArray()) }.value

    private fun gamenativeAppId(entryId: String): String? {
        val source = entryId.substringBefore(':')
        val nativeId = entryId.substringAfter(':', "")
        if (nativeId.isBlank()) return null
        return when (source) {
            "steam" -> "STEAM_$nativeId"
            "gog" -> "GOG_$nativeId"
            "epic" -> "EPIC_$nativeId"
            "amazon" -> "AMAZON_$nativeId"
            // A folder game's entry id IS the scanner's own appId.
            "folder" -> nativeId
            else -> null
        }
    }
}

/**
 * The one way a Windows program starts in a prefix droidtop resolved: a
 * library game ([DroidtopPcGameRuntime.launchWindows]) and a file opened
 * with droidtop ([WinePrefixes.open]) both come through here.
 */
internal suspend fun launchInPrefix(
    engine: WineEngine,
    container: Container,
    target: String,
    workingDir: File,
    arguments: List<String> = emptyList(),
): PcLaunchResult {
    val prefixHostPath = File(container.rootDir, ".wine")
    if (!prefixHostPath.isDirectory) {
        return PcLaunchResult(
            false,
            "container \"${container.name}\" has no Wine prefix at ${prefixHostPath.absolutePath}",
        )
    }
    return runCatching { engine.launch(container, target, workingDir, arguments) }
        .getOrElse { PcLaunchResult(false, it.message ?: it.toString()) }
}

/**
 * Every Wine prefix on this device, for choosing one by hand: the chooser
 * a downloaded `.exe` or `.msi` opens (docs/SPEC.md 4b). Plain values, so
 * `:app` needs none of gamenative's types.
 */
object WinePrefixes {
    data class Prefix(val id: String, val name: String)

    /** droidtop's own environment first, then every per-game prefix. */
    fun list(context: Context): List<Prefix> {
        val containers = runCatching { ContainerManager(context).containers }.getOrNull().orEmpty()
        return containers
            .sortedBy { if (it.id == DroidtopPcGameRuntime.CONTAINER_ID) 0 else 1 }
            .map { Prefix(it.id, it.name?.takeIf(String::isNotBlank) ?: it.id) }
    }

    /**
     * Runs [file] in prefix [prefixId], from where it is. An `.msi` is
     * handed to Wine's own `start /unix`, which opens it with the prefix's
     * registered installer (msiexec) exactly as a double-click in
     * Explorer would; anything else runs directly, as a library game does.
     */
    suspend fun open(context: Context, prefixId: String, file: File): PcLaunchResult {
        val container = withContext(Dispatchers.IO) {
            runCatching { ContainerManager(context).getContainerById(prefixId) }.getOrNull()
        } ?: return PcLaunchResult(false, "that Windows environment no longer exists")
        val workingDir = file.parentFile ?: file
        val engine = BionicWineEngine(context)
        return if (file.name.endsWith(".msi", ignoreCase = true)) {
            launchInPrefix(engine, container, "start", workingDir, listOf("/unix", file.absolutePath))
        } else {
            launchInPrefix(engine, container, file.absolutePath, workingDir)
        }
    }
}
