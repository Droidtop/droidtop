package dev.droidtop.app

import android.content.Context
import android.os.Bundle
import android.view.Display
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.text.style.TextOverflow
import dev.droidtop.library.LibraryEntry
import dev.droidtop.library.consoles.PlatformsDatabase
import dev.droidtop.library.displayName
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Rich companion display for the second/lower screen — per direction:
 * "we want the second screen to be a very rich informational display,"
 * not just a passive keyboard/trackpad surface. `android.app.Presentation`
 * (via `android.software.presentation`, declared in this module's
 * manifest) is Android's own standard mechanism for real content on a
 * secondary `Display` — this is that, hosting a Compose UI via
 * [ComposeView].
 *
 * `Presentation`'s own `Context` isn't automatically a
 * [LifecycleOwner]/[SavedStateRegistryOwner] the way an Activity's is —
 * [ComposeView] needs both attached manually or it throws at composition
 * time. [lifecycleOwner]/[savedStateOwner] below are the standard,
 * documented pattern for hosting Compose inside a Dialog/Presentation,
 * not something invented here.
 *
 * UNVERIFIED against a real dual-screen device — no hardware with a real
 * secondary `Display` was available while writing this. The mechanism
 * (`Presentation` + `ComposeView` + manual lifecycle wiring) is
 * documented, standard Android API usage, but this exact class has not
 * been run against a live second screen.
 */
class SecondScreenPresentation(outerContext: Context, display: Display) : android.app.Presentation(outerContext, display) {
    private val lifecycleOwner = object : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private val savedStateOwner = object : SavedStateRegistryOwner {
        val controller = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = lifecycleOwner.lifecycle
        override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedStateOwner.controller.performRestore(null)
        lifecycleOwner.registry.currentState = Lifecycle.State.CREATED

        val composeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(savedStateOwner)
            setContent {
                val entry by CompanionState.focusedEntry.collectAsState()
                // darkTheme = true: an ambient always-dark companion
                // surface (black ground is the design, like an idle
                // screen) -- see DroidtopTheme's own doc comment.
                dev.droidtop.app.ui.DroidtopTheme(darkTheme = true) { CompanionContent(entry) }
            }
        }
        setContentView(composeView)
    }

    override fun onStart() {
        super.onStart()
        lifecycleOwner.registry.currentState = Lifecycle.State.RESUMED
    }

    override fun onStop() {
        lifecycleOwner.registry.currentState = Lifecycle.State.DESTROYED
        super.onStop()
    }
}

/**
 * The one place the focused-entry feed lives — written by whatever drives
 * the shell ([dev.droidtop.shell.gamepad.GamepadShell]'s focus callback,
 * via MainActivity), read by BOTH companion hosts ([CompanionActivity] on
 * the built-in screen when the shell is on the addon, and
 * [SecondScreenPresentation] on the addon when the shell stays built-in).
 * A process-wide flow rather than a field on either host, since which
 * host exists changes with [DisplayRolePrefs] + live display attach.
 */
object CompanionState {
    val focusedEntry = MutableStateFlow<LibraryEntry?>(null)
}

@Composable
internal fun CompanionContent(entry: LibraryEntry?) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (entry == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("droidtop", color = Color.DarkGray, style = MaterialTheme.typography.headlineMedium)
            }
            return@Box
        }
        // No artwork here by design (per direction): this panel is the
        // ambient WIDGETS/INFO surface (§4's dual-screen roles -- the
        // shell itself lives on the other display when both exist), so
        // it carries focused-game information, not a second copy of the
        // shell's art.
        Row(
            modifier = Modifier.fillMaxSize().padding(48.dp),
            horizontalArrangement = Arrangement.spacedBy(40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(entry.title, color = Color.White, style = MaterialTheme.typography.headlineLarge)
                // Real system name (Nintendo 64, PlayStation 2) when this
                // is a console ROM; the shared kind grouping name otherwise.
                val systemName = entry.systemId
                    ?.let { id -> PlatformsDatabase.displayNameOrNull(id) }
                    ?: entry.kind.displayName()
                Text(systemName, color = Color(0xFF9BB4D0), style = MaterialTheme.typography.titleMedium)
                val detailLine = listOfNotNull(
                    entry.developer,
                    entry.releaseDate?.take(4),
                    entry.genre,
                ).joinToString("  ·  ")
                if (detailLine.isNotEmpty()) {
                    Text(detailLine, color = Color.LightGray, style = MaterialTheme.typography.bodyLarge)
                }
                entry.rating?.let { rating ->
                    Text(
                        "★".repeat((rating * 5).toInt().coerceIn(0, 5)) + "☆".repeat(5 - (rating * 5).toInt().coerceIn(0, 5)),
                        color = Color(0xFFE0C060),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                entry.description?.let { description ->
                    Text(
                        description,
                        color = Color.Gray,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 6,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
                if (entry.playtimeSeconds > 0) {
                    Text("Played ${entry.playtimeSeconds / 60} min", color = Color.Gray, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}
