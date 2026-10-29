package dev.droidtop.app

import android.app.ActivityOptions
import android.app.Application
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.droidtop.runtime.DisplayOutputRepository
import dev.droidtop.shell.standard.OnboardingGate
import dev.droidtop.runtime.ScreenNames
import dev.droidtop.runtime.ScreenNaming
import dev.droidtop.runtime.ScreenRole
import dev.droidtop.runtime.ScreenRoles
import dev.droidtop.shell.gamepad.hosted.HostedCursor
import dev.droidtop.shell.gamepad.hosted.HostedListSheet
import dev.droidtop.shell.gamepad.hosted.HostedRow
import java.util.concurrent.Executors

/**
 * The one question a new screen gets (docs/SPEC.md 4c, "What each screen is for", Droidtop/tracker#776): a screen
 * nobody has answered for, connected while droidtop runs or found at its start, is asked about once, on the screen the
 * menus are on: "<name, size> connected. Use it as: Main screen / Companion / Mirror / Nothing". The suggestion comes
 * from what the screens report ([ScreenRoles.suggest]) and is only where the cursor starts; nothing is applied
 * without the answer. One question at a time, every mode, main process only. A question closed without an answer is
 * not asked again while droidtop runs; the screen keeps nothing of droidtop's until it is answered here or in
 * Settings > Displays. Not while setup runs: its Your screens step asks the same.
 */
internal object ScreenRoleAsk {
    private const val TAG = "droidtop.ScreenRoles"
    private lateinit var app: Application
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "screen-role-ask").apply { isDaemon = true } }

    /** Screens asked about while droidtop runs (by stable id), answered or not. Touched on [worker] only. */
    private val asked = mutableSetOf<String>()

    @Volatile
    private var showing = false

    fun install(application: Application) {
        val main = Build.VERSION.SDK_INT < 28 || Application.getProcessName() == application.packageName
        if (!main) return
        app = application
        val displays = application.getSystemService(DisplayManager::class.java) ?: return
        displays.registerDisplayListener(
            object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) = check()
                override fun onDisplayRemoved(displayId: Int) = Unit
                override fun onDisplayChanged(displayId: Int) = Unit
            },
            Handler(Looper.getMainLooper()),
        )
        check()
    }

    /** Asks about the next unanswered screen, if no question is open. Off the main thread. */
    fun check() {
        worker.execute {
            if (showing || !OnboardingGate.isComplete(app)) return@execute
            val outputs = DisplayOutputRepository(app).currentOutputsSnapshot()
            val arrangement = ScreenRoles.arrangement(app, outputs)
            val next = arrangement.unanswered.asSequence()
                .mapNotNull { id -> outputs.firstOrNull { it.androidDisplayId == id } }
                .firstOrNull { it.uniqueId !in asked } ?: return@execute
            val main = outputs.firstOrNull { it.androidDisplayId == arrangement.mainId } ?: return@execute
            asked += next.uniqueId
            val name = ScreenNaming.names(app, outputs)[next.androidDisplayId] ?: next.name.ifBlank { ScreenNames.EXTERNAL }
            val suggested = ScreenRoles.suggest(ScreenRoles.sizeOf(next), ScreenRoles.sizeOf(main))
            showing = true
            runCatching {
                app.startActivity(
                    ScreenRoleActivity.intent(app, next.uniqueId, "$name, ${next.nativeWidthPx}x${next.nativeHeightPx}", suggested),
                    ActivityOptions.makeBasic().setLaunchDisplayId(arrangement.mainId).toBundle(),
                )
            }.onFailure {
                showing = false
                Log.w(TAG, "could not ask about display ${next.androidDisplayId}", it)
            }
        }
    }

    /** The question closed, answered ([role]) or not; the next unanswered screen is asked about. */
    fun closed(context: Context, uniqueId: String, role: ScreenRole?) {
        worker.execute {
            if (role != null) ScreenRoles.set(context, uniqueId, role)
            showing = false
            check()
        }
    }
}

/** The question itself: the shell's hosted list over whatever is on the screen, pad and touch alike. */
class ScreenRoleActivity : ComponentActivity() {
    private var answered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uniqueId = intent.getStringExtra(EXTRA_ID)
        if (uniqueId == null) {
            finish()
            return
        }
        val label = intent.getStringExtra(EXTRA_LABEL).orEmpty()
        val suggested = ScreenRole.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_SUGGESTED) }
        val roles = ScreenRole.entries
        val cursor = HostedCursor().apply { index = roles.indexOf(suggested).coerceAtLeast(0) }
        setContent {
            dev.droidtop.app.ui.DroidtopTheme(gamingThemed = dev.droidtop.app.ui.rememberGamingThemed()) {
                HostedListSheet(
                    title = "$label connected. Use it as",
                    rows = roles.map { role ->
                        HostedRow(
                            key = role.name,
                            title = role.label,
                            subtitle = whatItDoes(role),
                            value = if (role == suggested) "Suggested" else null,
                            onSelect = {
                                answered = true
                                ScreenRoleAsk.closed(applicationContext, uniqueId, role)
                                finish()
                            },
                        )
                    },
                    onClose = { finish() },
                    selectLabel = "Choose",
                    cursor = cursor,
                )
            }
        }
    }

    override fun onDestroy() {
        // Closed without an answer: the next screen, if any, is asked about; this one waits for Settings > Displays.
        if (!answered && isFinishing) intent.getStringExtra(EXTRA_ID)?.let { ScreenRoleAsk.closed(applicationContext, it, null) }
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_ID = "dev.droidtop.app.extra.SCREEN_ID"
        private const val EXTRA_LABEL = "dev.droidtop.app.extra.SCREEN_LABEL"
        private const val EXTRA_SUGGESTED = "dev.droidtop.app.extra.SCREEN_SUGGESTED"

        fun intent(context: Context, uniqueId: String, label: String, suggested: ScreenRole): Intent =
            Intent(context, ScreenRoleActivity::class.java)
                .putExtra(EXTRA_ID, uniqueId)
                .putExtra(EXTRA_LABEL, label)
                .putExtra(EXTRA_SUGGESTED, suggested.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        private fun whatItDoes(role: ScreenRole): String = when (role) {
            ScreenRole.MAIN -> "droidtop's menus move there; this screen gets the companion"
            ScreenRole.COMPANION -> "The companion shows there; the menus stay here"
            ScreenRole.MIRROR -> "droidtop leaves it to Android, which shows the same picture"
            ScreenRole.NOTHING -> "droidtop keeps it dark"
        }
    }
}
