package dev.droidtop.shell.gamepad

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.settings.SettingsScreenRegistry
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.GamepadKeyMap
import dev.droidtop.shell.gamepad.input.ownPadButtons

/**
 * Settings' "Game folders" screen, by [SettingsScreenRegistry] id: :app
 * registers it at process start, and this module cannot depend on :app.
 */
private const val GAME_FOLDERS_SCREEN_ID = "rom_folders"

/**
 * Launcher mode's Games grid (docs/SPEC.md 2c, "Games in the Launcher"),
 * drawn as droidtop's own chrome rather than a stock Material list: the
 * user did not recognise the first version as one of droidtop's screens
 * (rig, build 814). It is the shell's pieces, not a copy of them: the
 * Games section's own card ([GameCard], with its accent ring over a
 * raised fill), the black ground, the screen header, and a [TouchHintBar]
 * that names every action and dispatches it.
 *
 * What it does is Launcher mode's and no more: A plays, Y (or a long
 * press) pins the game to the home screen, Select opens Game folders in
 * place -- the settings screen that fills an empty grid, rendered by the
 * same navigator the shell's settings use -- and B leaves. There are no
 * themes, detail pages or Quick Menu here; those are Gaming's.
 *
 * [games] null is "not read yet", which says so rather than "no games".
 */
@Composable
fun LauncherGamesScreen(
    games: List<LibraryEntry>?,
    onPlay: (LibraryEntry) -> Unit,
    onPin: (LibraryEntry) -> Unit,
) {
    val shellWindow = currentShellWindow()
    CompositionLocalProvider(LocalShellWindow provides shellWindow) {
        val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
        val foldersScreen = remember { SettingsScreenRegistry.get(GAME_FOLDERS_SCREEN_ID) }
        var foldersOpen by rememberSaveable { mutableStateOf(false) }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MenuTokens.Ground)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                // The shell owns the pad here as everywhere: B is the back
                // dispatcher, which leaves this screen or closes Game folders.
                .ownPadButtons { backDispatcher?.onBackPressed() },
        ) {
            if (foldersOpen && foldersScreen != null) {
                BackHandler { foldersOpen = false }
                CatalogNavigator(root = foldersScreen, onExit = { foldersOpen = false })
            } else {
                GamesGrid(
                    games = games,
                    onPlay = onPlay,
                    onPin = onPin,
                    onOpenFolders = if (foldersScreen != null) ({ foldersOpen = true }) else null,
                )
            }
        }
    }
}

@Composable
private fun GamesGrid(
    games: List<LibraryEntry>?,
    onPlay: (LibraryEntry) -> Unit,
    onPin: (LibraryEntry) -> Unit,
    onOpenFolders: (() -> Unit)?,
) {
    val window = LocalShellWindow.current
    val focusManager = LocalFocusManager.current
    val firstCard = remember { FocusRequester() }
    val emptyAction = remember { FocusRequester() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            // One handler for the screen, above the cards: Compose moves
            // focus in a grid for nobody, so directions are moved here on
            // the UP edge (the DOWN edge is taken so the framework cannot
            // move a second time), and Select opens Game folders.
            .onKeyEvent { event ->
                val action = GamepadKeyMap.actionFor(event.key)
                val direction = when (action) {
                    GamepadAction.UP -> FocusDirection.Up
                    GamepadAction.DOWN -> FocusDirection.Down
                    GamepadAction.LEFT -> FocusDirection.Left
                    GamepadAction.RIGHT -> FocusDirection.Right
                    else -> null
                }
                if (event.type == KeyEventType.KeyDown) return@onKeyEvent direction != null
                if (event.type != KeyEventType.KeyUp) return@onKeyEvent false
                when {
                    direction != null -> {
                        focusManager.moveFocus(direction)
                        true
                    }
                    action == GamepadAction.SELECT && onOpenFolders != null -> {
                        onOpenFolders()
                        true
                    }
                    else -> false
                }
            },
    ) {
        MenuHeader(
            title = "Games",
            subtitle = when {
                games == null -> null
                games.size == 1 -> "1 game"
                else -> "${games.size} games"
            },
        )
        Box(modifier = Modifier.weight(1f)) {
            when {
                games == null -> EmptyLine("Reading the library…")
                games.isEmpty() -> {
                    // An empty grid offers the one thing that fills it.
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(window.edgePadding),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(Space.Lg),
                    ) {
                        Text("No games yet.", color = MenuTokens.OnSurfaceMuted, style = TypeRole.body)
                        if (onOpenFolders != null) {
                            ShellChip(
                                "Add a games folder",
                                primary = true,
                                modifier = Modifier.focusRequester(emptyAction),
                                onClick = onOpenFolders,
                            )
                            LaunchedEffect(Unit) { requestFocusWhenAttached(emptyAction, "Launcher games empty") }
                        }
                    }
                }
                else -> {
                    LaunchedEffect(Unit) { requestFocusWhenAttached(firstCard, "Launcher games") }
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = window.gridItemMinWidth),
                        modifier = Modifier.fillMaxSize().padding(horizontal = window.edgePadding),
                        horizontalArrangement = Arrangement.spacedBy(Space.Xl),
                        verticalArrangement = Arrangement.spacedBy(Space.Xl),
                        contentPadding = PaddingValues(top = Space.Sm, bottom = Space.Xl),
                    ) {
                        itemsIndexed(games, key = { _, entry -> entry.id }) { index, entry ->
                            GameCard(
                                entry = entry,
                                modifier = if (index == 0) Modifier.focusRequester(firstCard) else Modifier,
                                onLaunch = { onPlay(entry) },
                                // Y and a long press: the shell's "act on this
                                // one", which in the Launcher is pinning it.
                                onShowDetail = { onPin(entry) },
                            )
                        }
                    }
                }
            }
        }
        TouchHintBar(
            hints = buildList {
                if (!games.isNullOrEmpty()) {
                    add(GamepadAction.A to "Play")
                    add(GamepadAction.Y to "Pin to home screen")
                }
                if (onOpenFolders != null) add(GamepadAction.SELECT to "Game folders")
                add(GamepadAction.B to "Back")
            },
        )
    }
}

@Composable
private fun EmptyLine(text: String) {
    Box(Modifier.fillMaxSize().padding(Space.Xl), contentAlignment = Alignment.Center) {
        Text(text, color = MenuTokens.OnSurfaceMuted, style = TypeRole.body)
    }
}
