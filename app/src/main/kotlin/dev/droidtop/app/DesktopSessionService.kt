package dev.droidtop.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import dev.droidtop.hostbridge.HostBridge
import dev.droidtop.runtime.BundledImageRepositories
import dev.droidtop.runtime.CompositorProvisioning
import dev.droidtop.runtime.Container
import dev.droidtop.runtime.ContainerRole
import dev.droidtop.runtime.ContainerRuntime
import dev.droidtop.runtime.CraneImageCatalogResolver
import dev.droidtop.runtime.DisplayOutput
import dev.droidtop.runtime.DisplayOutputKind
import dev.droidtop.runtime.KnownImageRepository
import dev.droidtop.runtime.PrimaryProvisioning
import dev.droidtop.runtime.resolveCurrent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface DesktopSessionState {
    data object Idle : DesktopSessionState

    /** [detail] is the latest thing the container reported while booting (a first boot installs the desktop), if any. */
    data class Connecting(val detail: String? = null) : DesktopSessionState
    data class Connected(
        val hostBridge: HostBridge,
        val primaryOutput: DisplayOutput,
        /** The running primary container + the runtime that created it — what a native Linux game needs to run as a Wayland client sharing this same desktop (Windows games go through `:runtime-windows`'s own `WineEngine` and need neither). */
        val runtime: ContainerRuntime,
        val container: Container,
    ) : DesktopSessionState
    data class Failed(val message: String) : DesktopSessionState
}

/**
 * Owns the primary container's lifecycle and the host-bridge connection to
 * it — kept alive independent of whether MainActivity (or any other
 * Activity presenting a DisplayOutput) is in the foreground, since the
 * "desktop" should keep running when, e.g., the user is only interacting
 * via the second-screen trackpad.
 *
 * The session, in order: pick the backend ([ContainerRuntimeFactory]: root
 * gives droidspaces, anything else proot), prove the device can run it
 * ([ContainerRuntime.checkSystemRequirements]), find or create the PRIMARY
 * container from the user's Desktop-setup choice, boot it (a first boot
 * provisions the compositor, see [CompositorProvisioning]; progress lands
 * in [DesktopSessionState.Connecting.detail]), and connect [HostBridge] to
 * its compositor's socket.
 *
 * The primary container is made once and then reused, session after
 * session: it is the user's desktop, with whatever they installed in it.
 * It is only recreated when the user has since chosen a different image in
 * Desktop setup. Re-resolving the catalog's `latest` on every start (the
 * previous behaviour) meant a registry publishing a new `latest` silently
 * replaced the whole container on the next session.
 *
 * [state] is how `:shell-desktop`'s `DesktopShell` and `:app`'s
 * `MainActivity` observe the session.
 *
 * The session is Desktop mode's (docs/SPEC.md §3, "Stopping is a stop").
 * It ends when the person leaves Desktop for Gaming, turns Desktop mode
 * off, stops the primary in Containers or presses the notification's
 * Stop, and ending it stops the primary container with everything running
 * on the desktop, whether it had finished booting or not. It is not
 * sticky: a process Android killed does not come back as a desktop nobody
 * opened.
 */
class DesktopSessionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    /** The primary being booted, before the session is [DesktopSessionState.Connected]; stopped with the service all the same. */
    @Volatile
    private var booting: Pair<ContainerRuntime, Container>? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopSelf()
        return START_NOT_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        sessionScope = scope
        _stateHolder.value = DesktopSessionState.Connecting()
        scope.launch { connect() }
    }

    override fun onDestroy() {
        // Real fix for a confirmed on-device leak: droidspaces child
        // processes survived past app force-stop because nothing ever
        // stopped the container. The stop is an `su` process plus a
        // droidspaces stop, seconds long, so it runs in [reaperScope],
        // which outlives this service, rather than blocking the main
        // thread here (audit 2026-09-24, C4: an ANR risk); scope.cancel()
        // below cannot reach it. A session started before it finishes
        // waits for it (see connect).
        // A primary still booting is stopped too: cancelling the boot's
        // wait alone would leave its compositor coming up with nobody to
        // stop it.
        val connected = _stateHolder.value as? DesktopSessionState.Connected
        connected?.hostBridge?.disconnect()
        val live = connected?.let { it.runtime to it.container } ?: booting
        live?.let { (runtime, container) ->
            pendingStop = reaperScope.launch {
                runCatching { runtime.stop(container) }
                    .onFailure { android.util.Log.w(TAG, "Stopping primary container on destroy failed", it) }
            }
        }
        booting = null
        _stateHolder.value = DesktopSessionState.Idle
        sessionScope = null
        // The seat belongs to the bridge that has just gone away. Dropped
        // here so no surface -- the desktop viewport or the second screen
        // -- can be handed one pointing at a dead connection.
        dev.droidtop.input.InputSeats.clear()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun connect() {
        android.util.Log.i(TAG, "Desktop session connecting")
        // The last session's container stop, if it is still running.
        pendingStop?.join()
        val runtime: ContainerRuntime = ContainerRuntimeFactory.select(applicationContext)
        android.util.Log.i(TAG, "Runtime selected: ${runtime.javaClass.simpleName}")

        val check = runtime.checkSystemRequirements()
        if (!check.succeeded) {
            fail("This device can't run ${runtime.javaClass.simpleName}: ${check.stderr.ifBlank { check.stdout }.trim()}")
            return
        }
        android.util.Log.i(TAG, "${runtime.javaClass.simpleName} system check passed")

        val repository = try {
            chosenRepository()
        } catch (t: Throwable) {
            fail(t.message ?: "No desktop image chosen", t)
            return
        }
        val provisioning = try {
            provisioningFor(repository)
        } catch (t: Throwable) {
            fail("Couldn't determine how to provision a compositor: ${t.message}", t)
            return
        }

        val primary = try {
            findOrCreatePrimary(runtime, repository, provisioning)
        } catch (t: Throwable) {
            fail("Couldn't create the primary container: ${t.message}", t)
            return
        }
        android.util.Log.i(TAG, "Primary container: ${primary.id}")

        booting = runtime to primary
        try {
            runtime.start(primary, provisioning) { line ->
                _stateHolder.value = DesktopSessionState.Connecting(line)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // The session was stopped while booting (the notification's
            // Stop, leaving Desktop): that is not a failure to report.
            throw e
        } catch (t: Throwable) {
            fail("Couldn't start the primary container: ${t.message}", t)
            return
        }
        android.util.Log.i(TAG, "Primary container started")
        logContainerIdentity(runtime, primary)

        val hostBridge = HostBridge()
        val socketPath = runtime.primaryWaylandSocketPath()
        if (!hostBridge.connect(socketPath)) {
            fail("HostBridge couldn't connect to $socketPath")
            return
        }
        android.util.Log.i(TAG, "HostBridge connected to $socketPath — desktop session up")

        _stateHolder.value = DesktopSessionState.Connected(hostBridge, primaryDisplayOutput(), runtime, primary)
    }

    /**
     * The existing PRIMARY when it was made from the image the user still
     * has chosen; otherwise a new one from that image, resolved against the
     * live registry now (docs/SPEC.md §3a).
     */
    private suspend fun findOrCreatePrimary(
        runtime: ContainerRuntime,
        repository: KnownImageRepository,
        provisioning: PrimaryProvisioning,
    ): Container {
        val existing = runtime.listContainers().firstOrNull { it.container.role == ContainerRole.PRIMARY }?.container
        if (existing != null && DesktopSetupPrefs.primaryCreatedFrom(applicationContext) == repository.id) {
            android.util.Log.i(TAG, "Reusing the primary container made from ${repository.id}")
            return existing
        }
        _stateHolder.value = DesktopSessionState.Connecting("Downloading ${repository.repository}")
        val image = CraneImageCatalogResolver(applicationContext).resolveCurrent(repository)
        android.util.Log.i(
            TAG,
            "Creating the primary container from ${image.repository.registry}/${image.repository.repository}:${image.tag} " +
                "(pull + unpack can take a while)",
        )
        val created = runtime.createPrimary(image.toRootfsImage(), provisioning)
        DesktopSetupPrefs.setPrimaryCreatedFrom(applicationContext, repository.id)
        return created
    }

    /**
     * The USER-chosen primary repository (onboarding's `DESKTOP_SETUP`
     * step, or its Settings re-entry point, via [DesktopSetupPrefs]).
     * There is deliberately NO fallback pick: droidtop never chooses an
     * image the user didn't (per direction — an earlier "first PRIMARY-role
     * entry" fallback silently selected alpine on the first live run). No
     * choice, or a stale choice a catalog edit removed, fails with guidance.
     */
    private fun chosenRepository(): KnownImageRepository {
        val repositories = BundledImageRepositories.load(applicationContext).repositories
        val preferredId = DesktopSetupPrefs.preferredPrimaryImageId(applicationContext)
        return repositories.firstOrNull { it.id == preferredId }
            ?: error(
                if (preferredId == null) {
                    "No desktop image chosen yet — pick one in Desktop setup (Onboarding, or Settings → Desktop)"
                } else {
                    "The chosen desktop image ('$preferredId') is no longer in the catalog — pick one in Desktop setup"
                }
            )
    }

    private fun provisioningFor(repository: KnownImageRepository): PrimaryProvisioning {
        val desktopEnvironment = repository.desktopEnvironment
            ?: error("PRIMARY entry ${repository.id} has no desktopEnvironment set")
        return CompositorProvisioning.plan(repository.os, desktopEnvironment, DesktopSetupPrefs.printing(applicationContext))
            ?: error("No known compositor provisioning for ${repository.os}/$desktopEnvironment")
    }

    /**
     * One exec into the booted container, logged: which system is actually
     * running, and proof that [ContainerRuntime.exec] works before anything
     * (the terminal, the Start menu) depends on it.
     */
    private suspend fun logContainerIdentity(runtime: ContainerRuntime, primary: Container) {
        val probe = runCatching {
            runtime.exec(primary, listOf("/bin/sh", "-c", "uname -m; . /etc/os-release && echo \"\$PRETTY_NAME\"; id -u"))
        }
        probe.onSuccess { result ->
            android.util.Log.i(
                TAG,
                "Container identity (exec exit ${result.exitCode}): ${result.stdout.lines().filter { it.isNotBlank() }.joinToString(" | ")}" +
                    if (result.stderr.isNotBlank()) " stderr: ${result.stderr.trim()}" else "",
            )
        }.onFailure { android.util.Log.w(TAG, "Container identity exec failed", it) }
    }

    private fun primaryDisplayOutput(): DisplayOutput {
        @Suppress("DEPRECATION") // minSdk 26; WindowMetrics needs API 30+
        val metrics = resources.displayMetrics
        return DisplayOutput(
            id = "primary",
            androidDisplayId = android.view.Display.DEFAULT_DISPLAY,
            kind = DisplayOutputKind.PRIMARY_SCREEN,
            widthPx = metrics.widthPixels,
            heightPx = metrics.heightPixels,
        )
    }

    // Failures used to render ONLY in the Desktop shell's own UI text --
    // debugging the first real on-device pipeline run meant screenshotting
    // the shell to read error strings. Every failure now also lands in
    // logcat with its stack trace.
    private fun fail(message: String, cause: Throwable? = null) {
        android.util.Log.e(TAG, "Desktop session failed: $message", cause)
        _stateHolder.value = DesktopSessionState.Failed(message)
    }

    private fun buildNotification(): Notification {
        val channelId = "desktop_session"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(channelId, "Desktop session", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val stop = android.app.PendingIntent.getService(
            this,
            0,
            Intent(this, DesktopSessionService::class.java).setAction(ACTION_STOP),
            android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, channelId)
            .setContentTitle("droidtop desktop")
            .setContentText("Running the shared desktop session")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    companion object {
        private const val TAG = "droidtop.DesktopSession"
        private const val ACTION_STOP = "dev.droidtop.app.action.STOP_DESKTOP_SESSION"

        /** Starts the desktop session (a no-op while one runs). */
        fun start(context: android.content.Context) {
            context.startForegroundService(Intent(context, DesktopSessionService::class.java))
        }

        /** Ends the desktop session, stopping the primary container and everything on the desktop. */
        fun stop(context: android.content.Context) {
            context.stopService(Intent(context, DesktopSessionService::class.java))
        }

        /** Where a destroyed service's container stop runs; process-lifetime, never cancelled. */
        private val reaperScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        @Volatile
        private var pendingStop: kotlinx.coroutines.Job? = null
        private const val NOTIFICATION_ID = 1
        private val _stateHolder = MutableStateFlow<DesktopSessionState>(DesktopSessionState.Idle)

        /** Observed by DesktopShell/MainActivity instead of a null/null placeholder. */
        val state: StateFlow<DesktopSessionState> = _stateHolder.asStateFlow()

        @Volatile
        private var sessionScope: CoroutineScope? = null

        private val _launchFailure = MutableStateFlow<String?>(null)

        /** Why the last program started with [runInPrimary] did not run (or exited badly), until dismissed. */
        val launchFailure: StateFlow<String?> = _launchFailure.asStateFlow()

        fun dismissLaunchFailure() {
            _launchFailure.value = null
        }

        /** A launch that failed somewhere other than [runInPrimary] (the Start menu's library games), for the same banner. */
        fun reportLaunchFailure(message: String) {
            _launchFailure.value = message
        }

        /**
         * Runs a program in the desktop session's containers (the primary
         * handed to [block], or a sibling through the same runtime, which
         * is how "Open with droidtop" installs a package) for as long as
         * the desktop session lives. A program's `exec` lasts as long as its
         * window, and ending that wait ends the program (under proot the
         * session is killed), so the wait belongs to the session, not to a
         * screen: waiting in the desktop shell's own composition would have
         * killed every open window on a rotation. [block] returns a failure
         * message or null; a message lands in [launchFailure]. Returns false
         * when there is no connected session to run in.
         */
        fun runInPrimary(block: suspend (ContainerRuntime, Container) -> String?): Boolean {
            val session = _stateHolder.value as? DesktopSessionState.Connected ?: return false
            val scope = sessionScope ?: return false
            _launchFailure.value = null
            scope.launch {
                val failure = try {
                    block(session.runtime, session.container)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    android.util.Log.w(TAG, "Running a program in the primary container failed", t)
                    t.message ?: t.toString()
                }
                if (failure != null) _launchFailure.value = failure
            }
            return true
        }
    }
}
