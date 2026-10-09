package dev.droidtop.shell.desktop

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.droidtop.library.TaskbarPin
import dev.droidtop.library.TaskbarPins
import dev.droidtop.library.settings.LAUNCHER_PREFS_FILE_NAME
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The taskbar's pins and the one way to change them (docs/SPEC.md 2b, Droidtop/tracker#348). */
internal class Pins(val list: List<TaskbarPin>, val toggle: (TaskbarPin) -> Unit)

private const val KEY_PINS = "pref_desktop_taskbar_pins"

/**
 * The pins, read from the launcher preferences file off the main thread (they show as soon as it is read)
 * and written back the same way on every change. The rules are [TaskbarPins]'s.
 */
@Composable
internal fun rememberPins(): Pins {
    val appContext = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<TaskbarPin>>(emptyList()) }
    LaunchedEffect(Unit) {
        list = withContext(Dispatchers.IO) {
            TaskbarPins.decode(appContext.getSharedPreferences(LAUNCHER_PREFS_FILE_NAME, Context.MODE_PRIVATE).getString(KEY_PINS, null))
        }
    }
    return remember(list) {
        Pins(list) { pin ->
            val next = TaskbarPins.toggled(list, pin)
            list = next
            scope.launch(Dispatchers.IO) {
                appContext.getSharedPreferences(LAUNCHER_PREFS_FILE_NAME, Context.MODE_PRIVATE)
                    .edit().putString(KEY_PINS, TaskbarPins.encode(next)).apply()
            }
        }
    }
}
