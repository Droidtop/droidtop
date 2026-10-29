package dev.droidtop.shell.gamepad.pc

import dev.droidtop.library.installFolderFor
import dev.droidtop.library.StoreInstallVolumePrefs
import dev.droidtop.library.NO_GAME_FOLDER_LINE
import dev.droidtop.library.GamesRoots
import android.content.Context
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
import dev.droidtop.library.integrations.PluginJobsScreen
import dev.droidtop.pluginhost.JobState
import dev.droidtop.pluginhost.PluginJobsCenter
import dev.droidtop.library.StoreUpdate
import dev.droidtop.library.WindowsSetup
import dev.droidtop.library.GameNaming
import dev.droidtop.library.stores.StoreHolding
import dev.droidtop.library.stores.StoreInstallJob
import dev.droidtop.library.stores.StoreWeb
import dev.droidtop.library.stores.StoreLibraries
import dev.droidtop.library.stores.StoreLibrary
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
    /** How the download's bar moves (Droidtop/tracker#643); null when the page shows no download. */
    val look: dev.droidtop.library.settings.JobLook? = null,
    /** Set when no runner offers the game: the button opens the Engine picker, the one place to say what it is. */
    val chooseEngine: Boolean = false,
)

/** Where a store game is in its life on this device, when that is the thing to do next. */
internal enum class StoreStage { INSTALL, UPDATE, DOWNLOADING, WAITING, PAUSED, FAILED, WISHLIST }

/**
 * The store stage of [entry], or null when there is none (a folder game, an
 * installed store game with no known update). Pure, so the capsule badge,
 * the primary button and the tests share it. [download] is this game's job
 * ([StoreInstallJob.live]): one running, held, paused or failed wins over
 * everything, then not installed, then an update. An update is only ever
 * [StoreUpdate.AVAILABLE] when a store said so: an unknown stays unknown.
 */
internal fun storeStageOf(entry: LibraryEntry, download: PluginJobsCenter.Entry?): StoreStage? {
    val pc = entry.pcInfo?.takeIf { entry.isStoreRow() } ?: return null
    return when {
        // On a wishlist and not owned: there is nothing to install, only the store's page to find it on.
        pc.holding == StoreHolding.WISHLIST -> StoreStage.WISHLIST
        download != null -> when (download.state) {
            JobState.FAILED -> StoreStage.FAILED
            // Held by the download rules or a full queue: Download now is the answer.
            JobState.HELD -> StoreStage.WAITING
            JobState.PAUSED -> StoreStage.PAUSED
            JobState.DONE -> null
            // Still running, waiting for its connection or its next try: its line says which.
            JobState.RUNNING, JobState.WAITING_FOR_NETWORK, JobState.WAITING_TO_RETRY -> StoreStage.DOWNLOADING
        } ?: storeStageOf(entry, null)
        !pc.installed -> StoreStage.INSTALL
        pc.update == StoreUpdate.AVAILABLE -> StoreStage.UPDATE
        else -> null
    }
}

/**
 * The name of the store this row is from, or null for a folder game: the key
 * the install offer remembers a store's game folder under
 * ([StoreInstallVolumePrefs]), the store's own label, whatever the row's holding.
 */
internal fun LibraryEntry.storeLabel(): String? = (dev.droidtop.library.PcSource.of(this) as? dev.droidtop.library.PcSource.Store)?.label()

/** Whether a store owns this row: it carries a store's id and is not a folder or a Wine shortcut. */
internal fun LibraryEntry.isStoreRow(): Boolean =
    dev.droidtop.library.PcSource.storeIdOf(pcInfo?.storeId) != null

/** The store id an entry's live download is filed under, or null for a game no store owns. */
internal fun LibraryEntry.downloadKey(): String? = pcInfo?.storeId

/**
 * The primary state of a store game, from its [stage]; no runner is needed to
 * answer it. A store droidtop runs itself ([ownStore]) installs from here and
 * shows its download in the Downloads place; Steam still opens its own screen.
 */
