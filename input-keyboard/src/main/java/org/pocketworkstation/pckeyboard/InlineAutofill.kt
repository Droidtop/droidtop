package org.pocketworkstation.pckeyboard

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Size
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.inline.InlineContentView
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import android.widget.inline.InlinePresentationSpec
import androidx.annotation.RequiresApi
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.common.ImageViewStyle
import androidx.autofill.inline.common.TextViewStyle
import androidx.autofill.inline.common.ViewStyle
import androidx.autofill.inline.v1.InlineSuggestionUi

/**
 * Autofill suggestions in the keyboard (docs/SPEC.md 6a, "Inline autofill", Droidtop/tracker#342, Android 11+): a
 * password manager or the platform's autofill service offers its suggestions to the input method as small views,
 * which are drawn in a row above the keys instead of in a dropdown over the app. Only used on Android 11 and later;
 * the input method asks for them in `onCreateInlineSuggestionsRequest` and shows them with [show].
 */
@RequiresApi(Build.VERSION_CODES.R)
object InlineAutofill {
    private var generation = 0

    /** The request: chips in the keyboard's dark colours, up to six of them. */
    @JvmStatic
    fun request(context: Context): InlineSuggestionsRequest {
        val chip = Icon.createWithResource(context, androidx.autofill.R.drawable.autofill_inline_suggestion_chip_background)
            .setTint(Color.rgb(60, 64, 67))
        val style = InlineSuggestionUi.newStyleBuilder()
            .setSingleIconChipStyle(ViewStyle.Builder().setBackground(chip).setPadding(0, 0, 0, 0).build())
            .setChipStyle(ViewStyle.Builder().setBackground(chip).build())
            .setStartIconStyle(ImageViewStyle.Builder().setLayoutMargin(0, 0, 0, 0).build())
            .setTitleStyle(TextViewStyle.Builder().setTextColor(Color.WHITE).setTextSize(12f).build())
            .setSubtitleStyle(TextViewStyle.Builder().setTextColor(Color.rgb(170, 172, 176)).setTextSize(10f).build())
            .setEndIconStyle(ImageViewStyle.Builder().setLayoutMargin(0, 0, 0, 0).build())
            .build()
        val stylesBuilder = UiVersions.newStylesBuilder()
        stylesBuilder.addStyle(style)
        val styles = stylesBuilder.build()
        val density = context.resources.displayMetrics.density
        val min = Size((100 * density).toInt(), (36 * density).toInt())
        val max = Size((400 * density).toInt(), (48 * density).toInt())
        // Some password managers want a spec for each suggestion they may send.
        val specs = List(3) { InlinePresentationSpec.Builder(min, max).setStyle(styles).build() }
        return InlineSuggestionsRequest.Builder(specs).setMaxSuggestionCount(6).build()
    }

    /**
     * Inflates the response's suggestions and hands the views to [deck] once all have arrived (in order). A newer
     * response makes an older one that is still inflating drop its result.
     */
    @JvmStatic
    fun show(context: Context, response: InlineSuggestionsResponse, deck: ToolsDeck) {
        val mine = ++generation
        val suggestions = response.inlineSuggestions
        if (suggestions.isEmpty()) {
            deck.showInlineSuggestions(emptyList())
            return
        }
        val views = arrayOfNulls<View>(suggestions.size)
        var pending = suggestions.size
        val size = Size(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        suggestions.forEachIndexed { index, suggestion ->
            suggestion.inflate(context, size, context.mainExecutor) { view ->
                views[index] = view
                if (--pending == 0 && mine == generation) deck.showInlineSuggestions(views.filterNotNull())
            }
        }
    }
}

/**
 * Holds the autofill chips so they are drawn only inside it. A chip is a surface owned by the autofill service's
 * process and always draws on top of the app; without clipping, a chip scrolled out of the row would cover the keys
 * (Droidtop/tracker#342). Each frame every [InlineContentView] below is clipped to this view's bounds. The same
 * approach as the platform's inline suggestion sample (Apache-2.0): a transparent top-most surface lets the
 * chips' surfaces stack under the keyboard's window content.
 */
@RequiresApi(Build.VERSION_CODES.R)
class InlineClipView(context: Context) : FrameLayout(context) {
    private val parentBounds = Rect()
    private val contentBounds = Rect()
    private val drawListener = ViewTreeObserver.OnDrawListener { clipChips() }

    init {
        val background = SurfaceView(context)
        background.setZOrderOnTop(true)
        background.holder.setFormat(PixelFormat.TRANSPARENT)
        addView(background)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnDrawListener(drawListener)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnDrawListener(drawListener)
        super.onDetachedFromWindow()
    }

    private fun clipChips() {
        parentBounds.right = width
        parentBounds.bottom = height
        clipBelow(this)
    }

    private fun clipBelow(root: View) {
        if (root is InlineContentView) {
            contentBounds.set(parentBounds)
            offsetRectIntoDescendantCoords(root, contentBounds)
            root.clipBounds = contentBounds
            return
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) clipBelow(root.getChildAt(i))
        }
    }
}
