package app.murinelauncher.settings.prefs

import androidx.annotation.StringRes
import com.android.launcher3.R

/**
 * The colour of icon labels on the home screen (Nova's label colour, Droidtop/tracker#564): the theme's own, which
 * follows the wallpaper, or one fixed colour for a wallpaper the theme reads wrongly. The drawer keeps its theme.
 */
enum class LabelColor(@StringRes val title: Int, val argb: Int?) {
    AUTO(R.string.label_color_auto, null),
    WHITE(R.string.label_color_white, 0xFFFFFFFF.toInt()),
    BLACK(R.string.label_color_black, 0xFF000000.toInt()),
}
