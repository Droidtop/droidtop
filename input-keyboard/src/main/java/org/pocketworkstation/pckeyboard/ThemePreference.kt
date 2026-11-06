package org.pocketworkstation.pckeyboard

import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.AttributeSet
import android.util.TypedValue
import android.util.Xml
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckedTextView
import android.widget.LinearLayout
import org.xmlpull.v1.XmlPullParser

/**
 * The keyboard's theme setting with a preview of each theme (Droidtop/tracker#903 H12b; docs/SPEC.md 6a, "Themes,
 * light and dark"): the owner asked for "polish, new detailing, modern styling, extra modes, dynamic stuffs", and the
 * approved text for it, "Theme presets with a preview in HKR's settings". Each row shows a few keys drawn the way the
 * keyboard draws them, not a picture: a modern theme's plates by [KeyPlates.draw] in that theme's colours now
 * ([KeyboardAppearance.themeColors], so Dynamic shows this wallpaper's palette and every modern theme the light or dark
 * the keyboard would use), and a classic theme's own key drawable, background and label colours, read from its layout.
 *
 * The themes and their order are [ThemePresets.ids]. Picking a row stores it and closes the list, as a list setting does.
 */
class ThemePreference(context: Context, attrs: AttributeSet?) : AutoSummaryListPreference(context, attrs) {

    private val ids: IntArray = ThemePresets.ids(
        Build.VERSION.SDK_INT,
        KeyboardAppearance.host != null,
        KeyboardAppearance.storedTheme(context),
    )

    init {
        entries = ids.map { context.getString(labelOf(it)) }.toTypedArray()
        entryValues = ids.map { it.toString() }.toTypedArray()
    }

    override fun onPrepareDialogBuilder(builder: AlertDialog.Builder) {
        val chosen = ids.indexOf(value?.toIntOrNull() ?: KeyboardAppearance.FOLLOW)
        builder.setSingleChoiceItems(PresetAdapter(context), chosen) { dialog, which ->
            val picked = ids[which].toString()
            if (callChangeListener(picked)) value = picked
            dialog.dismiss()
        }
        builder.setPositiveButton(null, null)
    }

    /** One row per theme: its preview beside its name, built as the list scrolls. Reads resources only. */
    private inner class PresetAdapter(private val context: Context) : BaseAdapter() {
        private val looks = arrayOfNulls<PreviewLook>(ids.size)

        override fun getCount(): Int = ids.size
        override fun getItem(position: Int): Any = ids[position]
        override fun getItemId(position: Int): Long = ids[position].toLong()
        override fun hasStableIds(): Boolean = true

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView as? PresetRow ?: PresetRow(context)
            val look = looks[position] ?: PreviewLook.of(context, ids[position]).also { looks[position] = it }
            row.bind(look, entries[position])
            return row
        }
    }

    /** A row of the list: the preview, then the theme's name with the list's own check mark. */
    private class PresetRow(context: Context) : LinearLayout(context) {
        private val preview = ThemePreviewView(context)
        private val label: CheckedTextView = View.inflate(context, android.R.layout.simple_list_item_single_choice, null) as CheckedTextView

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val dp = context.resources.displayMetrics.density
            setPadding((16 * dp).toInt(), (6 * dp).toInt(), 0, (6 * dp).toInt())
            addView(preview, LayoutParams((PREVIEW_WIDTH_DP * dp).toInt(), (PREVIEW_HEIGHT_DP * dp).toInt()))
            addView(label, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }

        fun bind(look: PreviewLook, name: CharSequence) {
            preview.look = look
            label.text = name
        }

        // The list view checks the row; the check mark is the label's.
        override fun setActivated(activated: Boolean) {
            super.setActivated(activated)
            label.isChecked = activated
        }
    }

    companion object {
        const val PREVIEW_WIDTH_DP = 148f
        const val PREVIEW_HEIGHT_DP = 40f

        /** The name of the theme [id]. Classic names are Hacker's Keyboard's own, each beside the layout it draws. */
        @JvmStatic
        fun labelOf(id: Int): Int = when (id) {
            KeyboardAppearance.FOLLOW -> R.string.layout_modern
            KeyboardAppearance.DYNAMIC -> R.string.layout_dynamic
            KeyboardAppearance.HIGH_CONTRAST -> R.string.layout_modern_high_contrast
            KeyboardAppearance.MATCH_HOST -> R.string.layout_match_host
            0 -> R.string.layout_ics
            1 -> R.string.layout_gingerbread
            2 -> R.string.layout_stone_bold
            3 -> R.string.layout_trans_neon
            4 -> R.string.layout_material_dark
            5 -> R.string.layout_material_light
            6 -> R.string.layout_ics_darker
            7 -> R.string.layout_material_black
            else -> R.string.layout_modern
        }
    }
}

/**
 * How a theme's preview draws: [plates] for a modern theme, else a classic theme's own [keyBackground] over its
 * [background], with its label colours. Built once per row from resources.
 */
