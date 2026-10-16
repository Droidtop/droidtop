package dev.droidtop.app

import android.content.Context
import dev.droidtop.runtime.ContainerEnter
import dev.droidtop.runtime.ContainerExports
import dev.droidtop.runtime.ContainerInfo
import dev.droidtop.runtime.ContainerPods
import dev.droidtop.runtime.ContainerRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Deleting a container, the one way (docs/SPEC.md 3d): its page's Delete and `docker rm` both come here. The tree goes,
 * and so does what it exported into the desktop (apps, commands) and its program's settings and job.
 */
internal object ContainerRemoval {
    suspend fun remove(context: Context, runtime: ContainerRuntime, info: ContainerInfo) {
        ContainerPrograms.stop(info.container.id)
        runtime.destroy(info.container)
        val files = context.applicationContext.filesDir
        withContext(Dispatchers.IO) {
            ContainerExports.save(files, ContainerExports.without(ContainerExports.read(files), info.container.id))
            ContainerEnter.save(files, ContainerEnter.without(ContainerEnter.read(files), info.container.id))
            ContainerPrograms.forget(context.applicationContext, info.container.id)
            ContainerPods.save(files, ContainerPods.withoutMember(ContainerPods.read(files), info.container.id))
        }
    }
}
