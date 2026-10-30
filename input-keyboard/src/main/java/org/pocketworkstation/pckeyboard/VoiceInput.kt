package org.pocketworkstation.pckeyboard

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.inputmethod.InputMethodInfo
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype

/**
 * Where the voice key's dictation comes from (Droidtop/tracker#343). One provider answers at a time: the first in
 * [providers] that is available when the key is pressed: [OfflineVoiceTyping] once its download is in place and the
 * microphone is allowed, else [SystemVoiceKeyboard].
 */
interface VoiceProvider {
    /**
     * Whether this provider can dictate now; asked on every press of the voice key, so it must not do a lookup each
     * time (the system keyboard's answer is kept for the field, see [VoiceInput.fieldStarted]).
     */
    fun available(service: InputMethodService): Boolean

    /** Starts dictation for the field [service] is typing into. */
    fun start(service: InputMethodService)
}

object VoiceInput {
    val providers: List<VoiceProvider> = listOf(OfflineVoiceTyping, SystemVoiceKeyboard)

    /** The provider the voice key uses now, or null when none can dictate (the key then does nothing). */
    @JvmStatic
    fun provider(service: InputMethodService): VoiceProvider? = providers.firstOrNull { it.available(service) }

    /**
     * A new field started: answers kept for the last one are dropped (another keyboard may have been enabled or
     * disabled since), so the next press asks again. Main thread, from the input method's `onStartInputView`.
     */
    @JvmStatic
    fun fieldStarted() = SystemVoiceKeyboard.forget()

    /** The voice key: dictation from the first provider that can, if any. Returns whether one started. */
    @JvmStatic
    fun start(service: InputMethodService): Boolean {
        val provider = provider(service) ?: return false
        provider.start(service)
        return true
    }
}

/**
 * Another keyboard's voice typing: an enabled input method with a subtype whose mode is "voice" (Gboard's voice
 * typing, for one). The voice key switches to it; that keyboard switches back when it is done. This is what the
 * prebuilt `voiceimeutils.jar` (Google's 2011 VoiceRecognitionTrigger) did through its ImeTrigger, now in source; its
 * other path, a RecognizerIntent activity, needed activities this keyboard never declared and so never ran.
 */
object SystemVoiceKeyboard : VoiceProvider {
    const val VOICE_MODE = "voice"

    private fun manager(context: Context) = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    /** The first enabled input method, other than [ownPackage], with an enabled voice subtype. */
    fun find(context: Context, ownPackage: String): Pair<InputMethodInfo, InputMethodSubtype>? {
        val imm = manager(context)
        for (info in imm.enabledInputMethodList) {
            if (info.packageName == ownPackage) continue
            val subtype = imm.getEnabledInputMethodSubtypeList(info, true).firstOrNull { it.mode == VOICE_MODE } ?: continue
            return info to subtype
        }
        return null
    }

    // What find answered for this field (two IPC calls per enabled input method), until VoiceInput.fieldStarted.
    private var looked = false
    private var found: Pair<InputMethodInfo, InputMethodSubtype>? = null

    internal fun forget() {
        looked = false
        found = null
    }

    private fun voiceKeyboard(service: InputMethodService): Pair<InputMethodInfo, InputMethodSubtype>? {
        if (!looked) {
            found = find(service, service.packageName)
            looked = true
        }
        return found
    }

    override fun available(service: InputMethodService): Boolean = voiceKeyboard(service) != null

    override fun start(service: InputMethodService) {
        val (info, subtype) = voiceKeyboard(service) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            service.switchInputMethod(info.id, subtype)
        } else {
            val token = service.window?.window?.attributes?.token ?: return
            @Suppress("DEPRECATION")
            manager(service).setInputMethodAndSubtype(token, info.id, subtype)
        }
    }
}
