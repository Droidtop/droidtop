package app.murinelauncher.widget.radio

import android.app.AlertDialog
import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * droidtop's own replacement for [RadioGroupBottomSheet]'s single-select
 * picker: real, individually focusable rows in a plain [LinearLayout],
 * the same approach commit e978478e used for the mode switcher after a
 * stock `AlertDialog.setItems` list proved unreliable for D-pad focus.
 * `RadioGroupBottomSheet`'s `RadioPreferenceFragment` had a narrower
 * problem -- confirmed across four separate, individually-correct layout
 * fixes (docs/SPEC.md, "RadioGroupPreference sheet" section) that the
 * options list still measured to zero height on a 1080p landscape
 * handheld profile, the Retroid Pocket 5's own shape -- so rather than
 * keep debugging `PreferenceFragmentCompat`/`SelectorWithWidgetPreference`
 * internals blind, [RadioGroupPreference] now shows this dialog instead.
 *
 * `RadioGroupBottomSheet` itself stays: `FilterableIconPackSheet` and
 * `IconPickerBottomSheet` (the per-app icon-override picker,
 * `AppInfoPreferenceFragment`) still build on it directly for a genuinely
 * different job (a filterable icon grid with a live "show all" toggle),
 * so it is not dead code and is not deleted here.
 */
object RadioListDialog {

    fun show(
        context: Context,
        title: CharSequence?,
        entryCount: Int,
        iconPosition: RadioGroupBottomSheet.IconPosition,
        currentIndex: Int,
        textProvider: (Int) -> CharSequence,
        iconProvider: ((Int) -> Drawable?)?,
        iconTint: Int?,
        isVisibleProvider: ((Int) -> Boolean)?,
        isEnabledProvider: ((Int) -> Boolean)?,
        onSelected: (Int) -> Unit,
    ) {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(12))
        }
        if (!title.isNullOrEmpty()) {
            root.addView(
                TextView(context).apply {
                    text = title
                    textSize = 20f
                    setTextColor(TITLE_COLOR)
                    setPadding(dp(8), 0, dp(8), dp(16))
                },
            )
        }

        var dialog: AlertDialog? = null
        val rows = mutableListOf<View>()
        val rowList = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        for (index in 0 until entryCount) {
            if (isVisibleProvider?.invoke(index) == false) continue
            val enabled = isEnabledProvider?.invoke(index) != false
            val icon = iconProvider?.invoke(index)?.also { drawable ->
                if (iconTint != null) drawable.colorFilter = PorterDuffColorFilter(iconTint, PorterDuff.Mode.SRC_IN)
            }

            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = enabled
                isClickable = enabled
                isSelected = index == currentIndex
                alpha = if (enabled) 1.0f else 0.5f
                background = context.getDrawable(com.android.launcher3.R.drawable.droidtop_list_selector)
                setPadding(dp(16), dp(14), dp(16), dp(14))
            }

            fun addIcon() {
                if (icon == null) return
                row.addView(
                    ImageView(context).apply {
                        setImageDrawable(icon)
                        layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply {
                            marginEnd = dp(16)
                            marginStart = 0
                        }
                    },
                )
            }
            if (iconPosition == RadioGroupBottomSheet.IconPosition.START) addIcon()

            row.addView(
                TextView(context).apply {
                    text = textProvider(index)
                    textSize = 17f
                    setTextColor(ROW_COLOR)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                },
            )
            if (iconPosition == RadioGroupBottomSheet.IconPosition.END) {
                row.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                addIcon()
            }

            if (enabled) {
                row.setOnClickListener {
                    onSelected(index)
                    dialog?.dismiss()
                }
            }
            rows += row
            rowList.addView(row)
        }
        root.addView(
            ScrollView(context).apply {
                addView(rowList)
                // A bottom sheet used to expand to fit the whole list; a
                // long list (icon packs) inside a plain dialog instead
                // scrolls within a capped height rather than growing past
                // the screen.
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (context.resources.displayMetrics.heightPixels * 0.5f).toInt(),
                )
            },
        )

        root.addView(
            View(context).apply {
                setBackgroundColor(DIVIDER_COLOR)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                    topMargin = dp(12)
                    bottomMargin = dp(10)
                }
            },
        )
        root.addView(
            TextView(context).apply {
                text = "Up/Down Navigate  ·  A Select  ·  B Cancel"
                textSize = 13f
                setTextColor(HINT_COLOR)
                gravity = Gravity.START
                setPadding(dp(8), 0, dp(8), 0)
            },
        )

        dialog = AlertDialog.Builder(context, com.android.launcher3.R.style.DroidtopDialog)
            .setView(root)
            .show()
        // Same fix as the mode switcher (e978478e): give real pad focus to
        // the current row explicitly on open rather than waiting for the
        // first Down press to "acquire" it.
        (rows.getOrNull(currentIndex) ?: rows.firstOrNull())?.requestFocus()
    }

    private const val TITLE_COLOR = 0xFFEDEDED.toInt()
    private const val ROW_COLOR = 0xFFEDEDED.toInt()
    private const val HINT_COLOR = 0xFFA0A0A0.toInt()
    private const val DIVIDER_COLOR = 0x33FFFFFF
}
