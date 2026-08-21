package dev.droidtop.shell.standard

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import dev.droidtop.library.Library
import dev.droidtop.library.LibraryEntry
import kotlinx.coroutines.launch

/**
 * The "standard Android launcher" shell: a normal touch/mouse-first app-icon
 * grid, no gamepad navigation assumed or required — what a user expects if
 * they pick DroidTop as their Android home screen (see the HOME/DEFAULT
 * intent-filter on MainActivity in :app). This is what ships first; the
 * desktop (:shell-desktop) and gamepad-console (:shell-gamepad) shells are
 * later, optional alternatives built against the exact same [Library], not
 * variants of this one.
 */
@Composable
fun DefaultShell(library: Library) {
    var entries by remember { mutableStateOf<List<LibraryEntry>?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(library) {
        entries = library.scanAll()
    }

    val currentEntries = entries
    when {
        currentEntries == null -> Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator(modifier = Modifier.padding(16.dp))
        }

        currentEntries.isEmpty() -> Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Nothing in the library yet.", modifier = Modifier.padding(16.dp))
        }

        else -> LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 88.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(currentEntries, key = { it.id }) { entry ->
                AppIcon(entry = entry, onLaunch = { scope.launch { library.launch(entry) } })
            }
        }
    }
}

@Composable
private fun AppIcon(entry: LibraryEntry, onLaunch: () -> Unit) {
    val context = LocalContext.current
    // entry.id is a package name only for NATIVE_ANDROID_APP entries (the
    // only kind NativeAppProvider ever produces today) — any other kind
    // just falls through to the placeholder tile below via runCatching.
    val icon = remember(entry.id) {
        runCatching { context.packageManager.getApplicationIcon(entry.id) }.getOrNull()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onLaunch)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (icon != null) {
            Image(
                bitmap = icon.toBitmap(width = 128, height = 128).asImageBitmap(),
                contentDescription = entry.title,
                modifier = Modifier.size(64.dp),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .background(Color.DarkGray, RoundedCornerShape(12.dp)),
            )
        }
        Text(
            entry.title,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
