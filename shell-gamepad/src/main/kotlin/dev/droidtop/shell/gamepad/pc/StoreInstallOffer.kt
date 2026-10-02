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
 * and the free space of the volume the store installs to, named BEFORE the
 * store's own screen opens, so a 60 GB download on a 12 GB card is a
 * decision and not a 94 percent surprise. A picks a volume (the choice is
 * remembered per store, [StoreInstallVolumePrefs]) and opens the store's
 * screen; B closes with nothing opened.
 *
 * Downloads that are already running (Downloading, Paused) do not stop
 * here: the download is in flight and the store's queue is the place for
 * it, so the caller opens the store's screen directly.
 *
 * The one disk read of the sheet is the volume list's StatFs, off the
 * main thread on IO (Droidtop/tracker#227: no disk work while drawing);
 * everything else is already on the [LibraryEntry]. The pad comes through
 * the one pipeline, the same registered-hook shape
 * [dev.droidtop.shell.gamepad.WindowsSetupOfferDialog] uses for the
 * Windows system-files download.
 */
data class StoreInstallOffer(val entry: LibraryEntry, val stage: StoreStage)

@Composable
internal fun StoreInstallOfferSheet(
    offer: StoreInstallOffer?,
    onProceed: (StoreInstallOffer) -> Unit,
    onDismiss: () -> Unit,
) {
    val offer = offer ?: return
    val context = LocalContext.current
    val entry = offer.entry
    val store = entry.pcInfo?.source.orEmpty()
    val volumes by produceState(emptyList<InstallVolume>(), entry.id) {
        value = withContext(Dispatchers.IO) { installVolumes(context) }
    }
    val rememberedPath = if (store.isNotEmpty()) StoreInstallVolumePrefs.remembered(context, store) else null
    // The volume the install would go to now: the remembered one when it
    // is still among the volumes, otherwise the store's own default, the
    // primary.
    val current = volumes.indexOfFirst { it.path == rememberedPath }.takeIf { it >= 0 } ?: 0
    val formatSize: (Long) -> String = { Formatter.formatShortFileSize(context, it) }
    val window = LocalShellWindow.current
    var selected by remember(offer) { mutableIntStateOf(0) }
    // A volume row each, "Open the store's screen" when no volume could be
    // read, and "Not now" last: every A press is progress.
    val rows = (if (volumes.isEmpty()) 1 else volumes.size) + 1
    LaunchedEffect(rows) { selected = selected.coerceIn(0, rows - 1) }
    // The cursor starts on the volume the install would go to, whatever
    // the remembered choice is; once the volumes are read it is the
    // person's to move.
    LaunchedEffect(volumes) { if (volumes.isNotEmpty()) selected = current }

    fun pick(index: Int) {
        when {
            index < volumes.size -> {
                val volume = volumes[index]
                if (store.isNotEmpty() && volume.path != rememberedPath) {
                    StoreInstallVolumePrefs.remember(context, store, volume.path)
                }
                onProceed(offer)
            }
            volumes.isEmpty() && index == 0 -> onProceed(offer)
            else -> onDismiss()
        }
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
                "A picks a volume and opens the store's screen · B cancels",
                color = MenuTokens.OnSurfaceMuted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
            )
            val volume = volumes.getOrNull(current)
            val body = if (volume != null) {
                storeInstallOfferLines(
                    update = offer.stage == StoreStage.UPDATE,
                    sizeBytes = entry.pcInfo?.sizeBytes ?: 0L,
                    volume = volume,
                    formatSize = formatSize,
                )
            } else {
                listOf("The free space could not be read; the store will check it as the download starts.")
            }
            body.forEach { line ->
                Text(
                    line,
                    color = MenuTokens.Value,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            if (volumes.isEmpty()) {
                volumeRow(
                    text = "Open the store's screen",
                    selected = selected == 0,
                    action = { pick(0) },
                )
            } else {
                volumes.forEachIndexed { index, volume ->
                    val marker = if (index == current) " · current" else ""
                    volumeRow(
                        text = "${volume.name} · ${formatSize(volume.freeBytes)} free$marker",
                        selected = selected == index,
                        action = { pick(index) },
                    )
                }
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
