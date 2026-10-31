package dev.droidtop.app

import android.content.Intent
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.droidtop.app.ui.PadButton
import dev.droidtop.library.format.ValueFormat
import dev.droidtop.library.theme.ExtractionFailures
import dev.droidtop.runtime.StorageGuard
import dev.droidtop.shell.gamepad.GamingPalette
import dev.droidtop.shell.gamepad.Space
import dev.droidtop.shell.gamepad.TypeRole

/**
 * The screen droidtop shows instead of itself when its storage is too full to start (docs/SPEC.md 10c, "Storage
 * full"; Droidtop/tracker#849): "a plain message that storage is full, no ANR, no stuck state". With about 12 MB free,
 * opening droidtop timed out with "Application does not have a focused window" and drew nothing.
 *
 * It is decided first thing in an activity's onCreate, before any preference is read or any shell is built, and it
 * reads and writes no file: droidtop's own palette (never the theme's, which would mean loading the theme from the
 * very storage that is full), its own strings, and one statfs per Try again. Its buttons are [PadButton]s, which the
 * pad's A presses as well as a tap, and the first one takes the focus.
 */
internal object StorageFullScreen {
    /**
     * Shows the storage-full screen on [activity] when storage is too full now, and returns whether it did; the caller
     * then returns from onCreate without doing anything else. Try again, with room found, recreates the activity,
     * which starts it properly.
     */
    fun showIfFull(activity: ComponentActivity): Boolean {
        if (!StorageGuard.check(activity)) return false
        activity.setContent {
            StorageFull(
                onOpenSettings = { openStorageSettings(activity) },
                onTryAgain = {
                    if (StorageGuard.check(activity)) {
                        StorageGuard.availableBytes(activity)
                    } else {
                        // A theme whose extraction failed for want of room may be tried again now.
                        ExtractionFailures.clear()
                        activity.recreate()
                        null
                    }
                },
            )
        }
        return true
    }

    private fun openStorageSettings(activity: ComponentActivity) {
        runCatching { activity.startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)) }
            .recoverCatching { activity.startActivity(Intent(Settings.ACTION_SETTINGS)) }
            .onFailure { android.util.Log.w("droidtop.StorageFull", "No settings screen to open", it) }
    }
}

/** [onTryAgain] returns the bytes still free when there is still too little room, else null. */
@Composable
private fun StorageFull(onOpenSettings: () -> Unit, onTryAgain: () -> Long?) {
    val palette = GamingPalette.Default
    var stillFree by remember { mutableStateOf<Long?>(null) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    MaterialTheme(colorScheme = palette.colorScheme, typography = palette.typography) {
        Box(
            modifier = Modifier.fillMaxSize().background(palette.ground).systemBarsPadding().padding(Space.Xl),
            contentAlignment = Alignment.Center,
        ) {
            Column(modifier = Modifier.widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(Space.Lg)) {
                Text(stringResource(R.string.storage_full_title), style = TypeRole.screenTitle, color = palette.onSurface)
                Text(
                    stringResource(R.string.storage_full_body, ValueFormat.size(StorageGuard.LOW_SPACE_BYTES)),
                    style = TypeRole.body,
                    color = palette.onSurface,
                )
                stillFree?.let {
                    Text(stringResource(R.string.storage_full_still, ValueFormat.size(it)), style = TypeRole.body, color = palette.onSurfaceMuted)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Space.Md)) {
                    PadButton(
                        label = stringResource(R.string.storage_full_open_settings),
                        onClick = onOpenSettings,
                        modifier = Modifier.focusRequester(first),
                        filled = true,
                    )
                    PadButton(
                        label = stringResource(R.string.storage_full_try_again),
                        onClick = { stillFree = onTryAgain() },
                    )
                }
            }
        }
    }
}
