package dev.droidtop.shell.standard

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.android.launcher3.LauncherAppState
import com.android.launcher3.LauncherPrefChangeListener
import com.android.launcher3.LauncherPrefs
import com.android.launcher3.LauncherSettings
import com.android.launcher3.model.data.WorkspaceItemInfo
import com.android.launcher3.util.Executors
import dev.droidtop.library.settings.TaskbarPolicy

/**
 * Standard mode's taskbar for tablet-sized and larger displays (docs/SPEC.md
 * 2c "Taskbar on large screens", Droidtop/tracker#88): a strip along the
 * bottom of every such display, above whatever app is up, with the launcher's
 * pinned apps, the apps opened since it started, an Apps button that opens
 * the drawer, and Recents.
 *
 * Upstream Launcher3's taskbar is quickstep code that the system binds to
 * its own recents component, which a third-party home cannot host, so this is
 * droidtop's own component. It is an accessibility overlay window owned by
 * [app.murinelauncher.service.MurineAccessibilityService], the service the
 * gesture actions already need, so it adds no permission of its own beyond
 * that service's: `TYPE_ACCESSIBILITY_OVERLAY` needs no draw-over-apps grant.
 * From the service's events it reads only the package and activity name of the
 * window that came to the front, never window content.
 *
 * It is shown while another app is in front, and not over the home screen (the
 * hotseat there is the same pinned row, one source) nor over droidtop's own
 * screens. Reachable by touch, and by the D-pad: the Meta key or the pad's
 * Mode button moves focus into the strip, the D-pad and A act on it, B or
 * Back hands focus back to the app.
 */
class StandardTaskbar(private val service: AccessibilityService) {
    private class Entry(
        val label: String,
        val icon: Bitmap?,
        val component: ComponentName,
        val user: UserHandle,
    ) {
        val key: String get() = component.packageName
    }

    private val main = Handler(Looper.getMainLooper())
    private val displays = service.getSystemService(DisplayManager::class.java)
    private val bars = HashMap<Int, TaskbarWindow>()
    private var pinned: List<Entry> = emptyList()
    private var openKeys: List<String> = emptyList()
    private val openEntries = HashMap<String, Entry>()
    private var appInFront = false
    private var collapsed = false
    private var focusedDisplay: Int? = null

    private val prefListener = LauncherPrefChangeListener { key ->
        if (key == LauncherPrefs.TASKBAR_ENABLED.sharedPrefKey) main.post { applyServiceInfo(); renderAll() }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = renderAll()
        override fun onDisplayRemoved(displayId: Int) {
            bars.remove(displayId)?.remove()
        }
        override fun onDisplayChanged(displayId: Int) = renderAll()
    }

    fun attach() {
        LauncherPrefs.get(service).addListener(prefListener, LauncherPrefs.TASKBAR_ENABLED)
        displays.registerDisplayListener(displayListener, main)
        applyServiceInfo()
        refreshPinned()
    }

    fun detach() {
        LauncherPrefs.get(service).removeListener(prefListener, LauncherPrefs.TASKBAR_ENABLED)
        displays.unregisterDisplayListener(displayListener)
        bars.values.forEach { it.remove() }
        bars.clear()
    }

    private val enabled: Boolean get() = LauncherPrefs.TASKBAR_ENABLED.get(service)

    /**
     * The service asks for window-change events and the key filter only while
     * the taskbar is on: a person who enabled it just for the lock-screen
     * gesture is not sent anything else.
     */
    private fun applyServiceInfo() {
        val info = service.serviceInfo ?: return
        if (enabled) {
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        } else {
            info.eventTypes = 0
            info.flags = info.flags and AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS.inv()
        }
        service.serviceInfo = info
    }

