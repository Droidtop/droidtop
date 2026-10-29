package dev.droidtop.pluginhost

import android.util.Log
import dev.droidtop.net.AtomicWrite
import java.io.File
import org.json.JSONObject

/**
 * One file per plugin in [dir], `<pluginId><suffix>`, written whole through [AtomicWrite]: the store behind the vault,
 * the picked-file tokens, the call counts, the grants and the activity log (Droidtop/tracker#766). It replaced a copy of
 * the same read, write and delete in each of them.
 *
 * A file that does not parse is never read as empty and then written over, which lost a plugin's whole vault index when
 * one byte went bad: [readOrSetAside] moves it to `<file>.corrupt`, kept for a look and replaced by the next one, and the
 * plugin starts that store afresh. A store that must not start afresh reads [read] and decides itself ([PluginGrants]
 * treats a corrupt file as "ask about everything").
 */
class PerPluginJsonStore(val dir: File, private val suffix: String = ".json") {
    /** What reading a plugin's file found. */
    sealed class Read {
        data object Absent : Read()

        data class Found(val json: JSONObject) : Read()

        data class Corrupt(val failure: Exception) : Read()
    }

    fun fileFor(pluginId: String): File = File(dir, pluginId + suffix)

    fun read(pluginId: String): Read {
        val file = fileFor(pluginId)
        if (!file.isFile) return Read.Absent
        return try {
            Read.Found(JSONObject(file.readText()))
        } catch (e: Exception) {
            Read.Corrupt(e)
        }
    }

    /** [pluginId]'s object; null when it has none, or when its file did not parse and was set aside. */
    fun readOrSetAside(pluginId: String): JSONObject? = when (val read = read(pluginId)) {
        is Read.Found -> read.json
        Read.Absent -> null
        is Read.Corrupt -> {
            val file = fileFor(pluginId)
            val aside = File(dir, file.name + CORRUPT)
            aside.delete()
            val moved = file.renameTo(aside)
            // runCatching: a JVM unit test has no android.util.Log.
            runCatching { Log.w("droidtop.plugin", "${file.path} did not parse (${read.failure.message}); ${if (moved) "set aside as ${aside.name}" else "could not be set aside"}") }
            null
        }
    }

    fun write(pluginId: String, json: JSONObject) = writeText(pluginId, json.toString())

    fun writeText(pluginId: String, text: String) = AtomicWrite.text(fileFor(pluginId), text)

    /** Deletes [pluginId]'s file, a set-aside copy, and the temp file a build before [AtomicWrite] could leave. */
    fun delete(pluginId: String) {
        val file = fileFor(pluginId)
        file.delete()
        File(dir, file.name + CORRUPT).delete()
        File(dir, file.name + ".tmp").delete()
    }

    companion object {
        const val CORRUPT = ".corrupt"
    }
}
