package dev.droidtop.shell.gamepad.pc

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.groupingPath
import dev.droidtop.library.PcRunnerOptions
import dev.droidtop.library.ResolvedRunner
import dev.droidtop.library.RunnerState
import dev.droidtop.library.RunnerAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What the one primary button on a PC game says and whether it can be
 * pressed (docs/SPEC.md 7i, "The PC library is a controller-first
 * storefront view"): "Play" when the resolved runner is ready, the one
 * setup step's own name when it is not, and why not when there is
 * neither. [verb] is the button's label, [detail] the line under it.
 *
 * The ONE decision the library's focused-game hero, the game page's big
 * button and [PcGameMenu]'s first row all draw, so the three can never
 * disagree with what A actually does
 * ([dev.droidtop.library.PcRunnerOptions.resolveAndPlay]).
 */
internal data class PcPlayState(val verb: String, val detail: String, val pressable: Boolean, val ready: Boolean)

/** The state before the runner has been worked out: nothing to press yet. */
internal val PcPlayStateLoading = PcPlayState("…", "", pressable = false, ready = false)

internal fun playStateOf(runner: ResolvedRunner?, entry: LibraryEntry): PcPlayState {
    if (entry.missing) {
        return PcPlayState("Folder is missing", missingFolderLine(entry), pressable = false, ready = false)
    }
    val option = runner?.option
    val label = runner?.label.orEmpty()
    return when {
        option?.state == RunnerState.READY ->
            PcPlayState("Play", option.caveat ?: "Starts now on $label", pressable = true, ready = true)
        option?.action != null ->
            PcPlayState(primaryActionLabel(option.action), option.reason ?: "One step, then this becomes Play", pressable = true, ready = false)
        else ->
            PcPlayState("Choose a runner", option?.reason ?: "No runner on this device offers this game", pressable = false, ready = false)
    }
}

/** The verb used by both the primary control and the shell's A hint. */
internal fun primaryActionLabel(action: RunnerAction?): String = when (action) {
    RunnerAction.INSTALL_ENGINEHOST,
    RunnerAction.INSTALL_ENGINEHOST_PLUGIN,
    RunnerAction.INSTALL_KIRIKIROID2 -> "Install"
    RunnerAction.SET_UP_WINDOWS_GAMES -> "Set up"
    RunnerAction.CHOOSE_ENGINE_VERSION,
    null -> "Choose a runner"
}

/**
 * The focused game's resolved runner and the [PcPlayState] it gives,
 * worked out for ONE entry off the main thread (a folder walk), never per
 * card. [PcPlayStateLoading] until it answers; the resolved runner is
 * null for a game nothing can run.
 */
@Composable
internal fun rememberPcPlayState(entry: LibraryEntry): Pair<PcPlayState, ResolvedRunner?> {
    val context = LocalContext.current
    val resolved by produceState<Pair<PcPlayState, ResolvedRunner?>>(PcPlayStateLoading to null, entry.id) {
        value = PcPlayStateLoading to null
        val runner = withContext(Dispatchers.IO) {
            val runners = PcRunnerOptions.forEntry(context, entry)
            PcRunnerOptions.resolvedFor(context, entry, runners)
        }
        value = playStateOf(runner, entry) to runner
    }
    return resolved
}

/**
 * What the disabled button says under itself: where this game was. The
 * whole path, not its name -- the name is already the title above it,
 * and the path is the thing the person has to go and look at.
 */
internal fun missingFolderLine(entry: LibraryEntry): String =
    if (entry.groupingPath() != null) {
        "${entry.groupingPath()} is not there any more. Its history, favourite and collections are kept."
    } else {
        "Nothing droidtop scanned still has this game. Its history, favourite and collections are kept."
    }
