package dev.droidtop.app

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import dev.droidtop.library.settings.ModeGate
import dev.droidtop.library.settings.ModePiece
import dev.droidtop.library.settings.Modes
import kotlinx.coroutines.launch

/**
 * The one place that turns "which modes are enabled" into "what actually
 * starts" (docs/SPEC.md, "Modes and what each contributes"). Called once
 * from [DroidtopApplication.onCreate] and again by [Modes] whenever a mode
 * is switched on or off, so a mode enabled mid-session starts its pieces
 * without a restart and a mode disabled mid-session stops contributing to
 * the next process.
 *
 * What is NOT here is as deliberate as what is: the shared core -- the
 * library and its launch resolution ([LibraryCore]), the settings
 * catalogs, the self-updater -- runs in every mode, because every mode
 * builds on it.
 */
object ModeStartup {

    fun install(app: Application) {
        Modes.onChanged { apply(app) }
        apply(app)
    }

    private fun apply(app: Application) {
        val live = ModeGate.piecesToStart(Modes.enabled)
        fun on(piece: ModePiece) = piece in live

        // Launcher mode is the Launcher3 fork. Its Activities already
        // follow the HOME role (HomeRolePrefs enables exactly one of them,
        // and that is what Modes reads), but four of its components are
        // started by the SYSTEM rather than by any Activity of ours, so
        // with the fork switched off they still ran launcher code -- see
        // LAUNCHER_SYSTEM_COMPONENTS below.
        LAUNCHER_SYSTEM_COMPONENTS.forEach {
            setComponentEnabled(app, it, on(ModePiece.LAUNCHER_SYSTEM_COMPONENTS))
        }
        // Standard's own second screen (docs/SPEC.md 4c) is registered
        // once, unconditionally, and never unregistered: unlike Gaming's
        // and Desktop's, it is not gated on a ModePiece at all -- the
        // platform only ever places :display's SecondaryDisplayActivity
        // (which reads this registry) while droidtop holds Home, so an
        // unregistered Standard content would only ever matter in a state
        // nothing can show it in anyway.
        SecondaryDisplayRegistrations.registerStandard()

        // The Quick Menu's Notifications tab is the only reader of the
        // listener, and a NotificationListenerService is bound by the
        // SYSTEM whenever access is granted -- no Activity of ours is
        // involved, so the only way to not run it is to not offer the
        // component at all.
        setComponentEnabled(
            app,
            DroidtopNotificationListener::class.java.name,
            on(ModePiece.GAMING_NOTIFICATION_LISTENER),
        )
        // A mode switched off mid-session takes its surfaces with it, not
        // only its next process.
        SecondaryDisplayRegistrations.setGaming(on(ModePiece.GAMING_SECOND_SCREEN))
        SecondaryDisplayRegistrations.setDesktop(on(ModePiece.DESKTOP_SECOND_SCREEN))

        // Warm the platforms-database cache off the main thread: the
        // synchronous label lookups (GamepadShell's group labels, the
        // second-screen companion) read PlatformsDatabase.builtInsOrEmpty,
        // which serves only what this load put in the cache. Launch
        // resolution does its own loading and does not need this.
        if (on(ModePiece.GAMING_PLATFORMS_DATABASE) && !platformsWarmed) {
            platformsWarmed = true
            Thread {
                runCatching { dev.droidtop.library.consoles.PlatformsDatabase.builtIns(app) }
            }.start()
        }

        // Other apps' "Open with" lists offer droidtop only while Desktop
        // mode, which runs what it opens, is on.
        setComponentEnabled(app, OpenWithActivity::class.java.name, on(ModePiece.DESKTOP_OPEN_WITH))

        // Desktop switched off takes its running session with it: the
        // compositor and everything on the desktop, not only the next start.
        if (!on(ModePiece.DESKTOP_SESSION)) DesktopSessionService.stop(app)

        if (!on(ModePiece.DESKTOP_VPN)) dev.droidtop.app.vpn.DroidtopVpnService.stop(app)
        setComponentEnabled(app, dev.droidtop.app.vpn.DroidtopVpnService::class.java.name, on(ModePiece.DESKTOP_VPN))

        if (on(ModePiece.WINDOWS_BACKBONE)) ensureWindowsRuntime(app)
    }

