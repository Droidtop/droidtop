package dev.droidtop.app

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import dev.droidtop.library.settings.Mode
import dev.droidtop.library.settings.Modes

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
        applyGaming(app)
        applyDesktop(app)
    }

    private fun applyGaming(app: Application) {
        val enabled = Modes.isEnabled(Mode.GAMING)
        // The Quick Menu's Notifications tab is the only reader of the
        // listener, and a NotificationListenerService is bound by the
        // SYSTEM whenever access is granted -- no Activity of ours is
        // involved, so the only way to not run it is to not offer the
        // component at all.
        setComponentEnabled(app, DroidtopNotificationListener::class.java.name, enabled)
        if (!enabled) return
        // Warm the platforms-database cache off the main thread: the
        // synchronous label lookups (GamepadShell's group labels, the
        // second-screen companion) read PlatformsDatabase.builtInsOrEmpty,
        // which serves only what this load put in the cache. Launch
        // resolution does its own loading and does not need this.
        Thread {
            runCatching { dev.droidtop.library.consoles.PlatformsDatabase.builtIns(app) }
        }.start()
        SecondaryDisplayRegistrations.registerGaming()
        ensureGamenative(app)
    }

    private fun applyDesktop(app: Application) {
        if (!Modes.isEnabled(Mode.DESKTOP)) return
        SecondaryDisplayRegistrations.registerDesktop()
        ensureGamenative(app)
    }

    /**
     * The vendored gamenative backbone's process bootstrap: preferences,
     * the download service, Steam prerequisites, the container migration
     * and the container-file preload, PostHog, and a native library
     * preload. It is the heaviest thing droidtop starts, it reaches the
     * network, and neither Launcher mode nor a bare library scan needs
     * any of it.
     *
     * Both Gaming (the PC surface) and Desktop (containers and Wine) do,
     * so it is not one mode's alone. It is also reached from the PC launch
     * path, which is shared core: a Windows game launched with both modes
     * off still needs the backbone, and gets it here rather than through a
     * second init path.
     *
     * The vendored tree compiles into `:runtime-windows` and only there,
     * so the call goes through that module's own
     * [dev.droidtop.runtime.windows.WindowsBackbone] rather than naming an
     * `app.gamenative` class `:app` cannot see.
     */
    fun ensureGamenative(context: Context) {
        dev.droidtop.runtime.windows.WindowsBackbone.ensureStarted(context)
    }

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
