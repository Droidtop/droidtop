package dev.droidtop.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.gamenative.data.LibraryItem
import app.gamenative.ui.screen.downloads.HomeDownloadsScreen
import app.gamenative.ui.screen.library.AppScreen
import dev.droidtop.app.ui.DroidtopTheme
import dev.droidtop.runtime.windows.PcLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Install, download, verify and store management for ONE PC game, and the
 * downloads queue for all of them -- build-plan step 5 of the PC surface
 * (docs/SPEC.md 7i).
 *
 * Nothing here is droidtop's own UI, on purpose. `:runtime-windows`
 * compiles the whole vendored gamenative tree (7c's "increment 2"), so the
 * complete install lifecycle for all four stores is already in the APK:
 * `AppScreen` picks the right store's screen for a game and brings its
 * `GameManagerDialog` / `EpicGameManagerDialog` / `AmazonInstallDialog`,
 * depot and DLC selection, verify, update, pause and delete with it, and
 * `HomeDownloadsScreen` is the queue. droidtop supplies the entry point
 * and the palette, not a second implementation of any of it.
 *
 * **Playing is not this screen's job.** The hosted screens have play
 * buttons of their own, and droidtop resolves a runner for a game on the
 * game's own screen, where a runner that needs setup can say so (7i). So
 * those callbacks close this screen and return the user to exactly that
 * row rather than opening a second launch path that could disagree with
 * it -- and a store-installed engine game is the case that proves the
 * point: gamenative would launch it under Wine, while droidtop runs it on
 * enginehost.
 */
@dagger.hilt.android.AndroidEntryPoint
class PcStoreActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val entryId = intent.getStringExtra(EXTRA_ENTRY_ID)
        setContent {
            DroidtopTheme(darkTheme = true) {
                if (entryId == null) {
                    // Every download rather than one game's: the queue,
                    // its storage manager and its per-game rows.
                    HomeDownloadsScreen(
                        onBack = { finish() },
                        onClickPlay = { _, _ -> finish() },
                        onTestGraphics = { finish() },
                        onPlayWithDiagnostics = { finish() },
                    )
                } else {
                    StoreGame(entryId = entryId, onBack = { finish() })
                }
            }
        }
    }

    companion object {
        /** droidtop's own PC entry id ("steam:440"); absent means the downloads queue. */
        const val EXTRA_ENTRY_ID = "dev.droidtop.app.extra.PC_ENTRY_ID"

        private const val CLASS_NAME = "dev.droidtop.app.PcStoreActivity"

        /**
         * Started by explicit class name rather than a typed Intent: the
         * shells cannot depend on `:app` (it depends on them), the same
         * reason every other cross-module screen launch here does it.
         */
        fun intent(context: Context, entryId: String?): Intent =
            Intent().setClassName(context.packageName, CLASS_NAME).apply {
                entryId?.let { putExtra(EXTRA_ENTRY_ID, it) }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
    }
}

@Composable
private fun StoreGame(entryId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    var item by remember(entryId) { mutableStateOf<LibraryItem?>(null) }
    var resolved by remember(entryId) { mutableStateOf(false) }

    LaunchedEffect(entryId) {
        item = withContext(Dispatchers.IO) { PcLibrary.libraryItemFor(context, entryId) }
        resolved = true
    }

    val current = item
    when {
        current != null -> AppScreen(
            libraryItem = current,
            onClickPlay = { onBack() },
            onTestGraphics = { onBack() },
            onPlayWithDiagnostics = { onBack() },
            onBack = onBack,
        )
        resolved -> Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("No store knows this game", style = MaterialTheme.typography.titleMedium)
            Text(
                "droidtop found this game on disk rather than in a store library, so there is " +
                    "nothing to install, verify or download here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        else -> Text("Loading...", modifier = Modifier.padding(24.dp))
    }
}
