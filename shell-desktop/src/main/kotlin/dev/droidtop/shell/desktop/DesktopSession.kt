package dev.droidtop.shell.desktop

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.SurfaceHolder
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.droidtop.hostbridge.DesktopWindows
import dev.droidtop.hostbridge.HiddenWindow
import dev.droidtop.hostbridge.HostBridge
import dev.droidtop.hostbridge.SwayWindow
import dev.droidtop.hostbridge.Toplevel
import dev.droidtop.input.DesktopInputRouter
import dev.droidtop.input.InputSeats
import dev.droidtop.input.PointerTransform
import dev.droidtop.library.Library
import dev.droidtop.library.LaunchResult
import dev.droidtop.library.LibraryKinds
import dev.droidtop.runtime.ContainerApp
import dev.droidtop.runtime.DesktopLaunchRequests
import dev.droidtop.runtime.DesktopScale
import dev.droidtop.runtime.DisplayOutput
import coil3.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.droidtop.library.integrations.PluginHub
import dev.droidtop.library.integrations.PluginPanels
import dev.droidtop.pluginhost.PluginModes
import dev.droidtop.library.settings.DesktopPrefs
import dev.droidtop.library.settings.CatalogScreenLink
import dev.droidtop.library.settings.Place
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.scraper.isPcOrEngineGame
import dev.droidtop.shell.gamepad.pc.PcGameStandalone
import dev.droidtop.shell.gamepad.pc.PcLaunchOfferSheet
import dev.droidtop.library.TaskbarPin
import dev.droidtop.library.TaskbarPins
import dev.droidtop.library.tasks.TaskActions
import dev.droidtop.runtime.systemstatus.NotificationsStore
import dev.droidtop.runtime.tasks.CloseOutcome
import dev.droidtop.runtime.tasks.RunningApp
import dev.droidtop.runtime.tasks.TaskManager
import dev.droidtop.runtime.tasks.TaskPolicy
import dev.droidtop.runtime.tasks.text
import dev.droidtop.shell.gamepad.AppIcon
import dev.droidtop.shell.gamepad.currentShellWindow
import dev.droidtop.shell.gamepad.ActionNote
import dev.droidtop.shell.gamepad.ActionRow
import dev.droidtop.shell.gamepad.ActionSheet
import dev.droidtop.shell.gamepad.MenuTokens
import dev.droidtop.shell.gamepad.StatusCluster
import dev.droidtop.shell.gamepad.hosted.HostedArt
import dev.droidtop.shell.gamepad.query.LauncherSearchApp
import dev.droidtop.shell.gamepad.query.LauncherSearchScreen
import dev.droidtop.library.StartMenuSections
import dev.droidtop.shell.gamepad.hosted.HostedListSheet
import dev.droidtop.shell.gamepad.hosted.HostedRow
import dev.droidtop.shell.gamepad.hosted.PadLegend
import dev.droidtop.shell.gamepad.hosted.QuickMenuStandalone
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.pc.rememberPcLaunch

// Desktop mode's live session (Droidtop/tracker#764, split out of DesktopShell.kt): the surface the
// compositor's output is drawn on and its input seat, the windows and Android tasks the bar lists, and the
// on-screen keyboard that follows the container's text fields.

/** Mirrors dev.droidtop.app.DesktopSessionState without :shell-desktop depending on :app. */
sealed interface DesktopSessionMessage {
    data object Idle : DesktopSessionMessage
    /** [detail]: the latest line the booting container reported (a first boot installs the desktop), if any. */
    data class Connecting(val detail: String? = null) : DesktopSessionMessage
    /** [stopped]: the desktop was running and stopped on its own, rather than failing to start. */
    data class Failed(val reason: String, val stopped: Boolean = false) : DesktopSessionMessage
    /** No desktop image has been chosen, so there is no session to start until Desktop setup runs. */
    data object NeedsSetup : DesktopSessionMessage
}

