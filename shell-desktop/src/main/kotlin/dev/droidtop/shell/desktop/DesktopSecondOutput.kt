package dev.droidtop.shell.desktop

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.droidtop.hostbridge.DesktopWindows
import dev.droidtop.hostbridge.HostBridge
import dev.droidtop.input.DesktopInputRouter
import dev.droidtop.input.InputSeats
import dev.droidtop.input.PointerTarget
import dev.droidtop.input.PointerTransform
import dev.droidtop.library.settings.DesktopPrefs
import dev.droidtop.shell.gamepad.currentShellWindow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn

/**
 * The Linux desktop's second output on the Android display it was made for (Droidtop/tracker#908, step D3): a surface
 * showing that output, which takes touch and a mouse for it, and a bar of its own. The host (`:app`'s
 * DesktopSecondScreenActivity) gives it the session's bridge and the output's name, and sizes the output from
 * [onSurfaceSize] the way the primary's is sized (`DesktopSessionService.extendTo` with the surface's pixels and
 * density, the same [dev.droidtop.runtime.DesktopScale] maths as [DesktopViewport]).
 *
 * [outputName] is null until the session has made the output, and again once it has removed it; nothing is presented
 * and no pointer motion is sent meanwhile ([PointerTarget.Nowhere]). [outputCount] is how many outputs the desktop
 * has. The pad and the keys stay with the primary surface (the host's window is touch-only, as the companion's is);
 * the bar lists every window for now, since which output a window is on is step D4.
 */
@Composable
fun DesktopSecondOutput(
    hostBridge: HostBridge,
    outputName: String?,
    outputCount: Int,
    windows: DesktopWindows?,
    onSurfaceSize: (width: Int, height: Int, densityDpi: Int) -> Unit,
) {
    val context = LocalContext.current
    val prefs by remember(context) { DesktopPrefs.changes(context).flowOn(Dispatchers.IO) }
        .collectAsState(initial = DesktopPrefs.Values())
    val toplevels = rememberToplevels(hostBridge)
    dev.droidtop.shell.gamepad.GamingTheme(outsideGaming = true) {
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            val density = LocalDensity.current
            var barHeightPx by remember { mutableIntStateOf(0) }
            val barHeight = with(density) { barHeightPx.toDp() }
            val atTop = prefs.taskbarAtTop
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = if (atTop) barHeight else 0.dp, bottom = if (atTop) 0.dp else barHeight),
            ) {
                SecondOutputSurface(hostBridge, outputName, outputCount, onSurfaceSize)
            }
            Column(
                modifier = Modifier
                    .align(if (atTop) Alignment.TopStart else Alignment.BottomStart)
                    .fillMaxWidth()
                    .onSizeChanged { barHeightPx = it.height },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = currentShellWindow().barTarget(BarInputMode.padDriving))
                        .background(dev.droidtop.shell.gamepad.MenuTokens.Ground),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The container's windows, as the primary's bar lists them; Android's apps and droidtop's own
                    // screens stay on the primary's bar, where Start opened them.
                    TaskbarWindowList(
                        hostBridge = hostBridge,
                        toplevels = toplevels,
                        androidApps = emptyList(),
                        ownScreens = emptyList(),
                        windows = windows,
                        windowIcons = emptyMap(),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * The second output's surface: presents the output named [outputName] while both exist, and routes the touch and
 * mouse it takes to that output ([PointerTarget.choose]). Its own router over the session's one seat
 * ([InputSeats]), with no pad chords and no stick pointer: the pad stays with the primary surface.
 */
@Composable
private fun SecondOutputSurface(
    hostBridge: HostBridge,
    outputName: String?,
    outputCount: Int,
    onSurfaceSize: (width: Int, height: Int, densityDpi: Int) -> Unit,
) {
    val router = remember(hostBridge) {
        DesktopInputRouter().apply {
            seat = InputSeats.of(hostBridge)
            gamepadPointerEnabled = false
        }
    }
    DisposableEffect(router) {
        onDispose { router.seat = null }
    }
    SideEffect {
        router.pointerTarget = PointerTarget.choose(primary = false, surfaceOutput = outputName, outputCount = outputCount)
    }
    val sizeTo by rememberUpdatedState(onSurfaceSize)
    var liveHolder by remember(hostBridge) { mutableStateOf<SurfaceHolder?>(null) }
    var surfaceSize by remember(hostBridge) { mutableStateOf<Triple<Int, Int, Int>?>(null) }
    var presentFailed by remember { mutableStateOf(false) }
    // The output that is presented now, so the surface's end stops exactly that capture.
    val presented = remember(hostBridge) { java.util.concurrent.atomic.AtomicReference<String?>(null) }

    // Present once both the surface and the output exist, and again when either changes.
    LaunchedEffect(outputName, liveHolder) {
        val current = liveHolder
        val previous = presented.get()
        if (previous != null && previous != outputName) {
            hostBridge.stopPresenting(previous)
            presented.set(null)
        }
        if (current != null && outputName != null && presented.get() == null) {
            presentFailed = !hostBridge.presentOutput(outputName, current.surface)
            if (!presentFailed) presented.set(outputName)
        }
    }
    // The output takes the surface's size once it exists: asking before would size an output that is still being
    // made by the session, which then sizes it to the whole display.
    LaunchedEffect(outputName, surfaceSize) {
        val size = surfaceSize ?: return@LaunchedEffect
        if (outputName != null) sizeTo(size.first, size.second, size.third)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    router.attachTo(this)
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(created: SurfaceHolder) {
                            liveHolder = created
                        }

                        override fun surfaceChanged(changed: SurfaceHolder, format: Int, width: Int, height: Int) {
                            // Only the fraction of the way across reaches the compositor (the named call places it
                            // on the output by that fraction), so the view's own size stands for the output's.
                            router.transform = PointerTransform(width, height, width, height)
                            val dpi = screenDpi(this@apply) ?: ctx.resources.displayMetrics.densityDpi
                            surfaceSize = Triple(width, height, dpi)
                        }

                        override fun surfaceDestroyed(destroyed: SurfaceHolder) {
                            router.transform = null
                            router.releaseHeldInput()
                            // Stopped before the surface goes, as the primary's is.
                            presented.getAndSet(null)?.let { hostBridge.stopPresenting(it) }
                            liveHolder = null
                        }
                    })
                }
            },
        )
        if (presentFailed) {
            Text(
                stringResource(R.string.desktop_output_failed),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}
