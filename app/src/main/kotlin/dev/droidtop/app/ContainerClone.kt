package dev.droidtop.app

import android.content.Context
import dev.droidtop.runtime.Container
import dev.droidtop.runtime.ContainerInfo
import dev.droidtop.runtime.ContainerProgram
import dev.droidtop.runtime.ContainerRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Copying a container, the one way (Droidtop/tracker#757): its page's Make a copy. The backend copies its tree and
 * settings ([ContainerRuntime.clone]); droidtop's own settings for its program come with it. What it exported into the
 * desktop, start-up, pods and networks do not: the copy is a new container, and putting it in the desktop's menus or
 * starting it with the desktop is the person's choice for it.
 */
internal object ContainerClone {
    suspend fun clone(context: Context, runtime: ContainerRuntime, info: ContainerInfo, name: String): Container {
        val copy = runtime.clone(info.container, name)
        val files = context.applicationContext.filesDir
        withContext(Dispatchers.IO) {
            ContainerProgram.overridesFile(files, info.container.id).takeIf { it.isFile }
                ?.copyTo(ContainerProgram.overridesFile(files, copy.id))
        }
        ContainerPrograms.recordCreated(context.applicationContext, copy.id)
        return copy
    }
}
