package dev.droidtop.ui.shared

import android.content.Context
import android.util.TypedValue
import android.view.ViewGroup
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import dev.droidtop.library.settings.Notice
import dev.droidtop.library.settings.NoticePlacement
import dev.droidtop.library.theme.GamingThemeColors
import java.util.concurrent.ConcurrentHashMap

/**
 * [NoticeCard] for a View surface: Standard's settings are Preference rows in
 * the forked Launcher3 (no Compose of its own), so a notice row there uses the
 * [R.layout.notice_preference] layout and binds through [bind], which puts one
 * ComposeView in the frame and redraws it on every bind. The palette is
 * derived from the surface's own theme (its background, text and accent), the
 * same derivation the Gaming palette uses, so a light settings screen gets
 * light-ground notices.
 */
object NoticeViews {
    val LAYOUT: Int get() = R.layout.notice_preference

    fun bind(frame: ViewGroup, notice: Notice, placement: NoticePlacement, onAction: (() -> Unit)?, onInfo: (() -> Unit)?) {
        val target = frame.findViewById<ViewGroup>(R.id.notice_frame) ?: frame
        val view = target.getChildAt(0) as? ComposeView ?: ComposeView(target.context).also { target.addView(it) }
        val palette = paletteFor(target.context)
        view.setContent {
            CompositionLocalProvider(LocalNoticePalette provides palette) {
                NoticeCard(notice, placement, onAction = onAction, onInfo = onInfo)
            }
        }
    }

    private data class Key(val ground: Int, val text: Int, val accent: Int)

    private val palettes = ConcurrentHashMap<Key, NoticePalette>()

    /** The notice palette for a View surface's theme, built once per (background, text, accent). */
    fun paletteFor(context: Context): NoticePalette {
        val base = GamingThemeColors.DEFAULT
        val ground = attr(context, android.R.attr.colorBackground) ?: base.ground
        val text = attr(context, android.R.attr.textColorPrimary) ?: base.onSurface
        val accent = attr(context, android.R.attr.colorAccent) ?: base.accent
        val key = Key(ground, text, accent)
        return palettes.getOrPut(key) {
            if (key == Key(base.ground, base.onSurface, base.accent)) {
                NoticePalette.Default
            } else {
                NoticePalette.of(
                    base.copy(ground = ground, overlaySurface = ground, card = ground, onSurface = text, accent = accent),
                )
            }
        }
    }

    /** A colour attribute of a View surface's theme, opaque, or null when the theme has none. */
    internal fun attr(context: Context, id: Int): Int? {
        val value = TypedValue()
        if (!context.theme.resolveAttribute(id, value, true)) return null
        return when {
            value.type >= TypedValue.TYPE_FIRST_COLOR_INT && value.type <= TypedValue.TYPE_LAST_COLOR_INT -> value.data
            value.resourceId != 0 -> runCatching { context.getColorStateList(value.resourceId).defaultColor }.getOrNull()
            else -> null
        }?.let { it or (0xFF shl 24) }
    }
}
