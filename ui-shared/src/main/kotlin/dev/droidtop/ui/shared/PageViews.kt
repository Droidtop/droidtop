package dev.droidtop.ui.shared

import android.content.Context
import android.view.ViewGroup
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import dev.droidtop.library.settings.ButtonBarItem
import dev.droidtop.library.settings.PageHeader
import dev.droidtop.library.theme.OutsideGamingColors

/**
 * The page pieces for a View surface (docs/SPEC.md 7k, "Page anatomy"): Standard's settings are Preference
 * rows in the forked Launcher3, so a page's header and button bar there use [LAYOUT] and bind through
 * here, one ComposeView per row, redrawn on every bind, exactly as [NoticeViews] hosts a notice. The
 * colours are the surface's own background and text, and the accent of the colours outside Gaming
 * ([OutsideGamingColors], docs/SPEC.md 7k "Colours outside Gaming").
 */
object PageViews {
    val LAYOUT: Int get() = R.layout.page_preference

    private fun composeIn(frame: ViewGroup): ComposeView {
        val target = frame.findViewById<ViewGroup>(R.id.page_frame) ?: frame
        return target.getChildAt(0) as? ComposeView ?: ComposeView(target.context).also { target.addView(it) }
    }

    fun bindHeader(frame: ViewGroup, header: PageHeader) {
        val colors = colorsFor(frame.context)
        composeIn(frame).setContent { PageHeaderView(header, colors = colors) }
    }

    /** [onPress] gets the slot pressed ([ButtonBarItem.slots]); a touch surface has no cursor on the bar. */
    fun bindButtonBar(frame: ViewGroup, bar: ButtonBarItem, onPress: (Int) -> Unit) {
        val colors = colorsFor(frame.context)
        composeIn(frame).setContent { ButtonBarView(bar, selected = null, onPress = onPress, colors = colors) }
    }

    /** The page colours of a View surface; danger is the notice palette's, derived from the same colours. */
    fun colorsFor(context: Context): PageColors {
        val base = OutsideGamingColors.current(context)
        val ground = NoticeViews.attr(context, android.R.attr.colorBackground) ?: base.ground
        val text = NoticeViews.attr(context, android.R.attr.textColorPrimary) ?: base.onSurface
        val muted = NoticeViews.attr(context, android.R.attr.textColorSecondary) ?: base.onSurfaceMuted
        val accent = base.accent
        val notices = NoticeViews.paletteFor(context)
        val onAccent = dev.droidtop.library.theme.FocusRingInks.inner(accent) // black or white, whichever reads on the accent
        return PageColors(
            primary = Color(accent),
            onPrimary = Color(onAccent),
            secondary = Color(ground),
            onSecondary = Color(text),
            danger = notices.dangerFill,
            onDanger = notices.onDangerFill,
            outline = Color(muted).copy(alpha = 0.5f),
            text = Color(text),
            muted = Color(muted),
        )
    }
}
