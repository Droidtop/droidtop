package dev.droidtop.app

import android.content.Context
import dev.droidtop.runtime.ContainerRuntime
import dev.droidtop.runtime.Crane
import dev.droidtop.runtime.CraneRootfsPuller
import dev.droidtop.runtime.ImageCachePolicy
import dev.droidtop.runtime.OciImageStore
import dev.droidtop.runtime.RootAccess
import dev.droidtop.runtime.RootProcess
import dev.droidtop.runtime.linux.noroot.ProotRuntime
import dev.droidtop.runtime.linux.root.DroidSpacesRuntime
import dev.droidtop.runtime.linux.root.RootTarUnpacker

/**
 * The one place backend selection happens — root gives
 * [DroidSpacesRuntime]'s real namespace/cgroup isolation, anything else
 * gets [ProotRuntime]. Root is checked by actually running a root shell
 * command rather than inferring from e.g. build tags — the only real
 * signal. Shared by [DesktopSessionService], [ContainersActivity] and
 * onboarding's Desktop step (one mechanism, not a copy per caller).
 *
 * Both backends pull through the same [CraneRootfsPuller] into one
 * [OciImageStore]; they differ in who writes the rootfs (root for
 * droidspaces, the app itself for proot).
 *
 * Selection REPORTS root as a state ([rootAccess]) and never fails on its
 * absence: on an unrooted device `su` cannot even be started, and that
 * used to escape this function as an IOException and crash droidtop at
 * launch in every non-Gaming mode (emulator rig, 2026-09-10). Root is
 * desktop-only, and even in Desktop mode its absence only means the
 * proot backend.
 */
object ContainerRuntimeFactory {
    /** What root this device offers -- for callers that want to say so, not just pick a backend. */
    suspend fun rootAccess(): RootAccess = RootProcess.access()

    suspend fun select(context: Context): ContainerRuntime {
        val app = context.applicationContext
        val store = OciImageStore.of(app)
        val policy = ImageCachePolicy.DEFAULT
        return if (rootAccess().available) {
            DroidSpacesRuntime(
                context = app,
                rootfsPuller = CraneRootfsPuller({ Crane.binaryPath(app) }, store, RootTarUnpacker()),
                cachePolicy = policy,
            )
        } else {
            ProotRuntime(app, store, policy)
        }
    }
}