    /**
     * The warm pass is one-way: once the cache is loaded it stays loaded,
     * so re-applying after a mode switch must not start a second thread
     * doing work already done.
     */
    @Volatile
    private var platformsWarmed = false

    /**
     * The Windows runtime's process start (its preferences and storage
     * paths, read once in the background; [dev.droidtop.runtime.windows.WindowsBackbone]),
     * which neither Launcher mode nor a bare library scan needs
     * ([ModePiece.WINDOWS_BACKBONE]). Also reached from the PC launch path,
     * which is shared core; starting it twice is a no-op.
     */
    fun ensureWindowsRuntime(context: Context) {
        dev.droidtop.runtime.windows.WindowsBackbone.ensureStarted(context)
        carryOverSteam(context)
    }

    private val steamCarryOverStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Once, after the backbone is up: the Steam sign-in and the Steam
     * installs GameNative kept come across to droidtop's own Steam
     * (docs/SPEC.md 7g, "Stores"). GameNative kept them in the preferences
     * the Windows runtime reads, which is why this waits on it and never
     * starts it for this alone. Off the main thread.
     */
    private fun carryOverSteam(context: Context) {
        val app = context.applicationContext
        if (!steamCarryOverStarted.compareAndSet(false, true)) return
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching {
                if (dev.droidtop.stores.steam.SteamCarryOver.done(app)) return@runCatching
                dev.droidtop.runtime.windows.WindowsBackbone.awaitReady(app)
                val old = dev.droidtop.runtime.windows.GameNativeSteamSignIn
                val session = old.session()?.let {
                    dev.droidtop.stores.steam.SteamCarryOver.Session(it.accountName, it.refreshToken, it.steamId64, it.clientId, it.cellId)
                }
                dev.droidtop.stores.steam.SteamCarryOver.bringAcross(app, session, old.installRoots(app))
            }.onFailure { android.util.Log.w("droidtop.ModeStartup", "Bringing GameNative's Steam across failed", it) }
        }
    }

    /**
     * The Launcher3 fork's four system-started components
     * ([ModePiece.LAUNCHER_SYSTEM_COMPONENTS]): the home screen's
     * notification-dots listener and the fork's screen-off accessibility
     * service, both bound by the SYSTEM whenever their grant exists; the
     * session-commit receiver, which fires on EVERY app install on the
     * device and hands the work to the launcher model executor to place an
     * icon on a workspace nobody is showing; and the widgets-restored
     * receiver, the same for a restored widget.
     *
     * Switching a system-bound service off takes its grant with it:
     * re-enabling Launcher mode means granting notification access or the
     * accessibility service again. That is the honest price of "a disabled
     * mode runs no code" for components no Activity of ours can gate.
     *
     * Deliberately NOT here: `ScreenOffAdminReceiver`, because disabling an
     * active device-admin component is not ours to do behind the user's
     * back; the exported Activities (`AddItemActivity`, the launcher's
     * `SettingsActivity`), because the system only routes to them through
     * the HOME role the mode already follows; and the launcher's
     * ContentProviders, whose `onCreate` is a bare `return true`
     * (LauncherProvider.java) -- disabling them would break other apps'
     * reads for no measurable gain.
     */
    private val LAUNCHER_SYSTEM_COMPONENTS = listOf(
        "com.android.launcher3.notification.NotificationListener",
        "app.murinelauncher.service.MurineAccessibilityService",
        "com.android.launcher3.SessionCommitReceiver",
        "com.android.launcher3.AppWidgetsRestoredReceiver",
    )

    private fun setComponentEnabled(context: Context, className: String, enabled: Boolean) {
        val wanted = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        val component = ComponentName(context.packageName, className)
        val pm = context.packageManager
        // Writing the same state again is a disk write and a package
        // manager broadcast, on every process start; read first.
        if (runCatching { pm.getComponentEnabledSetting(component) }.getOrNull() == wanted) return
        runCatching {
            pm.setComponentEnabledSetting(component, wanted, PackageManager.DONT_KILL_APP)
        }.onFailure {
            android.util.Log.w("droidtop.ModeStartup", "Could not set $className enabled=$enabled", it)
        }
    }
}
