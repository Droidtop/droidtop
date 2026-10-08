package dev.droidtop.runtime.windows

import android.app.Application
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import com.winlator.container.Container
import dev.droidtop.runtime.windows.utils.StoragePaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * The Windows runtime's process start: the runtime's preferences
 * ([PrefManager], GameNative's DataStore file, read once), the storage
 * paths the folder scanner and the Steam carry-over read ([StoragePaths]),
 * and a Timber tree for the runtime's logging. What GameNative's
 * `PluviaApp.bootstrap` did besides (its Steam/store services, the
 * Hilt-built database, telemetry, power control, its container migration)
 * belonged to GameNative's app and went with the submodule (docs/SPEC.md 9).
 *
 * The first read is disk work, so it runs on a background scope; [state]
 * says when it is done and [awaitReady] suspends until then.
 * [ensureStarted] never blocks and starts the work at most once.
 */
object WindowsBackbone {

    enum class State { NOT_STARTED, STARTING, READY }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(State.NOT_STARTED)

    /** Current start progress, for a screen that wants to show it. */
    val state: StateFlow<State> = _state

    @Volatile
    private var app: Context? = null

    @JvmStatic
    fun ensureStarted(context: Context) {
        val application = context.applicationContext as? Application ?: return
        app = application
        if (!_state.compareAndSet(State.NOT_STARTED, State.STARTING)) return
        scope.launch {
            try {
                if (Timber.treeCount == 0) Timber.plant(Timber.DebugTree())
                runCatching { PrefManager.init(application) }
                    .onFailure { android.util.Log.w(TAG, "Windows runtime preferences unreadable", it) }
                runCatching { StoragePaths.init(application) }
                    .onFailure { android.util.Log.w(TAG, "Windows runtime storage paths unreadable", it) }
            } finally {
                _state.value = State.READY
            }
        }
    }

    /** Starts the runtime if needed and suspends until it is ready. */
    suspend fun awaitReady(context: Context) {
        ensureStarted(context)
        state.filter { it == State.READY }.first()
    }

    @Volatile
    private var screenSize: String? = null

    /**
     * The screen size a new prefix starts with: the closest of Winlator's
     * 4:3, 16:10 and 16:9 sizes to the built-in display's shape
     * (GameNative's `PluviaApp.getDefaultScreenSize`).
     */
    @JvmStatic
    fun defaultScreenSize(): String {
        screenSize?.let { return it }
        val result = runCatching {
            val display = app?.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
                ?: return@runCatching Container.DEFAULT_SCREEN_SIZE_16_9
            val width: Int
            val height: Int
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                width = display.mode.physicalWidth
                height = display.mode.physicalHeight
            } else {
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                display.getRealMetrics(metrics)
                width = metrics.widthPixels
                height = metrics.heightPixels
            }
            val aspect = maxOf(width, height).toFloat() / minOf(width, height).toFloat()
            when {
                aspect < 1.5f -> Container.DEFAULT_SCREEN_SIZE_4_3
                aspect < 1.7f -> Container.DEFAULT_SCREEN_SIZE_16_10
                else -> Container.DEFAULT_SCREEN_SIZE_16_9
            }
        }.getOrDefault(Container.DEFAULT_SCREEN_SIZE_16_9)
        if (app != null) screenSize = result
        return result
    }

    private const val TAG = "droidtop.WindowsRuntime"
}
