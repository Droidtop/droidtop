package dev.droidtop.app

import android.content.Context
import dev.droidtop.runtime.ContainerRuntime
import dev.droidtop.runtime.ImageCachePolicy
import dev.droidtop.runtime.linux.noroot.ProotRuntime
import dev.droidtop.runtime.linux.root.CraneRootfsPuller
import dev.droidtop.runtime.linux.root.DroidSpacesRuntime
import dev.droidtop.runtime.linux.root.FileImageCache
import dev.droidtop.runtime.RootAccess
import dev.droidtop.runtime.RootProcess

/**
 * The one place backend selection happens — root gives
 * [DroidSpacesRuntime]'s real namespace/cgroup isolation, anything else
 * falls back to [ProotRuntime]. Root is checked by actually running a root
 * shell command rather than inferring from e.g. build tags — the only real
 * signal. Shared by [DesktopSessionService] and [ContainersActivity] (one
 * mechanism, not a copy per caller).
 *
 * Selection REPORTS root as a state ([rootAccess]) and never fails on its
 * absence: on an unrooted device `su` cannot even be started, and that
 * used to escape this function as an IOException and crash droidtop at
 * launch in every non-Handheld mode (emulator rig, 2026-09-10). Root is
 * desktop-only, and even in Desktop mode its absence is a fallback rather
 * than an error.
 */
object ContainerRuntimeFactory {
    /** What root this device offers -- for callers that want to say so, not just pick a backend. */
    suspend fun rootAccess(): RootAccess = RootProcess.access()

    suspend fun select(context: Context): ContainerRuntime {
        return if (rootAccess().available) {
            DroidSpacesRuntime(
                context = context.applicationContext,
                rootfsPuller = CraneRootfsPuller(context.applicationContext),
                imageCache = FileImageCache(context.applicationContext),
                cachePolicy = ImageCachePolicy(enabled = true),
            )
        } else {
            ProotRuntime()
        }
    }
}
