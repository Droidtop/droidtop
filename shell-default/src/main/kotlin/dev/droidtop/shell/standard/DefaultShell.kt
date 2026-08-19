package dev.droidtop.shell.standard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.droidtop.library.Library
import dev.droidtop.library.LibraryEntry
import kotlinx.coroutines.launch

/**
 * The default UI: a normal touch/mouse-first library grid, no gamepad
 * navigation assumed or required. This is what ships first — the gamepad
 * console shell (:shell-gamepad) is a later, optional alternative built
 * against the exact same [Library], not a variant of this one.
 *
 * Deliberately plain right now: one flat list, no per-kind grouping/artwork/
 * filtering yet. Real UI polish is future work once there's more than one
 * working [dev.droidtop.library.LibraryProvider] to actually look at.
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

        else -> LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            items(currentEntries, key = { it.id }) { entry ->
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    Text(entry.title)
                    Text(entry.kind.name)
                    Button(onClick = { scope.launch { library.launch(entry) } }) {
                        Text("Launch")
                    }
                }
            }
        }
    }
}
