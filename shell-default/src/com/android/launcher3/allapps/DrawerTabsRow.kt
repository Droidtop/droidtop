package com.android.launcher3.allapps

import android.app.AlertDialog
import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupMenu
import app.murinelauncher.settings.prefs.DrawerTabs
import com.android.launcher3.R

/**
 * droidtop (Droidtop/tracker#564): the app drawer's tabs, a row of chips at the top of the drawer that stays
 * with the header as the list scrolls (a [FloatingHeaderRow], as Launcher3's own rows). "All" first, then
 * the person's tabs ([DrawerTabs]). A tap shows that tab; a long press renames, moves or deletes it. The row
 * shows only once a tab exists: a tab is made from an app's long-press menu ("Drawer tabs"). A tab can also
 * be shown as a drawer folder in All ("Show as a folder in All").
 */
class DrawerTabsRow @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    HorizontalScrollView(context, attrs), FloatingHeaderRow {

    private val chips = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val chipHeight = resources.getDimensionPixelSize(R.dimen.all_apps_header_pill_height)
    private val gap = dp(8)
    private var header: FloatingHeaderView? = null

    init {
        isHorizontalScrollBarEnabled = false
        addView(chips, LayoutParams(LayoutParams.WRAP_CONTENT, chipHeight))
        rebuild()
    }

    override fun setup(parent: FloatingHeaderView, allRows: Array<FloatingHeaderRow>, tabsHidden: Boolean) {
        header = parent
        rebuild()
    }

    override fun getExpectedHeight(): Int = if (hasVisibleContent()) chipHeight + gap else 0

    override fun shouldDraw(): Boolean = hasVisibleContent()

    override fun hasVisibleContent(): Boolean = DrawerTabs.tabs(context).isNotEmpty()

    override fun setVerticalScroll(scroll: Int, isScrolledOut: Boolean) {
        visibility = if (isScrolledOut || !hasVisibleContent()) INVISIBLE else VISIBLE
        if (!isScrolledOut) translationY = scroll.toFloat()
    }

    override fun getTypeClass(): Class<DrawerTabsRow> = DrawerTabsRow::class.java

    // getFocusedChild() is ViewGroup's own, which FloatingHeaderRow's matches.

    /** The tabs or the choice changed: the chips, the header's height and the drawer's list follow. */
    fun onTabsChanged() {
        rebuild()
        header?.onHeightUpdated()
        (header?.parent as? ActivityAllAppsContainerView<*>)?.onDrawerTabChanged()
    }

    private fun rebuild() {
        chips.removeAllViews()
        val tabs = DrawerTabs.tabs(context)
        visibility = if (tabs.isEmpty()) GONE else VISIBLE
        if (tabs.isEmpty()) return
        chips.addView(chip(context.getString(R.string.drawer_tab_all), DrawerTabs.selected == null, null))
        tabs.forEach { tab -> chips.addView(chip(tab.name, DrawerTabs.selected == tab.name, tab.name)) }
    }

    private fun chip(label: String, chosen: Boolean, tab: String?): Button = Button(context, null, android.R.attr.borderlessButtonStyle).apply {
        text = label
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(context.getColorStateList(R.color.murine_all_apps_tab_text))
        setBackgroundResource(R.drawable.all_apps_tabs_background)
        isSelected = chosen
        setPadding(dp(16), 0, dp(16), 0)
        minWidth = 0
        minimumWidth = 0
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, chipHeight).apply { marginEnd = gap }
        setOnClickListener {
            DrawerTabs.selected = tab
            onTabsChanged()
        }
        if (tab != null) setOnLongClickListener { edit(this, tab); true }
    }

    private fun edit(anchor: View, tab: String) {
        val menu = PopupMenu(context, anchor)
        menu.menu.add(0, MENU_RENAME, 0, R.string.drawer_tab_rename)
        menu.menu.add(0, MENU_MOVE_LEFT, 1, R.string.drawer_tab_move_left)
        menu.menu.add(0, MENU_MOVE_RIGHT, 2, R.string.drawer_tab_move_right)
        menu.menu.add(0, MENU_AS_FOLDER, 3, R.string.drawer_tab_as_folder).apply {
            isCheckable = true
            isChecked = DrawerTabs.tabs(context).firstOrNull { it.name == tab }?.folder == true
        }
        menu.menu.add(0, MENU_DELETE, 4, R.string.drawer_tab_delete)
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_RENAME -> askName(context, tab) { name ->
                    DrawerTabs.rename(context, tab, name).also { if (it) onTabsChanged() }
                }
                MENU_MOVE_LEFT -> { DrawerTabs.move(context, tab, -1); onTabsChanged() }
                MENU_MOVE_RIGHT -> { DrawerTabs.move(context, tab, +1); onTabsChanged() }
                MENU_DELETE -> { DrawerTabs.delete(context, tab); onTabsChanged() }
                MENU_AS_FOLDER -> { DrawerTabs.setFolder(context, tab, !item.isChecked); onTabsChanged() }
            }
            true
        }
        menu.show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        // A tab's long-press menu, by what each row does.
        private const val MENU_RENAME = 0
        private const val MENU_MOVE_LEFT = 1
        private const val MENU_MOVE_RIGHT = 2
        private const val MENU_DELETE = 3
        private const val MENU_AS_FOLDER = 4

        /**
         * Asks for a tab's name, filled with [current]; [accept] says whether it was taken. A blank or
         * taken name keeps the dialog open and says why on the field.
         */
        @JvmStatic
        fun askName(context: Context, current: String?, accept: (String) -> Boolean) {
            val field = EditText(context).apply {
                setText(current.orEmpty())
                setSingleLine()
                selectAll()
            }
            val dialog = AlertDialog.Builder(context, R.style.DroidtopDialog)
                .setTitle(if (current == null) R.string.drawer_tab_new else R.string.drawer_tab_rename)
                .setView(field)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    if (accept(field.text.toString())) {
                        dialog.dismiss()
                    } else {
                        field.error = context.getString(R.string.drawer_tab_name_taken)
                    }
                }
            }
            dialog.show()
        }
    }
}
