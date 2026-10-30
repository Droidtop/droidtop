package dev.droidtop.app

import android.app.Activity
import android.content.Context
import android.content.Context.INPUT_METHOD_SERVICE
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import dev.droidtop.display.SecondaryDisplayContent
import dev.droidtop.input.DesktopInputRouter
import dev.droidtop.input.FocusNavTrackpadSink
import dev.droidtop.input.InputSeats
import dev.droidtop.input.NavKey
import dev.droidtop.input.PointerTrackpadSink
import dev.droidtop.input.RoutedTrackpadOutput
import dev.droidtop.input.SeatTrackpadSink
import dev.droidtop.input.TRACKPAD_TRAVEL_MM_PER_SCREEN_WIDTH
import dev.droidtop.input.TrackpadGestureEngine
import dev.droidtop.input.TrackpadOutput
import dev.droidtop.input.TrackpadRoute
import dev.droidtop.input.TrackpadView
import dev.droidtop.runtime.keyboard.AccessibilityKeyboard
import dev.droidtop.shell.gamepad.KeyboardTargets
import org.pocketworkstation.pckeyboard.Slab
import org.pocketworkstation.pckeyboard.KeyboardColors
import org.pocketworkstation.pckeyboard.SlabSink
import org.pocketworkstation.pckeyboard.SlabView
import org.pocketworkstation.pckeyboard.KeyboardSink
import org.pocketworkstation.pckeyboard.SecondScreenKeyboard
import java.lang.ref.WeakReference
import kotlinx.coroutines.launch

/**
 * The foreground droidtop Activity, for the one thing the second screen
 * needs from it: somewhere to deliver synthetic navigation keys.
 *
 * `Activity.dispatchKeyEvent` is an ordinary public call into droidtop's
 * OWN window; it needs no permission and reaches the same Compose focus
 * machinery a real D-pad reaches. Reaching another app's window would need
 * `INJECT_EVENTS`, a signature permission, which is why the trackpad
 * navigates droidtop by key and plays touches in other apps through the
 * accessibility service instead -- see `FocusNavTrackpadSink` and
 * `AccessibilityPointer`.
 *
 * Weak, because holding an Activity from a process-wide object is the
 * classic leak, and a stale reference would deliver keys to a destroyed
 * window.
 */
object ForegroundShell {
    private var ref: WeakReference<Activity>? = null

    fun set(activity: Activity?) {
        ref = activity?.let { WeakReference(it) }
    }

    fun current(): Activity? = ref?.get()

    /**
     * Wires the touch-only second-screen activities to the shell (docs/SPEC.md 4c): when one of
     * them becomes the top activity, the shell's task is brought to the front if the shell is
     * resumed on another screen, and any key that still reaches the surface is delivered here.
     * Moving a task needs REORDER_TASKS (a normal permission); the caller is the top activity, so
     * the background-start rules allow it.
     */
    fun installPadReturn(context: Context) {
        appContext = context.applicationContext
        dev.droidtop.display.TouchOnlySurfaceFocus.returnPadToShell = returnPad@{ fromDisplayId ->
            val shell = current()?.takeIf { !it.isFinishing && !it.isDestroyed }
            if (shell != null && displayIdOf(shell) != fromDisplayId) {
                val activityManager = shell.getSystemService(android.app.ActivityManager::class.java)
                    ?: return@returnPad false
                activityManager.moveTaskToFront(shell.taskId, 0)
                return@returnPad true
            }
            returnFocusToApp(fromDisplayId)
        }
        dev.droidtop.display.TouchOnlySurfaceFocus.forwardKeyToShell = { event ->
            current()?.takeIf { !it.isFinishing && !it.isDestroyed }?.dispatchKeyEvent(event) ?: false
        }
    }

