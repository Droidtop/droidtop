package dev.droidtop.ui.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.pocketworkstation.pckeyboard.Slab
import org.pocketworkstation.pckeyboard.SlabView

/**
 * The one text entry (docs/SPEC.md 4c, "The text-entry slab"; Droidtop/tracker#485 S7; owner, 2026-10-14: "our text
 * popup appears a lot different than steams, which is weird"). Two forms of one thing:
 *
 * - [TextEntryField]: a field in place (a search bar docked under its header, a form field), with the slab under it.
 *   B keeps the text and leaves.
 * - [TextEntryPrompt]: Steam's rename dialog, a centred card (at most 560 dp) above the slab over a dimmed page, a
 *   title line, the plate, and small Cancel and Save buttons for touch. B cancels and restores the old value.
 *
 * The plate is a real text field (TalkBack reads its text, caret and selection) whose input method stays away: keys
 * come from the slab, which the pad drives through this entry's key handling while the field keeps focus. Where the
 * slab shows is the host's ([slabHere]): on this screen, or elsewhere (the companion, the internal screen), when the
 * plate shows [elsewhereCue] instead.
 */
data class TextEntryColors(
    val ground: Color,
    val plate: Color,
    val plateFocused: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val scrim: Color = Color.Black.copy(alpha = 0.6f),
) {
    internal fun slab() = SlabView.Colors(ground.toArgb(), plate.toArgb(), plateFocused.toArgb(), text.toArgb(), muted.toArgb(), accent.toArgb())
}

/** What a slab press does to the field's value. Pure, for tests: typing replaces the selection, Backspace removes it or one character before the caret. */
object TextEntryEdits {
    fun apply(value: TextFieldValue, output: Slab.Output, maxLength: Int = Int.MAX_VALUE): TextFieldValue = when (output) {
        is Slab.Output.Type -> {
            val start = value.selection.min
            val end = value.selection.max
            val room = (maxLength - (value.text.length - (end - start))).coerceAtLeast(0)
            val insert = output.text.take(room)
            val text = value.text.substring(0, start) + insert + value.text.substring(end)
            TextFieldValue(text, TextRange(start + insert.length))
        }
        is Slab.Output.Press -> when (output.keyCode) {
            android.view.KeyEvent.KEYCODE_DEL -> {
                val start = value.selection.min
                val end = value.selection.max
                when {
                    start != end -> TextFieldValue(value.text.removeRange(start, end), TextRange(start))
                    start > 0 -> TextFieldValue(value.text.removeRange(start - 1, start), TextRange(start - 1))
                    else -> value
                }
            }
            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> value.copy(selection = TextRange((value.selection.min - 1).coerceAtLeast(0)))
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> value.copy(selection = TextRange((value.selection.max + 1).coerceAtMost(value.text.length)))
            android.view.KeyEvent.KEYCODE_TAB -> apply(value, Slab.Output.Type("\t"), maxLength)
            else -> value
        }
        else -> value
    }
}

@Composable
private fun Plate(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    colors: TextEntryColors,
    focus: FocusRequester,
    elsewhereCue: String?,
    search: Boolean,
    modifier: Modifier = Modifier,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = if (search) 56.dp else 64.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(colors.plate)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (value.text.isEmpty()) Text(placeholder, color = colors.muted, fontSize = 18.sp)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                // The slab types; Android's keyboard stays away (S7 5.1).
                keyboardOptions = KeyboardOptions(showKeyboardOnFocus = false),
                textStyle = TextStyle(color = colors.text, fontSize = 18.sp, lineHeight = 24.sp),
                cursorBrush = SolidColor(colors.accent),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        // The accent underline while editing.
        Box(Modifier.fillMaxWidth().padding(horizontal = 2.dp).heightIn(min = 2.dp, max = 2.dp).background(colors.accent))
        if (elsewhereCue != null) Text(elsewhereCue, color = colors.muted, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
    }
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.hide()
    }
}

@Composable
private fun SlabKeys(
    kind: Slab.Kind,
    form: Slab.Form,
    enter: Slab.EnterPurpose,
    colors: TextEntryColors,
    capLabel: (Slab.Cap) -> String,
    onOutput: (Slab.Output) -> Unit,
    holder: (SlabView) -> Unit,
) {
    val output by rememberUpdatedState(onOutput)
    AndroidView(
        modifier = Modifier.fillMaxWidth(),
        factory = { ctx -> SlabView(ctx, kind, form, enter, colors.slab(), capLabel, { output(it) }).also(holder) },
    )
}

/**
 * A field in place with the slab under it. [onSubmit] is R2 (or the slab's Enter key); [onDone] is B, which keeps
 * the text. [slabHere] false shows [elsewhereCue] under the plate and no slab (the keys are typed on another screen).
 */
