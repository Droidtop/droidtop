package dev.droidtop.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.droidtop.library.LibraryKinds
import dev.droidtop.library.scanFollowingGamesRoots
import dev.droidtop.shell.gamepad.query.LauncherSearchApp
import dev.droidtop.shell.gamepad.query.LauncherSearchScreen
import dev.droidtop.shell.standard.LauncherSearch
import kotlinx.coroutines.launch

/**
 * Standard mode's search (docs/SPEC.md 12a "Launcher search"): what the
 * launcher's drawer search field opens. It draws the shared library search
 * ([LauncherSearchScreen], the same dialog the PC library and the console
 * lists use) over the launcher, with the installed apps and the library's
 * games as its local results, and the source plugins' "Get more" and
 * droidtop's Recommendations from the shared dialog itself. A translucent
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
        val initialText = intent?.getStringExtra(LauncherSearch.EXTRA_TEXT).orEmpty()
        setContent {
            dev.droidtop.app.ui.DroidtopTheme(darkTheme = true) {
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
                    onPlay = { GameLaunchActivity.dispatch(this@LauncherSearchActivity, it) },
                    onDismiss = { finish() },
                )
            }
        }
    }
}
