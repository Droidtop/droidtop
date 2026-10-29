package dev.droidtop.runtime

/**
 * `rmi` (Droidtop/tracker#715): which stored images the names given come off, decided before any is removed. A
 * container keeps its own tree and its own copy of the image's run settings from when it was made, so removing its
 * image breaks nothing in it; still, as Docker and Podman do, an image a container came from is only removed with
 * force. Pure.
 */
object ImageRemoval {
    sealed interface Outcome {
        /** The name as it was given. */
        val name: String
    }

    data class Removed(override val name: String, val entry: OciImageStore.Entry) : Outcome

    data class Missing(override val name: String) : Outcome

    /** [containers] (their names) came from it, and there was no force. */
    data class InUse(override val name: String, val entry: OciImageStore.Entry, val containers: List<String>) : Outcome

    /**
     * Each of [names] read against [entries] ([DockerApi.findImage]: any of Docker's spellings, the digest or a start
     * of it). A name for an image an earlier name already removed is missing, as Docker answers it.
     */
    fun plan(entries: List<OciImageStore.Entry>, containers: List<ContainerInfo>, names: List<String>, force: Boolean): List<Outcome> {
        val removed = mutableSetOf<String>()
        return names.map { name ->
            val entry = DockerApi.findImage(entries.filter { it.digest !in removed }, name) ?: return@map Missing(name)
            val users = containers.filter { it.digest == entry.digest }.map { it.displayName }
            if (users.isNotEmpty() && !force) {
                InUse(name, entry, users)
            } else {
                removed += entry.digest
                Removed(name, entry)
            }
        }
    }
}
