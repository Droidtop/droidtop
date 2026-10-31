package dev.droidtop.ui.shared

import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import dev.droidtop.shell.gamepad.ActionNote
import dev.droidtop.shell.gamepad.ActionRow
import dev.droidtop.shell.gamepad.ActionSheetItem
import dev.droidtop.shell.gamepad.AnchorRect
import dev.droidtop.shell.gamepad.AnchoredMenu
import dev.droidtop.shell.gamepad.GamingTheme

/**
 * The item menu for a View surface (docs/DESIGN-LANGUAGE.md, "The anchored menu"; Droidtop/tracker#827): Mobile is
 * the forked Launcher3, so droidtop's own menus there (a folder's options, a drawer tab's, an app's Drawer tabs) open
 * the one [AnchoredMenu] through here, beside the view they belong to, in the colours outside Gaming
 * ([GamingTheme] with `outsideGaming`, docs/SPEC.md 7k "Colours outside Gaming"). Launcher3's own popups are not
 * these and stay as they are.
 *
 * The menu is composed in a ComposeView added to the anchor's window for as long as it is open, and removed when it
 * closes; the window must carry a lifecycle owner, as every activity's does.
 */
object MenuViews {
    /**
     * One row: its [title], [checked] for an option it turns on or off (drawn as a switch; null for an action), and
     * [onSelect], run after the menu closes; null [onSelect] is a row that only states (a locked home screen).
     */
    class Row @JvmOverloads constructor(
        val title: String,
        val onSelect: Runnable?,
        val checked: Boolean? = null,
    )

    /** Opens [rows] under [title] beside [anchor], over a [note] when there is one (why the rows cannot act). */
    @JvmStatic
    @JvmOverloads
    fun show(anchor: View, title: String, rows: List<Row>, note: String? = null) {
        val root = anchor.rootView as? ViewGroup ?: return
        val at = IntArray(2).also { anchor.getLocationOnScreen(it) }
        val rect = AnchorRect(at[0], at[1], at[0] + anchor.width, at[1] + anchor.height)
        val host = ComposeView(root.context)
        root.addView(host, ViewGroup.LayoutParams(0, 0))
        host.setContent {
            var open by remember { mutableStateOf(true) }
            val close = {
                if (open) {
                    open = false
                    host.post { root.removeView(host) }
                }
            }
            if (open) {
                GamingTheme(outsideGaming = true) {
                    val items = buildList<ActionSheetItem> {
                        note?.let { add(ActionNote(it)) }
                        rows.forEach { row ->
                            val run = row.onSelect
                            add(
                                ActionRow(
                                    title = row.title,
                                    switchOn = row.checked,
                                    onSelect = run?.let { { close(); it.run() } },
                                ),
                            )
                        }
                    }
                    AnchoredMenu(anchor = rect, title = title, rows = items, onDismiss = close)
                }
            }
        }
    }
}
