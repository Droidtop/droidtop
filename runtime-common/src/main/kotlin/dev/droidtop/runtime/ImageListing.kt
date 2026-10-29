package dev.droidtop.runtime

/**
 * Settings > Containers > Images (Droidtop/tracker#757): the images stored on this device, each with the containers
 * made from it, for a person to read and tidy. Pure.
 */
object ImageListing {
    /** One stored image and the names of the containers that came from it. */
    data class Row(val entry: OciImageStore.Entry, val usedBy: List<String>) {
        /** The reference as a person reads it: Docker Hub's own prefixes dropped, as the container page does. */
        val name: String get() = shortReference(entry.reference)
    }

    /** Every image in [entries], most recently built first, with the containers among [containers] made from it. */
    fun rows(entries: List<OciImageStore.Entry>, containers: List<ContainerInfo>): List<Row> =
        entries
            .map { entry -> Row(entry, containers.filter { it.digest == entry.digest }.map { it.displayName }.sorted()) }
            .sortedWith(compareByDescending<Row> { it.entry.createdMs ?: Long.MIN_VALUE }.thenBy { it.name })

    fun shortReference(reference: String): String = reference.removePrefix("docker.io/library/").removePrefix("docker.io/")
}
