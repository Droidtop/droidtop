package dev.droidtop.app

import dev.droidtop.runtime.ContainerInfo
import dev.droidtop.runtime.ContainerRuntime
import dev.droidtop.runtime.ContainerSizeCache
import dev.droidtop.runtime.util.AppScopes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Each container's size in the Containers list (docs/SPEC.md 3d "The surface, precisely"; Droidtop/tracker#911): the
 * container page's own measurement ([ContainerRuntime.diskUsageBytes]), run off the main thread one container at a
 * time and kept in [ContainerSizeCache] until the container changes. The list reads [label] while it is built; a size
 * not known yet says "Measuring…" and is measured in the background, and [changes] tells the screen to read itself
 * again when it is known.
 */
internal object ContainerSizes {
    private val cache = ContainerSizeCache()

    /** One walk at a time: several rootfs trees walked at once only compete for the same storage. */
    private val walking = Mutex()

    private val changed = MutableStateFlow(0L)

    /** Moves whenever a size becomes known or is forgotten (the Containers screen's live flow). */
    val changes: StateFlow<Long> = changed

    fun stamp(info: ContainerInfo, running: Boolean) = ContainerSizeCache.Stamp(running, info.digest)

    /** What [info]'s row says about its size, starting a measurement when none is kept for its [running] state. */
    fun label(runtime: ContainerRuntime, info: ContainerInfo, running: Boolean): String? {
        val id = info.container.id
        val stamp = stamp(info, running)
        cache.get(id, stamp)?.let { return ContainerSizeCache.label(it) }
        val token = cache.begin(id) ?: return ContainerSizeCache.MEASURING
        AppScopes.io.launch {
            var bytes: Long? = null
            try {
                bytes = walking.withLock { runCatching { runtime.diskUsageBytes(info.container) }.getOrNull() }
            } finally {
                if (cache.finish(id, token, stamp, bytes)) changed.update { it + 1 }
            }
        }
        return ContainerSizeCache.MEASURING
    }

    /** The page measured [info] itself; the list shows that answer. */
    fun record(info: ContainerInfo, running: Boolean, bytes: Long?) {
        cache.record(info.container.id, stamp(info, running), bytes)
        changed.update { it + 1 }
    }

    /** [id] was changed (software updated or installed, restored, copied, deleted): measure it again when listed. */
    fun forget(id: String) {
        cache.forget(id)
        changed.update { it + 1 }
    }

    /** Forgets the containers that are gone. */
    fun keepOnly(ids: Set<String>) = cache.keepOnly(ids)
}
