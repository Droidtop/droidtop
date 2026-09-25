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
import androidx.activity.compose.BackHandler
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import dev.droidtop.shell.gamepad.MenuTokens
import dev.droidtop.shell.gamepad.selectionFrame
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.GamepadKeyMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import kotlinx.coroutines.withContext

/**
 * Browse and download ES-DE community themes (`ThemeDownloader`,
 * `ThemeAssets`). Reads the parsed `themes-list.git` clone
 * (`ThemeDownloader.parseThemesList`), and fetches that index itself when
 * it is missing or more than a week old (docs/SPEC.md 7f, "Browse
 * themes"): the empty state used to send the person back to a separate
 * "Sync theme index" row in Settings (UI pass 2026-09-24, M3).
 *
 * Downloading/updating a theme (`ThemeDownloader.downloadOrUpdateTheme`)
 * writes into `ThemeAssets.userThemesDir`, the exact same directory
 * `ThemeAssets.discoverThemes` already scans -- a newly downloaded theme
 * becomes selectable from Settings' own "Theme" cycle-link immediately,
 * no separate registration step.
 */
@Composable
fun ThemeBrowserScreen(
    onDismiss: () -> Unit,
    // False inside the Gaming shell, whose own hint row is already drawn
    // under this screen; two rows stacked read as a glitch (rig,
    // dq-onboard-02).
    showHints: Boolean = true,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<ThemeDownloader.ThemeDownloadEntry>>(emptyList()) }
    var installedDirNames by remember { mutableStateOf<Set<String>>(emptySet()) }
    var statusByDirName by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    // What the index fetch is doing, or why it failed; null once there is
    // a list to show.
    var fetchStatus by remember { mutableStateOf<String?>(null) }
    var fetchFailed by remember { mutableStateOf(false) }
    val firstFocus = remember { FocusRequester() }
    val emptyFocus = remember { FocusRequester() }

    suspend fun refresh() {
        withContext(Dispatchers.IO) {
            entries = ThemeDownloader.parseThemesList(ThemeAssets.userThemesDir(context))
            installedDirNames = ThemeAssets.discoverThemes(context).map { it.name }.toSet()
        }
    }
    suspend fun fetchIndex() {
        fetchFailed = false
        fetchStatus = "Fetching the theme list\u2026"
        val result = withContext(Dispatchers.IO) {
            ThemeDownloader.syncThemesList(ThemeAssets.userThemesDir(context))
        }
        refresh()
        fetchFailed = result.status == ThemeDownloader.ThemeSyncStatus.FAILED && entries.isEmpty()
        fetchStatus = when {
            fetchFailed -> "Could not fetch the theme list. Check the connection, then press A to try again."
            entries.isEmpty() -> "The theme list is empty."
            else -> null
        }
    }
    LaunchedEffect(Unit) {
        loading = true
        refresh()
        loading = false
        val stale = withContext(Dispatchers.IO) {
            val dir = ThemeDownloader.themesListDir(ThemeAssets.userThemesDir(context))
            val stamp = File(dir, ".git/FETCH_HEAD").takeIf { it.exists() } ?: dir
            System.currentTimeMillis() - stamp.lastModified() > INDEX_MAX_AGE_MS
        }
        if (entries.isEmpty() || stale) fetchIndex()
    }
    LaunchedEffect(entries.isEmpty(), loading) {
        if (entries.isEmpty() && !loading) runCatching { emptyFocus.requestFocus() }
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
    // The dispatcher route out, which is the only route when the list is
    // empty: with no row focused there is nothing for a key event to
    // bubble from, so the onKeyEvent below never ran and BACK, B and the
    // "B Back" pill were all inert on a rig with no theme index (build
    // 549). This screen replaces the settings navigator, whose own
    // BackHandler left with it.
    BackHandler { onDismiss() }

    Box(
        modifier = Modifier.fillMaxSize().background(MenuTokens.Ground)
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = dev.droidtop.shell.gamepad.LocalShellWindow.current.edgePadding, vertical = 24.dp),
        ) {
            Text("Browse themes", color = MenuTokens.OnSurface, style = MaterialTheme.typography.headlineSmall)
            Text(
                "Themes from the ES-DE community's theme list. Select one to download it, or to update it if you have it.",
                color = MenuTokens.OnSurfaceMuted,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
            )
            // A refresh of a stale list runs under the list it already has,
            // and says so above it.
            if (entries.isNotEmpty()) {
                fetchStatus?.let { Text(it, color = MenuTokens.OnSurfaceMuted, modifier = Modifier.padding(bottom = 8.dp)) }
            }
            when {
                loading -> Text("Loading\u2026", color = MenuTokens.OnSurfaceMuted)
                entries.isEmpty() -> Text(
                    fetchStatus ?: "Fetching the theme list\u2026",
                    color = MenuTokens.OnSurfaceMuted,
                    // The one thing on screen when the fetch failed, so A
                    // on it is the retry.
                    modifier = Modifier
                        .focusRequester(emptyFocus)
                        .onKeyEvent { event ->
                            if (fetchFailed && event.type == KeyEventType.KeyUp &&
                                GamepadKeyMap.actionFor(event.key) == GamepadAction.A
                            ) {
                                coroutineScope.launch { fetchIndex() }
                                true
                            } else {
                                false
                            }
                        }
                        .focusable()
                        .clickable(enabled = fetchFailed) { coroutineScope.launch { fetchIndex() } },
                )
                else -> LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    // The hint bar's own room (MenuTokens.HintBarRoom).
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        bottom = dev.droidtop.shell.gamepad.MenuTokens.HintBarRoom,
                    ),
                ) {
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
                                        ThemeDownloader.ThemeSyncStatus.DIVERGED -> "Not updated: your copy has local changes"
                                        ThemeDownloader.ThemeSyncStatus.FAILED -> "Download failed. Check the connection and select it again."
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
        // The hint row, and on a touch screen the buttons themselves; the
        // list already leaves it its room (MenuTokens.HintBarRoom). It had
        // none on its own, opened from Settings or onboarding (rig,
        // dq-onboard-01).
        if (showHints) dev.droidtop.shell.gamepad.TouchHintBar(
            hints = listOf(
                GamepadAction.A to "Download or update",
                GamepadAction.B to "Back",
            ),
            modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter),
        )
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
            // Ahead of the focus targets, not after them: see [GameCard].
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp && GamepadKeyMap.actionFor(event.key) == GamepadAction.A) {
                    onDownload()
                    true
                } else {
                    false
                }
            }
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            // Same real touch-input fix used throughout this shell --
            // .focusable() alone only covers D-pad/gamepad focus, never touch.
            .clickable(onClick = onDownload)
            .selectionFrame(focused, RoundedCornerShape(12.dp), rest = MenuTokens.Card)
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
                Text(entry.name, color = MenuTokens.OnSurface, style = MaterialTheme.typography.titleMedium)
                if (entry.deprecated) {
                    Text("DEPRECATED", color = MenuTokens.Danger, style = MaterialTheme.typography.labelSmall)
                }
            }
            if (entry.author.isNotBlank()) {
                Text("by ${entry.author}", color = MenuTokens.OnSurfaceMuted, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                status ?: if (installed) "Installed — select to check for updates" else "Not installed — select to download",
                color = MenuTokens.Accent,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** How old the theme list may get before opening this screen fetches it again. */
private const val INDEX_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
