package app.murinelauncher.settings.common

import android.view.View
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.android.launcher3.R

/**
 * The pad and keyboard focus ring every settings row of Standard's wears (`catalog_row_focus_ring`, the accent and
 * width of the shell's selection frame): the catalog pages had it and the stock-preference pages (Home, Icons, App
 * drawer, Search bar, Other) drew Android's own highlight, so Settings looked like two apps (Droidtop/tracker#705).
 * Drawn as the row's foreground, over its own switch and ripple.
 */
object RowFocusRing {
    /** Gives [view], a row as it attaches, the ring, and focus in touch mode so a requestFocus there is not dropped. */
    @JvmStatic
    fun wear(view: View) {
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        view.foreground = ContextCompat.getDrawable(view.context, R.drawable.catalog_row_focus_ring)
    }

    /**
     * Every row [list] attaches from now on that can be pressed wears the ring (a section header or a card that only
     * says something does not take focus); once per list, however often it is called.
     */
    @JvmStatic
    fun attach(list: RecyclerView) {
        if (list.getTag(R.drawable.catalog_row_focus_ring) == true) return
        list.setTag(R.drawable.catalog_row_focus_ring, true)
        list.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                if (view.isClickable) wear(view)
            }
            override fun onChildViewDetachedFromWindow(view: View) {}
        })
    }
}
