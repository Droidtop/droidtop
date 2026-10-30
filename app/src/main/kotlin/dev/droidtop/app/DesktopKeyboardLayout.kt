package dev.droidtop.app

import android.content.Context
import android.hardware.input.InputManager
import android.util.Log
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.inputmethod.InputMethodManager
import dev.droidtop.library.settings.CatalogPrefs
import dev.droidtop.runtime.ContainerLauncher
import dev.droidtop.runtime.ContainerLayout
import dev.droidtop.runtime.KeyboardLayouts
import dev.droidtop.runtime.KeyboardLayouts.XkbLayout
import java.io.File
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The layout a physical keyboard types in on the desktop (docs/SPEC.md 6b
 * "Keyboard layout", Droidtop/tracker#387). The setting is Automatic by
 * default: the attached physical keyboard's layout when Android reports one,
 * otherwise the on-screen keyboard's language, otherwise US; the row says
 * which and where it came from. The keymap is compiled in the primary by the
 * distro's own `xkbcli` ([KeyboardLayouts.compileCommand]) into a file on the
 * channel directory every container sees, read here and handed to host-bridge
 * for the layout keyboard. Keys Android derived from characters keep their US
 * keyboard (DesktopInputRouter), so on-screen typing is unaffected.
 *
 * Applied when the desktop comes up, when a keyboard is attached, removed or
 * changed, and when the setting changes; one exec each time, never per key.
 */
object DesktopKeyboardLayout {
    private const val TAG = "droidtop.DesktopKeyboard"
    private const val KEYMAP_FILE = "keymap.xkb"
    private const val LAYOUT_LIST_FILE = "xkb-layouts.lst"

    /** Where a layout came from, as the row says it. */
    enum class Source(val phrase: String) {
        SETTING("chosen in this row"),
        KEYBOARD("from the attached keyboard"),
        ON_SCREEN("from the on-screen keyboard's language"),
        DEFAULT("no keyboard language known"),
    }

    data class Choice(val layout: XkbLayout, val source: Source)

    private val scope = dev.droidtop.runtime.util.AppScopes.io
    private val applying = Mutex()

    @Volatile private var listener: InputManager.InputDeviceListener? = null

    /** Compiled keymaps by layout id: one exec per layout, not per focus change. Guarded by [applying]. */
    private val compiled = HashMap<String, String>()

    /** The layout the desktop's keyboard has now, and on which bridge; a new desktop starts again from its US map. */
    @Volatile private var applied: String? = null
    @Volatile private var appliedTo: Any? = null

    fun setting(context: Context): String =
        CatalogPrefs.prefs(context).getString(KeyboardLayouts.KEY, KeyboardLayouts.AUTOMATIC) ?: KeyboardLayouts.AUTOMATIC

    /** Stores [value] ([KeyboardLayouts.AUTOMATIC] or a layout id) and applies it to a running desktop. */
    fun setSetting(context: Context, value: String) {
        CatalogPrefs.prefs(context).edit().putString(KeyboardLayouts.KEY, value).apply()
        reapply(context)
    }

    /** What the setting means right now. Binder calls to the input and input-method services, no file work. */
    fun resolve(context: Context): Choice {
        val stored = setting(context)
        if (stored != KeyboardLayouts.AUTOMATIC) XkbLayout.fromId(stored)?.let { return Choice(it, Source.SETTING) }
        return automatic(context)
    }

    /** What Automatic resolves to now: the attached keyboard's layout, else the on-screen keyboard's language, else US. */
    fun automatic(context: Context): Choice {
        physicalKeyboardLayout(context)?.let { return Choice(it, Source.KEYBOARD) }
        onScreenLayout(context)?.let { return Choice(it, Source.ON_SCREEN) }
        return Choice(KeyboardLayouts.US, Source.DEFAULT)
    }

    /** "German (de), from the attached keyboard": the row's line for [choice], named from the container's list where it is known. */
    fun describe(choice: Choice, names: Map<String, String>): String {
        val name = names[choice.layout.layout]
        val id = choice.layout.id
        return (if (name != null) "$name ($id)" else id) + ", " + choice.source.phrase
    }

    /**
     * The layout a physical (not virtual), alphabetic keyboard reports. The
     * public way Android reports it is the keyboard's own KeyCharacterMap, to
     * which it applies the layout chosen for that keyboard in Android's
     * settings (InputDevice's language tag and layout type are not in the
     * public SDK); [KeyboardLayouts.fromKeyCharacters] reads it. Null when no
     * such keyboard is attached or its map matches no known layout.
     */
    private fun physicalKeyboardLayout(context: Context): XkbLayout? {
        val input = context.getSystemService(InputManager::class.java) ?: return null
        return input.inputDeviceIds.asSequence()
            .mapNotNull { input.getInputDevice(it) }
            .filter { !it.isVirtual && it.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC }
            .firstNotNullOfOrNull { device ->
                val map = device.keyCharacterMap
                KeyboardLayouts.fromKeyCharacters { code ->
                    val value = map.get(code, 0)
                    // A dead key reports its accent with a marker bit; the accent itself is what tells layouts apart.
                    if (value and KeyCharacterMap.COMBINING_ACCENT != 0) value and KeyCharacterMap.COMBINING_ACCENT_MASK else value
                }
            }
    }

    @Suppress("DEPRECATION") // InputMethodSubtype.locale is the only field an older keyboard app fills in.
    private fun onScreenLayout(context: Context): XkbLayout? {
        val subtype = context.getSystemService(InputMethodManager::class.java)?.currentInputMethodSubtype ?: return null
        return KeyboardLayouts.fromLanguageTag(subtype.languageTag.ifEmpty { subtype.locale })
    }

    /** Compiles [layout] in the desktop's container, by the distro's own `xkbcli`; null when that failed. Blocking. */
    private suspend fun compile(context: Context, session: DesktopSessionState.Connected, layout: XkbLayout): String? {
        val dir = ContainerLauncher.hostDir(context.filesDir).apply { mkdirs() }
        val keymapFile = File(dir, KEYMAP_FILE)
        keymapFile.delete()
        // Into a file on the channel directory, not through the exec's output, which keeps only its last lines.
        val inContainer = ContainerLayout.hostStorageToContainerPath(context.filesDir, keymapFile)
        val argv = listOf("sh", "-c", "exec \"\$@\" > \"\$0\"", inContainer) + KeyboardLayouts.compileCommand(layout)
        val result = runCatching { session.runtime.exec(session.container, argv, emptyMap()) }.getOrNull()
        val keymap = keymapFile.takeIf { result?.exitCode == 0 && it.isFile }?.readText()
        if (keymap.isNullOrBlank()) Log.w(TAG, "Compiling ${layout.id} in the desktop failed (${result?.stderr?.trim()})")
        return keymap?.takeIf { it.isNotBlank() }
    }

    /**
     * The layout of the container whose window has the focus, when it chose one of its own; null for the desktop's.
     * The window's process is sway's to name ([SwayIpc.focusedPid]); its container is the backend's to tell
     * ([dev.droidtop.runtime.ContainerRuntime.containerOfProcess]), and a backend that cannot gives the desktop's.
     */
    private suspend fun ownLayoutOfFocused(session: DesktopSessionState.Connected): XkbLayout? {
        val runtime = session.runtime
        if (runtime.windowOwnerUnavailableReason != null) return null
        val socket = ContainerLayout.findSwayIpcSocket(runtime.hostSocketDir()) ?: return null
        val pid = runCatching { dev.droidtop.hostbridge.SwayIpc(socket.absolutePath).focusedPid() }.getOrNull()?.takeIf { it > 0 } ?: return null
        val id = runtime.containerOfProcess(pid) ?: return null
        val container = runCatching { runtime.listContainers() }.getOrNull()?.firstOrNull { it.container.id == id }?.container ?: return null
        return runCatching { runtime.look(container) }.getOrNull()?.keyboardLayout?.let(XkbLayout::fromId)
    }

    /** The container's layout names (id to description), from the copy taken the last time a desktop ran; empty before. */
    fun layoutNames(context: Context): Map<String, String> =
        runCatching { KeyboardLayouts.parseLayoutList(File(ContainerLauncher.hostDir(context.filesDir), LAYOUT_LIST_FILE).readText()) }
            .getOrDefault(emptyList()).toMap()

    /** Starts following keyboards for the running desktop and applies the layout now. */
    fun startWatching(context: Context) {
        val app = context.applicationContext
        dev.droidtop.runtime.DesktopFocus.listener = { reapply(app) }
        val input = app.getSystemService(InputManager::class.java)
        if (input != null && listener == null) {
            val watching = object : InputManager.InputDeviceListener {
                override fun onInputDeviceAdded(deviceId: Int) = reapply(app)
                override fun onInputDeviceRemoved(deviceId: Int) = reapply(app)
                override fun onInputDeviceChanged(deviceId: Int) = reapply(app)
            }
            // Called from the session's IO thread, which has no looper: the callbacks only queue a reapply.
            input.registerInputDeviceListener(watching, android.os.Handler(android.os.Looper.getMainLooper()))
            listener = watching
        }
        reapply(app)
    }

    fun stopWatching(context: Context) {
        dev.droidtop.runtime.DesktopFocus.listener = null
        val watching = listener ?: return
        listener = null
        context.applicationContext.getSystemService(InputManager::class.java)?.unregisterInputDeviceListener(watching)
    }

    /** Applies the current layout to the running desktop, if there is one, off the main thread. */
    fun reapply(context: Context) {
        val app = context.applicationContext
        scope.launch { applying.withLock { apply(app) } }
    }

    private suspend fun apply(context: Context) {
        val session = DesktopSessionService.state.value as? DesktopSessionState.Connected ?: return
        // The container's own layout list, for the setting's choices; copied once.
        val list = File(ContainerLauncher.hostDir(context.filesDir).apply { mkdirs() }, LAYOUT_LIST_FILE)
        if (!list.isFile) {
            val target = ContainerLayout.hostStorageToContainerPath(context.filesDir, list)
            runCatching { session.runtime.exec(session.container, listOf("cp", KeyboardLayouts.LAYOUT_LIST, target), emptyMap()) }
        }
        val choice = resolve(context)
        // The focused window's container's own layout, when it has one (docs/SPEC.md 3d "Keyboard").
        val own = ownLayoutOfFocused(session)
        val layout = own ?: choice.layout
        if (layout.id == applied && session.hostBridge === appliedTo) return
        val keymap = compiled[layout.id] ?: compile(context, session, layout)?.also { compiled[layout.id] = it }
        if (keymap.isNullOrBlank()) {
            Log.w(TAG, "No keymap for ${layout.id}; the keyboard keeps its keymap")
        } else if (session.hostBridge.setKeymap(keymap)) {
            applied = layout.id
            appliedTo = session.hostBridge
            Log.i(TAG, "Desktop keyboard layout ${layout.id} (${if (own != null) "the focused container's own" else choice.source.phrase})")
        }
    }
}
