package dev.droidtop.app

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.os.Bundle
import android.view.View
import dev.droidtop.library.settings.CatalogPrefs

/**
 * droidtop's own two accessibility controls (docs/SPEC.md, Accessibility):
 * a colour-vision filter and a text-size scale, applied to every droidtop
 * activity (Compose and View surfaces alike, all three modes) from one
 * place, the Application's lifecycle callbacks, so no screen opts in.
 *
 * The text scale multiplies the SYSTEM font scale on each activity's own
 * Resources before its content is inflated or composed, which is what both
 * `sp` in Views and Compose's Density read. The colour filter is a hardware
 * layer with a colour-matrix paint on the activity's decor view, which
 * recolours everything the window draws. Dialogs and popups own separate
 * windows and are not recoloured; a game running in another app is not
 * droidtop's to filter.
 */
object AccessibilityPrefs {
    const val KEY_COLOR_VISION = "pref_global_color_vision"
    const val KEY_TEXT_SCALE = "pref_global_text_scale"

    const val VISION_NONE = "none"
    const val VISION_PROTAN = "protanopia"
    const val VISION_DEUTAN = "deuteranopia"
    const val VISION_TRITAN = "tritanopia"
    const val VISION_GREY = "greyscale"

    const val TEXT_SCALE_DEFAULT = "1.0"

    // Machado et al. 2009, severity 1.0 (sRGB): what a person with that
    // dichromacy sees, row-major 3x3.
    private val PROTAN = floatArrayOf(
        0.152286f, 1.052583f, -0.204868f,
        0.114503f, 0.786281f, 0.099216f,
        -0.003882f, -0.048116f, 1.051998f,
    )
    private val DEUTAN = floatArrayOf(
        0.367322f, 0.860646f, -0.227968f,
        0.280085f, 0.672501f, 0.047413f,
        -0.011820f, 0.042940f, 0.968881f,
    )
    private val TRITAN = floatArrayOf(
        1.255528f, -0.076749f, -0.178779f,
        -0.078411f, 0.930809f, 0.147602f,
        0.004733f, 0.691367f, 0.303900f,
    )

    // Where the colour the person cannot tell apart is moved to (the error
    // redistribution Android's own daltonizer uses): red/green loss shifts
    // onto green and blue, blue loss onto red and green.
    private val SHIFT_RED_GREEN = floatArrayOf(0f, 0f, 0f, 0.7f, 1f, 0f, 0.7f, 0f, 1f)
    private val SHIFT_BLUE = floatArrayOf(1f, 0f, 0.7f, 0f, 1f, 0.7f, 0f, 0f, 0f)

    /**
     * The 4x5 colour matrix (Android layout) for a filter id, or null for
     * none. The three colour-vision modes are corrections, not simulations:
     * out = in + shift * (in - simulated(in)), so what the person loses is
     * moved onto channels they can tell apart. Greyscale is luma only.
     */
    fun colorMatrix(mode: String?): FloatArray? = when (mode) {
        VISION_PROTAN -> correction(PROTAN, SHIFT_RED_GREEN)
        VISION_DEUTAN -> correction(DEUTAN, SHIFT_RED_GREEN)
        VISION_TRITAN -> correction(TRITAN, SHIFT_BLUE)
        VISION_GREY -> floatArrayOf(
            0.2126f, 0.7152f, 0.0722f, 0f, 0f,
            0.2126f, 0.7152f, 0.0722f, 0f, 0f,
            0.2126f, 0.7152f, 0.0722f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
        else -> null
    }

    private fun correction(sim: FloatArray, shift: FloatArray): FloatArray {
        // M = I + shift * (I - sim)
        val err = FloatArray(9) { (if (it % 4 == 0) 1f else 0f) - sim[it] }
        val m = FloatArray(9)
        for (r in 0 until 3) for (c in 0 until 3) {
            var s = 0f
            for (k in 0 until 3) s += shift[r * 3 + k] * err[k * 3 + c]
            m[r * 3 + c] = (if (r == c) 1f else 0f) + s
        }
        return floatArrayOf(
            m[0], m[1], m[2], 0f, 0f,
            m[3], m[4], m[5], 0f, 0f,
            m[6], m[7], m[8], 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    fun colorVision(context: Context): String =
        CatalogPrefs.prefs(context).getString(KEY_COLOR_VISION, VISION_NONE) ?: VISION_NONE

    fun textScale(context: Context): Float =
        (CatalogPrefs.prefs(context).getString(KEY_TEXT_SCALE, TEXT_SCALE_DEFAULT)?.toFloatOrNull() ?: 1f)
            .coerceIn(1f, 2f)

    private var resumed: Activity? = null
    private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    /** Called once from the Application; holds the pref listener for the process. */
    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = applyTextScale(activity)
            override fun onActivityResumed(activity: Activity) {
                resumed = activity
                applyTextScale(activity)
                applyColorFilter(activity)
            }
            override fun onActivityPaused(activity: Activity) { if (resumed === activity) resumed = null }
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            val a = resumed ?: return@OnSharedPreferenceChangeListener
            when (key) {
                KEY_COLOR_VISION -> applyColorFilter(a)
                // Already-composed text keeps its old density: rebuild the screen.
                KEY_TEXT_SCALE -> a.recreate()
            }
        }
        listener = l
        CatalogPrefs.prefs(app).registerOnSharedPreferenceChangeListener(l)
    }

    @Suppress("DEPRECATION")
    private fun applyTextScale(activity: Activity) {
        val res = activity.resources
        val wanted = Resources.getSystem().configuration.fontScale * textScale(activity)
        if (res.configuration.fontScale == wanted) return
        val config = Configuration(res.configuration).apply { fontScale = wanted }
        res.updateConfiguration(config, res.displayMetrics)
    }

    private fun applyColorFilter(activity: Activity) {
        val decor: View = activity.window?.decorView ?: return
        val matrix = colorMatrix(colorVision(activity))
        if (matrix == null) {
            decor.setLayerType(View.LAYER_TYPE_NONE, null)
        } else {
            val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix(matrix)) }
            decor.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
        }
    }
}
