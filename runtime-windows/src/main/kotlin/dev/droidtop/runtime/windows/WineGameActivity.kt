package dev.droidtop.runtime.windows

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import com.winlator.container.Container
import com.winlator.container.ContainerManager
import com.winlator.inputcontrols.TouchMouse
import com.winlator.widget.TouchpadView
import com.winlator.widget.XServerRendererView
import com.winlator.widget.XServerView
import com.winlator.widget.XServerViewGL
import com.winlator.winhandler.WinHandler
import com.winlator.xserver.Keyboard
import com.winlator.xserver.ScreenInfo
import com.winlator.xserver.XServer
import java.io.File
import java.util.concurrent.Executors
import app.gamenative.PrefManager as GameNativePrefManager
import com.winlator.PrefManager as WinlatorPrefManager

/**
 * Where a Windows game is actually seen and played.
 *
 * droidtop composes its own shell through `:host-bridge`, but a Wine
 * guest does not draw Wayland surfaces -- it draws into an X server that
 * gamenative already knows how to present, through
 * [XServerView]/[XServerViewGL] and the native renderers
 * (`libvulkan_renderer.so`, `libvortekrenderer.so`, `libwinlator.so`)
 * that this module has always packaged and never constructed. This
 * Activity is the piece that was missing: it makes that view, hands it
 * to the [XServer] the guest connects to, and starts a [WineXSession]
 * against it.
 *
 * It is an Activity, and not a surface inside the shell, for a concrete
 * reason: handheld launches are placed on the launch-target display with
 * `ActivityOptions.setLaunchDisplayId`, exactly like every other launch
 * droidtop makes (`LaunchDisplay`). A view hosted inside the shell's own
 * window could only ever appear where the shell already is.
 *
 * Desktop mode is out of scope here on purpose. There, a Windows program
 * should appear as a window among others inside the container's sway
 * compositor, which is a different presentation problem with a different
 * answer; nothing in this Activity assumes it, and nothing in it should
 * be stretched to cover it.
 *
 * Input follows gamenative's own routing and adds nothing: physical
 * controllers go to [WinHandler], which is the bridge that publishes
 * XInput state into the guest; keys that no controller claimed go to the
 * X keyboard; touch goes to [TouchpadView], which drives the X pointer.
 */
class WineGameActivity : Activity() {

    private var xServer: XServer? = null
    private var rendererView: XServerRendererView? = null
    private var session: WineXSession? = null
    private var touchpadView: TouchpadView? = null
    private var touchMouse: TouchMouse? = null
    private var keyboard: Keyboard? = null
    private var winHandler: WinHandler? = null
    private var finishing = false