    /**
     * No shell in front on another screen, but the user may be in an app there (an app launched onto the add-on
     * display covers the shell, which then is not [current]). When the system's own task list (the elevated helper)
     * shows an app visible on another screen, it is brought back to the front through the task manager's one switch
     * ([dev.droidtop.library.tasks.TaskActions.bringTo], its launcher intent on its own screen), so its screen holds
     * the system's focus again and its keys and keyboard reach it (Droidtop/tracker#314). Without that list droidtop
     * cannot tell an app still showing from one the user left, so it moves nothing; a tap on the app does the same.
     */
    private fun returnFocusToApp(fromDisplayId: Int?): Boolean {
        val context = appContext ?: return false
        // The list is read fresh (it is only polled while a task list is on screen), off the main thread; the
        // switch itself is an activity start, back on the main thread. Until it lands, keys stay here.
        refocusScope.launch {
            if (!dev.droidtop.runtime.tasks.TaskManager.privileges().listTasks) return@launch
            dev.droidtop.runtime.tasks.TaskManager.refresh(context)
            val snapshot = dev.droidtop.runtime.tasks.TaskManager.snapshot.value ?: return@launch
            val app = dev.droidtop.runtime.keyboard.FocusReturn.appToRefocus(snapshot, fromDisplayId, context.packageName)
                ?: return@launch
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                dev.droidtop.library.tasks.TaskActions.bringTo(context, app.packageName, app.displayId)
            }
        }
        return false
    }

    private var appContext: Context? = null

    private val refocusScope = dev.droidtop.runtime.util.AppScopes.io

    private fun displayIdOf(activity: Activity): Int? =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            activity.display?.displayId
        } else {
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay?.displayId
        }

    fun send(navKey: NavKey) {
        val activity = current() ?: return
        val keyCode = when (navKey) {
            NavKey.UP -> KeyEvent.KEYCODE_DPAD_UP
            NavKey.DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
            NavKey.LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
            NavKey.RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
            // GamepadKeyMap already reads DirectionCenter as the A action
            // and Back as BACK, so these are the shell's own vocabulary
            // rather than a second mapping invented here.
            NavKey.CONFIRM -> KeyEvent.KEYCODE_DPAD_CENTER
            NavKey.BACK -> KeyEvent.KEYCODE_BACK
        }
        val now = SystemClock.uptimeMillis()
        // Both halves: the shell reads a few actions on key-down and most
        // on key-up, and a down with no up would leave Compose believing
        // the key is still held.
        activity.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        activity.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
    }
}

/**
 * The second screen as an input surface: droidtop's own keyboard above a
 * trackpad (docs/SPEC.md sections 4, 6 and 6c).
 *
 * A plain `LinearLayout` rather than Compose, because both children are
 * real Views that already exist and must not be reimplemented:
 * `LatinKeyboardView` is the forked Hacker's Keyboard's own key grid with
 * its own layouts and themes, and `TrackpadView` is `:input-seat`'s.
 *
 * Where the input goes depends on the mode, and the two answers are not
 * the same thing dressed differently:
 *
 * - **Desktop**: the pointer and the keys go into the primary container
 *   through the one `InputSeat`, exactly as the main desktop surface's own
 *   touch and keyboard do. A real trackpad and a real keyboard.
 * - **Gaming / Standard**: the trackpad drives the screen the shell is
 *   drawn on (the pinned one, or the first other): while droidtop's shell
 *   is in front there it moves the shell's selection (pointer and focus
 *   are one selection), in any other app it is a pointer drawn and played
 *   by the accessibility service (`AccessibilityPointer`); the keyboard
 *   types into whatever Android editor has focus. What each needs is
 *   stated on the surface rather than failing quietly.
 */
