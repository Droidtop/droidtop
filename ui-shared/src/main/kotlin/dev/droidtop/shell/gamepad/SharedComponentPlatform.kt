package dev.droidtop.shell.gamepad

import android.util.Log
import android.view.KeyEvent
import dev.droidtop.shell.gamepad.input.ControllerLayouts
import dev.droidtop.shell.gamepad.input.PadKeyHooks
import dev.droidtop.shell.gamepad.input.PadKeyMap

/** Android's facts for the shared components. The key map, repeat state and focus behavior remain shared code. */
internal object SharedComponentPlatform {
    init {
        focusAttachFailed = { tag, message -> Log.w(tag, message); Unit }
        PadKeyMap.faceSwapped = { ControllerLayouts.layout.swapped }
        PadKeyHooks.isFallback = { (it.nativeKeyEvent.flags and KeyEvent.FLAG_FALLBACK) != 0 }
        PadKeyHooks.repeatCount = { it.nativeKeyEvent.repeatCount }
    }

    fun install() = Unit
}
