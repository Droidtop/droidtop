package dev.droidtop.ui.shared

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputMethodRequest

/**
 * Every droidtop text field under [content] asks the input method not to go full screen (IME_FLAG_NO_FULLSCREEN and
 * IME_FLAG_NO_EXTRACT_UI). On a landscape screen Gboard otherwise replaces the whole window with its own editor, and
 * the search's results were under it with no way back (rig on the console, 2026-10-16, Droidtop/tracker#485 comment
 * 6224857113: its Search and Enter did not close it). The themes' roots wrap their content in it, so it reaches every
 * Compose field, dialogs included, and the S7 TextEntry.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun NoFullscreenTextInput(content: @Composable () -> Unit) {
    InterceptPlatformTextInput(
        interceptor = { request, next ->
            next.startInputMethod(
                object : PlatformTextInputMethodRequest {
                    override fun createInputConnection(outAttributes: EditorInfo): InputConnection {
                        val connection = request.createInputConnection(outAttributes)
                        outAttributes.imeOptions = noFullscreen(outAttributes.imeOptions)
                        return connection
                    }
                },
            )
        },
        content = content,
    )
}

/** [imeOptions] with the input method kept out of full screen. Pure, for tests and for View fields. */
fun noFullscreen(imeOptions: Int): Int = imeOptions or EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
