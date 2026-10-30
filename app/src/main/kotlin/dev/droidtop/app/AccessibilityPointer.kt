package dev.droidtop.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import dev.droidtop.input.GestureRunner
import dev.droidtop.input.PointerGestureQueue
import dev.droidtop.input.PointerInjector
import dev.droidtop.input.PointerOp
import kotlinx.coroutines.launch

/**
 * The companion trackpad's pointer on the main screen (docs/SPEC.md "The companion's tabs", Input; Droidtop/tracker#486
 * item 3). It needs no root: droidtop's accessibility service (the one that already types for other apps) draws the
 * cursor in an accessibility overlay and plays the touches a pointer implies with `dispatchGesture`, on any screen from
 * Android 11 (`GestureDescription.Builder.setDisplayId`) and on the default screen before. The gesture logic is
 * `:input-seat`'s [PointerGestureQueue]; this is the Android part: the runner that builds gestures, the cursor, and the
 * service's connection. Only the main thread calls in (touch events), and gesture callbacks come back on it.
 */
internal object AccessibilityPointer {
    @Volatile
    private var service: AccessibilityService? = null

    fun connect(s: AccessibilityService) {
        service = s
    }

    fun disconnect(s: AccessibilityService) {
        if (service !== s) return
        service = null
        PointerCursor.hide()
    }

