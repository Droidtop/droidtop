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
import dev.droidtop.runtime.ImageCatalogRole
import dev.droidtop.runtime.ImageCachePolicy
import dev.droidtop.runtime.ResolvedImage
import dev.droidtop.runtime.linux.noroot.ProotRuntime
import dev.droidtop.runtime.linux.root.CraneImageCatalogResolver
import dev.droidtop.runtime.linux.root.CraneRootfsPuller
import dev.droidtop.runtime.linux.root.DroidSpacesRuntime
import dev.droidtop.runtime.linux.root.FileImageCache
import dev.droidtop.runtime.linux.root.RootProcess
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
        /** The running primary container + the runtime that created it — what `:runtime-windows`'s `WineSession`/`PcGameProvider` need to launch a Windows game as a Wayland client sharing this same desktop. */
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
 * [selectPrimaryImage] resolves the bundled seed list's PRIMARY entry
 * against the real registry via [ImageCatalogResolver] (docs/SPEC.md §3a's
 * "populate at runtime, don't prepopulate" model) — every PRIMARY entry
 * (runtime-common's `known-image-repositories.json`) now names a real,
 * already-published stock distro image (the same ones the SIBLING entries
 * use, e.g. `library/debian`), not a droidtop-maintained custom build:
 * [CompositorProvisioning] supplies the chosen distro's own package-manager
 * command, which [ContainerRuntime.createPrimary] runs once on first boot
 * to actually install a compositor into it. This is what makes §3a's "any
 * OCI image works" genuinely true for the PRIMARY role too.
 *
 * [state] is how `:shell-desktop`'s `DesktopShell`/`:app`'s `MainActivity`
 * are meant to observe the real session instead of the `null`/`null`
 * placeholder they currently pass in — that wiring isn't done yet either.
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
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun connect() {
        val runtime: ContainerRuntime = selectRuntime()

        if (runtime is DroidSpacesRuntime) {
            val check = runtime.checkSystemRequirements()
            if (!check.succeeded) {
                fail("droidspaces check failed: ${check.stderr.ifBlank { check.stdout }}")
                return
            }
        }

        val primaryImage = try {
            selectPrimaryImage(CraneImageCatalogResolver(applicationContext))
        } catch (t: Throwable) {
            fail("Couldn't resolve a primary image from the catalog: ${t.message}")
            return
        }

        val provisionCommand = try {
            val repo = primaryImage.repository
            val desktopEnvironment = repo.desktopEnvironment
                ?: error("PRIMARY entry ${repo.id} has no desktopEnvironment set")
            CompositorProvisioning.installCommand(repo.os, desktopEnvironment)
                ?: error("No known compositor-install command for ${repo.os}/$desktopEnvironment")
        } catch (t: Throwable) {
            fail("Couldn't determine how to provision a compositor: ${t.message}")
            return
        }

        val primary = try {
            runtime.createPrimary(primaryImage.toRootfsImage(), provisionCommand)
        } catch (t: Throwable) {
            // Expected to fail until this has actually been run against a
            // live droidspaces container (see this class's own doc
            // comment) — a clear, attributable failure beats a silent
            // no-op.
            fail("Couldn't create the primary container: ${t.message}")
            return
        }

        try {
            runtime.start(primary)
        } catch (t: Throwable) {
            fail("Couldn't start the primary container: ${t.message}")
            return
        }

        val hostBridge = HostBridge()
        val socketPath = runtime.primaryWaylandSocketPath()
        if (!hostBridge.connect(socketPath)) {
            fail("HostBridge couldn't connect to $socketPath")
            return
        }

        _stateHolder.value = DesktopSessionState.Connected(hostBridge, primaryDisplayOutput(), runtime, primary)
    }

    /**
     * Picks a PRIMARY-role repository — the user's own choice from
     * onboarding's `DESKTOP_SETUP` step (or its Settings re-entry point),
     * via [DesktopSetupPrefs], when one was actually made; first
     * PRIMARY/BOTH match otherwise (unset, or a stale id a catalog edit
     * removed — never a hard failure over a preference that no longer
     * resolves) — and resolves it against the real registry via [resolver]
     * — the catalog is populated live, not prepopulated with pinned
     * versions (docs/SPEC.md §3a). Picks whatever tag the registry lists
     * first; no "latest stable" ordering logic yet. Returns the full
     * [ResolvedImage] (not just a [dev.droidtop.runtime.RootfsImage]) so
     * [connect] can still see which distro/desktopEnvironment was picked —
     * needed to look up the right [CompositorProvisioning] command.
     */
    private suspend fun selectPrimaryImage(resolver: ImageCatalogResolver): ResolvedImage {
        val repositories = BundledImageRepositories.load(applicationContext).repositories
        val preferredId = DesktopSetupPrefs.preferredPrimaryImageId(applicationContext)
        val repo = repositories.firstOrNull { it.id == preferredId }
            ?: repositories.firstOrNull { it.role == ImageCatalogRole.PRIMARY || it.role == ImageCatalogRole.BOTH }
            ?: error("No PRIMARY-role repository in the bundled seed list")
        val tags = resolver.listTags(repo)
        val tag = tags.firstOrNull() ?: error("No tags published under ${repo.registry}/${repo.repository}")
        return resolver.resolve(repo, tag)
    }

    /**
     * Root gives access to [DroidSpacesRuntime]'s real namespace/cgroup
     * isolation; falls back to [ProotRuntime] (still entirely `TODO()`)
     * otherwise. Checked by actually running a root shell command rather
     * than inferring from e.g. build tags — the only real signal.
     */
    private suspend fun selectRuntime(): ContainerRuntime {
        val rootAvailable = RootProcess.run("id").succeeded
        return if (rootAvailable) {
            DroidSpacesRuntime(
                context = applicationContext,
                rootfsPuller = CraneRootfsPuller(applicationContext),
                imageCache = FileImageCache(applicationContext),
                cachePolicy = ImageCachePolicy(enabled = true),
            )
        } else {
            ProotRuntime()
        }
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

    private fun fail(message: String) {
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
        private const val NOTIFICATION_ID = 1
        private val _stateHolder = MutableStateFlow<DesktopSessionState>(DesktopSessionState.Idle)

        /** Observed by DesktopShell/MainActivity instead of a null/null placeholder. */
        val state: StateFlow<DesktopSessionState> = _stateHolder.asStateFlow()
    }
}