internal fun storePlayState(stage: StoreStage, entry: LibraryEntry, download: PluginJobsCenter.Entry?): PcPlayState {
    // The job's own words, as the Downloads place says them ("Waiting for Wi-Fi", "About 3 min left"); the bar under the
    // button shows how far (Droidtop/tracker#643).
    val said = download?.let { PluginJobsScreen.oneLine(it) }.orEmpty()
    val look = download?.let { PluginJobsScreen.lookOf(it) }
    val own = entry.ownStore() != null
    return when (stage) {
        StoreStage.INSTALL -> {
            val size = (entry.pcInfo?.sizeBytes ?: 0L).takeIf { it > 0 }?.let { "Downloads ${dev.droidtop.library.format.ValueFormat.size(it)}" }
            PcPlayState(
                "Install",
                when {
                    own -> size ?: "Downloads it from ${entry.storeLabel() ?: "the store"}"
                    size != null -> "$size. Opens the store's install screen"
                    else -> "Opens the store's install screen"
                },
                pressable = true, ready = false, store = stage,
            )
        }
        StoreStage.UPDATE -> PcPlayState(
            "Update",
            GameUpdates.line(GameUpdates.forStore(entry.pcInfo) ?: entry.availableUpdate ?: GameUpdates.NEWER_BUILD) +
                if (own) "" else ". Opens the store's update screen",
            pressable = true, ready = false, store = stage,
        )
        StoreStage.DOWNLOADING -> PcPlayState(
            "Downloading", said, pressable = true, ready = false, store = stage, progress = download?.fraction(), look = look,
        )
        // Held by the download rules: Download now puts it first in line, and it still waits for a connection downloads
        // may use (docs/SPEC.md 12a).
        StoreStage.WAITING -> PcPlayState(
            "Download now", said, pressable = true, ready = false, store = stage, progress = download?.fraction(), look = look,
        )
        StoreStage.PAUSED -> PcPlayState(
            "Resume", said, pressable = true, ready = false, store = stage, progress = download?.fraction(), look = look,
        )
        StoreStage.FAILED -> PcPlayState(
            "Try again", said, pressable = true, ready = false, store = stage, look = look,
        )
        StoreStage.WISHLIST -> PcPlayState(
            "Find on ${entry.storeLabel() ?: "the store"}",
            "On your wishlist. You do not own it",
            pressable = true, ready = false, store = stage,
        )
    }
}

/**
 * What A does on a wishlist game: the store's own pages, searched for its title, in the store's view. Returns the
 * line to show when it could not, else null.
 */
internal fun openOnStore(context: Context, entry: LibraryEntry): String? {
    val store = entry.ownStore() ?: return NO_STORE_LINE
    return if (StoreWeb.search(context, store, GameNaming.displayName(entry.title))) null else "${store.label} has no pages to open"
}

/** The state before the runner has been worked out: nothing to press yet. */
internal val PcPlayStateLoading = PcPlayState("…", "", pressable = false, ready = false)

internal fun playStateOf(
    runner: ResolvedRunner?,
    entry: LibraryEntry,
    download: PluginJobsCenter.Entry? = null,
    noRunnerLine: String = "No runner on this device offers this game",
    /** What Windows setup is doing or last did ([WindowsSetup.live]); null when nothing has happened this process. */
    windowsSetup: WindowsSetup.State? = null,
): PcPlayState {
    if (entry.missing) {
        return PcPlayState("Folder is missing", missingFolderLine(entry), pressable = false, ready = false)
    }
    storeStageOf(entry, download)?.let { return storePlayState(it, entry, download) }
    // A GOG installer in a games folder: A installs it, whatever runs the game afterwards (docs/SPEC.md 7g).
    entry.pcInfo?.installer?.let { return installerPlayState(it) }
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
            PcPlayState(primaryActionLabel(option), WindowsSetup.label(windowsSetup), pressable = true, ready = false)
        option?.action != null ->
            PcPlayState(primaryActionLabel(option), setupLine(option, label), pressable = true, ready = false)
        else ->
            PcPlayState("Choose a runner", option?.reason ?: noRunnerLine, pressable = true, ready = false, chooseEngine = true)
    }
}

/**
 * The button on a GOG installer's row ([dev.droidtop.library.PcInfo.installer]): Install, which takes the game out
 * of the installer without running it ([dev.droidtop.library.FolderInstalls]). Not [PcPlayState.ready]: nothing
 * launches.
 */
internal fun installerPlayState(installer: dev.droidtop.library.FolderInstaller): PcPlayState = PcPlayState(
    "Install",
    "Takes the game out of ${installer.file} without running it" + (installer.version?.let { ". Version $it" } ?: ""),
    pressable = true,
    ready = false,
)

/**
 * The words on the primary control and the shell's A hint for a runner's setup step: what it gets ("Install
 * Enginehost", "Get Ren'Py support"), never a bare "Install", which on a game's page reads as installing the game
 * (Droidtop/tracker#657; the game's own Install is [installerPlayState]'s). Pure.
 */
