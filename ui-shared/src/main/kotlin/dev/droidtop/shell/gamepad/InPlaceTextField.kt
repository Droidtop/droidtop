package dev.droidtop.shell.gamepad

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.droidtop.shell.gamepad.input.GamepadAction
import dev.droidtop.shell.gamepad.input.onPad

/**
 * Text typed where it stands (owner, 2026-11-19, mytop-project/tracker#954 item 12: no separate editor and no editing panel
 * anywhere). The row or box that shows a value becomes this field while the person types in it: it takes the cursor at
 * once, with Android's keyboard where droidtop does not serve the screen and droidtop's own under it where it does,
 * as every droidtop field does. The keyboard's Done (or Search), B, Back, or Up and Down off a one-line field keep the
 * text and call [onDone] with it; so does the field losing the cursor any other way. A secret shows dots; a multi-line
 * field keeps Up and Down for its lines.
 *
 * The caller shows the value again once [onDone] has run, and hands the pad back to whatever held it before.
 */
@Composable
fun InPlaceTextField(
    initial: String,
    hint: String,
    onDone: (String) -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    secret: Boolean = false,
    multiline: Boolean = false,
    search: Boolean = false,
) {
    var text by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    val focus = remember { FocusRequester() }
    val view = LocalView.current
    val keyboard = LocalSoftwareKeyboardController.current
    var finished by remember { mutableStateOf(false) }
    var hadFocus by remember { mutableStateOf(false) }
    fun finish() {
        if (finished) return
        finished = true
        keyboard?.hide()
        onDone(text.text)
    }
    fun androidKeyboard() {
        if (!KeyboardTargets.servesField(view.display?.displayId)) keyboard?.show()
    }
    LaunchedEffect(Unit) {
        requestFocusWhenAttached(focus, title ?: "Text field")
        androidKeyboard()
    }
    BackHandler { finish() }
    Column(modifier.fillMaxWidth()) {
        title?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = MenuTokens.OnSurfaceMuted) }
        Box(
            modifier = Modifier
                .padding(top = if (title != null) 4.dp else 0.dp)
                .fillMaxWidth()
                .clip(Corners.Crisp)
                .background(MenuTokens.SurfaceSelected)
                .focusRing(true, Corners.Crisp)
                .padding(12.dp),
        ) {
            if (text.text.isEmpty()) {
                Text(hint, color = MenuTokens.Placeholder, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            }
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = !multiline,
                keyboardOptions = KeyboardOptions(
                    imeAction = when {
                        multiline -> ImeAction.Default
                        search -> ImeAction.Search
                        else -> ImeAction.Done
                    },
                    showKeyboardOnFocus = raisesKeyboardWithFocus,
                ),
                keyboardActions = KeyboardActions(onDone = { finish() }, onSearch = { finish() }),
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MenuTokens.OnSurface),
                cursorBrush = SolidColor(MenuTokens.Accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .onFocusChanged { state ->
                        if (state.isFocused) hadFocus = true else if (hadFocus) finish()
                    }
                    // After the field: the keys it types never reach here, only the pad's.
                    .onPad { press ->
                        when (press.action) {
                            GamepadAction.B -> {
                                finish()
                                true
                            }
                            // Off a one-line field the list moves on as usual: the text is kept and the press passes.
                            GamepadAction.UP, GamepadAction.DOWN -> {
                                if (!multiline) finish()
                                false
                            }
                            GamepadAction.A -> {
                                androidKeyboard()
                                true
                            }
                            else -> false
                        }
                    },
            )
        }
        // droidtop's own keyboard where it serves this screen (SPEC 4c).
        OwnFieldKeyboard()
    }
}
