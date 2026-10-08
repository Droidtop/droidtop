package dev.droidtop.shell.gamepad

// Rise and the sheen are ported from DroidDeck (ui/FrontEndScreen.kt at 9310d19, `Rise`, `staggerIn`
// and `shine`, GPL-3.0, see NOTICE.md), reworked onto droidtop's Motion roles: Rise runs in the layer
// phase instead of an AnimatedVisibility, and the sheen's brush is built once per size, not per frame.

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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import dev.droidtop.library.settings.CatalogPrefs
import dev.droidtop.library.settings.GamingSettingsCatalog
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

/**
 * A one-shot diagonal sheen across the content each time [trigger] turns
 * true: the capsule that just took the cursor, the Play button. [play] is
 * the Play button's slower stripe. Drawn over the content in the draw phase
 * only; nothing is drawn while it is not sweeping.
 */
fun Modifier.shine(trigger: Boolean, play: Boolean = false, strength: Float = 0.22f): Modifier = composed {
    val x = remember { Animatable(1f) }
    LaunchedEffect(trigger) {
        if (trigger && Motion.enabled) {
            x.snapTo(-1f)
            x.animateTo(1f, Motion.shine(play))
        }
    }
    drawWithCache {
        val w = size.width
        val band = w * 0.35f
        val brush = Brush.linearGradient(
            listOf(Color.Transparent, Color.White.copy(alpha = strength), Color.Transparent),
            start = Offset(-band, 0f),
            end = Offset(band, size.height),
        )
        onDrawWithContent {
            drawContent()
            val p = x.value
            if (p > -1f && p < 1f) {
                val c = w * 0.5f + p * w * 0.9f
                translate(left = c) { drawRect(brush, topLeft = Offset(-c, 0f), size = size) }
            }
        }
    }
}
