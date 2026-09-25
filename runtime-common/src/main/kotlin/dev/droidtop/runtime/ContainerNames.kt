package dev.droidtop.runtime

import java.io.File
import java.util.Properties

/**
 * The names people call their containers ("Debian", "Work"), kept apart
 * from the ids the backends use for paths and processes
 * (`droidtop-sibling-8993dfbd`), which are not names anyone chose and
 * told two terminals nothing about which container each was in (rig,
 * dq-desk2-01). One file per backend, beside its containers; both
 * backends read and write it through this class, so the model is one.
 *
 * A container made before names existed, or whose name was lost, is
 * named the way a new one would be ([defaultName]), so nothing ever
 * shows an id.
 */
class ContainerNames(private val file: File) {
    @Synchronized
    fun get(id: String): String? = load().getProperty(id)?.takeIf { it.isNotBlank() }

    /**
     * [containers] with their names filled in: the stored name, or for a
     * container that has none the default ([defaultName]), numbered past
     * the names already given out.
     */
    fun named(containers: List<ContainerInfo>): List<ContainerInfo> {
        val stored = containers.associate { it.container.id to get(it.container.id) }
        val given = stored.values.filterNotNull().toMutableList()
        return containers.map { info ->
            val name = stored[info.container.id] ?: defaultName(info.container.role, info.image, given).also { given += it }
            info.copy(name = name)
        }
    }

    /** Renames [id] after checking [name] against the other containers' names ([problemWith]). */
    fun rename(id: String, name: String, containers: List<ContainerInfo>) {
        val others = named(containers).filter { it.container.id != id }.map { it.displayName }
        problemWith(name, others)?.let { error(it) }
        set(id, name.trim())
    }

    @Synchronized
    fun set(id: String, name: String) {
        val properties = load()
        properties.setProperty(id, name)
        save(properties)
    }

    @Synchronized
    fun remove(id: String) {
        val properties = load()
        if (properties.remove(id) != null) save(properties)
    }

    private fun load(): Properties = Properties().apply {
        if (file.isFile) file.inputStream().use { load(it) }
    }

    private fun save(properties: Properties) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, ".${file.name}.tmp")
        temp.outputStream().use { properties.store(it, "droidtop container names, by container id") }
        check(temp.renameTo(file)) { "couldn't save container names to ${file.path}" }
    }

    companion object {
        const val FILE_NAME = "container-names.properties"
        const val PRIMARY_NAME = "Desktop"
        const val MAX_LENGTH = 40

        /**
         * What a container is called before anyone renames it: "Desktop"
         * for the primary, and for a sibling its image's repository name,
         * capitalised ("Debian" for `docker.io/library/debian:latest`),
         * numbered when that name is taken ("Debian 2").
         */
        fun defaultName(role: ContainerRole, image: String?, others: Collection<String>): String {
            if (role == ContainerRole.PRIMARY) return PRIMARY_NAME
            val repository = image
                ?.substringBefore('@')
                ?.substringAfterLast('/')
                ?.substringBefore(':')
                ?.takeIf { it.isNotBlank() }
                ?: "Container"
            val base = repository.replaceFirstChar { it.uppercaseChar() }
            val taken = others.map { it.lowercase() }.toSet()
            if (base.lowercase() !in taken) return base
            var n = 2
            while ("$base $n".lowercase() in taken) n++
            return "$base $n"
        }

        /**
         * Why [name] cannot be used, or null when it can: not empty, at
         * most [MAX_LENGTH] characters, one line, and not another
         * container's name ([others], compared ignoring case).
         */
        fun problemWith(name: String, others: Collection<String>): String? {
            val trimmed = name.trim()
            return when {
                trimmed.isEmpty() -> "A name can't be empty."
                trimmed.length > MAX_LENGTH -> "A name can be at most $MAX_LENGTH characters."
                trimmed.any { it == '\n' || it == '\r' } -> "A name is one line."
                others.any { it.equals(trimmed, ignoreCase = true) } -> "Another container is already called $trimmed."
                else -> null
            }
        }
    }
}
