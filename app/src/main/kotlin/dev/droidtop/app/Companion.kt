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
 * NOTE: the `SecondScreenPresentation` class that used to head this file
 * is gone. droidtop no longer pushes a `Presentation` at the second
 * screen; `:display`'s `SecondaryDisplayActivity` holds
 * `SECONDARY_HOME` and the platform places it (docs/SPEC.md section 4c).
 * What remains here is the companion's own state and backdrop, which
 * both hosts still render.
 *
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
/**
 * The one place the focused-entry feed lives — written by whatever drives
 * the shell ([dev.droidtop.shell.gamepad.GamepadShell]'s focus callback,
 * via MainActivity), read by BOTH companion hosts ([CompanionActivity] on
 * the built-in screen when the shell is on the addon, and
 * :display's SecondaryDisplayActivity on the addon when the shell
 * stays built-in).
 * A process-wide flow rather than a field on either host, since which
 * host exists changes with [DisplayRolePrefs] + live display attach.
 */
object CompanionState {
    val focusedEntry = MutableStateFlow<LibraryEntry?>(null)

    /**
     * The library the idle rotation draws from (docs/SPEC.md section 4d).
     * Published by whatever drives the shell, same as [focusedEntry] --
     * the companion must not run its own scan, both because it would
     * duplicate work and because it renders on a screen the user is not
     * driving.
     */
    val libraryEntries = MutableStateFlow<List<LibraryEntry>>(emptyList())
}

@Composable
internal fun CompanionContent(entry: LibraryEntry?) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // Nothing focused: a slow rotation of the user's own library
        // artwork (docs/SPEC.md section 4d, from iiSU's "Show Hero on Idle
        // Bottom Screen"). This used to paint a "droidtop" wordmark, which
        // is the one thing a glanceable surface must never do: occupy a
        // whole panel and say nothing.
        if (entry == null) {
            val entries by CompanionState.libraryEntries.collectAsState()
            CompanionIdle(entries)
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
                // PC entries carry facts a ROM does not: which store it
                // came from, how much disk it holds, and what other
                // people's machines made of it. The compatibility line is
                // REFERENCE, never a verdict (docs/SPEC.md section 7g) --
                // it is phrased as counts so the user judges it.
                entry.pcInfo?.let { pc ->
                    val facts = buildList {
                        add(pc.source)
                        if (!pc.installed) add("Not installed")
                        if (pc.sizeBytes > 0) add(formatSize(pc.sizeBytes))
                    }
                    Text(
                        facts.joinToString("  ·  "),
                        color = Color(0xFF9BB4D0),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    pc.compatibility?.let { compat ->
                        Text(
                            compat.summary(),
                            color = Color.Gray,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}

/** Human-readable install size; GB once it passes a gigabyte, MB below. */
private fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> String.format("%.1f GB", bytes / 1_000_000_000.0)
    else -> "${bytes / 1_000_000} MB"
}
