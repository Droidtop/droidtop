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
import dev.droidtop.runtime.DesktopScreenChoice
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
 *
 * Desktop's question goes through the same flow and the same sheet (docs/SPEC.md 2b, "The desktop on a second screen",
 * Droidtop/tracker#908 step D5): while the desktop runs, a screen other than its own with a role but no Desktop answer
 * is asked once, on the desktop's screen, whether the desktop extends to it or it keeps what it has
 * ([ScreenRoles.desktopToAsk], [ScreenRoles.suggestDesktop]). Role questions come first; still one at a time.
 */
internal object ScreenRoleAsk {
    private const val TAG = "droidtop.ScreenRoles"
    private lateinit var app: Application
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "screen-role-ask").apply { isDaemon = true } }

    /** Screens asked about while droidtop runs (by stable id), answered or not. Touched on [worker] only. */
    private val asked = mutableSetOf<String>()

    /** Screens Desktop asked about while droidtop runs (by stable id), answered or not. Touched on [worker] only. */
    private val askedDesktop = mutableSetOf<String>()

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

    /** Asks about the next unanswered screen, if no question is open: its role first, then Desktop's. Off the main thread. */
    fun check() {
        worker.execute {
            if (showing || !OnboardingGate.isComplete(app)) return@execute
            val outputs = DisplayOutputRepository(app).currentOutputsSnapshot()
            val arrangement = ScreenRoles.arrangement(app, outputs)
            val next = arrangement.unanswered.asSequence()
                .mapNotNull { id -> outputs.firstOrNull { it.androidDisplayId == id } }
                .firstOrNull { it.uniqueId !in asked }
            if (next == null) {
                askDesktop(outputs, arrangement)
                return@execute
            }
            val main = outputs.firstOrNull { it.androidDisplayId == arrangement.mainId } ?: return@execute
            asked += next.uniqueId
            val suggested = ScreenRoles.suggest(ScreenRoles.sizeOf(next), ScreenRoles.sizeOf(main))
            ask(
                ScreenRoleActivity.intent(app, next.uniqueId, label(outputs, next), suggested),
                arrangement.mainId,
                next.androidDisplayId,
            )
        }
    }

    /**
     * Desktop's question, on [worker]: while the desktop runs in Desktop mode, the next screen other than its own with
     * no Desktop answer ([ScreenRoles.desktopToAsk]) is asked about on the desktop's screen.
     */
    private fun askDesktop(outputs: List<dev.droidtop.runtime.DisplayOutput>, arrangement: dev.droidtop.runtime.ScreenArrangement) {
        val desktopId = DesktopScreens.desktopDisplayId() ?: return
        val facts = outputs.map(ScreenNaming::facts)
        val choices = ScreenRoles.desktopSaved(app)
        val nextFacts = ScreenRoles.desktopToAsk(facts, desktopId, choices, arrangement.unanswered, askedDesktop) ?: return
        val next = outputs.firstOrNull { it.androidDisplayId == nextFacts.displayId } ?: return
        val desktop = outputs.firstOrNull { it.androidDisplayId == desktopId } ?: return
        askedDesktop += next.uniqueId
        val suggested = ScreenRoles.suggestDesktop(ScreenRoles.sizeOf(next), ScreenRoles.sizeOf(desktop))
        ask(
            ScreenRoleActivity.desktopIntent(
                app, next.uniqueId, label(outputs, next), suggested,
                companionThere = arrangement.companionId == next.androidDisplayId,
            ),
            desktopId,
            next.androidDisplayId,
        )
    }

    /** "<name>, <width>x<height>", as the role question names a screen. */
    private fun label(outputs: List<dev.droidtop.runtime.DisplayOutput>, screen: dev.droidtop.runtime.DisplayOutput): String {
        val name = ScreenNaming.names(app, outputs)[screen.androidDisplayId] ?: screen.name.ifBlank { ScreenNames.EXTERNAL }
        return "$name, ${screen.nativeWidthPx}x${screen.nativeHeightPx}"
    }

    private fun ask(intent: Intent, onDisplay: Int, aboutDisplay: Int) {
        showing = true
        runCatching {
            app.startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(onDisplay).toBundle())
        }.onFailure {
            showing = false
            Log.w(TAG, "could not ask about display $aboutDisplay", it)
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

    /** Desktop's question closed, answered ([choice]) or not; the next question, if any, is asked. */
    fun desktopClosed(context: Context, uniqueId: String, choice: DesktopScreenChoice?) {
        worker.execute {
            if (choice != null) ScreenRoles.setDesktop(context, uniqueId, choice)
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
        val desktop = intent.getBooleanExtra(EXTRA_DESKTOP, false)
        val rows = if (desktop) desktopRows(uniqueId) else roleRows(uniqueId)
        val cursor = HostedCursor().apply { index = rows.indexOfFirst { it.value != null }.coerceAtLeast(0) }
        setContent {
            dev.droidtop.app.ui.DroidtopTheme(gamingThemed = dev.droidtop.app.ui.rememberGamingThemed()) {
                HostedListSheet(
                    title = if (desktop) "The desktop can use $label too" else "$label connected. Use it as",
                    rows = rows,
                    onClose = { finish() },
                    selectLabel = "Choose",
                    cursor = cursor,
                )
            }
        }
    }

    private fun roleRows(uniqueId: String): List<HostedRow> {
        val suggested = ScreenRole.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_SUGGESTED) }
        return ScreenRole.entries.map { role ->
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
        }
    }

    // Desktop's question (Droidtop/tracker#908 step D5): the same sheet, two answers, the suggestion marked.
    private fun desktopRows(uniqueId: String): List<HostedRow> {
        val suggested = DesktopScreenChoice.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_SUGGESTED) }
        val companionThere = intent.getBooleanExtra(EXTRA_COMPANION_THERE, false)
        return DesktopScreenChoice.entries.map { choice ->
            HostedRow(
                key = choice.name,
                title = when (choice) {
                    DesktopScreenChoice.EXTEND -> "Use this screen for the desktop"
                    DesktopScreenChoice.COMPANION -> if (companionThere) "Keep the companion here" else "Keep it as it is"
                },
                subtitle = when (choice) {
                    DesktopScreenChoice.EXTEND -> "Windows move across to it, with a bar of its own"
                    DesktopScreenChoice.COMPANION -> "The desktop stays on this screen only"
                },
                value = if (choice == suggested) "Suggested" else null,
                onSelect = {
                    answered = true
                    ScreenRoleAsk.desktopClosed(applicationContext, uniqueId, choice)
                    finish()
                },
            )
        }
    }

    override fun onDestroy() {
        // Closed without an answer: the next screen, if any, is asked about; this one waits for Settings > Displays.
        if (!answered && isFinishing) intent.getStringExtra(EXTRA_ID)?.let {
            if (intent.getBooleanExtra(EXTRA_DESKTOP, false)) {
                ScreenRoleAsk.desktopClosed(applicationContext, it, null)
            } else {
                ScreenRoleAsk.closed(applicationContext, it, null)
            }
        }
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_ID = "dev.droidtop.app.extra.SCREEN_ID"
        private const val EXTRA_LABEL = "dev.droidtop.app.extra.SCREEN_LABEL"
        private const val EXTRA_SUGGESTED = "dev.droidtop.app.extra.SCREEN_SUGGESTED"
        private const val EXTRA_DESKTOP = "dev.droidtop.app.extra.SCREEN_DESKTOP"
        private const val EXTRA_COMPANION_THERE = "dev.droidtop.app.extra.SCREEN_COMPANION_THERE"

        fun intent(context: Context, uniqueId: String, label: String, suggested: ScreenRole): Intent =
            Intent(context, ScreenRoleActivity::class.java)
                .putExtra(EXTRA_ID, uniqueId)
                .putExtra(EXTRA_LABEL, label)
                .putExtra(EXTRA_SUGGESTED, suggested.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /** Desktop's question about the screen [uniqueId]; [companionThere]: the companion is what it keeps. */
        fun desktopIntent(context: Context, uniqueId: String, label: String, suggested: DesktopScreenChoice, companionThere: Boolean): Intent =
            Intent(context, ScreenRoleActivity::class.java)
                .putExtra(EXTRA_ID, uniqueId)
                .putExtra(EXTRA_LABEL, label)
                .putExtra(EXTRA_SUGGESTED, suggested.name)
                .putExtra(EXTRA_DESKTOP, true)
                .putExtra(EXTRA_COMPANION_THERE, companionThere)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        private fun whatItDoes(role: ScreenRole): String = when (role) {
            ScreenRole.MAIN -> "droidtop's menus move there; this screen gets the companion"
            ScreenRole.COMPANION -> "The companion shows there; the menus stay here"
            ScreenRole.MIRROR -> "droidtop leaves it to Android, which shows the same picture"
            ScreenRole.NOTHING -> "droidtop keeps it dark"
        }
    }
}
