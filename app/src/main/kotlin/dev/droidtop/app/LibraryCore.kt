package dev.droidtop.app

import android.content.Context
import dev.droidtop.library.EngineGameProvider
import dev.droidtop.library.Library
import dev.droidtop.library.NativeAppProvider
import dev.droidtop.library.FileLibraryIndexStore
import dev.droidtop.library.RoomFavoritesStore
import dev.droidtop.library.RoomPlayHistoryStore
import dev.droidtop.library.consoles.ConsoleRomProvider
import dev.droidtop.runtime.PrimaryContainerSession
import dev.droidtop.runtime.windows.PcGameProvider

/**
 * The shared core every mode builds on: one library, scanned from one set
 * of providers, and one launch resolution behind it. Deliberately NOT
 * mode-gated (docs/SPEC.md, "Modes and what each contributes") -- it is
 * what makes "with Gaming off a game still launches through the library's
 * own resolution" true, rather than a claim.
 *
 * It used to be built in [MainActivity.onCreate], which only ever runs in
 * Gaming or Desktop; with both off, nothing had registered
 * [dev.droidtop.library.PcGameRuntimeRegistry] and a PC game could not
 * resolve at all.
 */
object LibraryCore {

    @Volatile
    private var instance: Library? = null

    /** One instance per process: providers read their roots fresh on every scan, so nothing here goes stale. */
    fun library(context: Context): Library = instance ?: synchronized(this) {
        instance ?: build(context.applicationContext).also { instance = it }
    }

    private fun build(app: Context): Library {
        // Fills library-core's PcGameRuntime seam, which is what makes the
        // WINE_PREFIX / LINUX_CONTAINER launch strategies real rather than
        // error() stubs. The session supplier is only for the native-Linux
        // half; Windows games go through the WineEngine seam and need
        // neither a desktop session nor root. It stays a supplier because
        // DesktopSessionService may still be connecting when this runs --
        // and, with Desktop disabled, never connects at all, which the
        // supplier already expresses as "no primary session".
        dev.droidtop.library.PcGameRuntimeRegistry.runtime =
            dev.droidtop.runtime.windows.DroidtopPcGameRuntime(
                context = app,
                primarySession = {
                    (DesktopSessionService.state.value as? DesktopSessionState.Connected)
                        ?.let { PrimaryContainerSession(it.runtime, it.container) }
                },
            )
        return Library(
            listOf(
                NativeAppProvider(app),
                EngineGameProvider(
                    app,
                    // Every store's install directories, not just Steam's, so a
                    // Ren'Py or RPG Maker game installed from GOG/Epic/Amazon
                    // flows through the same engine detection and launch
                    // resolution as one sitting in a games folder (docs/SPEC.md
                    // section 7g).
                    extraRoots = { dev.droidtop.runtime.windows.PcLibrary.knownInstallRoots() },
                    // The store's own facts about those installs, so engine
                    // detection owning a store game does not also lose its
                    // store, size, compatibility and cover art.
                    storeInstalls = { dev.droidtop.runtime.windows.PcLibrary.knownInstalls() },
                ),
                // Same roots as EngineGameProvider -- a folder can hold real
                // console ROMs (<root>/<systemId>/<romFile>), engine games
                // (<root>/<gameFolder>/...), or both; each provider only ever
                // matches what is actually its own shape.
                ConsoleRomProvider(app),
                // Real discovery (com.winlator.container.ContainerManager's own
                // shortcut scan), themed as ES-DE's "pc" system like any other.
                // It launches through the WineEngine seam, so it needs no
                // desktop session and no root.
                PcGameProvider(app),
            ),
            playHistory = RoomPlayHistoryStore(app),
            favorites = RoomFavoritesStore(app),
            index = FileLibraryIndexStore(java.io.File(app.filesDir, "library-index")),
        )
    }
}
