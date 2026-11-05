package dev.droidtop.runtime

import dev.droidtop.shared.trust.Sha256

/**
 * The id a container goes by in the engine's fronts and APIs (docs/SPEC.md 3d, "Container ids"; Droidtop/tracker#555):
 * 64 hex digits, as Docker's and Podman's are. droidtop's own container ids (`droidtop-sibling-1a2b3c4d`) share their
 * first twelve characters, which is all both tools' `ps` show, so every container read `droidtop-sib` there. The
 * engine id is a digest of droidtop's id: the same on every boot, never stored, and every place a container is named
 * takes it (or a unique start of it) as well as the container's name and droidtop's own id. Pure.
 */
object EngineIds {
    /** [containerId]'s engine id. */
    fun of(containerId: String): String {
        return Sha256.hex("droidtop-container:$containerId".toByteArray(Charsets.UTF_8))
    }

    /**
     * The container [ref] names, as Docker's daemon takes a reference: its name (with or without Docker's leading
     * `/`), its engine id or droidtop's own id, or a unique start of either id, three characters or more.
     */
    fun find(containers: List<ContainerInfo>, ref: String): ContainerInfo? {
        val name = ref.removePrefix("/")
        containers.firstOrNull { it.displayName == name }?.let { return it }
        containers.firstOrNull { it.container.id == ref || of(it.container.id) == ref }?.let { return it }
        if (ref.length < 3) return null
        return containers.filter { it.container.id.startsWith(ref) || of(it.container.id).startsWith(ref) }.singleOrNull()
    }
}
