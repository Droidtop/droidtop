package dev.droidtop.library

import android.annotation.SuppressLint
import android.content.Context
import android.os.PowerManager
import android.util.Log
import dev.droidtop.library.settings.GameAwakeMode
import dev.droidtop.library.settings.GameAwakePrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Keeps the screen on while a game runs and its window is the one in front (docs/SPEC.md 7f, "Sleep and
 * return to game"): the Steam Deck's model, where the console never sleeps on its own during a game and
 * the person sleeps it with the power button. droidtop cannot hold the screen on for another app's window
 * with a window flag, so this is a platform wake lock, held only while all of these are true and released
 * the moment any stops being true:
 *
 * - the setting is [GameAwakeMode.NEVER_SLEEP];
 * - [LaunchDisplay.running] names a game;
 * - the shell is not the window in front on the display the game runs on ([shellInFront]).
 *
 * The shell reports where it is; nothing here polls the task list. A game that ended without droidtop
 * hearing of it is noticed by Android's force-stopped flag, checked every [CHECK_MS] while the lock is held.
 */
object GameWakeLock {
    private const val TAG = "droidtop.GameWakeLock"
    private const val CHECK_MS = 30_000L

    /** The display the shell's window is in front on, or null when it is not the window in front anywhere. */
    private val shellFront = MutableStateFlow<Int?>(null)

    /** Called by the shell's activity as it gains or loses the front. */
    fun shellInFront(displayId: Int?) {
        shellFront.value = displayId
    }

    /** The rule, pure: whether the lock is wanted for these inputs. */
    fun shouldHold(mode: GameAwakeMode, gameRunning: Boolean, gameDisplayId: Int?, shellFrontDisplayId: Int?): Boolean {
        if (mode != GameAwakeMode.NEVER_SLEEP || !gameRunning) return false
        // The shell in front on the game's own display hides the game. On another display (a dual-screen
        // handheld with the game on the addon) the game is still the window being played.
        val covered = shellFrontDisplayId != null && (gameDisplayId == null || gameDisplayId == shellFrontDisplayId)
        return !covered
    }

    private var started = false

    /** Starts watching, once per process. */
    @Synchronized
    fun install(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            val power = app.getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val lock = power.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "droidtop:game").apply { setReferenceCounted(false) }
            combine(GameAwakePrefs.changes(app), LaunchDisplay.running, LaunchDisplay.parked, shellFront) { mode, running, parked, front ->
                shouldHold(mode, running != null, parked, front)
            }
                .distinctUntilChanged()
                .collectLatest { hold ->
                    if (!hold) {
                        release(lock)
                        return@collectLatest
                    }
                    acquire(lock)
                    // Held only for a game droidtop believes is running: look for one that ended unseen.
                    while (true) {
                        delay(CHECK_MS)
                        if (LaunchDisplay.isRunningPackageForceStopped(app)) LaunchDisplay.clearRunning()
                    }
                }
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun acquire(lock: PowerManager.WakeLock) {
        if (!lock.isHeld) lock.acquire()
        Log.i(TAG, "held")
    }

    private fun release(lock: PowerManager.WakeLock) {
        if (lock.isHeld) lock.release()
        Log.i(TAG, "released")
    }
}
