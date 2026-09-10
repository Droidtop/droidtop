package dev.droidtop.library

import android.content.Context
import java.io.File

/**
 * Measures the real device and folder facts behind [RunnerAvailability]
 * for one library entry, and runs the one named action a
 * [RunnerState.NEEDS_SETUP] row asks for.
 *
 * Works for **every** PC entry, not only engine-detected folders
 * (docs/SPEC.md 7i): a Steam game with no engine at all is still a game
 * with a Wine row and a Linux row, and it is the same object in the same
 * list as a Ren'Py folder. That is why the facts are gathered here rather
 * than inside [EngineGameProvider], which by definition only ever sees
 * engine games.
 *
 * The rules themselves live in [RunnerAvailability] and are tested there
 * as plain JVM tests; nothing in this file decides anything.
 */
object PcRunnerOptions {

    /**
     * The folder this entry's runners work on: an engine entry IS its
     * folder, and a store or folder-scanned entry carries its install
     * directory in [PcInfo]. Null when a store game is not installed --
     * there is nothing on disk to ask questions about yet.
     */
    fun gameFolderFor(entry: LibraryEntry): File? {
        entry.pcInfo?.installPath?.takeIf { it.isNotBlank() }?.let { path ->
            return File(path).takeIf { it.isDirectory }
        }
        return File(entry.id).takeIf { it.isDirectory }
    }

    /**
     * Every runner's state for [entry], ordered by [RunnerAvailability].
     *
     * Touches the filesystem, the package manager and enginehost's
     * capabilities provider, so call it off the main thread.
     */
    fun forEntry(context: Context, entry: LibraryEntry): List<RunnerOption> {
        val folder = gameFolderFor(entry) ?: return emptyList()
        val detected = runCatching {
            GameEngineDetector.detectGame(
                folder,
                EnginesDatabase.defs(context),
                override = { candidate -> EngineOverridePrefs.engineFor(context, candidate.absolutePath) },
            )
        }.getOrNull()
        val gameRoot = detected?.gameRoot ?: folder
        val engine = detected?.engine
        val target = engine?.let { EnginesDatabase.enginehostTargetFor(context, it) }
        val engineVersion = engine?.let { resolveEngineVersion(context, gameRoot, it) }
        val runtime = PcGameRuntimeRegistry.runtime
        return RunnerAvailability.evaluate(
            GameLaunchStrategyResolver.facts(
                engine = engine,
                folder = gameRoot,
                kirikiroid2Installed = Kirikiroid2.isInstalled(context),
                engineHostInstalled = EngineHost.isInstalled(context),
                engineHostEngineVersionKnown = engineVersion != null,
                engineHostCanReachFolder = EngineHost.canReachGameFolder(context, gameRoot),
                enginehostSupported = target != null,
                enginehostBundleCovers = bundleCoverage(context, target, engineVersion),
                windowsEnvironmentReady = runtime?.isProvisioned == true,
                wineRendererWired = RunnerAvailability.WINE_RENDERER_WIRED,
                linuxContainerAvailable = runtime?.isLinuxContainerAvailable == true,
                x86TranslationRegistered = x86TranslationReady(gameRoot),
                preferredOrder = engine?.let { EnginesDatabase.priorityFor(context, it) },
            ),
        )
    }

