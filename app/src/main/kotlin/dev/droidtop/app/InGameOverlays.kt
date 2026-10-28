package dev.droidtop.app

import androidx.compose.foundation.layout.fillMaxSize
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
import dev.droidtop.shell.gamepad.hosted.AppInFrontStandalone
import dev.droidtop.shell.gamepad.hosted.GameOptionsStandalone
import dev.droidtop.shell.gamepad.hosted.LeftMenuStandalone
import dev.droidtop.shell.gamepad.hosted.MenuPickerStandalone
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
 * Over whatever is on screen (the owner, 2026-10-29: "home+ r2 draws over the screen, too. On steanos, the qam can
 * always be called up"): any app or game, droidtop's own screens in every mode, Android's Settings and dialogs. The
 * Quick Menu (HOME + R2), the shell's own and unchanged; HOME + L2, the menu of what is in front: the running game's
 * own overlay ([GameOptionsStandalone]: its name, Resume, "Pause the game", the one explicit way to pause it, the
 * game's own options, Quit and Kill), another app's ([AppInFrontStandalone]), or, over droidtop's own screen, that
 * screen's own L2 menu; and the left menu's launcher content (HOME + Start, [LeftMenuStandalone]), whose destinations
 * open droidtop, its Gaming sections only while Gaming is the mode in use.
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
                // A window Android took away (its display went, the service unbound) is not open any more: the chord
                // opens a new one instead of closing what is no longer there (Droidtop/tracker#740).
                shown?.takeIf { !it.view.isAttachedToWindow }?.let {
                    Log.i(TAG, "${it.kind} was no longer on screen")
                    close()
                }
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
        // HOME + L2 is the menu of what is in front: the game droidtop launched, another app (Android's Settings included),
        // or droidtop's own screen, whose own L2 menu it opens.
        val front = request.frontPackage
        val gameInFront = runningEntry != null && (front == null || front == dev.droidtop.library.LaunchDisplay.runningPackageName)
        if (request.kind == OverlayRequest.Kind.CONTEXT_MENU && !gameInFront) {
            when (front) {
                null -> {
                    Log.i(TAG, "HOME + L2 with nothing known in front")
                    return
                }
                app.packageName -> {
                    pressL2OnDroidtop()
                    return
                }
            }
        }
        // The left menu's Gaming sections open Gaming: they are offered only while Gaming is the mode in use, so a row
        // never switches the person into a mode they are not using (the mode boundary, as PlaceLinks); the places open
        // in every mode.
        val gamingInUse = Place.opensInGaming(Modes.lastMode(app), Modes.enabled)
        val (windowContext, type) = GameOverlay.windowContext(app, request.displayId) ?: run {
            Log.i(TAG, "no overlay on display ${request.displayId} (no accessibility service, no Display over other apps)")
            return
        }
        val wm = windowContext.getSystemService(WindowManager::class.java) ?: return
        val (view, lifecycle) = overlayView(windowContext, request.displayId) {
            when (request.kind) {
                OverlayRequest.Kind.QUICK_MENU -> QuickMenuStandalone(
                    // Start swaps to the left menu, as it does in Gaming.
                    onOpenStartMenu = { swap(request.copy(kind = OverlayRequest.Kind.LEFT_MENU)) },
                    onDismiss = { close() },
                    // Power and mode's Search tile, the left menu's Search row until Droidtop/tracker#621 M4.
                    onSearch = { open(Intent(app, LauncherSearchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                )
                OverlayRequest.Kind.CONTEXT_MENU -> if (!gameInFront) {
                    front?.let { packageName ->
                        AppInFrontStandalone(
                            packageName = packageName,
                            onResume = { close() },
                            onAppInfo = {
                                open(
                                    Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", packageName, null))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            },
                            onDismiss = { close() },
                        )
                    }
                } else runningEntry?.let { entry ->
                    GameOptionsStandalone(
                        library = library,
                        entry = entry,
                        // The game never left: Play is closing the overlay.
                        onResume = { close() },
                        // Restart, once the game really ended: the one launch path, as the shell's.
                        onLaunch = { scope.launch { runCatching { library.launch(it) }.onFailure { e -> Log.w(TAG, "restart of ${it.title} failed", e) } } },
                        onOpenDownloads = { open(PlaceLinks.intent(app, Place.DOWNLOADS)) },
                        onDismiss = { close() },
                    )
                }
                OverlayRequest.Kind.PICKER -> MenuPickerStandalone(
                    contextTitle = when {
                        gameInFront -> "This game"
                        front == app.packageName -> "This screen"
                        else -> "This app"
                    },
                    onQuickMenu = { swap(request.copy(kind = OverlayRequest.Kind.QUICK_MENU)) },
                    onContextMenu = { swap(request.copy(kind = OverlayRequest.Kind.CONTEXT_MENU)) },
                    onStartMenu = { swap(request.copy(kind = OverlayRequest.Kind.LEFT_MENU)) },
                    onSwitchMode = {
                        close()
                        runCatching { dev.droidtop.shell.standard.ModeSwitcher.open(app) }.onFailure { Log.w(TAG, "mode switcher did not open", it) }
                    },
                    onDismiss = { close() },
                )
                OverlayRequest.Kind.LEFT_MENU -> LeftMenuStandalone(
                    gamingSections = gamingInUse,
                    runningTitle = runningEntry?.let { dev.droidtop.library.GameNaming.displayName(it.title) },
                    onResume = { close() },
                    onOpenSection = { section, placeScreenId -> open(sectionIntent(section, placeScreenId)) },
                    onOpenQuickMenu = { swap(request.copy(kind = OverlayRequest.Kind.QUICK_MENU)) },
                    onDismiss = { close() },
                )
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
                shown = Shown(request.kind, view, wm, lifecycle)
                OverlayRequests.open = true
                view.requestFocus()
                Log.i(TAG, "${request.kind} over ${request.frontPackage ?: "the screen"} on display ${request.displayId}")
            }
            .onFailure {
                lifecycle.currentState = Lifecycle.State.DESTROYED
                Log.w(TAG, "${request.kind} on display ${request.displayId} refused", it)
            }
    }

    /**
     * The one host for an overlay's content, in a window that has no activity: its own lifecycle and saved state,
     * droidtop's theme, what an activity would give the shell's screens ([ProvideOverlayOwners][dev.droidtop.shell.gamepad.hosted.ProvideOverlayOwners],
     * Droidtop/tracker#788) and [LocalInGameOverlay]. The overlays and the on-device check of every Quick Menu tab
     * (OverlayQuickMenuRigTest) both draw through it. Returns the view and its lifecycle, set to destroyed on close.
     */
    internal fun overlayView(
        windowContext: android.content.Context,
        displayId: Int = android.view.Display.DEFAULT_DISPLAY,
        content: @androidx.compose.runtime.Composable () -> Unit,
    ): Pair<ComposeView, LifecycleRegistry> {
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
        // The shell's text scale and canvas density on this display, as droidtop's activities get them.
        val view = ComposeView(AccessibilityPrefs.canvasContext(windowContext, displayId)).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(savedStateOwner)
            setContent {
                dev.droidtop.app.ui.DroidtopTheme(gamingThemed = dev.droidtop.app.ui.rememberGamingThemed()) {
                    // What an activity would give the shell's screens: a catalog tab's pickers and Back.
                    dev.droidtop.shell.gamepad.hosted.ProvideOverlayOwners {
                        CompositionLocalProvider(LocalInGameOverlay provides true) {
                            // An overlay window holds its own focus: its first D-pad press moves it (Droidtop/tracker#820).
                            dev.droidtop.shell.gamepad.PadFocusHolder(androidx.compose.ui.Modifier.fillMaxSize(), content = content)
                        }
                    }
                }
            }
        }
        return view to lifecycleOwner.registry
    }

    /** One overlay for another on the same display (R2 in the left menu, Start in the Quick Menu). */
    private fun swap(next: OverlayRequest) {
        // Posted: the press that asks for it is still being handled by the window being removed.
        scope.launch(Dispatchers.Main) {
            close()
            show(next)
        }
    }

    /**
     * HOME + L2 over droidtop's own screen: that screen's own context menu, the one L2 opens there, so droidtop's screen
     * gets the L2 press the chord took. Nothing is drawn by the overlay itself.
     */
    private fun pressL2OnDroidtop() {
        // Only the screen the person is on: a shell stopped behind another droidtop screen (Settings) has no focus.
        val shell = ForegroundShell.current()?.takeIf { !it.isFinishing && !it.isDestroyed && it.hasWindowFocus() } ?: run {
            Log.i(TAG, "HOME + L2 over droidtop with no screen of its own to take it")
            return
        }
        val now = android.os.SystemClock.uptimeMillis()
        listOf(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.ACTION_UP).forEach { action ->
            shell.dispatchKeyEvent(
                android.view.KeyEvent(
                    now, now, action, android.view.KeyEvent.KEYCODE_BUTTON_L2, 0, 0, android.view.KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
                    android.view.InputDevice.SOURCE_GAMEPAD,
                ),
            )
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

    /**
     * Takes the overlay down and hands the pad back to what is under it (Droidtop/tracker#740: after the overlays the
     * D-pad no longer reached RetroArch). In order: the composition first, while its window is still attached, so every
     * focusable sub-window it opened (a sheet's Popup) is removed and none is left holding window focus; then the window
     * itself, at once rather than on the next traversal; then the lifecycle. The window that has focus afterwards is
     * logged, so a console log shows whether anything of droidtop's still holds the pad.
     */
    private fun close() {
        val open = shown ?: return
        shown = null
        OverlayRequests.open = false
        runCatching { open.view.disposeComposition() }.onFailure { Log.w(TAG, "${open.kind}: composition not disposed", it) }
        runCatching { open.windowManager.removeViewImmediate(open.view) }.onFailure { Log.w(TAG, "${open.kind}: window not removed", it) }
        open.lifecycle.currentState = Lifecycle.State.DESTROYED
        Log.i(TAG, "${open.kind} closed; window attached=${open.view.isAttachedToWindow}")
    }
}
