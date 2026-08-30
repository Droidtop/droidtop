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
import dev.droidtop.library.LibraryEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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

    private val _focusedEntry = MutableStateFlow<LibraryEntry?>(null)

    /** Set by whatever's driving the primary screen (currently [dev.droidtop.shell.gamepad.GamepadShell]'s focus callback) — the companion panel just reflects it live. */
    var focusedEntry: LibraryEntry?
        get() = _focusedEntry.value
        set(value) {
            _focusedEntry.value = value
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedStateOwner.controller.performRestore(null)
        lifecycleOwner.registry.currentState = Lifecycle.State.CREATED

        val composeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(savedStateOwner)
            setContent {
                val entry by _focusedEntry.collectAsState()
                // darkTheme = true: an ambient always-dark companion
                // surface (black ground is the design, like an idle
                // screen) -- see DroidtopTheme's own doc comment.
                dev.droidtop.app.ui.DroidtopTheme(darkTheme = true) { CompanionInfoPanel(entry) }
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
 * The actual "rich informational display" content — currently: whatever
 * [LibraryEntry] is focused on the primary screen, live. Room to grow
 * (artwork, achievements, system status) without touching the
 * `Presentation` plumbing above — this composable is the only thing that
 * needs to change to make the panel richer.
 */
@Composable
private fun CompanionInfoPanel(entry: LibraryEntry?) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        if (entry == null) {
            Text("droidtop", color = Color.DarkGray, style = MaterialTheme.typography.headlineMedium)
        } else {
            Column(
                modifier = Modifier.padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(entry.title, color = Color.White, style = MaterialTheme.typography.headlineLarge)
                Text(entry.kind.name, color = Color.Gray, style = MaterialTheme.typography.titleMedium)
                if (entry.playtimeSeconds > 0) {
                    Text("Played ${entry.playtimeSeconds / 60} min", color = Color.Gray, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}
