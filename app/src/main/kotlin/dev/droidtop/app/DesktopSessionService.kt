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
import dev.droidtop.runtime.ContainerRuntime
import dev.droidtop.runtime.DisplayOutput
import dev.droidtop.runtime.DisplayOutputKind
import dev.droidtop.runtime.ImageCatalogResolver
import dev.droidtop.runtime.ResolvedImage
import dev.droidtop.runtime.linux.noroot.ProotRuntime
import dev.droidtop.runtime.linux.root.CraneImageCatalogResolver
import dev.droidtop.runtime.linux.root.DroidSpacesRuntime
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
    data object Connecting : DesktopSessionState
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
 * Real orchestration (root detection, picking a [ContainerRuntime] backend,
 * creating/starting the primary container, connecting [HostBridge]) — not
 * yet verified against a live compositor or a real device (no rooted
 * device available in this environment). One known, already-documented gap
 * this can't get past regardless of code correctness: [ProotRuntime] (the
 * non-root path) is still `TODO()` throughout.
 *
 * [selectPrimaryImage] resolves the USER-CHOSEN repository (Desktop
 * setup; droidtop never auto-picks — see that method's own comment)
 * against the real registry via [ImageCatalogResolver] (docs/SPEC.md §3a's
 * "populate at runtime, don't prepopulate" model) — every catalog entry
 * (runtime-common's `known-image-repositories.json`) names a real,
 * already-published stock distro image (e.g. `library/debian`), not a
 * droidtop-maintained custom build:
 * [CompositorProvisioning] supplies the chosen distro's own package-manager
 * command, which [ContainerRuntime.createPrimary] runs once on first boot
 * to actually install a compositor into it. This is what makes §3a's "any
 * OCI image works" genuinely true for the PRIMARY role too.
 *
 * [state] is how `:shell-desktop`'s `DesktopShell` and `:app`'s
 * `MainActivity` observe the real session — wired: MainActivity collects
 * this flow and passes the live `hostBridge`/`primaryOutput` out of a
 * `Connected` state straight into `DesktopShell`, falling back to the
 * honest "no desktop session" message otherwise. What remains unproven is
 * the session itself against real hardware, not the plumbing to it.
 */
class DesktopSessionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        _stateHolder.value = DesktopSessionState.Connecting
        scope.launch { connect() }
    }

    override fun onDestroy() {
        // Real fix for a confirmed on-device leak: droidspaces child
        // processes survived past app force-stop because nothing ever
        // stopped the container -- runBlocking here is deliberate:
        // onDestroy is the last chance to reap, and scope.cancel() below
        // would kill an async attempt mid-flight.
        (_stateHolder.value as? DesktopSessionState.Connected)?.let { session ->
            kotlinx.coroutines.runBlocking {
                runCatching { session.runtime.stop(session.container) }
                    .onFailure { android.util.Log.w(TAG, "Stopping primary container on destroy failed", it) }
            }
        }
        _stateHolder.value = DesktopSessionState.Idle
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun connect() {
        android.util.Log.i(TAG, "Desktop session connecting")
        val runtime: ContainerRuntime = selectRuntime()
        android.util.Log.i(TAG, "Runtime selected: ${runtime.javaClass.simpleName}")

        if (runtime is DroidSpacesRuntime) {
            val check = runtime.checkSystemRequirements()
            if (!check.succeeded) {
                fail("droidspaces check failed: ${check.stderr.ifBlank { check.stdout }}")
                return
            }
            android.util.Log.i(TAG, "droidspaces check passed")
        }

        val primaryImage = try {
            selectPrimaryImage(CraneImageCatalogResolver(applicationContext))
        } catch (t: Throwable) {
            fail("Couldn't resolve a primary image from the catalog: ${t.message}", t)
            return
        }
        android.util.Log.i(TAG, "Primary image resolved: ${primaryImage.repository.registry}/${primaryImage.repository.repository} @ ${primaryImage.tag}")

        val provisionCommand = try {
            val repo = primaryImage.repository
            val desktopEnvironment = repo.desktopEnvironment
                ?: error("PRIMARY entry ${repo.id} has no desktopEnvironment set")
            CompositorProvisioning.installCommand(repo.os, desktopEnvironment)
                ?: error("No known compositor-install command for ${repo.os}/$desktopEnvironment")
        } catch (t: Throwable) {
            fail("Couldn't determine how to provision a compositor: ${t.message}", t)
            return
        }

        android.util.Log.i(TAG, "Creating primary container (pull + unpack can take a while on first run)")
        val primary = try {
            runtime.createPrimary(primaryImage.toRootfsImage(), provisionCommand)
        } catch (t: Throwable) {
            // Expected to fail until this has actually been run against a
            // live droidspaces container (see this class's own doc
            // comment) — a clear, attributable failure beats a silent
            // no-op.
            fail("Couldn't create the primary container: ${t.message}", t)
            return
        }
        android.util.Log.i(TAG, "Primary container created: ${primary.id}")

        try {
            runtime.start(primary)
        } catch (t: Throwable) {
            fail("Couldn't start the primary container: ${t.message}", t)
            return
        }
        android.util.Log.i(TAG, "Primary container started")

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
     * Resolves the USER-chosen primary repository (onboarding's
     * `DESKTOP_SETUP` step, or its Settings re-entry point, via
     * [DesktopSetupPrefs]) against the real registry via [resolver] — the
     * catalog is populated live, not prepopulated with pinned versions
     * (docs/SPEC.md §3a). There is deliberately NO fallback pick: droidtop
     * never chooses an image the user didn't (per direction — an earlier
     * "first PRIMARY-role entry" fallback here silently selected alpine
     * on the first live run). Within the chosen repository the registry's
     * own `latest` tag is preferred (first-listed picked `alpine:2.6`, a
     * 2015 image); a real per-tag picker is Desktop setup UI work, not
     * this method's. Returns the full [ResolvedImage] (not just a
     * [dev.droidtop.runtime.RootfsImage]) so [connect] can still see which
     * distro/desktopEnvironment was picked — needed to look up the right
     * [CompositorProvisioning] command.
     */
    private suspend fun selectPrimaryImage(resolver: ImageCatalogResolver): ResolvedImage {
        val repositories = BundledImageRepositories.load(applicationContext).repositories
        val preferredId = DesktopSetupPrefs.preferredPrimaryImageId(applicationContext)
        // The USER chooses the primary image (onboarding's Desktop setup
        // step, re-enterable from Settings) -- droidtop NEVER auto-picks
        // one. The previous "first PRIMARY-role entry in the seed list"
        // fallback was a real spec violation (per direction, and §3a's
        // whole point): it silently selected an image the user never
        // chose (alpine, on the first live run). No choice, or a stale
        // choice a catalog edit removed, now fails with guidance instead.
        val repo = repositories.firstOrNull { it.id == preferredId }
            ?: error(
                if (preferredId == null) {
                    "No desktop image chosen yet — pick one in Desktop setup (Onboarding, or Settings → Desktop)"
                } else {
                    "The chosen desktop image ('$preferredId') is no longer in the catalog — pick one in Desktop setup"
                }
            )
        val tags = resolver.listTags(repo)
        // Prefer the registry's own real "latest" convention -- taking
        // whatever tag the listing starts with picked alpine:2.6 (a 2015
        // image with long-dead package repos) on the first live run,
        // because `crane ls` returns tags in ascending registry order.
        // Every stock distro repository in the bundled seed list
        // publishes a real `latest` tag; anything without one falls back
        // to the first listed tag as before.
        val tag = tags.firstOrNull { it.equals("latest", ignoreCase = true) }
            ?: tags.firstOrNull()
            ?: error("No tags published under ${repo.registry}/${repo.repository}")
        return resolver.resolve(repo, tag)
    }

    // Backend selection lives in ContainerRuntimeFactory (shared with the
    // container manager screen) -- see its doc comment.
    private suspend fun selectRuntime(): ContainerRuntime = ContainerRuntimeFactory.select(applicationContext)

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
        return Notification.Builder(this, channelId)
            .setContentTitle("droidtop desktop")
            .setContentText("Running the shared desktop session")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .build()
    }

    companion object {
        private const val TAG = "droidtop.DesktopSession"
        private const val NOTIFICATION_ID = 1
        private val _stateHolder = MutableStateFlow<DesktopSessionState>(DesktopSessionState.Idle)

        /** Observed by DesktopShell/MainActivity instead of a null/null placeholder. */
        val state: StateFlow<DesktopSessionState> = _stateHolder.asStateFlow()
    }
}
