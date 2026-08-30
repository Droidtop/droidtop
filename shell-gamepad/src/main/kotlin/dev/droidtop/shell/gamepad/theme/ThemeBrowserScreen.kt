package dev.droidtop.shell.gamepad.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.droidtop.library.theme.ThemeAssets
import dev.droidtop.library.theme.ThemeDownloader
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.GamepadKeyMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import kotlinx.coroutines.withContext

/**
 * Real browse/download UI for the theme-downloader stack built earlier
 * this session (`ThemeDownloader`, `ThemeAssets`) -- until now that whole
 * real, working backend had no UI beyond Settings' own "Sync theme
 * index" action (which only fetches the real index, never installs a
 * theme). Reads real parsed entries from the already-synced
 * `themes-list.git` clone (`ThemeDownloader.parseThemesList`) -- if
 * that's empty, the real fix is going back and running the sync action
 * first, not a bug in this screen.
 *
 * Downloading/updating a theme (`ThemeDownloader.downloadOrUpdateTheme`)
 * writes into `ThemeAssets.userThemesDir`, the exact same directory
 * `ThemeAssets.discoverThemes` already scans -- a newly downloaded theme
 * becomes selectable from Settings' own "Theme" cycle-link immediately,
 * no separate registration step.
 */
@Composable
fun ThemeBrowserScreen(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<ThemeDownloader.ThemeDownloadEntry>>(emptyList()) }
    var installedDirNames by remember { mutableStateOf<Set<String>>(emptySet()) }
    var statusByDirName by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    val firstFocus = remember { FocusRequester() }

    suspend fun refresh() {
        withContext(Dispatchers.IO) {
            entries = ThemeDownloader.parseThemesList(ThemeAssets.userThemesDir(context))
            installedDirNames = ThemeAssets.discoverThemes(context).map { it.name }.toSet()
        }
    }
    LaunchedEffect(Unit) {
        loading = true
        refresh()
        loading = false
    }
    // Real, confirmed-live crash this fixes: requesting focus in the SAME
    // LaunchedEffect that just set `entries` raced ahead of Compose actually
    // recomposing the list and attaching firstFocus to its own first row --
    // "FocusRequester is not initialized" whenever this screen is entered
    // directly (no longer masked by an intermediate list screen's own
    // earlier LaunchedEffect giving recomposition a free extra frame first,
    // now that "Browse themes" jumps straight in -- see GamepadShell's own
    // SettingsSection). Same real fix already used elsewhere in this
    // codebase for the identical race (GamesSection's own firstFocus
    // handling): key a SEPARATE effect off the state that must have already
    // recomposed, not `Unit`.
    LaunchedEffect(entries) {
        if (entries.isNotEmpty()) firstFocus.requestFocus()
    }

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black)
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp &&
                    (GamepadKeyMap.actionFor(event.key) == GamepadAction.BACK || GamepadKeyMap.actionFor(event.key) == GamepadAction.B)
                ) {
                    onDismiss()
                    true
                } else {
                    false
                }
            },
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            Text("Browse themes", color = Color.White, style = MaterialTheme.typography.headlineSmall)
            Text(
                "The real ES-DE community theme index. Selecting an entry downloads or updates it.",
                color = Color.Gray,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
            )
            when {
                loading -> Text("Loading...", color = Color.Gray)
                entries.isEmpty() -> Text(
                    "No themes indexed yet. Go back and run \"Sync theme index\" in Settings → Appearance first.",
                    color = Color.Gray,
                )
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(entries, key = { _, entry -> entry.reponame.ifBlank { entry.name } }) { index, entry ->
                        val dirName = entry.reponame.ifBlank { entry.name }
                        val installed = dirName in installedDirNames
                        val status = statusByDirName[dirName]
                        // Real screenshot preview -- ThemeDownloader already
                        // parses this (real ES-DE theme authors check
                        // screenshot images straight into their own
                        // themes-list.git entry, confirmed via
                        // GuiThemeDownloader.cpp's own real
                        // mThemeDirectory + "themes-list/" + image path
                        // convention), no extra network fetch needed --
                        // this was just never read by the UI until now.
                        val screenshotPath = entry.screenshots.firstOrNull()?.let {
                            File(ThemeDownloader.themesListDir(ThemeAssets.userThemesDir(context)), it.image).path
                        }
                        ThemeBrowserRow(
                            entry = entry,
                            installed = installed,
                            status = status,
                            screenshotPath = screenshotPath,
                            modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
                            onDownload = {
                                statusByDirName = statusByDirName + (dirName to "Downloading...")
                                coroutineScope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        ThemeDownloader.downloadOrUpdateTheme(ThemeAssets.userThemesDir(context), entry)
                                    }
                                    statusByDirName = statusByDirName + (dirName to when (result.status) {
                                        ThemeDownloader.ThemeSyncStatus.CLONED -> "Downloaded"
                                        ThemeDownloader.ThemeSyncStatus.UPDATED -> "Updated"
                                        ThemeDownloader.ThemeSyncStatus.UP_TO_DATE -> "Already up to date"
                                        ThemeDownloader.ThemeSyncStatus.DIVERGED -> "Has local changes -- skipped"
                                        ThemeDownloader.ThemeSyncStatus.FAILED -> "Failed: ${result.error?.message ?: "unknown error"}"
                                    })
                                    // A theme UPDATED in place keeps its name -- the
                                    // name-keyed parse cache would silently keep
                                    // serving the old version without this (see
                                    // ThemeAssets' own change-listener doc comment).
                                    if (result.status == ThemeDownloader.ThemeSyncStatus.CLONED ||
                                        result.status == ThemeDownloader.ThemeSyncStatus.UPDATED
                                    ) {
                                        dev.droidtop.library.theme.ThemePrefs.notifyThemesChanged()
                                    }
                                    refresh()
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemeBrowserRow(
    entry: ThemeDownloader.ThemeDownloadEntry,
    installed: Boolean,
    status: String?,
    screenshotPath: String?,
    modifier: Modifier,
    onDownload: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            // Same real touch-input fix used throughout this shell --
            // .focusable() alone only covers D-pad/gamepad focus, never touch.
            .clickable(onClick = onDownload)
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp && GamepadKeyMap.actionFor(event.key) == GamepadAction.A) {
                    onDownload()
                    true
                } else {
                    false
                }
            }
            .background(if (focused) Color(0xFF2A2A2A) else Color(0xFF1A1A1A), RoundedCornerShape(12.dp))
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (screenshotPath != null) {
            AsyncImage(
                model = screenshotPath,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.width(140.dp).height(90.dp).clip(RoundedCornerShape(8.dp)),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(entry.name, color = Color.White, style = MaterialTheme.typography.titleMedium)
                if (entry.deprecated) {
                    Text("DEPRECATED", color = Color(0xFFAA5555), style = MaterialTheme.typography.labelSmall)
                }
            }
            if (entry.author.isNotBlank()) {
                Text("by ${entry.author}", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                status ?: if (installed) "Installed -- select to check for updates" else "Not installed -- select to download",
                color = Color(0xFF8AB4FF),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
