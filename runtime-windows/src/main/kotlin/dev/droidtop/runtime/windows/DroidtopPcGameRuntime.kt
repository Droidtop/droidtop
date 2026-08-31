package dev.droidtop.runtime.windows

import android.content.Context
import com.winlator.container.ContainerManager
import dev.droidtop.library.PcGameRuntime
import dev.droidtop.library.PcLaunchResult
import dev.droidtop.runtime.NativeLinuxGameSession
import dev.droidtop.runtime.PrimaryContainerSession
import java.io.File

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

    override suspend fun launchWindows(executable: File, gameRoot: File): PcLaunchResult {
        val session = primarySession()
            ?: return PcLaunchResult(false, "no live container session")

        val container = runCatching { ContainerManager(context).containers.firstOrNull() }.getOrNull()
            ?: return PcLaunchResult(
                false,
                "no Wine container exists yet -- create one under Desktop mode > Containers, then retry",
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
}