    /**
     * Performs the one named action a [RunnerState.NEEDS_SETUP] row asks
     * for, and returns null on success or a line to show on failure.
     *
     * Here rather than in the surface because every one of these is a
     * conversation with something library-core already owns the contract
     * for -- enginehost's configure intent, the PC runtime's provision
     * step, a store page for an app droidtop does not bundle. droidtop
     * never side-loads a plugin itself (docs/SPEC.md 7d): it asks
     * enginehost to.
     */
    suspend fun runAction(
        context: Context,
        entry: LibraryEntry,
        action: RunnerAction,
        onStatus: (String) -> Unit = {},
    ): String? = when (action) {
        RunnerAction.INSTALL_ENGINEHOST -> openStorePage(context, EngineHost.PACKAGE_NAME)
        RunnerAction.INSTALL_KIRIKIROID2 -> openStorePage(context, Kirikiroid2.PACKAGE_NAME)
        RunnerAction.INSTALL_ENGINEHOST_PLUGIN, RunnerAction.CHOOSE_ENGINE_VERSION -> configureWithEnginehost(context, entry)
        RunnerAction.SET_UP_WINDOWS_GAMES -> {
            val runtime = PcGameRuntimeRegistry.runtime
            if (runtime == null) {
                "droidtop's PC runtime isn't registered in this process."
            } else {
                val result = runtime.provision(GamesRoots.current(context), onStatus)
                if (result.succeeded) null else result.detail
            }
        }
    }

    private fun openStorePage(context: Context, packageName: String): String? = try {
        context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=$packageName"))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        null
    } catch (t: Throwable) {
        "No app store on this device could open $packageName."
    }

    private fun configureWithEnginehost(context: Context, entry: LibraryEntry): String? {
        val folder = gameFolderFor(entry) ?: return "This game has no folder on this device yet."
        val detected = runCatching {
            GameEngineDetector.detectGame(
                folder,
                EnginesDatabase.defs(context),
                override = { candidate -> EngineOverridePrefs.engineFor(context, candidate.absolutePath) },
            )
        }.getOrNull() ?: return "droidtop can't tell which engine this game uses."
        val target = EnginesDatabase.enginehostTargetFor(context, detected.engine)
        return try {
            context.startActivity(EngineHost.configureIntent(detected.gameRoot, target))
            null
        } catch (t: Throwable) {
            "enginehost didn't accept the configure request: ${t.message}"
        }
    }

    /** [RunnerAvailability.resolve] over [forEntry], with the user's stored override applied. */
    fun resolvedFor(context: Context, entry: LibraryEntry, options: List<RunnerOption>): ResolvedRunner? =
        RunnerAvailability.resolve(
            options,
            LaunchStrategyOverridePrefs.get(context, entry.id),
            defaultLabel = entry.kind.takeIf { it != LibraryEntryKind.WINE_PROFILE }?.displayName(),
        )

    /**
     * Whether an installed enginehost bundle covers this game, or null
     * when enginehost is not installed or reported nothing.
     *
     * Null rather than false on an empty list is the advisory contract
     * (docs/SPEC.md 7d): a provider droidtop could not read must never
     * turn a launch enginehost would resolve into a setup step.
     */
    private fun bundleCoverage(context: Context, target: EnginehostTarget?, engineVersion: String?): Boolean? {
        if (target == null) return null
        val bundles = EnginehostCapabilities.installedBundles(context)
        if (bundles.isEmpty()) return null
        return bundles.any { EnginehostCapabilities.covers(it, target.engine, target.engineContext, engineVersion) }
    }

    /**
     * Whether this folder's native Linux build can actually execute here.
     *
     * A build whose launcher is `<Game>.x86_64`/`.x86` is x86 code and
     * needs binary translation registered with the kernel (`binfmt_misc`,
     * docs/SPEC.md 3c); an arm64 build or a shell wrapper needs nothing,
     * so it is not held to a requirement that does not apply to it. Read
     * from the real registration directory rather than assumed either
     * way -- on a device where it is not mounted the listing is null,
     * which is the honest "not registered".
     */
    private fun x86TranslationReady(gameRoot: File): Boolean {
        val x86Launcher = gameRoot.listFiles()
            ?.any { it.isFile && it.extension.lowercase() in setOf("x86_64", "x86") } == true
        if (!x86Launcher) return true
        return File("/proc/sys/fs/binfmt_misc").listFiles()
            ?.any { it.name.contains("fex", ignoreCase = true) || it.name.contains("x86", ignoreCase = true) } == true
    }
}
