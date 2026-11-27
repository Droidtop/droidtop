package dev.droidtop.runtime.windows

import android.content.Context
import com.winlator.container.Container
import com.winlator.xenvironment.ImageFs
import com.winlator.xserver.ScreenInfo
import com.winlator.xserver.XServer
import dev.droidtop.library.stores.StoreCommandResult
import dev.droidtop.library.stores.StoreWineContext
import dev.droidtop.runtime.windows.utils.SteamworksShim
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Uses the existing non-root Wine session and prefix preparation; no host values stand in for guest hardware. */
internal class WineStoreTools(private val app: Context, private val container: Container) : StoreWineContext {
    override val prefix: File get() = File(container.rootDir, ".wine")
    override val windowsUser: String get() = ImageFs.USER

    override fun windowsPath(file: File): String = pathInWine(prefix, container.drivesIterator().map { it[0] to it[1] }, file)

    override suspend fun command(target: String, arguments: List<String>, workingDir: File, timeoutMs: Long): StoreCommandResult = withContext(Dispatchers.IO) {
        require(timeoutMs in 1..900_000) { "The store command has an invalid time limit" }
        ensureActive()
        val xServer = XServer(ScreenInfo(container.screenSize), false)
        val session = WineXSession(app, container, target, workingDir, xServer, arguments, silent = true)
        val ended = CompletableDeferred<StoreCommandResult>()
        try {
            session.start { code, output -> ended.complete(StoreCommandResult(code, output)) }
            withTimeoutOrNull(timeoutMs) { ended.await() }
                ?: error("The store's Wine command did not finish within ${timeoutMs / 1000} seconds")
        } finally {
            session.stop()
        }
    }

    companion object {
        internal fun pathInWine(prefix: File, drives: List<Pair<String, String>>, file: File): String {
            val cDrive = File(prefix, "drive_c").canonicalFile
            val actual = file.canonicalFile
            return if (actual == cDrive || actual.toPath().startsWith(cDrive.toPath())) {
                "C:\\" + actual.relativeTo(cDrive).path.replace('/', '\\')
            } else SteamworksShim.windowsPath(drives, actual)
        }
    }
}