/**
 * One live session's input (Droidtop/tracker#764): the seat, the router that drives it, and the router's pad chords
 * (docs/SPEC.md 6b "Pad chords", Droidtop/tracker#383), the bundled layout read off the main thread here rather than
 * by the surface's composition. Until the layout is in, the pad behaves as it always has.
 *
 * The seat comes from [InputSeats] rather than being made here: the second-screen trackpad and keyboard (docs/SPEC.md
 * 6c) reach the same container, and two seats over one bridge would break the single normalized seat :input-seat
 * exists to be.
 */
internal class DesktopInputSession(hostBridge: HostBridge, appContext: Context) {
    val router = DesktopInputRouter().also { it.seat = InputSeats.of(hostBridge) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        scope.launch {
            router.chords = withContext(Dispatchers.IO) {
                runCatching {
                    appContext.resources.openRawResource(dev.droidtop.input.R.raw.desktop_pad_chords).bufferedReader().use {
                        dev.droidtop.mapping.ControllerLayout.parse(it.readText())
                    }
                }.onFailure { android.util.Log.w("DesktopSession", "The pad chord layout did not load", it) }.getOrNull()
            }
        }
    }

    /**
     * Ends the session's input. The router is inert with no seat, so clearing it releases whatever the container
     * still thinks is held.
     */
    fun close() {
        scope.cancel()
        router.seat = null
    }
}