class SecondScreenInputView(
    context: Context,
    private val mode: SecondaryDisplayContent.Mode,
) : LinearLayout(context) {

    private val trackpad = TrackpadView(context)
    private val status = TextView(context)
    private val imePicker = Button(context)

    // Outside Desktop the trackpad is a pointer on the main screen, which needs droidtop's accessibility service.
    private val pointerHelp = Button(context)
    private var pointerSink: PointerTrackpadSink? = null
    private var lastRoute: TrackpadRoute? = null

    // The header (slice C12): where the keys go, Pin for that screen, and Tabs, which brings the companion's bar back
    // over Input; then the chord keys and the controllers with their batteries.
    private val target = TextView(context)
    private val pin = Button(context)
    private val tabs = Button(context)
    private val controllers = TextView(context)

    /** The keyboard ([slab]), set while the view is built: declared before init so init's value is the one kept. */
    private var keys: SlabView? = null

    /** The screens Input can type to, read off the main thread when the view attaches or regains focus. */
    @Volatile
    private var screens: List<InputTarget.Screen> = emptyList()

    init {
        orientation = VERTICAL
        setBackgroundColor(dev.droidtop.shell.gamepad.MenuTokens.Ground.toArgb())

        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        target.setTextColor(dev.droidtop.shell.gamepad.MenuTokens.OnSurfaceMuted.toArgb())
        target.textSize = 14f
        header.addView(target, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        pin.setOnClickListener { togglePin() }
        header.addView(pin, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        tabs.text = "Tabs"
        tabs.contentDescription = "Show tabs"
        tabs.setOnClickListener { CompanionInputHandle.showTabs.value = !CompanionInputHandle.showTabs.value }
        header.addView(tabs, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // The chord keys, played into the same destination as the keyboard ([CompanionChords]).
        val chordSink = requestAwareSink(keyboardSink())
        val player = org.pocketworkstation.pckeyboard.MacroPlayer({ code, down -> chordSink.key(code, down) }, { chordSink.text(it) })
        val chords = LinearLayout(context).apply { orientation = HORIZONTAL }
        // Typing from a phone or PC on the same network (Droidtop/tracker#645), opened on this screen.
        chords.addView(
            Button(context).apply {
                text = "Type from another device"
                isAllCaps = false
                setOnClickListener { RemoteInputActivity.open(context, display?.displayId) }
            },
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT),
        )
        CompanionChords.ALL.forEach { macro ->
            chords.addView(
                Button(context).apply {
                    text = macro.name
                    isAllCaps = false
                    setOnClickListener { player.play(macro) }
                },
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT),
            )
        }
        addView(
            android.widget.HorizontalScrollView(context).apply { addView(chords) },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )

        // The S7 keyboard (Slab) with a whole keyboard's keys: Esc, Tab, Ctrl, Alt, Super, the arrows and F1 to F12
        // (Droidtop/tracker#567, docs/SPEC.md 4c). It takes a fixed share of the tab and lays its rows out from that
        // share's real height, so it scales with the screen and the font, keeps its keys at least MIN_ROW_DP tall, and
        // never runs past its edge. Only the destination is this surface's own.
        val slabSink = requestAwareSink(keyboardSink())
        val tokens = dev.droidtop.shell.gamepad.MenuTokens
        val colors = KeyboardColors(
            ground = tokens.Ground.toArgb(),
            key = tokens.Card.toArgb(),
            keyFocused = tokens.CardFocused.toArgb(),
            text = tokens.OnSurface.toArgb(),
            muted = tokens.OnSurfaceMuted.toArgb(),
            accent = tokens.Accent.toArgb(),
        )
        addView(slab(colors, slabSink), LayoutParams(LayoutParams.MATCH_PARENT, 0, KEYBOARD_WEIGHT))

        status.setTextColor(dev.droidtop.shell.gamepad.MenuTokens.OnSurfaceMuted.toArgb())
        status.textSize = 13f
        status.gravity = Gravity.CENTER
        // Fixed heights: a fact that changes (the target, a controller, the input method) never moves the keys.
        status.setLines(2)
        addView(status, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        imePicker.text = "Use droidtop keyboard"
        imePicker.gravity = Gravity.CENTER
        imePicker.setOnClickListener {
            (context.getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.showInputMethodPicker()
        }
        addView(imePicker, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        pointerHelp.gravity = Gravity.CENTER
        pointerHelp.setOnClickListener { AccessibilityKeyboard.openSettings(context) }
        pointerHelp.visibility = View.INVISIBLE
        addView(pointerHelp, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        controllers.setTextColor(dev.droidtop.shell.gamepad.MenuTokens.OnSurfaceMuted.toArgb())
        controllers.textSize = 13f
        controllers.gravity = Gravity.CENTER
        controllers.setSingleLine()
        addView(controllers, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        addView(trackpad, LayoutParams(LayoutParams.MATCH_PARENT, 0, TRACKPAD_WEIGHT))
    }

    private fun slab(colors: KeyboardColors, sink: KeyboardSink): SlabView = SlabView(
        context, Slab.Kind.PLAIN, Slab.Form.FIELD, Slab.EnterPurpose.ENTER, colors,
        dev.droidtop.shell.gamepad.input.SlabCaps::label,
        // Nothing on this tab closes: the keys stay until the person leaves the tab.
        { output -> SlabSink.deliver(output, sink, onClose = {}) },
        extras = true,
        // The keys repeat here unless the destination repeats a held key itself (Desktop's seat).
        repeatHeld = { sink.repeatsAtSource },
    ).also { keys = it }

    /**
     * The keyboard gets its share (KEYBOARD_WEIGHT of what is left under the header and the fact lines), and never less
     * than the height that keeps its keys at least MIN_ROW_DP tall: on a short screen (the RP5's panel turned to
     * landscape) the trackpad gets only what is left, down to nothing, rather than the keys being cut off
     * (Droidtop/tracker#567).
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val slab = keys
        val params = slab?.layoutParams as? LayoutParams
        if (slab == null || params == null || MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        params.height = 0
        params.weight = KEYBOARD_WEIGHT
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val least = SlabView.minimumHeight(resources.displayMetrics.density, extras = true)
        if (slab.measuredHeight < least) {
            params.height = least
            params.weight = 0f
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    /** Re-reads the screens and the controllers off the main thread, then names the target. */
    private fun refreshFacts() {
        val app = context.applicationContext
        dev.droidtop.runtime.util.AppScopes.io.launch {
            val outputs = runCatching { dev.droidtop.runtime.DisplayOutputRepository(app).currentOutputsSnapshot() }.getOrDefault(emptyList())
            val names = runCatching { dev.droidtop.runtime.ScreenNaming.names(app, outputs) }.getOrDefault(emptyMap())
            screens = outputs.map { InputTarget.Screen(it.androidDisplayId, it.uniqueId, names[it.androidDisplayId] ?: it.name) }
            val pads = runCatching { Controllers.read(app) }.getOrDefault(emptyList())
            post {
                controllers.text = Controllers.line(pads)?.let { "Controllers: $it" }.orEmpty()
                controllers.visibility = if (pads.isEmpty()) View.INVISIBLE else View.VISIBLE
                syncTarget()
                syncPointerHelp()
                status.text = statusText()
            }
        }
    }

    private fun targetId(): Int? = InputTarget.displayId(
        own = display?.displayId,
        shell = ForegroundShell.current()?.window?.decorView?.display?.displayId,
        screens = screens,
        pinned = dev.droidtop.library.settings.CompanionPrefs.settings.value.inputPin,
    )

    private fun syncTarget() {
        val desktop = mode == SecondaryDisplayContent.Mode.DESKTOP
        val pinned = dev.droidtop.library.settings.CompanionPrefs.settings.value.inputPin
        val id = targetId()
        val screen = screens.firstOrNull { it.id == id }
        target.text = InputTarget.label(desktop, screen, pinned != null && screen?.uniqueId == pinned)
        pin.visibility = if (desktop || screen?.uniqueId == null) View.INVISIBLE else View.VISIBLE
        pin.text = if (pinned != null) "Unpin" else "Pin"
        pin.contentDescription = if (pinned != null) "Stop typing only to ${screen?.name}" else "Always type to ${screen?.name}"
    }

    /** Pins the screen Input types to now, or lets it follow the shell again. */
    private fun togglePin() {
        val app = context.applicationContext
        val pinned = dev.droidtop.library.settings.CompanionPrefs.settings.value.inputPin
        val next = if (pinned != null) null else screens.firstOrNull { it.id == targetId() }?.uniqueId
        dev.droidtop.runtime.util.AppScopes.io.launch {
            dev.droidtop.library.settings.CompanionPrefs.setInputPin(app, next)
            post { syncTarget() }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Built on attach rather than cached, so a desktop session that
        // connected after this view was created is picked up, and one that
        // went away leaves no sink pointing at a dead bridge.
        val settings = dev.droidtop.library.settings.CompanionPrefs.settings.value.trackpad
        trackpad.engine = TrackpadGestureEngine(trackpadOutput(), trackpadConfig(settings))
        status.text = statusText()
        refreshFacts()
        syncImePicker()
        syncImeSuppression()
        syncPointerHelp()
        dev.droidtop.runtime.util.AppScopes.io.launch {
            elevated = runCatching { dev.droidtop.runtime.tasks.TaskManager.shell.capabilities().shellCommand }.getOrDefault(false)
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        syncImePicker()
        syncImeSuppression()
    }

    private var countedForIme = false

    /**
     * While this tab is on screen the input method does not also put its own view up, so there are never two keyboards
     * ([SecondScreenKeyboard.setAttached]). It follows window visibility, not attachment, because a stopped Activity's
     * views stay attached (Droidtop/tracker#156).
     */
    private fun syncImeSuppression() {
        val shouldCount = isAttachedToWindow && windowVisibility == View.VISIBLE
        if (shouldCount == countedForIme) return
        countedForIme = shouldCount
        SecondScreenKeyboard.setAttached(shouldCount)
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        syncImePicker()
        syncPointerHelp()
        status.text = statusText()
        if (hasWindowFocus) refreshFacts()
    }

    private fun syncImePicker() {
        imePicker.visibility = if (
            mode != SecondaryDisplayContent.Mode.DESKTOP && !SecondScreenKeyboard.imeRunning &&
                !AccessibilityKeyboard.connected && KeyboardTargets.companion.value == null
        ) View.VISIBLE else View.INVISIBLE
    }

    override fun onDetachedFromWindow() {
        if (countedForIme) {
            countedForIme = false
            SecondScreenKeyboard.setAttached(false)
        }
        trackpad.engine = null
        pointerSink?.cancel()
        PointerCursor.hide()
        super.onDetachedFromWindow()
    }

    /** The display droidtop's shell is drawn on, when it is in front. */
    private fun shellDisplayId(): Int? = ForegroundShell.current()?.window?.decorView?.display?.displayId

    /**
     * What the trackpad drives outside Desktop right now: droidtop's own selection while the shell is what the target
     * screen shows (pointer and focus are one selection there), else the game's mouse while droidtop's own game screen
     * is there (Droidtop/tracker#565), else a pointer on that screen when droidtop's accessibility service or the helper
     * app's shell (Droidtop/tracker#811) can play touches on it, else nothing.
     */
    private fun currentRoute(): TrackpadRoute {
        val id = targetId()
        return TrackpadRoute.of(
            shellOnTarget = id != null && shellDisplayId() == id,
            pointerReachable = id != null && AccessibilityPointer.canPoint(id),
            gameOnTarget = id != null && gamePointer()?.displayId == id,
        )
    }

    /** The pointer of droidtop's own game screen while one runs. */
    private fun gamePointer(): dev.droidtop.library.OwnGameScreens.Pointer? = dev.droidtop.library.OwnGameScreens.current?.pointer

    private fun syncPointerHelp() {
        val missing = mode != SecondaryDisplayContent.Mode.DESKTOP && targetId() != null && currentRoute() == TrackpadRoute.UNAVAILABLE
        pointerHelp.visibility = if (missing) View.VISIBLE else View.INVISIBLE
        pointerHelp.text = if (AccessibilityKeyboard.connected) "Turn the accessibility service off and on" else "Turn on the pointer"
    }

    /** The trackpad outside Desktop: the shell's selection or a pointer on the target screen, chosen as a gesture starts. */
    private fun routedOutput(): TrackpadOutput {
        val settings = dev.droidtop.library.settings.CompanionPrefs.settings.value.trackpad
        val focus = FocusNavTrackpadSink(emit = ForegroundShell::send)
        val pointer = PointerTrackpadSink(
            injector = AccessibilityPointer.injector(context) { targetId() },
            screenPx = { targetId()?.let { AccessibilityPointer.screenSize(context, it) } },
            userSpeed = trackpadSpeed(settings),
            naturalScroll = settings.naturalScroll,
        )
        pointerSink = pointer
        val game = GamePointerTrackpadSink(::gamePointer, userSpeed = trackpadSpeed(settings), naturalScroll = settings.naturalScroll)
        var lastTarget: Int? = null
        return RoutedTrackpadOutput {
            val id = targetId()
            if (id != lastTarget) {
                lastTarget = id
                pointer.reset()
            }
            val route = currentRoute()
            if (route != lastRoute) {
                lastRoute = route
                post {
                    syncPointerHelp()
                    status.text = statusText()
                }
            }
            when (route) {
                TrackpadRoute.FOCUS -> focus
                TrackpadRoute.GAME -> game
                TrackpadRoute.POINTER -> pointer
                TrackpadRoute.UNAVAILABLE -> null
            }
        }
    }

    private fun trackpadOutput(): TrackpadOutput {
        val session = DesktopSessionService.state.value as? DesktopSessionState.Connected
        if (mode == SecondaryDisplayContent.Mode.DESKTOP && session != null) {
            val settings = dev.droidtop.library.settings.CompanionPrefs.settings.value.trackpad
            val seatSink = SeatTrackpadSink(
                seat = InputSeats.of(session.hostBridge),
                // Gain from the DESTINATION output's width, so the same
                // hand movement crosses whatever the container renders at
                // -- not from this panel's own size.
                gainPxPerMm = session.primaryOutput.widthPx / TRACKPAD_TRAVEL_MM_PER_SCREEN_WIDTH,
                userSpeed = trackpadSpeed(settings),
                naturalScroll = settings.naturalScroll,
            )
            // Three fingers swiped sideways switch the desktop's workspace, the content following the fingers: a swipe
            // to the left brings the next one (Droidtop/tracker#426).
            return object : TrackpadOutput by seatSink {
                override fun onSwipe(leftward: Boolean) {
                    dev.droidtop.runtime.util.AppScopes.io.launch {
                        session.windows.switchWorkspace(next = leftward)
                    }
                }
            }
        }
        return routedOutput()
    }

    private fun keyboardSink(): KeyboardSink =
        if (mode == SecondaryDisplayContent.Mode.DESKTOP) {
            // Routed through DesktopInputRouter rather than at the seat
            // directly: it owns the Android-keycode-to-evdev step and the
            // held-key bookkeeping, and a second path beside it is exactly
            // the duplication :input-seat exists to prevent.
            val router = DesktopInputRouter()
            object : KeyboardSink {
                // No text channel: a compositor takes keys, not strings.
                override val takesText: Boolean = false

                // And it repeats a held key by itself, as for a lapdock's keyboard.
                override val repeatsAtSource: Boolean = false

                override fun key(androidKeyCode: Int, down: Boolean) {
                    router.seat = (DesktopSessionService.state.value as? DesktopSessionState.Connected)
                        ?.let { InputSeats.of(it.hostBridge) }
                    val now = SystemClock.uptimeMillis()
                    val action = if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
                    router.onKeyEvent(KeyEvent(now, now, action, androidKeyCode, 0))
                }
            }
        } else {
            RoutedKeyboardSink(
                displayId = { targetId() },
                elevated = { elevated },
                onNoRoute = { status.text = statusText() },
            )
        }

    /**
     * The controller's keys: into the field that asked for the companion's keyboard ([KeyboardTargets.companion],
     * SPEC 4c) while one does, else this mode's own destination.
     */
    private fun requestAwareSink(own: KeyboardSink): KeyboardSink = object : KeyboardSink {
        override val takesText: Boolean get() = own.takesText

        override val repeatsAtSource: Boolean get() = (KeyboardTargets.companion.value?.sink ?: own).repeatsAtSource

        override fun key(androidKeyCode: Int, down: Boolean) =
            (KeyboardTargets.companion.value?.sink ?: own).key(androidKeyCode, down)

        override fun text(chars: CharSequence) = (KeyboardTargets.companion.value?.sink ?: own).text(chars)
    }

    /** Whether the elevated helper can type (`input -d`), read off the main thread when the view attaches. */
    @Volatile
    private var elevated = false

    /**
     * The honest one-line description of what this surface can currently
     * do. Every state below is real and none of them is droidtop's to fix
     * silently: an IME that is not the selected one is never bound, and a
     * container that is not connected has no pointer.
     */
    private fun statusText(): String {
        if (mode == SecondaryDisplayContent.Mode.DESKTOP) {
            return if (DesktopSessionService.state.value is DesktopSessionState.Connected) {
                "Trackpad and keyboard"
            } else {
                "No desktop session"
            }
        }
        val keys = when {
            KeyboardTargets.companion.value != null -> "Typing"
            !SecondScreenKeyboard.imeRunning && !AccessibilityKeyboard.connected && !elevated -> "Keyboard off"
            !SecondScreenKeyboard.androidTargetAvailable() && !AccessibilityKeyboard.hasEditor() && !elevated -> "No text field"
            else -> null
        }
        return listOfNotNull(keys, padLine()).joinToString(" · ")
    }

    /** What the trackpad is doing outside Desktop, in a few words. */
    private fun padLine(): String = when (currentRoute()) {
        TrackpadRoute.FOCUS -> "Trackpad moves the selection"
        TrackpadRoute.GAME -> "Trackpad is the game's mouse"
        TrackpadRoute.POINTER -> "Trackpad is a pointer"
        TrackpadRoute.UNAVAILABLE ->
            if (targetId() == null) "No other screen" else "Pointer needs the accessibility service or Shizuku"
    }

    companion object {
        /** The keyboard's and the trackpad's shares of the height left under the header and the fact lines. */
        const val KEYBOARD_WEIGHT = 3f
        const val TRACKPAD_WEIGHT = 2f
    }
}

/**
 * The input surface as a composable, for `:display`'s registry -- which
 * hands a mode a `@Composable`, not a View. The View is the real thing;
 * this is only the adapter.
 */
@Composable
fun SecondScreenInputSurface(mode: SecondaryDisplayContent.Mode) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context -> SecondScreenInputView(context, mode) as View },
    )
}
