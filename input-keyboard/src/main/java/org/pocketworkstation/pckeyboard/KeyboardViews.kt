package org.pocketworkstation.pckeyboard

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.Button

/** [value] dp in this view's pixels. */
internal fun View.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

/**
 * The keyboard panels' one flat text button (the tool strip's tools, the emoji panel's tabs and its search's Done;
 * Droidtop/tracker#772): [label] in [colors]' text colour on no background, 13 sp, as small as its text plus
 * [paddingDp] at the sides, and never a focus target, so a tap leaves focus on the field being typed into.
 */
internal fun flatButton(
    context: Context,
    colors: KeyboardColors,
    label: String,
    paddingDp: Int = 12,
    onClick: () -> Unit,
): Button = Button(context).apply {
    text = label
    setAllCaps(false)
    textSize = 13f
    setTextColor(colors.text)
    setBackgroundColor(Color.TRANSPARENT)
    minHeight = 0
    minimumHeight = 0
    minWidth = 0
    minimumWidth = 0
    setPadding(dp(paddingDp), dp(6), dp(paddingDp), dp(6))
    isFocusable = false
    setOnClickListener { onClick() }
}
