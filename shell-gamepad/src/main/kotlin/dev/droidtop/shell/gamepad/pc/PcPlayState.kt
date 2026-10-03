package dev.droidtop.shell.gamepad.pc

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import dev.droidtop.library.GameUpdates
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.groupingPath
import dev.droidtop.library.PcRunnerOptions
import dev.droidtop.library.ResolvedRunner
import dev.droidtop.library.RunnerState
import dev.droidtop.library.RunnerAction
import dev.droidtop.library.StoreDownloads
import dev.droidtop.library.StoreUpdate
import dev.droidtop.library.WindowsSetup
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
 *
 * A store game that is not installed, has an update, or is downloading says
 * so here too ([store], from [storeStageOf]): the capsule's badge reads the
 * same stage, so the corner and the button cannot disagree.
 */
internal data class PcPlayState(
    val verb: String,
    val detail: String,
    val pressable: Boolean,
    val ready: Boolean,
    /** Set when the primary action is a store action (docs/SPEC.md 7i): A opens the store's own screen for this game. */
    val store: StoreStage? = null,
    /** How far a running download is, 0 to 1; null when none is. */
    val progress: Float? = null,
)

/** Where a store game is in its life on this device, when that is the thing to do next. */
internal enum class StoreStage { INSTALL, UPDATE, DOWNLOADING, PAUSED }

/**
 * The store stage of [entry], or null when there is none (a folder game, an
 * installed store game with no known update). Pure, so the capsule badge,
 * the primary button and the tests share it. [download] is what
 * [StoreDownloads] says about this game; a running download wins over
 * everything, then not installed, then an update. An update is only ever
 * [StoreUpdate.AVAILABLE] when a store said so: an unknown stays unknown.
 */
internal fun storeStageOf(entry: LibraryEntry, download: StoreDownloads.Progress?): StoreStage? {
    val pc = entry.pcInfo?.takeIf { entry.isStoreRow() } ?: return null
    return when {
        download != null -> if (download.paused) StoreStage.PAUSED else StoreStage.DOWNLOADING
        !pc.installed -> StoreStage.INSTALL
        pc.update == StoreUpdate.AVAILABLE -> StoreStage.UPDATE
        else -> null
    }
}

/** Whether a store owns this row: it carries a store's id and is not a folder or a Wine shortcut. */
internal fun LibraryEntry.isStoreRow(): Boolean =
    pcInfo?.let { it.storeId != null && it.source != "Folder" && it.source != "Wine" } == true

/** The store id an entry's live download is filed under, or null for a game no store owns. */
internal fun LibraryEntry.downloadKey(): String? = pcInfo?.storeId

/** The primary state of a store game, from its [stage]; no runner is needed to answer it. */
internal fun storePlayState(stage: StoreStage, entry: LibraryEntry, download: StoreDownloads.Progress?): PcPlayState = when (stage) {
    StoreStage.INSTALL -> PcPlayState(
        "Install",
        (entry.pcInfo?.sizeBytes ?: 0L).takeIf { it > 0 }?.let { "Downloads ${downloadSizeLabel(it)}. Opens the store's install screen" }
            ?: "Opens the store's install screen",
        pressable = true, ready = false, store = stage,
    )
    StoreStage.UPDATE -> PcPlayState(
        "Update",
        GameUpdates.line(GameUpdates.forStore(entry.pcInfo) ?: entry.availableUpdate ?: GameUpdates.NEWER_BUILD) +
            ". Opens the store's update screen",
        pressable = true, ready = false, store = stage,
    )
    StoreStage.DOWNLOADING -> PcPlayState(
        "Downloading",
        "${download?.percent ?: 0}%. Opens the download",
        pressable = true, ready = false, store = stage, progress = download?.fraction,
    )
    StoreStage.PAUSED -> PcPlayState(
        "Resume",
        "Stopped at ${download?.percent ?: 0}%. Opens the download to resume it",
        pressable = true, ready = false, store = stage, progress = download?.fraction,
    )
}

/** A download size as a person reads it: whole megabytes below a gigabyte, one decimal above. Pure. */
internal fun downloadSizeLabel(bytes: Long): String =
    if (bytes >= 1_000_000_000L) String.format("%.1f GB", bytes / 1_000_000_000.0) else "${(bytes / 1_000_000L).coerceAtLeast(1)} MB"

/** The state before the runner has been worked out: nothing to press yet. */
internal val PcPlayStateLoading = PcPlayState("…", "", pressable = false, ready = false)

