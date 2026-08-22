package dev.droidtop.app

import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import dev.droidtop.shell.gamepad.GamepadShell
import dev.droidtop.shell.standard.BackButtonMenu
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

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
 * Desktop mode is temporarily disconnected here while `:shell-gamepad`'s
 * Handheld UI gets real implementation work — [DesktopShell] and
 * [DesktopSessionService] are both untouched and still compile, just not
 * invoked from this Activity right now. Re-wiring them is the only change
 * needed to bring Desktop mode back once Handheld work resumes.
 */
class MainActivity : AppCompatActivity() {
    private var secondScreenPresentation: SecondScreenPresentation? = null
    private lateinit var library: Library
    private var mode by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Every folder OnboardingActivity resolved from the user's own SAF
        // picks (GamesRootPrefs.resolveStoragePath -- primary storage or a
        // real SD card, see that function's own doc comment), zero or more
        // of them -- ROM/game support is opt-in per direction, so an empty
        // set here is a completely normal, expected state (EngineGameProvider
        // just finds nothing to scan), not a fallback-worthy problem. Falls
        // back to the old app-private default only for a fresh install that
        // hasn't been through onboarding at all yet.
        val gamesRoots = GamesRootPrefs.gamesRootPaths(this).map(::File).ifEmpty {
            listOf(File(getExternalFilesDir(null), "games").apply { mkdirs() })
        }
        library = Library(
            listOf(
                NativeAppProvider(applicationContext),
                EngineGameProvider(applicationContext, gamesRoots),
                // Same roots as EngineGameProvider -- a folder can hold
                // real console ROMs (<root>/<systemId>/<romFile>), engine
                // games (<root>/<gameFolder>/...), or both; each provider
                // only ever matches what's actually its own shape.
                ConsoleRomProvider(applicationContext, gamesRoots),
            ),
        )
        mode = intent.getStringExtra(BackButtonMenu.EXTRA_MODE)

        observeSecondScreen()

        setContent {
            when (mode) {
                BackButtonMenu.MODE_HANDHELD -> GamepadShell(
                    library = library,
                    onFocusedEntryChanged = { secondScreenPresentation?.focusedEntry = it },
                )
                else -> DesktopStub()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        mode = intent.getStringExtra(BackButtonMenu.EXTRA_MODE)
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

/**
 * Stands in for [DesktopShell] while Desktop mode is disconnected (see this
 * file's class doc comment). Deliberately doesn't start [DesktopSessionService]
 * or touch [dev.droidtop.runtime.linux.root.RootProcess] at all -- no root
 * prompt, no dimmed-screen risk, nothing to interfere with Handheld testing.
 */
@Composable
private fun DesktopStub() {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Text(
            "Desktop mode is temporarily disabled while Handheld is being built.",
            color = Color.Gray,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
