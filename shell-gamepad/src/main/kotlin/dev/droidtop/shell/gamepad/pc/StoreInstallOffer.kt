package dev.droidtop.shell.gamepad.pc

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.droidtop.library.GameNaming
import dev.droidtop.library.InstallVolume
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.NO_GAME_FOLDER_LINE
import dev.droidtop.library.StoreInstallVolumePrefs
import dev.droidtop.library.installVolumes
import dev.droidtop.library.storeInstallOfferLines
import dev.droidtop.shell.gamepad.LocalShellWindow
import dev.droidtop.shell.gamepad.MenuPanel
import dev.droidtop.shell.gamepad.MenuTokens
import dev.droidtop.shell.gamepad.menuMove
import dev.droidtop.shell.gamepad.selectionFrame
import dev.droidtop.shell.gamepad.input.GamepadAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The offer droidtop stops on before a store install or update
 * (Droidtop/tracker#227): the download's size, when the library knows it,
 * and the free space of the game folder the store installs to, named BEFORE
 * anything downloads, so a 60 GB download on a 12 GB card is a decision and
 * not a 94 percent surprise.
 *
 * For a store droidtop runs itself (docs/SPEC.md 7g, "Stores") the places
 * offered are the person's own game folders (Settings > Game folders,
 * [installVolumes]), the folders every library walk reads, never droidtop's
 * Android/data folder: A picks one (remembered per store,
 * [StoreInstallVolumePrefs]) and the install starts in that folder's store
 * subfolder. With no game folder named yet the offer says where to add one
 * and starts nothing. Steam keeps its own install location, so for Steam
 * the offer names the size and opens the store's screen; B closes with
 * nothing started.
 *
 * Downloads that are already running (Downloading, Paused) do not stop
 * here: the download is in flight and the store's queue is the place for
 * it, so the caller opens the store's screen directly.
 *
 * The one disk read of the sheet is the folder list's StatFs, off the
 * main thread on IO (Droidtop/tracker#227: no disk work while drawing);
 * everything else is already on the [LibraryEntry]. The pad comes through
 * the one pipeline, the same registered-hook shape
 * [dev.droidtop.shell.gamepad.WindowsSetupOfferDialog] uses for the
 * Windows system-files download.
 */
internal data class StoreInstallOffer(val entry: LibraryEntry, val stage: StoreStage)

@Composable
internal fun StoreInstallOfferSheet(
    offer: StoreInstallOffer?,
    /** The offer was taken; for a store droidtop runs, into the game folder at this path (empty for Steam, which picks its own). */
    onProceed: (StoreInstallOffer, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val offer = offer ?: return
    val context = LocalContext.current
    val entry = offer.entry
    val store = entry.pcInfo?.source.orEmpty()
    // A store droidtop runs installs into a game folder; Steam still opens its own screen.
    val own = entry.ownStore() != null
    // null until the folders are read, so "no game folder yet" is never
    // drawn for the frame before the list arrives.
    val read by produceState<List<InstallVolume>?>(null, entry.id) {
        value = if (own) withContext(Dispatchers.IO) { installVolumes(context) } else emptyList()
    }
    val places = read.orEmpty()
    // "DLC and versions" is offered when the store has anything to choose for this game (read off the main thread).
    val contentStore = entry.ownStore()
    val hasContent by produceState(false, entry.id) {
        value = contentStore != null && withContext(Dispatchers.IO) {
            runCatching { contentStore.contentOptions(context, (entry.pcInfo?.storeId ?: entry.id).substringAfter(':')) }.getOrNull()
        } != null
    }
    var contentOpen by remember(offer) { mutableStateOf(false) }
    val rememberedPath = if (store.isNotEmpty()) StoreInstallVolumePrefs.remembered(context, store) else null
    // The folder the install would go to now: the remembered one when it
    // is still one of the person's game folders, otherwise the first.
    val current = places.indexOfFirst { it.path == rememberedPath }.takeIf { it >= 0 } ?: 0
    val formatSize: (Long) -> String = { Formatter.formatShortFileSize(context, it) }
    val window = LocalShellWindow.current
    var selected by remember(offer) { mutableIntStateOf(0) }
    // A folder row each (Steam: one "Open the store's screen" row; no game
    // folder: none), and "Not now" last: every A press is progress.
    val choices = if (own) places.size else 1
    val rows = choices + (if (hasContent) 1 else 0) + 1
    LaunchedEffect(rows) { selected = selected.coerceIn(0, rows - 1) }
    // The cursor starts on the folder the install would go to, whatever
    // the remembered choice is; once the folders are read it is the
    // person's to move.
    LaunchedEffect(places) { if (places.isNotEmpty()) selected = current }

    fun pick(index: Int) {
        when {
            !own && index == 0 -> onProceed(offer, "")
            own && index < places.size -> {
                val place = places[index]
                if (store.isNotEmpty() && place.path != rememberedPath) {
                    StoreInstallVolumePrefs.remember(context, store, place.path)
                }
                onProceed(offer, place.path)
            }
            hasContent && index == choices -> contentOpen = true
            else -> onDismiss()
        }
    }

    if (contentOpen && contentStore != null) {
        StoreContentSheet(
            entry = entry,
            store = contentStore,
            // The choice is kept for the install this offer goes on to start.
            onApplied = { contentOpen = false },
            onDismiss = { contentOpen = false },
        )
    }
    Dialog(onDismissRequest = onDismiss) {
        MenuPanel(
            modifier = Modifier.width(window.panelWidth(400.dp)),
            focusLabel = "Store install offer",
            onPad = { press ->
                when (press.action) {
                    GamepadAction.UP, GamepadAction.DOWN -> selected = menuMove(selected, rows, press)
                    GamepadAction.A -> pick(selected)
                    GamepadAction.B -> onDismiss()
                    else -> Unit
                }
                true
            },
        ) {
            Text(
                "${if (offer.stage == StoreStage.UPDATE) "Update" else "Install"} ${GameNaming.displayName(entry.title)}",
                color = MenuTokens.OnSurface,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                if (own) "A picks the game folder it installs to · B cancels" else "A opens the store's screen · B cancels",
                color = MenuTokens.OnSurfaceMuted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
            )
            val place = places.getOrNull(current)
            val body = when {
                !own -> storeInstallOfferLines(
                    update = offer.stage == StoreStage.UPDATE,
                    sizeBytes = entry.pcInfo?.sizeBytes ?: 0L,
                    volume = null,
                    formatSize = formatSize,
                )
                read == null -> emptyList()
                place == null -> listOf(NO_GAME_FOLDER_LINE)
                else -> storeInstallOfferLines(
                    update = offer.stage == StoreStage.UPDATE,
                    sizeBytes = entry.pcInfo?.sizeBytes ?: 0L,
                    volume = place,
                    formatSize = formatSize,
                )
            }
            body.forEach { line ->
                Text(
                    line,
                    color = MenuTokens.Value,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            if (!own) {
                volumeRow(
                    text = "Open the store's screen",
                    selected = selected == 0,
                    action = { pick(0) },
                )
            } else {
                places.forEachIndexed { index, folder ->
                    val marker = if (index == current) " · current" else ""
                    volumeRow(
                        text = "${folder.name} · ${formatSize(folder.freeBytes)} free$marker",
                        selected = selected == index,
                        action = { pick(index) },
                    )
                }
            }
            if (hasContent) {
                volumeRow(
                    text = "DLC and versions",
                    selected = selected == choices,
                    action = { pick(choices) },
                )
            }
            volumeRow(
                text = "Not now",
                selected = selected == rows - 1,
                action = { onDismiss() },
            )
        }
    }
}

/**
 * One row of the offer: a button, the way [dev.droidtop.shell.gamepad.WindowsSetupOfferDialog]'s
 * rows are, at least as big as a finger on a screen without a pad. A tap
 * presses the row's own action; the pad's cursor answers the same press.
 */
@Composable
private fun volumeRow(text: String, selected: Boolean, action: () -> Unit) {
    val window = LocalShellWindow.current
    Text(
        text,
        color = if (selected) MenuTokens.OnSurface else MenuTokens.Value,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (window.touchFirst) Modifier.heightIn(min = window.minTouchTarget) else Modifier)
            .clip(RoundedCornerShape(8.dp))
            .selectionFrame(selected, RoundedCornerShape(8.dp), rest = Color.Transparent)
            .clickable(onClick = action)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}
