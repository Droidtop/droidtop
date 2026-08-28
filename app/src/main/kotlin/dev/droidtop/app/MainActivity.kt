package dev.droidtop.app

import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import dev.droidtop.library.EngineGameProvider
import dev.droidtop.library.Library
import dev.droidtop.library.NativeAppProvider
import dev.droidtop.library.consoles.ConsoleRomProvider
import dev.droidtop.runtime.DisplayOutputKind
import dev.droidtop.runtime.DisplayOutputRepository
import dev.droidtop.runtime.DualScreenCoordinator
import dev.droidtop.runtime.DualScreenRole
import dev.droidtop.runtime.PrefsDualScreenAssignmentStore
import dev.droidtop.runtime.PrimaryContainerSession
import dev.droidtop.runtime.windows.PcGameProvider
import dev.droidtop.shell.desktop.DesktopSessionMessage
import dev.droidtop.shell.desktop.DesktopShell
import dev.droidtop.shell.gamepad.GamepadShell
import dev.droidtop.shell.standard.BackButtonMenu
import dev.droidtop.shell.standard.ModePrefs
import dev.droidtop.shell.standard.OnboardingGate
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Not an app-drawer entry point — droidtop defaults to the normal Android
 * home-screen experience (`com.android.launcher3.Launcher`, forked in as
 * `:shell-default`'s "Standard" shell; see that module's README and its own
 * AndroidManifest.xml for the real HOME/LAUNCHER intent-filter). This
 * Activity only ever gets started by explicit Intent from a long-press of
 * the back key (`BackButtonMenu`, wired into both `Launcher` and here — see
 * that class's own doc comment), carrying [BackButtonMenu.EXTRA_MODE] to say
 * which of the two non-Standard shells to render.
 *
 * `android:launchMode="singleTask"` (see AndroidManifest.xml) + [onNewIntent]
 * below are both required, not just one or the other: without singleTask,
 * `FLAG_ACTIVITY_NEW_TASK` from [BackButtonMenu] can spawn a second
 * MainActivity instance instead of reusing the running one; without
 * overriding onNewIntent, Android's documented behavior for re-launching an
 * activity that's already the top of its task is to just bring it forward
 * with its *original* Intent/mode still in effect, silently dropping
 * whatever mode the new Intent asked for. This was a real, confirmed bug —
 * once Desktop mode had opened once, no `EXTRA_MODE` switch back to Handheld
 * (or vice versa) could ever take effect, because onCreate (where `mode` was
 * read) never ran a second time.
 *
 * Desktop mode starts [DesktopSessionService] and observes its
 * [DesktopSessionService.state] instead of a hardcoded null HostBridge/
 * DisplayOutput -- real wiring, but the session itself is still expected
 * to land in [DesktopSessionState.Failed] on any real device right now
 * (see that service's own doc comment for the two concrete gaps: no
 * primary container image published yet, and the non-root runtime
 * ([dev.droidtop.runtime.linux.noroot.ProotRuntime]) is unimplemented).
 * [DesktopShell] renders that Idle/Connecting/Failed/Connected state
 * distinctly via its own [dev.droidtop.shell.desktop.DesktopSessionMessage]
 * rather than a single generic placeholder.
 */
class MainActivity : AppCompatActivity() {
    private var secondScreenPresentation: SecondScreenPresentation? = null
    private lateinit var library: Library
    private var mode by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Real gap this closes: OnboardingGate was only ever called from
        // LauncherApplication.java (Standard's own boot) -- a user who
        // launches straight into Desktop/Handheld (droidtop not set as
        // system HOME, or opened via BackButtonMenu/EXTRA_MODE directly)
        // never saw onboarding at all. Both real entry points need this,
        // not just one.
        OnboardingGate.launchIfNeeded(this)

        // Real roots are read fresh by each provider on every scan (see
        // GamesRoots.current's own doc comment) -- not resolved once here
        // and frozen, since that would silently ignore any root added or
        // removed at runtime via the "ROM folders" Settings screen.
        library = Library(
            listOf(
                NativeAppProvider(applicationContext),
                EngineGameProvider(applicationContext),
                // Same roots as EngineGameProvider -- a folder can hold
                // real console ROMs (<root>/<systemId>/<romFile>), engine
                // games (<root>/<gameFolder>/...), or both; each provider
                // only ever matches what's actually its own shape.
                ConsoleRomProvider(applicationContext),
                // Real discovery (com.winlator.container.ContainerManager's
                // own shortcut scan), themed as ES-DE's "pc" system like
                // any other. primarySession is a supplier, not a value,
                // because DesktopSessionService may still be Connecting (or
                // not started at all) at this point -- see PcGameProvider's
                // own doc comment.
                PcGameProvider(applicationContext) {
                    (DesktopSessionService.state.value as? DesktopSessionState.Connected)
                        ?.let { PrimaryContainerSession(it.runtime, it.container) }
                },
            ),
        )
        mode = resolveMode(intent)

        if (mode != BackButtonMenu.MODE_HANDHELD) {
            startForegroundService(Intent(this, DesktopSessionService::class.java))
        }

        observeSecondScreen()

        setContent {
            when (mode) {
                BackButtonMenu.MODE_HANDHELD -> GamepadShell(
                    library = library,
                    onFocusedEntryChanged = { secondScreenPresentation?.focusedEntry = it },
                )
                else -> {
                    val sessionState by DesktopSessionService.state.collectAsState()
                    val connected = sessionState as? DesktopSessionState.Connected
                    DesktopShell(
                        library = library,
                        hostBridge = connected?.hostBridge,
                        primaryOutput = connected?.primaryOutput,
                        sessionMessage = when (val state = sessionState) {
                            is DesktopSessionState.Idle -> DesktopSessionMessage.Idle
                            is DesktopSessionState.Connecting -> DesktopSessionMessage.Connecting
                            is DesktopSessionState.Connected -> DesktopSessionMessage.Idle
                            is DesktopSessionState.Failed -> DesktopSessionMessage.Failed(state.message)
                        },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        mode = resolveMode(intent)
    }

    /**
     * Prefers an explicit [BackButtonMenu.EXTRA_MODE] (a real user choice,
     * from [BackButtonMenu] or Launcher's own cold-boot redirect — see the
     * "droidtop patch" in `Launcher.onCreate`); falls back to
     * [ModePrefs]'s last app-hosted mode when absent, so this Activity
     * resumes correctly even if launched by something that didn't set the
     * extra. Persists whatever mode is resolved as a safety net — every
     * known real caller already does this before launching, but a null
     * write here would be wrong (it would forget the real last mode).
     */
    private fun resolveMode(intent: Intent): String? {
        val explicit = intent.getStringExtra(BackButtonMenu.EXTRA_MODE)
        val resolved = explicit ?: ModePrefs.lastMode(this).takeIf {
            it == BackButtonMenu.MODE_HANDHELD || it == BackButtonMenu.MODE_DESKTOP
        }
        if (resolved != null) ModePrefs.setLastMode(this, resolved)
        return resolved
    }

    /**
     * Live display detection + role resolution + [SecondScreenPresentation]
     * lifecycle management — "primary/lower display detection" and "multi-
     * display fixing" (per direction), not a one-shot check at launch.
     * [DisplayOutputRepository] reacts to a second screen/external lapdock
     * monitor being connected or removed at runtime; [DualScreenCoordinator]
     * resolves which one currently plays [DualScreenRole.LOWER_INPUT] (§4 —
     * a starting guess, user-overridable, persisted). Whenever that
     * resolution names a display, this shows/keeps a [SecondScreenPresentation]
     * on it; when it doesn't (no second screen right now), any existing
     * presentation is dismissed.
     *
     * UNVERIFIED against a real dual-screen device — no hardware with a
     * real secondary `Display` was available while writing this.
     */
    private fun observeSecondScreen() {
        val displayOutputs = DisplayOutputRepository(applicationContext)
        val coordinator = DualScreenCoordinator(PrefsDualScreenAssignmentStore(applicationContext))
        val displayManager = getSystemService(DisplayManager::class.java)

        lifecycleScope.launch {
            displayOutputs.observe().collectLatest { outputs ->
                val roles = coordinator.resolve(outputs)
                val lowerOutput = roles.entries.firstOrNull { it.value == DualScreenRole.LOWER_INPUT }?.key

                if (lowerOutput == null) {
                    secondScreenPresentation?.dismiss()
                    secondScreenPresentation = null
                    return@collectLatest
                }

                // Already showing on this exact display -- nothing to do (avoids
                // tearing down and recreating the Presentation, and losing
                // focusedEntry state, on every unrelated DisplayListener callback).
                if (secondScreenPresentation?.display?.displayId == lowerOutput.androidDisplayId) return@collectLatest

                secondScreenPresentation?.dismiss()
                val display = displayManager.getDisplay(lowerOutput.androidDisplayId) ?: return@collectLatest
                secondScreenPresentation = SecondScreenPresentation(applicationContext, display).also { it.show() }
            }
        }
    }

    override fun onDestroy() {
        secondScreenPresentation?.dismiss()
        secondScreenPresentation = null
        super.onDestroy()
    }

    // Same long-press-of-back shell switcher as Launcher — see
    // BackButtonMenu's doc comment for why long-press rather than a plain
    // back press (which keeps doing its normal job, here just finishing
    // this Activity).
    override fun onKeyLongPress(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            BackButtonMenu.show(this)
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }
}
