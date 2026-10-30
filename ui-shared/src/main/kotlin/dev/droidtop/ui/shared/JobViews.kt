package dev.droidtop.ui.shared

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import dev.droidtop.library.settings.JobLook

/**
 * A job's bar for a View surface (Droidtop/tracker#643): Standard's settings are Preference rows, so a job's row there
 * uses [LAYOUT] and binds its bar through here, one ComposeView per row as [PageViews] hosts a page's pieces, the same
 * [JobProgress] Gaming and the companion draw, in the surface's own theme colours. A finished job's row draws its
 * [JobMark] instead.
 */
object JobViews {
    val LAYOUT: Int get() = R.layout.job_preference

    /** Draws [look]'s bar at [fraction] (null for a finished job's mark) into [row]'s frame. */
    fun bind(row: View, fraction: Float?, look: JobLook) {
        val frame = row.findViewById<ViewGroup>(R.id.job_bar) ?: return
        val view = frame.getChildAt(0) as? ComposeView ?: ComposeView(frame.context).also { frame.addView(it) }
        val colors = colorsFor(frame.context)
        view.setContent {
            if (fraction != null) {
                JobProgress(fraction, look, colors, Modifier.fillMaxWidth())
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) { JobMark(look, colors, size = 20.dp) }
            }
        }
    }

    /** A View surface as a job's colours: the accent outside Gaming fills, the surface's secondary text tints the track. */
    fun colorsFor(context: Context): JobProgressColors {
        val accent = Color(dev.droidtop.library.theme.OutsideGamingColors.current(context).accent)
        val muted = NoticeViews.attr(context, android.R.attr.textColorSecondary)?.let(::Color) ?: Color.Gray
        val notices = NoticeViews.paletteFor(context)
        return JobProgressColors(track = muted.copy(alpha = 0.25f), fill = accent, done = accent, failed = notices.dangerFill)
    }
}