    fun onEvent(event: AccessibilityEvent) {
        if (!enabled || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString() ?: return
        // Only an Activity coming to the front is an app switch; dialogs, popups,
        // the keyboard and our own overlay report window changes too.
        if (!isActivity(pkg, cls)) return
        if (pkg == service.packageName || pkg in homePackages()) {
            appInFront = false
        } else if (pkg != SYSTEM_UI) {
            appInFront = true
            val launch = service.packageManager.getLaunchIntentForPackage(pkg)?.component
            if (launch != null) {
                openKeys = TaskbarPolicy.withOpened(openKeys, pkg)
                if (pkg !in openEntries) loadOpenEntry(pkg, launch)
            }
        }
        refreshPinned()
        renderAll()
    }

    /** The Meta key or the pad's Mode button toggles focus in the strip; nothing else is taken. */
    fun onKey(event: KeyEvent): Boolean {
        if (!enabled) return false
        val trigger = event.keyCode == KeyEvent.KEYCODE_META_LEFT ||
            event.keyCode == KeyEvent.KEYCODE_META_RIGHT ||
            event.keyCode == KeyEvent.KEYCODE_BUTTON_MODE
        if (!trigger) return false
        if (event.action == KeyEvent.ACTION_UP) {
            if (focusedDisplay != null) endFocus() else beginFocus()
        }
        return true
    }

    private fun beginFocus() {
        val id = bars.keys.minOrNull() ?: return
        focusedDisplay = id
        bars[id]?.setFocusable(true)
    }

    private fun endFocus() {
        focusedDisplay?.let { bars[it]?.setFocusable(false) }
        focusedDisplay = null
    }

    private fun isActivity(pkg: String, cls: String): Boolean = try {
        service.packageManager.getActivityInfo(ComponentName(pkg, cls), 0)
        true
    } catch (e: Exception) {
        false
    }

    private fun homePackages(): Set<String> {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return service.packageManager.queryIntentActivities(home, 0).map { it.activityInfo.packageName }.toSet()
    }

    /** The launcher's hotseat apps, read from its own model, so pinned is one list on the home screen and here. */
    private fun refreshPinned() {
        LauncherAppState.getInstance(service).model.enqueueModelUpdateTask { _, dataModel, _ ->
            val found = ArrayList<WorkspaceItemInfo>()
            for (item in dataModel.itemsIdMap) {
                if (item is WorkspaceItemInfo &&
                    item.container == LauncherSettings.Favorites.CONTAINER_HOTSEAT &&
                    item.itemType == LauncherSettings.Favorites.ITEM_TYPE_APPLICATION &&
                    item.targetComponent != null
                ) {
                    found.add(item)
                }
            }
            found.sortBy { it.screenId }
            val entries = found.map {
                Entry(it.title.toString(), it.bitmap.icon, it.targetComponent!!, it.user)
            }
            main.post {
                pinned = entries
                renderAll()
            }
        }
    }

    private fun loadOpenEntry(pkg: String, component: ComponentName) {
        Executors.THREAD_POOL_EXECUTOR.execute {
            val launcherApps = service.getSystemService(LauncherApps::class.java)
            val activity = launcherApps.getActivityList(pkg, Process.myUserHandle()).firstOrNull()
            val icon = activity?.getIcon(0)?.let { toBitmap(it) }
            val label = activity?.label?.toString() ?: pkg
            main.post {
                openEntries[pkg] = Entry(label, icon, component, Process.myUserHandle())
                renderAll()
            }
        }
    }

    private fun toBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) return drawable.bitmap
        val size = (ICON_DP * service.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        return bitmap
    }

    private fun renderAll() {
        val wanted = HashMap<Int, Display>()
        if (enabled && appInFront && HomeRolePrefs.isDroidtopHome(service)) {
            for (display in displays.displays) {
                if (display.state == Display.STATE_OFF) continue
                if (display.flags and Display.FLAG_PRIVATE != 0) continue
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                display.getRealMetrics(metrics)
                val smallestDp = (minOf(metrics.widthPixels, metrics.heightPixels) / metrics.density).toInt()
                if (TaskbarPolicy.shownOnDisplay(smallestDp, display.displayId == Display.DEFAULT_DISPLAY)) {
                    wanted[display.displayId] = display
                }
            }
        }
        bars.keys.filter { it !in wanted }.forEach { bars.remove(it)?.remove() }
        focusedDisplay?.let { if (it !in wanted) focusedDisplay = null }
        val running = openKeys.toSet()
        val open = openKeys.mapNotNull { openEntries[it] }.filter { entry -> pinned.none { it.key == entry.key } }
        for ((id, display) in wanted) {
            val bar = bars.getOrPut(id) { TaskbarWindow(display) }
            bar.show(pinned, open, running, collapsed)
        }
    }

