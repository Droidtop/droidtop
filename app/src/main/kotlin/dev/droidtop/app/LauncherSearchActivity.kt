package dev.droidtop.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.droidtop.library.LibraryKinds
import dev.droidtop.library.scanFollowingGamesRoots
import dev.droidtop.library.scraper.isPcOrEngineGame
import dev.droidtop.shell.gamepad.query.LauncherSearchApp
import dev.droidtop.shell.gamepad.query.LauncherSearchScreen
import dev.droidtop.shell.standard.LauncherSearch
import kotlinx.coroutines.launch

/**
 * Standard mode's search (docs/SPEC.md 12a "Launcher search"): what the
 * launcher's drawer search field opens. It draws the shared library search
 * ([LauncherSearchScreen], the same dialog the PC library and the console
 * lists use) over the launcher: the installed apps, the library's games and
 * the download sources answer in its one ranked list, and droidtop's
 * Recommendations come from the shared dialog itself. A translucent
 * window, so the drawer stays visible behind it.
 *
 * The launcher reaches it by the action [LauncherSearch.ACTION_SEARCH]
 * because `:shell-default` cannot depend on `:app`.
 */
class LauncherSearchActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val library = LibraryCore.library(applicationContext)
        // The library this search reads is the one every surface reads: while
        // this window is up the games roots are followed, as the Games grid does.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                library.scanFollowingGamesRoots(applicationContext, LibraryKinds.GAMES)
            }
        }
        val games = library.backgroundScanState(LibraryKinds.GAMES)
        // Get games, outside Gaming (Downloads and Updates' row, Droidtop/tracker#825): the same one search Gaming opens,
        // with the source filter and what the sources feature.
        if (intent?.getBooleanExtra(EXTRA_GET_GAMES, false) == true) {
            setContent {
                dev.droidtop.app.ui.DroidtopTheme {
                    dev.droidtop.shell.gamepad.GetGamesSearch(onDismiss = { finish() })
                }
            }
            return
        }
        val initialText = intent?.getStringExtra(LauncherSearch.EXTRA_TEXT).orEmpty()
        setContent {
            dev.droidtop.app.ui.DroidtopTheme {
                val entries by games.collectAsStateWithLifecycle()
                LauncherSearchScreen(
                    initialText = initialText,
                    games = entries,
                    findApps = { query ->
                        LauncherSearch.findApps(applicationContext, query).map { hit ->
                            LauncherSearchApp(
                                key = "${hit.component.flattenToShortString()}/${hit.user}",
                                title = hit.title,
                                icon = hit.icon,
                                open = { LauncherSearch.launch(this@LauncherSearchActivity, hit) },
                            )
                        }
                    },
                    // The game's page on the Games screen, where Play or Install is (docs/SPEC.md 12a). Standard gives
                    // PC and engine games a page; any other game starts as before.
                    onOpen = { entry ->
                        if (entry.isPcOrEngineGame) {
                            startActivity(LauncherGamesActivity.pageIntent(this@LauncherSearchActivity, entry.id))
                            finish()
                        } else {
                            GameLaunchActivity.dispatch(this@LauncherSearchActivity, entry)
                        }
                    },
                    // Standard's own settings (the Global settings screen) are searched with the rest.
                    settingsRoot = remember { dev.droidtop.library.settings.SettingsScreenRegistry.get(dev.droidtop.app.settings.DroidtopWideSettings.SCREEN_GLOBAL) },
                    // The web, with the search bar's provider, as one row (Droidtop/tracker#564).
                    webSearch = { query -> app.murinelauncher.widget.search.WebSearch.open(this@LauncherSearchActivity, query) },
                    onDismiss = { finish() },
                )
            }
        }
    }

    companion object {
        private const val EXTRA_GET_GAMES = "dev.droidtop.app.extra.GET_GAMES"

        /** Get games over whatever is in front: the one search, opened on what the sources feature. */
        fun getGamesIntent(context: android.content.Context): android.content.Intent =
            android.content.Intent(context, LauncherSearchActivity::class.java)
                .putExtra(EXTRA_GET_GAMES, true)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