@Composable
fun TextEntryField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    colors: TextEntryColors,
    capLabel: (Slab.Cap) -> String,
    onSubmit: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    kind: Slab.Kind = Slab.Kind.SEARCH,
    enter: Slab.EnterPurpose = Slab.EnterPurpose.SEARCH,
    maxLength: Int = 500,
    slabHere: Boolean = true,
    elsewhereCue: String? = null,
    focus: FocusRequester = remember { FocusRequester() },
) {
    val current by rememberUpdatedState(value)
    var slab by remember { mutableStateOf<SlabView?>(null) }
    val system = rememberSystemKeyboard()
    fun deliver(output: Slab.Output) {
        when (output) {
            Slab.Output.Close -> onDone()
            Slab.Output.UseSystemKeyboard -> system.use()
            is Slab.Output.Press -> if (output.keyCode == android.view.KeyEvent.KEYCODE_ENTER) onSubmit() else onValueChange(TextEntryEdits.apply(current, output, maxLength))
            else -> onValueChange(TextEntryEdits.apply(current, output, maxLength))
        }
    }
    Column(modifier.onPreviewKeyEvent { event -> system.leave(event.nativeKeyEvent) || slab?.handleKey(event.nativeKeyEvent) == true }) {
        Plate(value, onValueChange, placeholder, colors, focus, system.cue ?: elsewhereCue.takeIf { !slabHere }, kind == Slab.Kind.SEARCH)
        if (slabHere && !system.active) SlabKeys(kind, Slab.Form.FIELD, enter, colors, capLabel, ::deliver) { slab = it }
    }
}

/**
 * Steam's rename dialog: [title], the plate, Cancel and Save, above the slab over a dimmed page. [onSave] gets the
 * text (R2, or Save); [onCancel] is B or Cancel, and the caller restores the old value.
 */
@Composable
fun TextEntryPrompt(
    title: String,
    initial: String,
    colors: TextEntryColors,
    capLabel: (Slab.Cap) -> String,
    onSave: (String) -> Unit,
    onCancel: () -> Unit,
    placeholder: String = "",
    kind: Slab.Kind = Slab.Kind.PLAIN,
    enter: Slab.EnterPurpose = Slab.EnterPurpose.SAVE,
    maxLength: Int = 500,
    slabHere: Boolean = true,
    elsewhereCue: String? = null,
) {
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    var slab by remember { mutableStateOf<SlabView?>(null) }
    val focus = remember { FocusRequester() }
    val system = rememberSystemKeyboard()
    fun deliver(output: Slab.Output) {
        when (output) {
            Slab.Output.Close -> onCancel()
            Slab.Output.UseSystemKeyboard -> system.use()
            is Slab.Output.Press -> if (output.keyCode == android.view.KeyEvent.KEYCODE_ENTER) onSave(value.text) else value = TextEntryEdits.apply(value, output, maxLength)
            else -> value = TextEntryEdits.apply(value, output, maxLength)
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.scrim)
            .onPreviewKeyEvent { event -> system.leave(event.nativeKeyEvent) || slab?.handleKey(event.nativeKeyEvent) == true },
        verticalArrangement = Arrangement.Bottom,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier
                .padding(16.dp)
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(colors.ground)
                .padding(16.dp),
        ) {
            Text(title, color = colors.text, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
            Plate(value, { value = it }, placeholder, colors, focus, system.cue ?: elsewhereCue.takeIf { !slabHere }, search = false, modifier = Modifier.padding(top = 12.dp))
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                SmallButton("Cancel", colors, onCancel)
                SmallButton(enter.label, colors) { onSave(value.text) }
            }
        }
        if (slabHere && !system.active) SlabKeys(kind, Slab.Form.PROMPT, enter, colors, capLabel, ::deliver) { slab = it }
    }
}

@Composable
private fun SmallButton(text: String, colors: TextEntryColors, onClick: () -> Unit) {
    Box(
        Modifier
            .heightIn(min = 36.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(colors.plate)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = colors.text, fontSize = 15.sp) }
}

/**
 * The globe key's hand-off (S7 5.6): this entry only is typed with the system keyboard, the plate says so, and B
 * comes back to the slab. Android's keyboard follows the field's focus, which the entry keeps throughout.
 */
private class SystemKeyboardHandOff(private val show: () -> Unit, private val hide: () -> Unit) {
    var active by mutableStateOf(false)
        private set

    val cue: String? get() = if (active) "System keyboard. B returns to droidtop's" else null

    fun use() {
        active = true
        show()
    }

    /** B while the system keyboard types: back to the slab, and the key is taken. */
    fun leave(event: android.view.KeyEvent): Boolean {
        if (!active) return false
        // The pad's cancel button, the swap applied (FaceKeys), or back; not a hardware keyboard's Escape, which types.
        val back = event.keyCode != android.view.KeyEvent.KEYCODE_ESCAPE && org.pocketworkstation.pckeyboard.FaceKeys.isCancel(event.keyCode)
        if (!back) return false
        if (event.action == android.view.KeyEvent.ACTION_UP) {
            active = false
            hide()
        }
        return true
    }
}

@Composable
private fun rememberSystemKeyboard(): SystemKeyboardHandOff {
    val keyboard = LocalSoftwareKeyboardController.current
    return remember(keyboard) { SystemKeyboardHandOff({ keyboard?.show() }, { keyboard?.hide() }) }
}