    private fun launch(entry: Entry, displayId: Int) {
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
        try {
            service.getSystemService(LauncherApps::class.java)
                .startMainActivity(entry.component, entry.user, null, options)
        } catch (e: Exception) {
            Log.w(TAG, "Could not open ${entry.component}", e)
        }
        endFocus()
    }

    private fun openDrawer(displayId: Int) {
        val intent = Intent(Intent.ACTION_ALL_APPS)
            .setClassName(service.packageName, LAUNCHER_ACTIVITY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
        try {
            service.startActivity(intent, options)
        } catch (e: Exception) {
            Log.w(TAG, "Could not open the drawer", e)
        }
        endFocus()
    }

    /** One taskbar: an overlay window on one display. */
    private inner class TaskbarWindow(display: Display) {
        private val displayId = display.displayId
        private val windowContext: Context =
            if (displayId == Display.DEFAULT_DISPLAY) {
                service
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                service.createWindowContext(display, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
            } else {
                service.createDisplayContext(display)
            }
        private val windowManager = windowContext.getSystemService(WindowManager::class.java)
        private val density = windowContext.resources.displayMetrics.density
        private val root = TaskbarRoot(windowContext)
        private val params = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            NOT_FOCUSABLE_FLAGS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            title = "droidtop taskbar"
        }
        private var added = false

        fun show(pinned: List<Entry>, open: List<Entry>, running: Set<String>, collapsed: Boolean) {
            root.removeAllViews()
            if (collapsed) {
                params.width = ViewGroup.LayoutParams.WRAP_CONTENT
                params.gravity = Gravity.BOTTOM or Gravity.END
                root.setBackgroundColor(BAR_COLOR)
                root.addView(textButton("Show taskbar") {
                    this@StandardTaskbar.collapsed = false
                    renderAll()
                })
            } else {
                params.width = ViewGroup.LayoutParams.MATCH_PARENT
                params.gravity = Gravity.BOTTOM or Gravity.START
                root.setBackgroundColor(BAR_COLOR)
                root.addView(buildBar(pinned, open, running))
            }
            try {
                if (added) windowManager.updateViewLayout(root, params) else windowManager.addView(root, params)
                added = true
            } catch (e: Exception) {
                Log.w(TAG, "Could not draw the taskbar on display $displayId", e)
                added = false
            }
        }

        fun remove() {
            if (!added) return
            try {
                windowManager.removeView(root)
            } catch (e: Exception) {
                Log.w(TAG, "Could not remove the taskbar from display $displayId", e)
            }
            added = false
        }

        fun setFocusable(focusable: Boolean) {
            params.flags = if (focusable) FOCUSABLE_FLAGS else NOT_FOCUSABLE_FLAGS
            if (added) windowManager.updateViewLayout(root, params)
            if (focusable) {
                root.post { root.firstFocusable()?.requestFocus() }
            } else {
                root.clearFocus()
            }
        }

        private fun buildBar(pinned: List<Entry>, open: List<Entry>, running: Set<String>): View {
            val bar = LinearLayout(windowContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(4), dp(8), dp(4))
            }
            bar.addView(textButton("Apps") { openDrawer(displayId) })
            val apps = LinearLayout(windowContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            pinned.forEach { apps.addView(appButton(it, it.key in running, onLong = null)) }
            if (pinned.isNotEmpty() && open.isNotEmpty()) apps.addView(divider())
            open.forEach { entry ->
                apps.addView(
                    appButton(entry, running = true) {
                        openKeys = TaskbarPolicy.without(openKeys, entry.key)
                        openEntries.remove(entry.key)
                        renderAll()
                    },
                )
            }
            val scroller = HorizontalScrollView(windowContext).apply {
                isHorizontalScrollBarEnabled = false
                addView(apps)
            }
            bar.addView(scroller, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            bar.addView(textButton("Recents") {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
            })
            bar.addView(textButton("Hide") {
                this@StandardTaskbar.collapsed = true
                endFocus()
                renderAll()
            })
            return bar
        }

        private fun appButton(entry: Entry, running: Boolean, onLong: (() -> Unit)?): View {
            val button = FrameLayout(windowContext).apply {
                minimumWidth = dp(48)
                minimumHeight = dp(48)
                isFocusable = true
                isClickable = true
                contentDescription = entry.label
                background = focusRing()
                setOnClickListener { launch(entry, displayId) }
                if (onLong != null) {
                    setOnLongClickListener {
                        onLong()
                        true
                    }
                }
            }
            val icon = ImageView(windowContext).apply { setImageBitmap(entry.icon) }
            button.addView(icon, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER))
            if (running) {
                val dot = View(windowContext).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(ACCENT_COLOR)
                    }
                }
                button.addView(dot, FrameLayout.LayoutParams(dp(5), dp(5), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
            }
            return button
        }

        private fun textButton(label: String, onClick: () -> Unit): View = TextView(windowContext).apply {
            text = label
            setTextColor(TEXT_COLOR)
            gravity = Gravity.CENTER
            minHeight = dp(48)
            setPadding(dp(12), 0, dp(12), 0)
            isFocusable = true
            isClickable = true
            background = focusRing()
            setOnClickListener { onClick() }
        }

        private fun divider(): View = View(windowContext).apply {
            setBackgroundColor(DIVIDER_COLOR)
            layoutParams = LinearLayout.LayoutParams(dp(1), dp(28)).apply { setMargins(dp(6), 0, dp(6), 0) }
        }

        private fun focusRing(): Drawable = StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_focused),
                GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setStroke(dp(2), ACCENT_COLOR)
                    setColor(FOCUS_FILL)
                },
            )
            addState(
                intArrayOf(android.R.attr.state_pressed),
                GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(FOCUS_FILL)
                },
            )
        }

        private fun dp(value: Int): Int = (value * density).toInt()
    }

    /** The window's root view: clears the navigation bar, and hands focus back on B or Back. */
    private inner class TaskbarRoot(context: Context) : FrameLayout(context) {
        init {
            setOnApplyWindowInsetsListener { _, insets ->
                @Suppress("DEPRECATION")
                setPadding(0, 0, 0, insets.systemWindowInsetBottom)
                insets
            }
        }

        fun firstFocusable(): View? {
            val found = ArrayList<View>()
            addFocusables(found, View.FOCUS_RIGHT)
            return found.firstOrNull()
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            val leave = event.keyCode == KeyEvent.KEYCODE_BACK ||
                event.keyCode == KeyEvent.KEYCODE_BUTTON_B ||
                event.keyCode == KeyEvent.KEYCODE_ESCAPE
            if (leave) {
                if (event.action == KeyEvent.ACTION_UP) endFocus()
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
            super.onWindowFocusChanged(hasWindowFocus)
            if (!hasWindowFocus && focusedDisplay != null) main.post { endFocus() }
        }
    }

    private companion object {
        const val TAG = "StandardTaskbar"
        const val ICON_DP = 32
        const val SYSTEM_UI = "com.android.systemui"
        const val LAUNCHER_ACTIVITY = "com.android.launcher3.Launcher"
        const val BAR_COLOR = 0xE61F2124.toInt()
        const val TEXT_COLOR = 0xFFFFFFFF.toInt()
        const val ACCENT_COLOR = 0xFF8AB4F8.toInt()
        const val DIVIDER_COLOR = 0x55FFFFFF
        const val FOCUS_FILL = 0x33FFFFFF
        const val NOT_FOCUSABLE_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        const val FOCUSABLE_FLAGS = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
    }
}
