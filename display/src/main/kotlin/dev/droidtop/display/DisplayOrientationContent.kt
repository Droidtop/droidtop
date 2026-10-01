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
import dev.droidtop.library.settings.Mode
import dev.droidtop.library.settings.Modes

/** Rotate fixed-orientation content inside a display that cannot rotate with its host Activity. */
@Composable
internal fun DisplayOrientationContent(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val context = LocalContext.current
        val prefs = remember(context) { CatalogPrefs.prefs(context) }
        var modeId by remember(context) { mutableStateOf(Modes.lastMode(context)) }
        DisposableEffect(prefs) {
            val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == "droidtop_last_mode") modeId = Modes.lastMode(context)
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
        val wantsLandscape = setting == "landscape" || setting == "landscape_flipped"
        val fixed = setting != null && setting != "follow"
        val rotate = fixed && wantsLandscape != (maxWidth > maxHeight)
        if (rotate) {
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
