package dev.droidtop.app

import android.content.Context
import dev.droidtop.runtime.ContainerRuntime
import dev.droidtop.runtime.ImageCachePolicy
import dev.droidtop.runtime.linux.noroot.ProotRuntime
import dev.droidtop.runtime.linux.root.CraneRootfsPuller
import dev.droidtop.runtime.linux.root.DroidSpacesRuntime
import dev.droidtop.runtime.linux.root.FileImageCache
import dev.droidtop.runtime.RootProcess

/**
 * The one place backend selection happens — root gives
 * [DroidSpacesRuntime]'s real namespace/cgroup isolation, anything else
 * falls back to [ProotRuntime]. Root is checked by actually running a root
 * shell command rather than inferring from e.g. build tags — the only real
 * signal. Shared by [DesktopSessionService] and [ContainersActivity] (one
 * mechanism, not a copy per caller).
 */
object ContainerRuntimeFactory {
    suspend fun select(context: Context): ContainerRuntime {
        val rootAvailable = RootProcess.run("id").succeeded
        return if (rootAvailable) {
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
