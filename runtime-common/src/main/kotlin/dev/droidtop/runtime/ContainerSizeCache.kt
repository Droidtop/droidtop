package dev.droidtop.runtime

import dev.droidtop.library.format.ValueFormat

/**
 * Each container's size on disk as the Containers list shows it (docs/SPEC.md 3d "The surface, precisely";
 * Droidtop/tracker#911), kept per container until the container changes, so reopening the list does not walk every
 * rootfs again. The pure half: what is known, when it no longer holds, and whether a measurement's answer still
 * counts. `ContainerSizes` in :app measures ([ContainerRuntime.diskUsageBytes], the walk the page's "Storage used"
 * does) off the main thread and feeds the answers in.
 *
 * A size holds while the container's [Stamp] is the same: started or stopped (anywhere, not only from the list) or
 * remade from another image, and it is measured again. What changes a container without changing its stamp
 * ("Update its software", a terminal's first install, a restore, a copy, a delete) calls [forget]. A measurement
 * begun before a [forget] is not recorded: it may have read the tree half-changed.
 */
class ContainerSizeCache {
    /** What a size depends on besides the files: whether it runs, and the image it was made from. */
    data class Stamp(val running: Boolean, val digest: String?)

    sealed interface Size {
        /** Not known yet; a measurement is under way. */
        data object Measuring : Size

        data class Known(val bytes: Long) : Size

        /** The measurement could not read the tree (droidspaces' root-owned one): no size is shown. */
        data object Unavailable : Size
    }

    private class Slot(var generation: Long = 0, var stamp: Stamp? = null, var size: Size? = null, var measuring: Boolean = false)

    private val slots = HashMap<String, Slot>()

    /** The size of [id] as of [stamp]: known, unavailable, measuring, or null when it needs measuring. */
    @Synchronized
    fun get(id: String, stamp: Stamp): Size? {
        val slot = slots[id] ?: return null
        slot.size?.takeIf { slot.stamp == stamp }?.let { return it }
        return if (slot.measuring) Size.Measuring else null
    }

    /**
     * Starts measuring [id]: a token to [finish] with, or null when a measurement of it is already under way (one walk
     * per container at a time).
     */
    @Synchronized
    fun begin(id: String): Long? {
        val slot = slots.getOrPut(id) { Slot() }
        if (slot.measuring) return null
        slot.measuring = true
        return slot.generation
    }

    /**
     * Records what the measurement begun with [token] found ([bytes], null when the tree could not be read) for
     * [stamp]. False, and nothing is recorded, when [forget] came in between.
     */
    @Synchronized
    fun finish(id: String, token: Long, stamp: Stamp, bytes: Long?): Boolean {
        val slot = slots[id] ?: return false
        if (slot.generation != token) return false
        slot.measuring = false
        slot.stamp = stamp
        slot.size = bytes?.let { Size.Known(it) } ?: Size.Unavailable
        return true
    }

    /** A size measured elsewhere (the container's own page, which measures each time it opens) replaces the kept one. */
    @Synchronized
    fun record(id: String, stamp: Stamp, bytes: Long?) {
        val slot = slots.getOrPut(id) { Slot() }
        slot.generation++
        slot.measuring = false
        slot.stamp = stamp
        slot.size = bytes?.let { Size.Known(it) } ?: Size.Unavailable
    }

    /** [id] changed: what is kept no longer holds, and a measurement under way is not recorded. */
    @Synchronized
    fun forget(id: String) {
        val slot = slots[id] ?: return
        slot.generation++
        slot.measuring = false
        slot.stamp = null
        slot.size = null
    }

    /** Drops every container not in [ids] (deleted, by any route). */
    @Synchronized
    fun keepOnly(ids: Set<String>) {
        slots.keys.retainAll(ids)
    }

    companion object {
        const val MEASURING = "Measuring…"

        /** What the row says: "Measuring…" until known (null is about to be measured), the size, or nothing at all. */
        fun label(size: Size?): String? = when (size) {
            null, Size.Measuring -> MEASURING
            is Size.Known -> ValueFormat.size(size.bytes)
            Size.Unavailable -> null
        }
    }
}