internal class PreviewLook(
    val plates: KeyboardColors?,
    val background: Drawable?,
    val keyBackground: Drawable?,
    val text: Int,
    val hint: Int,
    val shadowColor: Int,
    val shadowRadius: Float,
    val bold: Boolean,
) {
    companion object {
        /** The look of theme [id] as the keyboard would draw it now. Reads resources and settings. */
        fun of(context: Context, id: Int): PreviewLook {
            if (KeyboardAppearance.modern(id)) {
                val light = KeyboardAppearance.lightOf(context, id, embedded = false)
                val c = KeyboardAppearance.themeColors(context, id, light)
                return PreviewLook(c, null, null, c.text, c.muted, 0, 0f, false)
            }
            return classic(context, KeyboardSwitcher.themeLayout(id))
        }

        /** A classic theme's key drawing and colours, read from its layout's attributes as the keyboard view reads them. */
        private fun classic(context: Context, layout: Int): PreviewLook {
            val parser = context.resources.getLayout(layout)
            try {
                var type = parser.next()
                while (type != XmlPullParser.START_TAG && type != XmlPullParser.END_DOCUMENT) type = parser.next()
                val attrs = Xml.asAttributeSet(parser)
                val a = context.obtainStyledAttributes(attrs, R.styleable.LatinKeyboardBaseView, 0, R.style.LatinKeyboardBaseView)
                val look = try {
                    val ground = context.obtainStyledAttributes(attrs, intArrayOf(android.R.attr.background), 0, R.style.LatinKeyboardBaseView)
                    val background = try { ground.getDrawable(0) } finally { ground.recycle() }
                    PreviewLook(
                        plates = null,
                        background = background,
                        keyBackground = a.getDrawable(R.styleable.LatinKeyboardBaseView_keyBackground),
                        text = a.getColor(R.styleable.LatinKeyboardBaseView_keyTextColor, 0xFF000000.toInt()),
                        hint = a.getColor(R.styleable.LatinKeyboardBaseView_keyHintColor, 0xFFBBBBBB.toInt()),
                        shadowColor = a.getColor(R.styleable.LatinKeyboardBaseView_shadowColor, 0),
                        shadowRadius = a.getFloat(R.styleable.LatinKeyboardBaseView_shadowRadius, 0f),
                        bold = a.getInt(R.styleable.LatinKeyboardBaseView_keyTextStyle, 0) == 1,
                    )
                } finally {
                    a.recycle()
                }
                return look
            } finally {
                parser.close()
            }
        }
    }
}

/**
 * A few keys of a theme, drawn: Esc (a function key), "a" with its alt-key hint, "s" pressed, and Shift latched. With
 * plates, exactly as [LatinKeyboardBaseView] draws them ([KeyPlates.plateColor], [KeyPlates.draw]).
 */
internal class ThemePreviewView(context: Context) : View(context) {
    var look: PreviewLook? = null
        set(value) {
            field = value
            invalidate()
        }

    private val plate = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT }
    private val rect = RectF()
    private val density = context.resources.displayMetrics.density
    private val labelSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 15f, context.resources.displayMetrics)

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        val look = look ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        val radius = KeyPlates.RADIUS_DP * density
        if (look.plates != null) {
            plate.color = look.plates.ground
            rect.set(0f, 0f, w, h)
            canvas.drawRoundRect(rect, radius, radius, plate)
        } else {
            look.background?.let {
                it.setBounds(0, 0, width, height)
                it.draw(canvas)
            }
        }
        label.textSize = labelSize
        label.typeface = if (look.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        hint.textSize = labelSize * 0.6f
        val keyWidth = w / KEYS.size
        for ((i, key) in KEYS.withIndex()) {
            rect.set(i * keyWidth, 0f, (i + 1) * keyWidth, h)
            drawKey(canvas, look, key)
        }
    }

    private fun drawKey(canvas: Canvas, look: PreviewLook, key: PreviewKey) {
        val plates = look.plates
        if (plates != null) {
            KeyPlates.draw(canvas, rect, KeyPlates.plateColor(plates, key.functional, key.pressed, key.latched), density, plate)
            label.clearShadowLayer()
        } else {
            look.keyBackground?.let {
                // The states Keyboard.Key.getCurrentDrawableState gives a key at rest, pressed, and latched on.
                it.state = when {
                    key.pressed -> STATE_PRESSED
                    key.latched -> STATE_ON
                    else -> STATE_NORMAL
                }
                it.setBounds(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt())
                it.draw(canvas)
            }
            if (look.shadowRadius > 0f) label.setShadowLayer(look.shadowRadius, 0f, 0f, look.shadowColor) else label.clearShadowLayer()
        }
        label.color = look.text
        val baseline = rect.centerY() - (label.descent() + label.ascent()) / 2
        canvas.drawText(key.label, rect.centerX(), baseline, label)
        if (key.hint != null) {
            hint.color = look.hint
            val pad = (KeyPlates.INSET_DP + 3f) * density
            canvas.drawText(key.hint, rect.right - pad, rect.top + pad - hint.ascent(), hint)
        }
    }

    private class PreviewKey(val label: String, val hint: String?, val functional: Boolean, val pressed: Boolean, val latched: Boolean)

    private companion object {
        val STATE_NORMAL = intArrayOf()
        val STATE_PRESSED = intArrayOf(android.R.attr.state_pressed)
        val STATE_ON = intArrayOf(android.R.attr.state_checkable, android.R.attr.state_checked)

        val KEYS = listOf(
            PreviewKey("Esc", null, functional = true, pressed = false, latched = false),
            PreviewKey("a", "@", functional = false, pressed = false, latched = false),
            PreviewKey("s", "#", functional = false, pressed = true, latched = false),
            PreviewKey("⇧", null, functional = true, pressed = false, latched = true),
        )
    }
}
