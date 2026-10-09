package dev.droidtop.display

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.droidtop.library.settings.CatalogPrefs
import dev.droidtop.library.settings.CompanionOrientation
import dev.droidtop.library.settings.Mode
import dev.droidtop.library.settings.Modes

/**
 * Every companion surface's content, turned a quarter inside a window whose shape is not the one asked for
 * ([CompanionOrientation.rotateContent]): the companion's "Lock to landscape", else the active mode's Screen
 * orientation. A Presentation, and an activity on a display that cannot rotate, cannot change the window's shape.
 */
@Composable
fun DisplayOrientationContent(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val context = LocalContext.current
        val prefs = remember(context) { CatalogPrefs.prefs(context) }
        var modeId by remember(context) { mutableStateOf(Modes.lastMode(context)) }
        var lockLandscape by remember(prefs) { mutableStateOf(CompanionOrientation.lockLandscape(context)) }
        DisposableEffect(prefs) {
            val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == "droidtop_last_mode") modeId = Modes.lastMode(context)
                if (key == CompanionOrientation.KEY_LOCK_LANDSCAPE) lockLandscape = CompanionOrientation.lockLandscape(context)
            }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }
        val mode = Mode.byId(modeId) ?: Mode.LAUNCHER
        var setting by remember(mode, prefs) {
            mutableStateOf(prefs.getString("pref_screen_orientation_${mode.id}", "follow"))
        }
        DisposableEffect(prefs, mode) {
            val key = "pref_screen_orientation_${mode.id}"
            val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, changed ->
                if (changed == key) setting = prefs.getString(key, "follow")
            }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }
        if (CompanionOrientation.rotateContent(setting, lockLandscape, windowLandscape = maxWidth > maxHeight)) {
            Box(
                Modifier.align(Alignment.Center)
                    .requiredSize(width = maxHeight, height = maxWidth)
                    .graphicsLayer(rotationZ = 90f),
            ) { content() }
        } else {
            content()
        }
    }
}
