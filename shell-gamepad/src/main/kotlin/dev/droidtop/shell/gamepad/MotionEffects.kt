package dev.droidtop.shell.gamepad

// Rise is ported from DroidDeck (ui/FrontEndScreen.kt at 9310d19, `Rise` and `staggerIn`, GPL-3.0, see
// NOTICE.md), reworked onto droidtop's Motion roles: it runs in the layer phase instead of an
// AnimatedVisibility. The sheen ported with it is in :ui-shared's Shine.kt.

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import dev.droidtop.library.settings.CatalogPrefs
import dev.droidtop.library.settings.GamingSettingsCatalog
import dev.droidtop.ui.shared.Motion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn

/**
 * Keeps [Motion.enabled] current for as long as the Gaming shell is
 * composed: Gaming's Animations switch and Android's animator duration
 * scale, both observed (a preference listener and a settings observer), so
 * turning either off in Settings or in Android's developer options takes
 * effect on the next frame without a restart. Read off the main thread.
 */
@Composable
internal fun MotionSync() {
    val context = LocalContext.current.applicationContext
    LaunchedEffect(context) {
        motionInputs(context).collect { (switchOn, scale) -> Motion.update(switchOn, scale) }
    }
}

private fun motionInputs(context: Context) = callbackFlow {
    val prefs = CatalogPrefs.prefs(context)
    fun read() = GamingSettingsCatalog.animationsOn(context) to
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == GamingSettingsCatalog.ID_ANIMATIONS) trySend(read())
    }
    val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            trySend(read())
        }
    }
    prefs.registerOnSharedPreferenceChangeListener(listener)
    context.contentResolver.registerContentObserver(
        Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
        false,
        observer,
    )
    trySend(read())
    awaitClose {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
        context.contentResolver.unregisterContentObserver(observer)
    }
}.conflate().flowOn(Dispatchers.IO)

/**
 * Block [index] of a page fading in when it first composes (docs/SPEC.md
 * "Gaming motion and focus", Rise): it fades up from [Motion.RiseDp] below,
 * one stagger step after the block before it. Layer phase only, so the
 * block lays out once and nothing recomposes while it rises; with motion
 * off it is simply there.
 */
fun Modifier.rise(index: Int): Modifier = composed {
    val t = remember { Animatable(if (Motion.enabled) 0f else 1f) }
    LaunchedEffect(Unit) { t.animateTo(1f, Motion.rise(index)) }
    graphicsLayer {
        val p = t.value
        alpha = p
        translationY = (1f - p) * Motion.RiseDp * density
    }
}