@Composable
internal fun BoxScope.DesktopViewport(
    hostBridge: HostBridge?,
    primaryOutput: DisplayOutput?,
    sessionMessage: DesktopSessionMessage,
    onStartSession: () -> Unit,
    onOpenSetup: () -> Unit,
    prefs: DesktopPrefs.Values,
) {
    if (hostBridge != null && primaryOutput != null) {
        var presentFailed by remember { mutableStateOf(false) }

        // One input session per live HostBridge: a session that goes away and comes back does not leave the old
        // connection's held buttons behind ([DesktopInputSession]).
        val context = LocalContext.current
        val input = remember(hostBridge) { DesktopInputSession(hostBridge, context.applicationContext) }
        DisposableEffect(input) {
            onDispose { input.close() }
        }
        val router = input.router

        // The output is sized from the surface and the scale and resolution settings; the latest settings are read
        // here, so a change made while the desktop shows applies at once, not at the next surface change.
        val currentPrefs by rememberUpdatedState(prefs)
        var surfaceSize by remember(hostBridge) { mutableStateOf<Pair<Int, Int>?>(null) }
        // The density of the screen the desktop's surface is on (Droidtop/tracker#386): the one source the scale is
        // worked out from, here and in Settings, rather than whichever screen the activity or Settings is on.
        var surfaceDpi by remember(hostBridge) { mutableStateOf<Int?>(null) }
        val scope = rememberCoroutineScope()
        fun applySize(width: Int, height: Int) {
            val dpi = surfaceDpi ?: context.resources.displayMetrics.densityDpi
            val scale = DesktopScale.resolve(currentPrefs.scale, dpi)
            // Below full resolution the output is asked for fewer pixels than the view
            // has and SurfaceFlinger scales the buffer up (Droidtop/tracker#426); the
            // scale shrinks with it so the layout keeps its size.
            val factor = DesktopScale.resolutionFactor(currentPrefs.resolution)
            val (outW, outH) = DesktopScale.outputSize(width, height, factor)
            val sized = hostBridge.setOutputSize(outW, outH, DesktopScale.outputScale(scale, factor))
            router.transform = PointerTransform(
                viewWidth = width,
                viewHeight = height,
                outputWidth = if (sized) outW else primaryOutput.widthPx,
                outputHeight = if (sized) outH else primaryOutput.heightPx,
            )
        }
        LaunchedEffect(prefs.scale, prefs.resolution) {
            surfaceSize?.let { (width, height) -> applySize(width, height) }
        }

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                dev.droidtop.input.DesktopSurfaceView(ctx, router).apply {
                    router.attachTo(this)
                    DesktopKeyboardTarget.surface = java.lang.ref.WeakReference(this)
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            presentFailed = !hostBridge.presentOutput(primaryOutput, holder.surface)
                        }

                        // The only place the view's real size is known:
                        // width/height here are the surface's, which is not
                        // the panel size (the bar's height is laid out beside it, #682). The
                        // compositor is asked to make its output exactly
                        // this size, so a frame lands 1:1 on the view rather
                        // than being stretched, and the input transform is
                        // rebuilt with it. Both on every change, so a
                        // rotation or a lapdock resize cannot leave a stale
                        // size behind. Until the compositor has applied the
                        // new size the frame is scaled to fit, which the
                        // STRETCH transform already accounts for (it only
                        // hands the compositor ratios).
                        override fun surfaceChanged(
                            holder: SurfaceHolder,
                            format: Int,
                            width: Int,
                            height: Int,
                        ) {
                            surfaceSize = width to height
                            surfaceDpi = screenDpi(this@apply)?.also { dpi ->
                                scope.launch(Dispatchers.IO) { DesktopPrefs.setScreenDpi(context.applicationContext, dpi) }
                            }
                            applySize(width, height)
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            router.transform = null
                            router.releaseHeldInput()
                            hostBridge.stopPresenting()
                        }
                    })
                }
            },
        )

        if (presentFailed) {
            Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.desktop_output_failed), color = MaterialTheme.colorScheme.onSurface)
                Text(
                    stringResource(R.string.desktop_output_failed_hint),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    } else {
        Column(
            modifier = Modifier.align(Alignment.Center).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (sessionMessage) {
                is DesktopSessionMessage.Connecting -> {
                    Text(stringResource(R.string.desktop_starting), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleLarge)
                    // What is going on and how long it takes, nothing about root or the machinery (Droidtop/tracker#693):
                    // droidtop never asks for root itself, and the line under it is the boot's own progress.
                    Text(
                        stringResource(R.string.desktop_starting_detail),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    sessionMessage.detail?.let { detail ->
                        Text(
                            detail,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                    }
                }
                is DesktopSessionMessage.Failed -> {
                    Text(stringResource(if (sessionMessage.stopped) R.string.desktop_stopped else R.string.desktop_start_failed), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleLarge)
                    Text(
                        sessionMessage.reason,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    // The failure may be the setup itself (an image the catalog no longer has), so the
                    // way to fix it sits beside the retry rather than in the sentence (Droidtop/tracker#370).
                    Row(modifier = Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = onStartSession) { Text(stringResource(R.string.desktop_try_again)) }
                        androidx.compose.material3.OutlinedButton(onClick = onOpenSetup) { Text(stringResource(R.string.desktop_setup)) }
                    }
                }
                is DesktopSessionMessage.NeedsSetup -> {
                    // Nothing to start yet: the one next step is choosing the Linux system the
                    // desktop runs, so the page offers exactly that (Droidtop/tracker#370).
                    Text(stringResource(R.string.desktop_set_up), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Choose the Linux system it runs.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Button(onClick = onOpenSetup, modifier = Modifier.padding(top = 16.dp)) { Text(stringResource(R.string.desktop_setup)) }
                }
                is DesktopSessionMessage.Idle -> {
                    // The one thing to do here is start it, so the screen
                    // offers exactly that (rig, dq-desk2-02: the taskbar's
                    // "Start" is the Start menu, not the session).
                    Text(stringResource(R.string.desktop_not_running), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleLarge)
                    Text(
                        "It was stopped. Start it to see your desktop here.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Button(onClick = onStartSession, modifier = Modifier.padding(top = 16.dp)) { Text(stringResource(R.string.desktop_start)) }
                }
            }
        }
    }
}

/**
 * The on-screen keyboard comes up when a text field in the container takes the focus, and goes when it loses it
 * (Droidtop/tracker#685); the bar's Keyboard button still shows and hides it by hand.
 */
@Composable
internal fun FollowTextFields(hostBridge: HostBridge?) {
    DisposableEffect(hostBridge) {
        val mainHandler = Handler(Looper.getMainLooper())
        hostBridge?.textFieldListener = { focused ->
            // Fires from a native worker thread (see HostBridge.kt).
            mainHandler.post { DesktopKeyboardTarget.surface?.get()?.followTextField(focused) }
        }
        onDispose { hostBridge?.textFieldListener = null }
    }
}

/**
 * The container's windows as the bridge reports them, kept current by the bridge's one change listener
 * (it has room for just one, so the taskbar and the windows sheet share this list). Empty with no bridge.
 */
@Composable
internal fun rememberToplevels(hostBridge: HostBridge?): List<Toplevel> {
    var toplevels by remember(hostBridge) { mutableStateOf(hostBridge?.toplevels() ?: emptyList()) }
    DisposableEffect(hostBridge) {
        val mainHandler = Handler(Looper.getMainLooper())
        hostBridge?.toplevelsChangedListener = {
            // Fires from a native worker thread (see HostBridge.kt).
            mainHandler.post { toplevels = hostBridge.toplevels() }
        }
        onDispose { hostBridge?.toplevelsChangedListener = null }
    }
    return toplevels
}

/**
 * The Android apps and games droidtop started and that are still running ([TaskManager.snapshot], the one
 * task list the Quick Menu, the companion and Standard's home read too), kept current while Desktop is
 * showing and only then. A game launched from the Start menu is a fullscreen Activity over the desktop,
 * which the compositor's window list cannot show, so without this the bar listed neither it nor any
 * Android app (Droidtop/tracker#352). Without Shizuku the list holds just the apps droidtop opened itself.
 */
@Composable
internal fun rememberAndroidApps(): List<RunningApp> {
    val context = LocalContext.current
    LaunchedEffect(Unit) { TaskManager.watch(context) }
    val snapshot by TaskManager.snapshot.collectAsState()
    return remember(snapshot) { snapshot?.apps.orEmpty() }
}

internal fun openAndroidApp(context: Context, app: RunningApp) {
    TaskActions.bringTo(context, app.packageName, app.displayId)?.let {
        dev.droidtop.library.settings.Notices.flash(it, context = context)
    }
}

/** Brings one of droidtop's own screens back to the front, and says so when Android no longer has it. */
internal fun openOwnScreen(context: Context, screen: dev.droidtop.runtime.tasks.OwnScreen) {
    if (!dev.droidtop.runtime.tasks.OwnScreens.bringToFront(context, screen)) {
        dev.droidtop.library.settings.Notices.flash("${screen.title} is no longer open", context = context)
    }
}

/** Closes through the task manager's strongest path and says so only when that did not end it. */
internal suspend fun closeAndroidApp(context: Context, app: RunningApp) {
    val outcome = TaskManager.close(context, app.packageName)
    if (outcome !is CloseOutcome.Closed) dev.droidtop.library.settings.Notices.flash(outcome.text, context = context)
}

/** The desktop's surface, for the bar's Keyboard button (Droidtop/tracker#685). Main thread. */
internal object DesktopKeyboardTarget {
    var surface: java.lang.ref.WeakReference<dev.droidtop.input.DesktopSurfaceView>? = null
}

/** Android's density for the screen [view] is on, or null before it is attached to one (Droidtop/tracker#386). */
@Suppress("DEPRECATION")
private fun screenDpi(view: android.view.View): Int? {
    val display = view.display ?: return null
    val metrics = android.util.DisplayMetrics()
    display.getRealMetrics(metrics)
    return metrics.densityDpi.takeIf { it > 0 }
}
