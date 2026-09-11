package dev.droidtop.shell.gamepad.theme

import android.graphics.RenderEffect
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidColorFilter

/**
 * The shared ES-DE colour pipeline as an `android.graphics.RenderEffect`,
 * for the one surface that is a View and not a Compose draw: the video
 * player. Returns null when the theme asks for nothing, so the effect is
 * cleared rather than set to an identity matrix.
 *
 * The matrix itself comes from `esDeImageColorFilter` -- the one port of
 * core.glsl's own brightness-then-saturation-then-shift order this package
 * has -- rather than a second copy of that maths.
 *
 * Why this lives in its own file rather than beside its caller in
 * EsDeThemeRenderer.kt: `RenderEffect` is API 31, and a top-level function
 * returning it puts that type in the RETURN TYPE of a method on the file
 * class `EsDeThemeRendererKt`. A type in a class's own shape is resolved
 * when the class is LOADED, so the whole renderer -- every top-level
 * function in that file -- would fail to load below API 31, no matter how
 * carefully the call itself is version-checked. Held in an object of its
 * own, the type is confined to a class that is only ever loaded from
 * inside the caller's `SDK_INT >= S` branch. Same shape as
 * com.android.launcher3.util.PredictiveBackAdapter, and the reason
 * build-scripts/check_class_load_api.py exists.
 */
@RequiresApi(Build.VERSION_CODES.S)
internal object EsDeVideoRenderEffect {

    fun of(tint: Color?, saturation: Float, brightness: Float): RenderEffect? {
        if (tint == null && saturation == 1f && brightness == 0f) return null
        val filter = esDeImageColorFilter(tint, saturation, brightness, dimming = 1f) ?: return null
        return RenderEffect.createColorFilterEffect(filter.asAndroidColorFilter())
    }
}
