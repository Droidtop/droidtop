package dev.droidtop.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.LifecycleOwner
import androidx.savedstate.SavedStateRegistryOwner
import dev.droidtop.library.LibraryEntry
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * NOTE: the `SecondScreenPresentation` class that used to head this file
 * is gone. droidtop no longer pushes a `Presentation` at the second
 * screen; `:display`'s `SecondaryDisplayActivity` holds
 * `SECONDARY_HOME` and the platform places it (docs/SPEC.md section 4c).
 * What remains here is the companion's own state and backdrop, which
 * both hosts still render.
 *
 * Rich companion display for the second/lower screen — per direction:
 * "we want the second screen to be a very rich informational display,"
 * not just a passive keyboard/trackpad surface. `android.app.Presentation`
 * (via `android.software.presentation`, declared in this module's
 * manifest) is Android's own standard mechanism for real content on a
 * secondary `Display` — this is that, hosting a Compose UI via
 * [ComposeView].
 *
 * `Presentation`'s own `Context` isn't automatically a
 * [LifecycleOwner]/[SavedStateRegistryOwner] the way an Activity's is —
 * [ComposeView] needs both attached manually or it throws at composition
 * time. [lifecycleOwner]/[savedStateOwner] below are the standard,
 * documented pattern for hosting Compose inside a Dialog/Presentation,
 * not something invented here.
 *
 * UNVERIFIED against a real dual-screen device — no hardware with a real
 * secondary `Display` was available while writing this. The mechanism
 * (`Presentation` + `ComposeView` + manual lifecycle wiring) is
 * documented, standard Android API usage, but this exact class has not
 * been run against a live second screen.
 */
/**
 * The one place the focused-entry feed lives — written by whatever drives
 * the shell ([dev.droidtop.shell.gamepad.GamepadShell]'s focus callback,
 * via MainActivity), read by BOTH companion hosts ([CompanionActivity] on
 * the built-in screen when the shell is on the addon, and
 * :display's SecondaryDisplayActivity on the addon when the shell
 * stays built-in).
 * A process-wide flow rather than a field on either host, since which
 * host exists changes with [dev.droidtop.display.DisplayRolePrefs] + live display attach.
 */
object CompanionState {
    val focusedEntry = MutableStateFlow<LibraryEntry?>(null)

    /**
     * The library the idle rotation draws from (docs/SPEC.md section 4d).
     * Published by whatever drives the shell, same as [focusedEntry] --
     * the companion must not run its own scan, both because it would
     * duplicate work and because it renders on a screen the user is not
     * driving.
     */
    val libraryEntries = MutableStateFlow<List<LibraryEntry>>(emptyList())

    /**
     * What the Gaming shell's own Home builds Continue playing and Recently added from: the folded PC games,
     * the Retro library and the launcher apps that are games. The companion's rails read this, so they show
     * what Home shows; [libraryEntries] (games only, no apps) stands in until the shell has published it.
     */
    val homeActivity = MutableStateFlow<List<LibraryEntry>>(emptyList())

    /**
     * Tap-to-launch, installed by MainActivity (the owner of the one
     * [dev.droidtop.library.Library] instance) and invoked by the
     * companion's recent-games rail. Null while no shell is alive to
     * launch through -- the rail still renders, and a tap simply does
     * nothing rather than half-launching outside the real launch path
     * (play history, launch-screen memory, error handling all live
     * there).
     */
    @Volatile
    var onLaunchEntry: ((LibraryEntry) -> Unit)? = null

    /** Quit for the running game (the Now card), installed by MainActivity beside [onLaunchEntry]: `Library.quitRunning`. */
    @Volatile
    var onQuitEntry: ((LibraryEntry) -> Unit)? = null

    /**
     * Why the last rail launch failed, shown under the rail. The shell's
     * own error line is on the other screen, and a log line alone left a
     * tap on the companion looking like it did nothing.
     */
    val launchError = MutableStateFlow<String?>(null)

    /**
     * Whether the add-on display currently looks broken -- mirroring, a
     * missing live companion, or a foreign app left behind after it
     * exits -- per
     * [dev.droidtop.runtime.DualScreenOrchestration.secondScreenNeedsReinit].
     * Published by [MainActivity]'s own display-role orchestration (the
     * one place that already knows the addon's id, the parked-display
     * state, the live Presentation and the idle-cover Activity), read by
     * the "Reinitialize displays" pill wherever it is drawn -- the same
     * process-wide-flow pattern as [focusedEntry] and [libraryEntries],
     * for the same reason: the pill can be drawn from a Compose tree
     * that has none of that orchestration state of its own.
     */
    val dualScreenBroken = MutableStateFlow(false)
}

/**
 * [CompanionState.focusedEntry] once browsing settles: a new focus is
 * drawn only after it has held for [FOCUS_SETTLE_MS], so a fast scroll
 * down a gamelist does not repaint the companion's artwork at every row
 * (docs/SPEC.md 4d, from iiSU's "post-idle delay before hero, title, and
 * backdrop artwork commits"). Clearing the focus is drawn at once: going
 * back to the idle rotation is not a scroll. Every companion host reads
 * this rather than the raw flow.
 */
@Composable
internal fun settledFocusedEntry(): LibraryEntry? {
    val focused by CompanionState.focusedEntry.collectAsState()
    var settled by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(focused) }
    androidx.compose.runtime.LaunchedEffect(focused) {
        if (focused != null) kotlinx.coroutines.delay(FOCUS_SETTLE_MS)
        settled = focused
    }
    return settled
}

// Long enough to skip rows passed at D-pad repeat rate, short enough that
// stopping on a game feels immediate.
private const val FOCUS_SETTLE_MS = 350L

/**
 * The companion Home's ground: the idle art rotation while nothing is focused on the other screen
 * (docs/SPEC.md section 4d, from iiSU's "Show Hero on Idle Bottom Screen"; it used to paint a "droidtop"
 * wordmark, the one thing a glanceable surface must never do), else the plain ground. The focused game's
 * facts are not drawn here: they are a section of the scrolling Home (the Now card, [CompanionNowSection]), so no text
 * is ever drawn under another block (rig, p1-dt-companion-text-overlap).
 */
@Composable
internal fun CompanionContent(entry: LibraryEntry?) {
    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (entry == null) {
            val entries by CompanionState.libraryEntries.collectAsState()
            CompanionIdle(entries)
        }
    }
}

