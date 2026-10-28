package org.pocketworkstation.pckeyboard

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.inputmethod.InputMethodInfo
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype

/**
 * Where the voice key's dictation comes from (Droidtop/tracker#343). One provider answers at a time: the first in
 * [providers] that is available when the key is pressed. Today that is [SystemVoiceKeyboard]; the offline engine the
 * issue asks for joins this list as another provider, ahead of it.
 */
interface VoiceProvider {
    /** Whether this provider can dictate now. Cheap: asked on every press of the voice key. */
    fun available(service: InputMethodService): Boolean

    /** Starts dictation for the field [service] is typing into. */
    fun start(service: InputMethodService)
}

object VoiceInput {
    val providers: List<VoiceProvider> = listOf(SystemVoiceKeyboard)

    /** The provider the voice key uses now, or null when none can dictate (the key then does nothing). */
    @JvmStatic
    fun provider(service: InputMethodService): VoiceProvider? = providers.firstOrNull { it.available(service) }

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

    override fun available(service: InputMethodService): Boolean = find(service, service.packageName) != null

    override fun start(service: InputMethodService) {
        val (info, subtype) = find(service, service.packageName) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            service.switchInputMethod(info.id, subtype)
        } else {
            val token = service.window?.window?.attributes?.token ?: return
            @Suppress("DEPRECATION")
            manager(service).setInputMethodAndSubtype(token, info.id, subtype)
        }
    }
}
