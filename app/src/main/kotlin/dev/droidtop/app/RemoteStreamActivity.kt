package dev.droidtop.app

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import dev.droidtop.library.computers.ComputerLaunch
import dev.windowcast.AudioPlayer
import dev.windowcast.ConnectRequest
import dev.windowcast.Event
import dev.windowcast.Gamepads
import dev.windowcast.Input
import dev.windowcast.InputAdapter
import dev.windowcast.Letterbox
import dev.windowcast.SessionHolder
import dev.windowcast.SessionListener
import dev.windowcast.WantedWindow
import dev.windowcast.WatchSet
import dev.windowcast.WindowDecoder
import dev.windowcast.WindowInfo
import dev.windowcast.WindowRenderer
import dev.windowcast.WindowRenderers
import dev.windowcast.WindowcastClient
import dev.windowcast.WindowcastSession
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Shows one window of a computer's windowcast host, full screen (docs/SPEC.md 7a, "Remote streaming is
 * windowcast's library"). droidtop writes no streaming code: this is a client of windowcast's Android library,
 * making the same calls as the reference viewer: [SessionHolder] starts the session, [WantedWindow] waits for the
 * window a launch named, [WatchSet] says what showing it means, [WindowRenderers] draws it, and [InputAdapter] and
 * [Gamepads] turn touches, keys and pads into the host's input.
 *
 * It is asked for by [ComputerLaunch.openWindowcast] (a game the computer started
 * with `stream: windowcast`) and by opening a computer's application in the library. The request is the computer's
 * device id, its windowcast address and, optionally, part of a window's title. A computer this device has not
 * paired with windowcast asks for the PIN its windowcast shows.
 *
 * Not built: switching a window's carrier while watching (the library's automatic switching is turned off, so the
 * window stays on the carrier it started on), the host's pointer, popups and extra windows, the soft keyboard, and
 * starting a program that is not running.
 */
class RemoteStreamActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())

    /** Library calls block: they never run on the main thread. */
    private val worker = Executors.newSingleThreadExecutor()
    private val inputWorker = Executors.newSingleThreadExecutor()
    private val sessions = SessionHolder<WindowcastSession>()
    private val watchSet = WatchSet()
    private val gamepads = Gamepads { send(it) }

    private lateinit var stage: FrameLayout
    private lateinit var surface: SurfaceView
    private lateinit var status: TextView

    private var client: WindowcastClient? = null
    private var request: ConnectRequest? = null
    private var wanted: WantedWindow? = null
    private var watching: WindowInfo? = null
    private var renderer: WindowRenderer? = null
    private var audio: AudioPlayer? = null
    private var dialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val asked = ConnectRequest.of(
            intent?.getStringExtra(ComputerLaunch.EXTRA_HOST_ID),
            intent?.getStringExtra(ComputerLaunch.EXTRA_ADDRESS),
            intent?.getStringExtra(ComputerLaunch.EXTRA_WINDOW),
        )
        if (asked == null) {
            end("No computer to show a window of")
            return
        }
        request = asked
        wanted = asked.window?.let { WantedWindow(it, System.currentTimeMillis()) }

        surface = SurfaceView(this).apply {
            visibility = View.GONE
            setOnTouchListener { v, event -> touch(v, event) }
        }
        status = TextView(this).apply {
            setTextColor(android.graphics.Color.WHITE)
            setPadding(24, 16, 24, 16)
            textSize = 16f
        }
        stage = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            addView(surface, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.CENTER))
            addView(status, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))
            // A new screen size (rotation, split screen) changes the room the picture is fitted in.
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitSurface() }
        }
        setContentView(stage)

        client = try {
            WindowcastClient(File(filesDir, "windowcast"))
        } catch (e: IOException) {
            Log.w(TAG, "no identity", e)
            end("Could not open this device's windowcast identity: ${e.message}")
            return
        }
        connect(null)
    }

    /** Connects to the computer, with the PIN it showed when this device is not paired with it yet. */
    private fun connect(pin: String?) {
        val c = client ?: return
        val asked = request ?: return
        say("Connecting to ${asked.address}…")
        worker.execute {
            try {
                sessions.start(listener(), opened = { s ->
                    // Switching a window's carrier while watching is not built here: it stays on the one it started on.
                    s.autoSwitch(false)
                }) {
                    val s = c.connect(asked.address, pin)
                    if (s.hostId != asked.hostId) {
                        s.close()
                        throw IOException("it answered as another computer, not the one asked for")
                    }
                    s
                }
            } catch (e: IOException) {
                Log.w(TAG, "connect failed", e)
                main.post { connectFailed(pin != null, e.message) }
            }
        }
    }

    /** The first try has no PIN and works for a computer already paired; a failure then asks for the PIN. */
    private fun connectFailed(hadPin: Boolean, reason: String?) {
        if (hadPin) {
            end("Could not connect: $reason")
            return
        }
        val field = EditText(this).apply {
            hint = "PIN"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        dialog = AlertDialog.Builder(this)
            .setTitle("Pair with this computer")
            .setMessage("Could not connect ($reason). If this device has not been paired with that computer's windowcast, enter the PIN it shows.")
            .setView(field)
            .setPositiveButton("Pair") { _, _ -> connect(field.text.toString().trim().ifEmpty { null }) }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    /** What this screen does with each event of the session, on the main thread. */
    private fun listener() = object : SessionListener {
        override fun onWindows(windows: List<WindowInfo>) {
            main.post { windowsListed(windows) }
        }

        override fun onStreamStarted(event: Event.StreamStarted) {
            main.post { if (event.window == watching?.id) render(event) }
        }

        override fun onStreamRefused(event: Event.StreamRefused) {
            main.post { if (event.window == watching?.id) end("The computer would not stream it: ${event.reason}") }
        }

        override fun onStreamStopped(event: Event.StreamStopped) {
            main.post { if (event.window == watching?.id) end("The stream ended") }
        }

        override fun onClosed() {
            main.post { end("Disconnected") }
        }
    }

    private fun windowsListed(list: List<WindowInfo>) {
        if (watching != null) return
        val want = wanted
        if (want == null) {
            choose(list)
            return
        }
        when (val step = want.next(list, System.currentTimeMillis())) {
            is WantedWindow.Step.Found -> {
                wanted = null
                watch(step.window)
            }
            WantedWindow.Step.GiveUp -> {
                wanted = null
                say("No window titled “${want.title}” appeared.")
                choose(list)
            }
            is WantedWindow.Step.Wait -> {
                say("Waiting for a window titled “${want.title}”…")
                main.postDelayed({
                    val s = sessions.session
                    if (wanted === want && s != null && s.isOpen) worker.execute { s.requestWindows() }
                }, step.retryInMs)
            }
        }
    }

    /** No window was asked for, or none appeared: the person picks one of the computer's. */
    private fun choose(list: List<WindowInfo>) {
        val capturable = list.filter { it.width > 0 && it.height > 0 }
        dialog?.dismiss()
        when (capturable.size) {
            0 -> say("This computer has no window to show.")
            1 -> watch(capturable.single())
            else -> dialog = AlertDialog.Builder(this)
                .setTitle("Show which window?")
                .setItems(capturable.map { it.title.ifEmpty { it.appId } }.toTypedArray()) { _, which -> watch(capturable[which]) }
                .setNegativeButton("Cancel") { _, _ -> finish() }
                .setCancelable(false)
                .show()
        }
    }

    private fun watch(window: WindowInfo) {
        if (watchSet.begin(window.id) != WatchSet.Plan.PRIMARY) return
        watching = window
        say("Starting ${window.title.ifEmpty { window.appId }}…")
        val codecs = WindowDecoder.decodableCodecs()
        worker.execute {
            try {
                sessions.session?.startWindow(window.id, codecs)
            } catch (e: IOException) {
                Log.w(TAG, "start failed", e)
                main.post { end("Could not start the window: ${e.message}") }
            }
        }
    }

    /** The stream is on its way: draw it on the surface as soon as there is one. */
    private fun render(event: Event.StreamStarted) {
        val window = watching ?: return
        val s = sessions.session ?: return
        surface.visibility = View.VISIBLE
        fitSurface()
        val begin = {
            renderer = WindowRenderers.create(s, window, event.backend, event.codec, surface.holder) { p ->
                if (p.ended) main.post { end("The stream ended" + (p.error?.let { ": $it" } ?: "")) }
            }.also { it.start() }
            audio = AudioPlayer(s, event.window).also { it.start() }
            status.visibility = View.GONE
        }
        if (surface.holder.surface?.isValid == true) {
            begin()
        } else {
            surface.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    holder.removeCallback(this)
                    begin()
                }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                override fun surfaceDestroyed(holder: SurfaceHolder) {}
            })
        }
    }

    /** The picture keeps the window's shape, centred, with bars around it. */
    private fun fitSurface() {
        if (!::surface.isInitialized) return
        val w = watching
        val fit = if (w == null) null else Letterbox.fit(w.width, w.height, stage.width, stage.height)
        val params = FrameLayout.LayoutParams(fit?.first ?: MATCH_PARENT, fit?.second ?: MATCH_PARENT, Gravity.CENTER)
        if (surface.layoutParams.let { it.width != params.width || it.height != params.height }) surface.layoutParams = params
    }

    private fun say(text: String) {
        status.text = text
        status.visibility = View.VISIBLE
    }

    /** Says why the screen is over, and leaves when the person has read it. */
    private fun end(message: String) {
        if (isFinishing) return
        dialog?.dismiss()
        dialog = AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton("OK") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun send(input: Input) {
        inputWorker.execute { sessions.session?.send(input) }
    }

    private fun touch(view: View, event: MotionEvent): Boolean {
        val window = watching ?: return false
        for (input in InputAdapter.touches(window.id, view, event)) send(input)
        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (watching == null || event.keyCode == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
        if (gamepads.onKey(event)) return true
        val (input, handled) = InputAdapter.key(event)
        input?.let { send(it) }
        return handled || super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (watching != null && gamepads.onMotion(event)) return true
        return super.dispatchGenericMotionEvent(event)
    }

    override fun onDestroy() {
        dialog?.dismiss()
        main.removeCallbacksAndMessages(null)
        val shown = renderer
        val sound = audio
        val window = watching
        val c = client
        renderer = null
        audio = null
        // The host is told every pad is gone, then the input queue drains before the session closes.
        gamepads.releaseAll()
        inputWorker.shutdown()
        worker.shutdown()
        // Stopping the stream and closing the session wait on the host and on the reader, so not on the main thread.
        Thread({
            shown?.stop()
            sound?.stop()
            inputWorker.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)
            runCatching { window?.let { sessions.session?.stopWindow(it.id) } }
            sessions.end()
            c?.close()
        }, "remote-stream-close").start()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "RemoteStream"
    }
}