internal fun primaryActionLabel(option: dev.droidtop.library.RunnerOption): String = option.actionLabel ?: when (option.action) {
    RunnerAction.INSTALL_ENGINEHOST -> "Install Enginehost"
    RunnerAction.INSTALL_ENGINEHOST_PLUGIN -> "Get engine support"
    RunnerAction.INSTALL_KIRIKIROID2 -> "Install Kirikiroid2"
    RunnerAction.SET_UP_WINDOWS_GAMES -> "Set up Windows games"
    RunnerAction.CHOOSE_ENGINE_VERSION,
    null -> "Choose a runner"
}

/** The line under a setup step's button: which runner it is for and why, then that Play follows. Pure. */
internal fun setupLine(option: dev.droidtop.library.RunnerOption, runnerLabel: String): String {
    val why = option.reason?.takeIf { it.isNotBlank() && it != option.actionLabel }
    val runner = runnerLabel.takeIf { it.isNotBlank() }
    return when {
        why != null && runner != null -> "$why for $runner, then this becomes Play"
        why != null -> "$why, then this becomes Play"
        runner != null -> "$runner runs this game. One step, then this becomes Play"
        else -> "One step, then this becomes Play"
    }
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
    val downloads by StoreInstallJob.live.collectAsState()
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
    // A store stage or an installer needs no runner, so neither is held back by the lookup.
    val state = if (loaded || storeStageOf(entry, download) != null || entry.pcInfo?.installer != null) {
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

/** The store this game is from (docs/SPEC.md 7g, "Stores"); null for a folder game. */
internal fun LibraryEntry.ownStore(): StoreLibrary? = StoreLibraries.forKey(pcInfo?.storeId ?: id)

/**
 * Starts installing or updating [entry] from the store droidtop runs for
 * it, as a job in the Downloads place ([StoreInstallJob]), into the store's
 * subfolder of the game folder at [folderPath] (the install offer's choice;
 * blank for the folder remembered for the store, or the first game folder).
 * A store game goes into one of the person's game folders (Settings > Game
 * folders), never droidtop's own Android/data folder (docs/SPEC.md 7g,
 * "Where a store installs"). The one starter, whichever surface asked.
 * Returns the line to show.
 */
internal suspend fun startOwnStoreInstall(context: Context, entry: LibraryEntry, store: StoreLibrary, folderPath: String): String {
    val key = entry.pcInfo?.storeId ?: entry.id
    val folder = folderPath.ifBlank {
        withContext(Dispatchers.IO) {
            installFolderFor(
                StoreInstallVolumePrefs.remembered(context, entry.storeLabel().orEmpty()),
                GamesRoots.configured(context).map { it.absolutePath },
            )
        }.orEmpty()
    }
    if (folder.isBlank()) return NO_GAME_FOLDER_LINE
    return if (StoreInstallJob.start(
            context, key, GameNaming.displayName(entry.title), StoreInstallJob.rootFor(folder, store),
            sizeBytes = entry.pcInfo?.sizeBytes ?: 0L,
        ) != null) {
        "Downloading. It is listed under Downloads"
    } else {
        "${store.label} cannot install this game in this build"
    }
}

/**
 * What a press on a store game's primary action does when the game already
 * has a job: a paused one resumes, a waiting one is Download now (first in
 * line, still waiting for the connection), a failed one runs again, and a
 * running one opens the Downloads place ([onOpenDownloads]). Returns the line
 * to show, or null.
 */
internal fun continueStoreDownload(context: Context, entry: LibraryEntry, stage: StoreStage, onOpenDownloads: () -> Unit): String? {
    if (entry.ownStore() == null) return NO_STORE_LINE
    val job = (entry.pcInfo?.storeId ?: entry.id).let { StoreInstallJob.live.value[it] }
    when {
        job == null -> Unit
        stage == StoreStage.PAUSED || stage == StoreStage.WAITING -> if (PluginJobsCenter.resume(job.jobId)) return null
        stage == StoreStage.FAILED -> return if (PluginJobsCenter.retry(context, job.jobId) != null) null else "This download can no longer be tried again"
    }
    onOpenDownloads()
    return null
}

/** How far [this] job is, 0 to 1; null while it does not know. */
internal fun PluginJobsCenter.Entry.fraction(): Float? = percent.takeIf { it >= 0 }?.let { it.coerceAtMost(100) / 100f }

/** What a store action says for a game whose store this build does not have. */
internal const val NO_STORE_LINE = "No store in this build installs this game"

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
