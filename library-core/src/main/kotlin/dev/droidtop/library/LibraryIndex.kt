package dev.droidtop.library

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The library index: each provider's last complete scan result, kept on
 * disk and shown at the next start instead of walking the games roots
 * again (docs/SPEC.md 7g, "the library is an index"). One slice per
 * [LibraryProvider.indexKey]; [Library] decides when a slice is shown and
 * when a walk replaces it.
 *
 * A plain interface for the same reason as [PlayHistoryStore]: [Library]
 * stays constructible in a JVM test with a fake, and the default
 * [NoOpLibraryIndexStore] keeps every existing call site walking as it
 * always did.
 */
interface LibraryIndexStore {
    /** The slice for [providerKey], or null when nothing complete was ever saved for it. */
    suspend fun load(providerKey: String): List<LibraryEntry>?
    suspend fun save(providerKey: String, entries: List<LibraryEntry>)
}

object NoOpLibraryIndexStore : LibraryIndexStore {
    override suspend fun load(providerKey: String): List<LibraryEntry>? = null
    override suspend fun save(providerKey: String, entries: List<LibraryEntry>) {}
}

/**
 * One JSON file per slice under [dir], written whole and renamed into
 * place so a crash mid-write leaves the previous slice, never a torn one.
 * A file this build cannot read (an older shape, a corrupt write) is
 * treated as no slice, which makes the next start a walk -- the same
 * thing a first run is -- and never an error the user sees.
 */
class FileLibraryIndexStore(private val dir: File) : LibraryIndexStore {

    @Serializable
    private data class Slice(val formatVersion: Int, val entries: List<LibraryEntry>)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    private fun fileFor(providerKey: String) = File(dir, providerKey.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json")

    override suspend fun load(providerKey: String): List<LibraryEntry>? {
        val file = fileFor(providerKey)
        if (!file.isFile) return null
        return try {
            val slice = json.decodeFromString(Slice.serializer(), file.readText())
            if (slice.formatVersion != FORMAT_VERSION) {
                ScanLog.write("index: ${file.name} is format ${slice.formatVersion}, this build reads $FORMAT_VERSION; walking")
                null
            } else {
                slice.entries
            }
        } catch (t: Throwable) {
            ScanLog.write("index: ${file.name} could not be read (${t.javaClass.simpleName}: ${t.message}); walking")
            null
        }
    }

    override suspend fun save(providerKey: String, entries: List<LibraryEntry>) {
        dir.mkdirs()
        val file = fileFor(providerKey)
        val tmp = File(dir, file.name + ".tmp")
        tmp.writeText(json.encodeToString(Slice.serializer(), Slice(FORMAT_VERSION, entries)))
        if (!tmp.renameTo(file)) {
            // A rename across the same directory does not fail on any
            // filesystem droidtop runs on; if it ever does, the old slice
            // is still intact and the walk simply repeats next start.
            file.delete()
            tmp.renameTo(file)
        }
    }

    companion object {
        /** Bump when [LibraryEntry]'s shape changes in a way a reader cannot absorb. */
        const val FORMAT_VERSION = 1
    }
}
