package dev.droidtop.app

import android.os.Environment
import android.os.StatFs
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.droidtop.display.secondScreenScroll
import dev.droidtop.library.settings.GamingSettingsCatalog
import dev.droidtop.library.settings.UiModeRefresh
import dev.droidtop.shell.gamepad.quickSectionGroups
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The companion's System tab (docs/SPEC.md "The companion's tabs", Droidtop/tracker#414): the Quick Menu's own
 * System section at the top, then Display, Sound, Power, Storage and Privacy, folded. Every row is a catalog item
 * drawn for touch ([CompanionCatalogItems]); the catalog is built off the main thread while the tab shows and again
 * after each change or a return from another screen (a grant), and nothing reads while the tab is not composed.
 */
@Composable
internal fun CompanionSystemTab() {
    val context = LocalContext.current
    val nav = LocalCompanionNav.current
    val uiMode by UiModeRefresh.mode.collectAsState()
    var version by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { version++ }
    val cards by produceState<List<SystemCard>?>(null, version, uiMode) {
        value = withContext(Dispatchers.IO) {
            val catalog = GamingSettingsCatalog.groups(context)
            companionSystemCards(
                section = { section -> quickSectionGroups(catalog, section, uiMode).flatMap { it.items } },
                performanceMode = GamingSettingsCatalog.performanceModeItem(),
                privacy = GamingSettingsCatalog.privacyDashboardItem(),
                accessibility = dev.droidtop.runtime.systemstatus.AccessibilityControls.items(context.applicationContext),
            )
        }
    }
    val folded = remember { mutableStateMapOf<String, Boolean>() }
    Column(modifier = Modifier.fillMaxSize().secondScreenScroll(rememberScrollState()).padding(16.dp)) {
        val list = cards
        if (list == null) {
            CompanionNote("Reading…")
            return@Column
        }
        list.forEach { card ->
            val isFolded = folded[card.id] ?: card.folded
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                CompanionSectionHeader(card.title, open = !isFolded) { folded[card.id] = !isFolded }
                if (!isFolded) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(8.dp),
                    ) {
                        if (card.storage) {
                            StorageLine()
                        } else {
                            // One card per screen above the Quick Menu's Display items (slice C14).
                            if (card.id == "display") CompanionDisplayCards()
                            // Battery, clocks and what the probe finds (slice C16), under performance mode.
                            if (card.id == "power") CompanionPowerCard()
                            // Recent camera, mic and location use, and droidtop's own grants (slice C17).
                            if (card.id == "privacy") CompanionPrivacyCard()
                            // A slider per stream through the one volume path, and the network and Bluetooth (C19).
                            if (card.id == "sound") CompanionStreamVolumes()
                            if (card.id == "connections") CompanionConnections()
                            CompanionCatalogItems(
                                card.items,
                                onChanged = { version++ },
                                onOpenSocial = nav?.let { n -> { n.openTab(CompanionTab.SOCIAL) } },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** One storage volume as the Storage card shows it. */
internal data class StorageVolumeLine(val name: String, val free: Long, val total: Long)

/**
 * Every storage volume's free and total space (slice C21): internal storage, then each SD card or USB drive Android
 * has mounted (`StorageManager.getStorageVolumes`, each volume's own folder measured with `StatFs`), read off the
 * main thread, each with its bar.
 */
@Composable
internal fun StorageLine() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val volumes by produceState<List<StorageVolumeLine>?>(null) {
        value = withContext(Dispatchers.IO) { storageVolumes(context.applicationContext) }
    }
    val list = volumes ?: run { CompanionNote("Reading…"); return }
    list.forEach { volume ->
        Text(volume.name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        Text(storageText(volume.free, volume.total), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        CompanionBar(if (volume.total > 0) (volume.total - volume.free).toFloat() / volume.total else 0f)
    }
}

private fun storageVolumes(context: android.content.Context): List<StorageVolumeLine> {
    val internal = runCatching { StatFs(Environment.getDataDirectory().path).let { StorageVolumeLine("Internal storage", it.availableBytes, it.totalBytes) } }.getOrNull()
    val manager = context.getSystemService(android.os.storage.StorageManager::class.java)
    val removable = manager?.storageVolumes.orEmpty().filter { it.isRemovable && it.state == Environment.MEDIA_MOUNTED }.mapNotNull { volume ->
        val dir = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            volume.directory
        } else {
            // Before Android 11 a volume names no folder: droidtop's own folder on it is measured instead.
            context.getExternalFilesDirs(null).filterNotNull().firstOrNull { Environment.isExternalStorageRemovable(it) }
        } ?: return@mapNotNull null
        runCatching { StatFs(dir.path).let { StorageVolumeLine(volume.getDescription(context), it.availableBytes, it.totalBytes) } }.getOrNull()
    }
    return listOfNotNull(internal) + removable.distinctBy { it.name to it.total }
}

/** A thin filled bar, [fraction] of the way: storage used, a download's progress. */
@Composable
internal fun CompanionBar(fraction: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

/** "12.3 GB free of 128.0 GB"; pure so it is testable. */
internal fun storageText(freeBytes: Long, totalBytes: Long): String =
    "%.1f GB free of %.1f GB".format(freeBytes / GB, totalBytes / GB)

private const val GB = 1_000_000_000.0