    /**
     * Whether a touch can be played on [displayId] now: the service is on and may perform gestures (a build that
     * added the capability reaches an already-enabled service only when it is turned off and on), and the screen is the
     * default one or Android is 11 or later.
     */
    fun canReach(displayId: Int): Boolean {
        val s = service ?: return false
        val capable = s.serviceInfo?.capabilities?.and(AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES) ?: 0
        return capable != 0 && (displayId == Display.DEFAULT_DISPLAY || Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
    }

    /** [displayId]'s size in pixels, as a touch there is measured, or null when the screen is gone. */
    @Suppress("DEPRECATION")
    fun screenSize(context: Context, displayId: Int): Pair<Int, Int>? {
        val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(displayId) ?: return null
        val metrics = DisplayMetrics()
        display.getRealMetrics(metrics)
        return metrics.widthPixels to metrics.heightPixels
    }

    /**
     * Whether the helper app's shell can play a touch (Droidtop/tracker#811): a running `priv.shell` provider, for a
     * device where the accessibility service is off or cannot reach the screen. Cheap: no provider call.
     */
    fun shellCanPoint(): Boolean = runCatching { dev.droidtop.runtime.tasks.TaskManager.shell.capabilities().shellCommand }.getOrDefault(false)

    /** Whether a touch can be played on [displayId] now, by the accessibility service or else the helper's shell. */
    fun canPoint(displayId: Int): Boolean = canReach(displayId) || shellCanPoint()

    /** An injector for the screen [displayId] names now (null: nowhere to play a touch). */
    fun injector(context: Context, displayId: () -> Int?): PointerInjector {
        val app = context.applicationContext
        PointerCursor.fallbackContext = app
        val accessibility = PointerGestureRunner(
            service = { service },
            displayId = { displayId() },
            screenSize = { id -> screenSize(app, id) },
        )
        val shell = ShellPointerRunner(displayId = { displayId() }, screenSize = { id -> screenSize(app, id) })
        val runner = RoutedPointerRunner(accessibility, shell) { displayId()?.let(::canReach) == true }
        return PointerGestureQueue(runner) { x, y -> displayId()?.let { PointerCursor.show(it, x, y) } }
    }
}

/**
 * The accessibility service where it can reach the screen, else the helper's shell (Droidtop/tracker#811). A drag stays
 * on the route it started on until it ends, since each route keeps its own stroke.
 */
internal class RoutedPointerRunner(
    private val accessibility: GestureRunner,
    private val shell: GestureRunner,
    private val useAccessibility: () -> Boolean,
) : GestureRunner {
    private var dragOn: GestureRunner? = null

    override fun run(op: PointerOp, done: (completed: Boolean) -> Unit) {
        val runner = dragOn ?: if (useAccessibility()) accessibility else shell
        dragOn = if (op is PointerOp.DragStart || op is PointerOp.DragMove) runner else null
        runner.run(op, done)
    }
}

/**
 * Plays a [PointerOp] as the `input` shell command through the helper app ([dev.droidtop.input.ShellPointerCommands]),
 * off the main thread; the queue plays one at a time, so the commands keep their order. Answers on the main thread.
 */
internal class ShellPointerRunner(
    private val displayId: () -> Int?,
    private val screenSize: (Int) -> Pair<Int, Int>?,
) : GestureRunner {
    private val main = Handler(Looper.getMainLooper())

    override fun run(op: PointerOp, done: (completed: Boolean) -> Unit) {
        val id = displayId()
        val size = id?.let(screenSize)
        if (id == null || size == null) return done(false)
        val argv = dev.droidtop.input.ShellPointerCommands.argv(op, id, (size.first - 1).toFloat(), (size.second - 1).toFloat())
        dev.droidtop.runtime.util.AppScopes.io.launch {
            val ok = runCatching { dev.droidtop.runtime.tasks.TaskManager.shell.exec(argv) }.getOrNull()?.exit == 0
            main.post { done(ok) }
        }
    }
}

/**
 * Builds and plays a [PointerOp] as an accessibility gesture. A drag is one stroke: [PointerOp.DragStart] touches down
 * (the stroke "will continue"), each [PointerOp.DragMove] continues it to the new point, [PointerOp.DragEnd] continues it
 * to the last point and lifts.
 */
internal class PointerGestureRunner(
    private val service: () -> AccessibilityService?,
    private val displayId: () -> Int?,
    private val screenSize: (Int) -> Pair<Int, Int>?,
) : GestureRunner {
    private val main = Handler(Looper.getMainLooper())
    private var stroke: GestureDescription.StrokeDescription? = null
    private var lastX = 0f
    private var lastY = 0f

    override fun run(op: PointerOp, done: (completed: Boolean) -> Unit) {
        val s = service()
        val id = displayId()
        val size = id?.let(screenSize)
        if (s == null || id == null || size == null) {
            stroke = null
            return done(false)
        }
        val maxX = (size.first - 1).toFloat()
        val maxY = (size.second - 1).toFloat()
        fun cx(x: Float) = x.coerceIn(0f, maxX)
        fun cy(y: Float) = y.coerceIn(0f, maxY)

        var next: GestureDescription.StrokeDescription? = null
        var endX = lastX
        var endY = lastY
        val built = runCatching {
            when (op) {
                is PointerOp.Tap -> {
                    stroke = null
                    GestureDescription.StrokeDescription(point(cx(op.x), cy(op.y)), 0, TAP_MS)
                }

                is PointerOp.ContextClick -> {
                    stroke = null
                    GestureDescription.StrokeDescription(point(cx(op.x), cy(op.y)), 0, LONG_PRESS_MS)
                }

                is PointerOp.Scroll -> {
                    stroke = null
                    val x0 = cx(op.x)
                    val y0 = cy(op.y)
                    val line = Path().apply {
                        moveTo(x0, y0)
                        lineTo(cx(x0 + op.dx), cy(y0 + op.dy))
                    }
                    GestureDescription.StrokeDescription(line, 0, SWIPE_MS)
                }

                is PointerOp.DragStart -> {
                    endX = cx(op.x)
                    endY = cy(op.y)
                    GestureDescription.StrokeDescription(point(endX, endY), 0, DRAG_STEP_MS, true).also { next = it }
                }

                is PointerOp.DragMove, is PointerOp.DragEnd -> {
                    val previous = stroke ?: return done(false)
                    val (tx, ty, last) = when (op) {
                        is PointerOp.DragMove -> Triple(op.x, op.y, false)
                        is PointerOp.DragEnd -> Triple(op.x, op.y, true)
                        else -> return done(false)
                    }
                    endX = cx(tx)
                    endY = cy(ty)
                    val line = Path().apply {
                        moveTo(lastX, lastY)
                        lineTo(endX, endY)
                    }
                    previous.continueStroke(line, 0, DRAG_STEP_MS, !last).also { next = if (last) null else it }
                }
            }
        }.getOrNull() ?: run {
            // continueStroke refuses a stroke that is not continuing: the drag is over.
            stroke = null
            return done(false)
        }

        val builder = GestureDescription.Builder().addStroke(built)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) builder.setDisplayId(id)
        val sent = s.dispatchGesture(
            builder.build(),
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (op is PointerOp.DragStart || op is PointerOp.DragMove || op is PointerOp.DragEnd) {
                        stroke = next
                        lastX = endX
                        lastY = endY
                    }
                    done(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    stroke = null
                    done(false)
                }
            },
            main,
        )
        if (!sent) {
            stroke = null
            done(false)
        }
    }

    private fun point(x: Float, y: Float) = Path().apply { moveTo(x, y) }

    private companion object {
        const val TAP_MS = 40L

        /** Longer than Android's long-press timeout (500 ms), so the touch is a long press. */
        const val LONG_PRESS_MS = 650L
        const val SWIPE_MS = 150L
        const val DRAG_STEP_MS = 30L
    }
}

