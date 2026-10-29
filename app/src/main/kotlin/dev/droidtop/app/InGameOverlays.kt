package dev.droidtop.app

import android.app.Application
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.WindowManager
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.droidtop.runtime.overlay.OverlayRequest
import dev.droidtop.runtime.overlay.OverlayRequests
import dev.droidtop.shell.gamepad.LocalInGameOverlay
import dev.droidtop.shell.gamepad.hosted.GameOptionsStandalone
import dev.droidtop.shell.gamepad.hosted.LeftMenuStandalone
import dev.droidtop.shell.gamepad.hosted.QuickMenuStandalone
import dev.droidtop.shell.standard.BackButtonMenu
import dev.droidtop.library.settings.Mode
import dev.droidtop.library.settings.Modes
import dev.droidtop.library.settings.Place
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The in-game overlays (docs/SPEC.md "In-game overlays: HOME chords", Droidtop/tracker#740): droidtop's own menus drawn
 * OVER the app in front, which keeps running and keeps its window. The owner: "Not backgrounding the game, just drawing
 * over it"; and, correcting the first plan, "We don't want to pause the game or etc when we do this": nothing is sent to
 * the app when an overlay opens or closes.
 *
 * A HOME chord ([OverlayRequests], raised by the accessibility key filter) opens one on the display the app is on, in
 * [GameOverlay]'s window, at that display's canvas and Interface size (the theme's ProvideShellCanvas reads the
 * window's own display). It is the shell's own composable, not a copy: the Quick Menu is [QuickMenuStandalone], the one
 * Desktop's tray and Standard's gesture open. Its sheets are ShellDialogs, which inside an overlay are sub-windows of it
 * ([LocalInGameOverlay]), since a Dialog has no activity here. The overlay window is focusable, so the pad's keys go to
 * it, and to a sheet it opens, by Android's own focus (as [LaunchAlertOverlay]'s card takes them); B, or the same chord
 * again, closes it. Main process only.
 *
 * Built: the Quick Menu (HOME + R2), on the running game's Game section, whose "Pause the game" is the one explicit
 * way to pause it; the running game's own menu (HOME + L2, [GameOptionsStandalone]), what L2 opens on it in Gaming;
 * and the left menu's launcher content (HOME + Start, [LeftMenuStandalone]), whose destinations open droidtop.
 */
internal object InGameOverlays {
    private const val TAG = "droidtop.InGameOverlay"
    private lateinit var app: Application
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var shown: Shown? = null

    private class Shown(val kind: OverlayRequest.Kind, val view: ComposeView, val windowManager: WindowManager, val lifecycle: LifecycleRegistry)

    fun install(application: Application) {
        val main = Build.VERSION.SDK_INT < 28 || Application.getProcessName() == application.packageName
        if (!main) return
        app = application
        scope.launch {
            OverlayRequests.requests.collect { request ->
                val open = shown
                // The same chord again closes it; another chord replaces it.
                if (open != null && open.kind == request.kind) {
                    close()
                } else {
                    close()
                    show(request)
                }
            }
        }
    }

    private fun show(request: OverlayRequest) {
        val library = LibraryCore.library(app)
        // The game under the overlay: the one droidtop launched and still runs.
        val runningEntry = dev.droidtop.library.LaunchDisplay.running.value?.context?.gameId?.let { id ->
            library.backgroundScanState(dev.droidtop.library.LibraryKinds.GAMES).value?.firstOrNull { it.id == id }
        }
        // HOME + L2 is a game's menu: with no game droidtop started under it there is nothing to show.
        if (request.kind == OverlayRequest.Kind.CONTEXT_MENU && runningEntry == null) {
            Log.i(TAG, "HOME + L2 with no game droidtop started in front")
            return
        }
        // The left menu is Gaming's, and its rows open Gaming: it is drawn only when Gaming is the mode in use, so a row
        // never switches the person into a mode they are not using (the mode boundary, as PlaceLinks).
        if (request.kind == OverlayRequest.Kind.LEFT_MENU && !Place.opensInGaming(Modes.lastMode(app), Modes.enabled)) {
            Log.i(TAG, "HOME + Start with Gaming not the mode in use")
            return
        }
        val (windowContext, type) = GameOverlay.windowContext(app, request.displayId) ?: run {
            Log.i(TAG, "no overlay on display ${request.displayId} (no accessibility service, no Display over other apps)")
            return
        }
        val wm = windowContext.getSystemService(WindowManager::class.java) ?: return
        val lifecycleOwner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        val savedStateOwner = object : SavedStateRegistryOwner {
            val controller = SavedStateRegistryController.create(this)
            override val lifecycle: Lifecycle get() = lifecycleOwner.lifecycle
            override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry
        }
        savedStateOwner.controller.performRestore(null)
        lifecycleOwner.registry.currentState = Lifecycle.State.RESUMED
        val view = ComposeView(windowContext).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(savedStateOwner)
            setContent {
                dev.droidtop.app.ui.DroidtopTheme(gamingThemed = dev.droidtop.app.ui.rememberGamingThemed()) {
                    CompositionLocalProvider(LocalInGameOverlay provides true) {
                        when (request.kind) {
                            OverlayRequest.Kind.QUICK_MENU -> QuickMenuStandalone(
                                library = library,
                                // Start swaps to the left menu, as it does in Gaming.
                                onOpenStartMenu = { swap(OverlayRequest.Kind.LEFT_MENU, request.displayId) },
                                onDismiss = { close() },
                                runningEntry = runningEntry,
                            )
                            OverlayRequest.Kind.CONTEXT_MENU -> runningEntry?.let { entry ->
                                GameOptionsStandalone(
                                    library = library,
                                    entry = entry,
                                    // The game never left: Play is closing the overlay.
                                    onResume = { close() },
                                    onOpenDownloads = { open(PlaceLinks.intent(app, Place.DOWNLOADS)) },
                                    onDismiss = { close() },
                                )
                            }
                            OverlayRequest.Kind.LEFT_MENU -> LeftMenuStandalone(
                                runningTitle = runningEntry?.let { dev.droidtop.library.GameNaming.displayName(it.title) },
                                onResume = { close() },
                                onOpenSection = { section, placeScreenId -> open(sectionIntent(section, placeScreenId)) },
                                onOpenQuickMenu = { swap(OverlayRequest.Kind.QUICK_MENU, request.displayId) },
                                onSearch = { open(Intent(app, LauncherSearchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                                onDismiss = { close() },
                            )
                        }
                    }
                }
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            // Focusable on purpose: the pad's keys must reach the menu, never the game under it.
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        runCatching { wm.addView(view, params) }
            .onSuccess {
                shown = Shown(request.kind, view, wm, lifecycleOwner.registry)
                view.requestFocus()
                Log.i(TAG, "${request.kind} over the app on display ${request.displayId}")
            }
            .onFailure {
                lifecycleOwner.registry.currentState = Lifecycle.State.DESTROYED
                Log.w(TAG, "${request.kind} on display ${request.displayId} refused", it)
            }
    }

    /** One overlay for another on the same display (R2 in the left menu, Start in the Quick Menu). */
    private fun swap(kind: OverlayRequest.Kind, displayId: Int) {
        // Posted: the press that asks for it is still being handled by the window being removed.
        scope.launch(Dispatchers.Main) {
            close()
            show(OverlayRequest(kind, displayId))
        }
    }

    /** Leaves the game for droidtop at [intent]: the overlay closes first. */
    private fun open(intent: Intent) {
        close()
        runCatching { app.startActivity(intent) }.onFailure { Log.w(TAG, "could not open $intent", it) }
    }

    /**
     * Where a left-menu row goes: a place through [PlaceLinks] (Gaming on it when Gaming is the mode in use, else the
     * screen host), any other section in Gaming.
     */
    private fun sectionIntent(section: String, placeScreenId: String?): Intent =
        placeScreenId?.let { Place.byScreenId(it) }?.let { PlaceLinks.intent(app, it) }
            ?: Intent(app, MainActivity::class.java)
                .putExtra(BackButtonMenu.EXTRA_MODE, Mode.GAMING.id)
                .putExtra(BackButtonMenu.EXTRA_GAMING_START_SECTION, section)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun close() {
        val open = shown ?: return
        shown = null
        open.lifecycle.currentState = Lifecycle.State.DESTROYED
        runCatching { open.windowManager.removeView(open.view) }
    }
}
