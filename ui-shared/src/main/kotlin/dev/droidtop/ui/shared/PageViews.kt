package dev.droidtop.ui.shared

import android.content.Context
import android.view.ViewGroup
import androidx.compose.ui.unit.dp
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

    /**
     * A View [button] drawn as the shell's secondary page button ([PageButtonView], ButtonRole.SECONDARY): sentence
     * case, never Android's ALL-CAPS, [fill] with a hairline [outline], [ink] at 16sp medium, at least
     * [PageMetrics.ButtonHeight] tall, [PageMetrics.ButtonCorner] corners, and none of Material's raised elevation.
     * For a View surface whose labels and visibility change at run time (the companion's Input tab,
     * Droidtop/tracker#524), where a Compose host per button would be the heavier change.
     */
    fun styleSecondary(button: android.widget.Button, fill: Int, ink: Int, outline: Int) {
        val density = button.resources.displayMetrics.density
        fun px(dp: androidx.compose.ui.unit.Dp) = (dp.value * density).toInt()
        button.isAllCaps = false
        button.setTextColor(ink)
        button.textSize = 16f
        button.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        button.minHeight = px(PageMetrics.ButtonHeight)
        button.minimumHeight = px(PageMetrics.ButtonHeight)
        button.minWidth = 0
        button.minimumWidth = 0
        button.setPadding(px(16.dp), px(8.dp), px(16.dp), px(8.dp))
        button.stateListAnimator = null
        button.elevation = 0f
        button.background = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(ink and 0x00FFFFFF or 0x33000000),
            android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = px(PageMetrics.ButtonCorner).toFloat()
                setColor(fill)
                setStroke(maxOf(1, px(1.dp)), outline)
            },
            null,
        )
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