/**
 * The pointer's cursor: a small arrow in an accessibility overlay on the target screen, never touchable, so a touch
 * played under it reaches the app. Without the service (the shell route, Droidtop/tracker#811) it is an ordinary
 * overlay window when droidtop may draw over other apps, else there is no arrow. It moves with the pointer (one layout update a frame) and goes away after a few
 * seconds without movement and when the trackpad goes.
 */
internal object PointerCursor {
    private const val IDLE_HIDE_MS = 5000L
    private val main = Handler(Looper.getMainLooper())
    private var view: View? = null
    private var windowManager: WindowManager? = null
    private var params: WindowManager.LayoutParams? = null
    private var shownOn: Int? = null
    private var updateQueued = false
    private val hideLater = Runnable { hide() }

    /** droidtop's own context, for the overlay window drawn without the accessibility service. */
    @Volatile
    var fallbackContext: Context? = null

    fun show(displayId: Int, x: Float, y: Float) {
        if (shownOn != displayId) hide()
        if (view == null) {
            val service = AccessibilityTyping.overlayContext(displayId)
            val context = service ?: overlayWindowContext(displayId) ?: return
            val manager = context.getSystemService(WindowManager::class.java) ?: return
            val size = (CURSOR_DP * context.resources.displayMetrics.density).toInt()
            val layout = WindowManager.LayoutParams(
                size,
                size,
                if (service != null) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.TOP or Gravity.START }
            layout.x = x.toInt()
            layout.y = y.toInt()
            val cursor = CursorView(context)
            val added = runCatching { manager.addView(cursor, layout) }.isSuccess
            if (!added) return
            view = cursor
            windowManager = manager
            params = layout
            shownOn = displayId
        } else {
            params?.let {
                it.x = x.toInt()
                it.y = y.toInt()
            }
            queueUpdate()
        }
        main.removeCallbacks(hideLater)
        main.postDelayed(hideLater, IDLE_HIDE_MS)
    }

    /** [displayId]'s context for an ordinary overlay window, when droidtop may draw over other apps. */
    private fun overlayWindowContext(displayId: Int): Context? {
        val app = fallbackContext ?: return null
        if (!android.provider.Settings.canDrawOverlays(app)) return null
        val display = app.getSystemService(DisplayManager::class.java)?.getDisplay(displayId) ?: return null
        return app.createDisplayContext(display)
    }

    fun hide() {
        main.removeCallbacks(hideLater)
        val v = view
        val manager = windowManager
        view = null
        windowManager = null
        params = null
        shownOn = null
        updateQueued = false
        if (v != null && manager != null) runCatching { manager.removeView(v) }
    }

    private fun queueUpdate() {
        if (updateQueued) return
        val cursor = view ?: return
        updateQueued = true
        cursor.postOnAnimation {
            updateQueued = false
            val v = view ?: return@postOnAnimation
            val p = params ?: return@postOnAnimation
            runCatching { windowManager?.updateViewLayout(v, p) }
        }
    }

    private const val CURSOR_DP = 24

    /** A classic arrow with its tip at the view's top-left: white, outlined in black. */
    private class CursorView(context: Context) : View(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 2f * context.resources.displayMetrics.density
            strokeJoin = Paint.Join.ROUND
        }
        private val arrow = Path()

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val inset = outline.strokeWidth
            arrow.reset()
            arrow.moveTo(inset, inset)
            arrow.lineTo(inset, h * 0.82f)
            arrow.lineTo(w * 0.28f, h * 0.64f)
            arrow.lineTo(w * 0.46f, h - inset)
            arrow.lineTo(w * 0.6f, h * 0.93f)
            arrow.lineTo(w * 0.42f, h * 0.58f)
            arrow.lineTo(w * 0.72f, h * 0.58f)
            arrow.close()
            canvas.drawPath(arrow, fill)
            canvas.drawPath(arrow, outline)
        }
    }
}
