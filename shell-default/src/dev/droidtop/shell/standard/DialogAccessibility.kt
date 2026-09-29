package dev.droidtop.shell.standard

import android.view.View
import android.widget.Button
import android.widget.RadioButton
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat

/**
 * The accessibility half of droidtop's plain-View dialogs
 * ([BackButtonMenu], `RadioListDialog`; docs/SPEC.md, "Accessibility of the
 * custom dialogs"). Both replaced a stock AlertDialog list with individually
 * focusable rows so a D-pad reaches every one, and a stock list gave a
 * screen reader its roles for free: a row that is only a focusable
 * TextView is read as bare text, with no "button" or "radio button, selected"
 * to tell the person what it does. These calls put those back on the same
 * views, one mechanism for both dialogs.
 */
object DialogAccessibility {

    /** The dialog's own name, spoken when it opens (a custom view has no window title). */
    fun paneTitle(root: View, title: CharSequence) {
        ViewCompat.setAccessibilityPaneTitle(root, title)
    }

    fun heading(view: View) {
        ViewCompat.setAccessibilityHeading(view, true)
    }

    /** A row that does something when chosen. */
    fun button(row: View) = role(row, Button::class.java.name, null)

    /** A row that is one of a single-select set; [checked] is the current choice. */
    fun radio(row: View, checked: Boolean) = role(row, RadioButton::class.java.name, checked)

    /** Purely visual or controller-only content: an icon beside a label already read, a button-hint row. */
    fun hide(view: View) {
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun role(row: View, className: String, checked: Boolean?) {
        ViewCompat.setAccessibilityDelegate(
            row,
            object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = className
                    if (checked != null) {
                        info.isCheckable = true
                        info.isChecked = checked
                    }
                }
            },
        )
    }
}
