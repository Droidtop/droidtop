package dev.droidtop.hostbridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.droidtop.library.settings.Keyboards

/**
 * Text copied in Android is pasteable in the container, and text copied in
 * the container is pasteable in Android.
 *
 * The container half goes through [HostBridge]'s ext-data-control-v1 client
 * (see host-bridge/native/src/wayland_client.cpp) — the Wayland protocol for
 * a privileged client that observes and sets a seat's selection without
 * holding focus, which is exactly host-bridge's position: it has no surface
 * of its own at all, so `wl_data_device` is not available to it.
 *
 * The Android half is [ClipboardManager]. Neither side polls: Android
 * reports changes through [ClipboardManager.OnPrimaryClipChangedListener],
 * and the compositor reports them through the protocol's own `selection`
 * event. The one non-event read is [onWindowFocusChanged] — see below.
 *
 * **What happens when Android refuses.** From Android 10 only the focused
 * app or the owner of the current input method may read the clipboard (see
 * [ClipboardAccess]), and a change that happens while droidtop is neither
 * arrives as a listener callback whose `primaryClip` is null. That is not
 * treated as "the clipboard was cleared": it is skipped, logged, and picked
 * up on the next moment droidtop is allowed to look — which is what
 * [onWindowFocusChanged] is for, a single read on regaining focus rather
 * than a timer. The container→Android direction has no such gate, because
 * `setPrimaryClip` is not focus-restricted.
 *
 * Owned by the Activity rather than by `DesktopSessionService`, deliberately:
 * the read permission above is a property of window focus, which a Service
 * does not have and cannot observe.
 */
class ClipboardBridge(
    private val context: Context,
    private val hostBridge: HostBridge,
    private val sync: ClipboardSync = ClipboardSync(),
) {
    private val clipboard: ClipboardManager? =
        context.getSystemService(ClipboardManager::class.java)

    private val mainHandler = Handler(Looper.getMainLooper())

    private var started = false
    private var hasWindowFocus = false

    private val androidClipListener = ClipboardManager.OnPrimaryClipChangedListener {
        pushAndroidSelectionToContainer("Android clipboard changed")
    }

    fun start() {
        if (started) return
        val manager = clipboard
        if (manager == null) {
            Log.w(TAG, "No ClipboardManager — clipboard bridge not started")
            return
        }
        started = true
        sync.reset()
        manager.addPrimaryClipChangedListener(androidClipListener)
        hostBridge.containerClipboardListener = { text ->
            // The native side calls this on its own transfer thread.
            mainHandler.post { applyContainerSelectionToAndroid(text) }
        }
        Log.i(TAG, "Clipboard bridge started")
    }

    fun stop() {
        if (!started) return
        started = false
        hostBridge.containerClipboardListener = null
        clipboard?.removePrimaryClipChangedListener(androidClipListener)
        sync.reset()
        Log.i(TAG, "Clipboard bridge stopped")
    }

    /**
     * Forwarded from the Activity. Regaining focus is the one moment
     * droidtop is newly allowed to read a clipboard it may have been unable
     * to see while it was away, so it reads exactly once here. This is the
     * whole of the "catch up" behaviour — there is no polling loop, by
     * design: reads are user-visible on Android 12+ (the system shows a
     * toast naming the app), so a timer would both drain battery and pester
     * the user for nothing.
     */
    fun onWindowFocusChanged(focused: Boolean) {
        hasWindowFocus = focused
        if (focused) pushAndroidSelectionToContainer("window focus regained")
    }

    private fun canReadAndroidClipboard(): Boolean = ClipboardAccess.canRead(
        sdkInt = Build.VERSION.SDK_INT,
        hasWindowFocus = hasWindowFocus,
        ownKeyboardActive = Keyboards.ownKeyboardActive(context),
    )

    private fun pushAndroidSelectionToContainer(reason: String) {
        if (!started) return
        val manager = clipboard ?: return

        if (!canReadAndroidClipboard()) {
            Log.d(TAG, "Not reading the clipboard ($reason): ${ClipboardAccess.WHY_BLOCKED}")
            return
        }

        // `item.text` rather than `coerceToText`: this bridge is text-only,
        // and coercing would make a ContentResolver call for a content:// URI
        // item — real work, possibly a permission failure, for something we
        // would then have nothing to do with.
        val text = runCatching {
            manager.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.text
                ?.toString()
        }.getOrElse {
            Log.w(TAG, "Reading the Android clipboard failed", it)
            null
        }

        when (val decision = sync.accept(text)) {
            is ClipboardSync.Decision.Forward ->
                if (!hostBridge.offerClipboardText(decision.text)) {
                    Log.w(
                        TAG,
                        "Container refused the selection — the compositor may not expose " +
                            "ext_data_control_manager_v1",
                    )
                }
            ClipboardSync.Decision.AlreadyInSync -> Unit
            ClipboardSync.Decision.NothingToCopy -> Unit
            ClipboardSync.Decision.TooLarge ->
                Log.w(TAG, "Android clipboard exceeds ${ClipboardSync.MAX_BYTES} bytes — not forwarded")
        }
    }

    private fun applyContainerSelectionToAndroid(text: String) {
        if (!started) return
        val manager = clipboard ?: return

        when (val decision = sync.accept(text)) {
            is ClipboardSync.Decision.Forward ->
                // Writing is not focus-restricted the way reading is, but it
                // can still fail on OEM builds that police background
                // clipboard writes — an attributable warning beats a silent
                // no-op.
                runCatching {
                    manager.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, decision.text))
                }.onFailure {
                    Log.w(TAG, "Setting the Android clipboard from the container failed", it)
                }
            ClipboardSync.Decision.AlreadyInSync -> Unit
            ClipboardSync.Decision.NothingToCopy -> Unit
            ClipboardSync.Decision.TooLarge ->
                Log.w(TAG, "Container selection exceeds ${ClipboardSync.MAX_BYTES} bytes — not applied")
        }
    }

    private companion object {
        const val TAG = "droidtop.Clipboard"
        const val CLIP_LABEL = "droidtop desktop"
    }
}