internal fun playStateOf(
    runner: ResolvedRunner?,
    entry: LibraryEntry,
    download: StoreDownloads.Progress? = null,
    noRunnerLine: String = "No runner on this device offers this game",
    /** What Windows setup is doing or last did ([WindowsSetup.live]); null when nothing has happened this process. */
    windowsSetup: WindowsSetup.State? = null,
): PcPlayState {
    if (entry.missing) {
        return PcPlayState("Folder is missing", missingFolderLine(entry), pressable = false, ready = false)
    }
    storeStageOf(entry, download)?.let { return storePlayState(it, entry, download) }
    val option = runner?.option
    val label = runner?.label.orEmpty()
    return when {
        option?.state == RunnerState.READY ->
            PcPlayState("Play", option.caveat ?: "Starts now on $label", pressable = true, ready = true)
        // The shared setup's own state is the line under its button: it is
        // running (A does not start it twice), or why the last try failed.
        option?.action == RunnerAction.SET_UP_WINDOWS_GAMES && windowsSetup is WindowsSetup.State.Installing ->
            PcPlayState(
                "Installing", WindowsSetup.label(windowsSetup), pressable = false, ready = false,
                progress = windowsSetup.percent?.let { it / 100f },
            )
        option?.action == RunnerAction.SET_UP_WINDOWS_GAMES && windowsSetup is WindowsSetup.State.Failed ->
            PcPlayState(primaryActionLabel(option.action), WindowsSetup.label(windowsSetup), pressable = true, ready = false)
        option?.action != null ->
            PcPlayState(primaryActionLabel(option.action), option.reason ?: "One step, then this becomes Play", pressable = true, ready = false)
        else ->
            PcPlayState("Choose a runner", option?.reason ?: noRunnerLine, pressable = false, ready = false)
    }
}

/** The verb used by both the primary control and the shell's A hint. */
internal fun primaryActionLabel(action: RunnerAction?): String = when (action) {
    RunnerAction.INSTALL_ENGINEHOST,
    RunnerAction.INSTALL_ENGINEHOST_PLUGIN,
    RunnerAction.INSTALL_KIRIKIROID2 -> "Install"
    RunnerAction.SET_UP_WINDOWS_GAMES -> "Set up Windows games"
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
    val downloads by StoreDownloads.active.collectAsState()
    val download = entry.downloadKey()?.let { downloads[it] }
    val windowsSetup by WindowsSetup.live.collectAsState()
    // The runner is worked out again when a Windows setup starts or ends: the
    // page stayed on "Set up Windows games" after it finished because nothing
    // re-asked (Droidtop/tracker#300). Progress lines do not re-ask.
    val setupRunning = windowsSetup is WindowsSetup.State.Installing
    val resolved by produceState<Triple<Boolean, ResolvedRunner?, String?>>(Triple(false, null, null), entry.id, setupRunning) {
        // Keep the last answer on screen while it is re-asked after a setup, so the button does not flash.
        if (!value.first) value = Triple(false, null, null)
        val (runner, line) = withContext(Dispatchers.IO) {
            val runners = PcRunnerOptions.forEntry(context, entry)
            PcRunnerOptions.resolvedFor(context, entry, runners) to runners.noRunnerLine
        }
        value = Triple(true, runner, line)
    }
    val (loaded, runner, noRunnerLine) = resolved
    // A store stage needs no runner, so it is never held back by the lookup.
    val state = if (loaded || storeStageOf(entry, download) != null) {
        if (noRunnerLine != null) {
            playStateOf(runner, entry, download, noRunnerLine, windowsSetup)
        } else {
            playStateOf(runner, entry, download, windowsSetup = windowsSetup)
        }
    } else {
        PcPlayStateLoading
    }
    return state to runner
}

/**
 * Opens the game's own store screen (install, update, download, verify,
 * remove), the one place those happen; returns null, or what went wrong in
 * words a person can read. Started by class name because this module cannot
 * depend on :app, the same route every cross-module screen here takes.
 */
internal fun openStoreScreen(context: Context, entry: LibraryEntry): String? = runCatching {
    context.startActivity(
        Intent()
            .setClassName(context.packageName, PC_STORE_ACTIVITY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(EXTRA_PC_ENTRY_ID, entry.pcInfo?.storeId ?: entry.id),
    )
    null
}.getOrElse { "droidtop couldn't open that screen: ${it.message}" }

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