    private val startupExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "droidtop-wine-start")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Both preference stores are read from deep inside the vendored
        // view and renderer code, and both inits are no-ops once done.
        GameNativePrefManager.init(this)
        WinlatorPrefManager.init(this)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        goFullscreen()

        val target = intent.getStringExtra(EXTRA_TARGET)
        val containerId = intent.getStringExtra(EXTRA_CONTAINER_ID)
        val workingDir = intent.getStringExtra(EXTRA_WORKING_DIR)?.let(::File)
        val prefix = containerId?.let { id ->
            runCatching { ContainerManager(this).getContainerById(id) }.getOrNull()
        }
        if (target.isNullOrBlank() || prefix == null || workingDir == null) {
            failAndFinish("the Windows launch was missing its prefix or its target")
            return
        }

        val xServer = XServer(ScreenInfo(prefix.screenSize), false)
        this.xServer = xServer

        // virgl is OpenGL passthrough and has to be presented by the GL
        // view, which shares its EGL context with the VirGL component;
        // everything else is the Vulkan/SurfaceFlinger view, picked by
        // the prefix's own display-renderer field.
        val view: XServerRendererView = if (prefix.graphicsDriver == "virgl" ||
            prefix.displayRenderer.equals("gl", ignoreCase = true)
        ) {
            XServerViewGL(this, xServer)
        } else {
            XServerView(this, xServer, prefix.displayRenderer)
        }
        rendererView = view
        xServer.renderer = view.renderer

        // The virtual desktop `explorer` opens is the window a game lives
        // in; the shell window itself must not be presented, and the
        // game's own window is what should fill the screen.
        view.renderer.setUnviewableWMClasses("explorer.exe")
        // The WM class the renderer should blow up to fill the screen is
        // the executable's own file name. Both separators are stripped
        // because a .desktop shortcut stores a Windows path, and
        // File(...).name would keep the whole of one on Android.
        target.substringAfterLast('/').substringAfterLast('\\')
            .takeIf { it.isNotBlank() }
            ?.let { view.renderer.forceFullscreenWMClass = it }

        winHandler = WinHandler(xServer, view).also { xServer.winHandler = it }
        touchMouse = TouchMouse(xServer)
        keyboard = Keyboard(xServer)

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        root.addView(view as View, matchParent())
        // Constructed after the renderer is on the server: its own
        // constructor asks the renderer whether it is fullscreen to build
        // the touch-to-screen transform.
        val touchpad = TouchpadView(this, xServer, GameNativePrefManager.getBoolean("capture_pointer_on_external_mouse", true))
        touchpadView = touchpad
        root.addView(touchpad, matchParent())
        setContentView(root)

        val session = WineXSession(this, prefix, target, workingDir, xServer)
        this.session = session
        startupExecutor.execute {
            runCatching { session.start(::onGuestTerminated) }
                .onFailure { failure ->
                    runOnUiThread {
                        failAndFinish(failure.message ?: "the Wine environment failed to start")
                    }
                }
        }
    }

    private fun onGuestTerminated(status: Int) {
        val detail = session?.output().orEmpty()
        runOnUiThread {
            if (status != 0) {
                failAndFinish(
                    if (detail.isBlank()) "wine exited $status" else "wine exited $status: ${detail.takeLast(TOAST_DETAIL_CHARS)}",
                )
            } else {
                finish()
            }
        }
    }

    private fun failAndFinish(message: String) {
        if (finishing) return
        finishing = true
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    // ---- input -------------------------------------------------------
    // Order matches gamenative's own: a controller claims the event
    // first, because that is the only consumer that can turn it into
    // XInput state; anything left is a keyboard key for the X server.

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
            finish()
            return true
        }
        if (winHandler?.onKeyEvent(event) == true) return true
        if (keyboard?.onKeyEvent(event) == true) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (winHandler?.onGenericMotionEvent(event) == true) return true
        if (touchMouse?.onExternalMouseEvent(event) == true) return true
        return super.onGenericMotionEvent(event)
    }

    // ---- lifecycle ---------------------------------------------------

    override fun onResume() {
        super.onResume()
        goFullscreen()
        rendererView?.onResume()
        session?.onResume()
    }

    override fun onPause() {
        session?.onPause()
        rendererView?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        session?.stop()
        session = null
        startupExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun goFullscreen() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
            )
    }

    private fun matchParent() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    )

    companion object {
        private const val EXTRA_CONTAINER_ID = "dev.droidtop.wine.CONTAINER_ID"
        private const val EXTRA_TARGET = "dev.droidtop.wine.TARGET"
        private const val EXTRA_WORKING_DIR = "dev.droidtop.wine.WORKING_DIR"
        private const val TOAST_DETAIL_CHARS = 400

        /**
         * The intent a launch dispatches. Deliberately carries ids and
         * paths rather than objects: the [Container] is re-resolved here
         * from the same [ContainerManager] that owns it, so there is one
         * source of prefix state and no copy to fall out of date.
         */
        fun intent(context: Context, prefix: Container, target: String, workingDir: File): Intent =
            Intent(context, WineGameActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(EXTRA_CONTAINER_ID, prefix.id)
                putExtra(EXTRA_TARGET, target)
                putExtra(EXTRA_WORKING_DIR, workingDir.absolutePath)
            }
    }
}
