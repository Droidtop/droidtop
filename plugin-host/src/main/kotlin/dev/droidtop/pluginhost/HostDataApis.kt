package dev.droidtop.pluginhost

import java.io.File
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * A plugin's own data, kept by droidtop (docs/plugin-api.md 3 H1): the folder `filesDir/plugins/<id>/data`, the same one
 * a full-trust plugin gets as `privateDataDir`, reached by name. A contained plugin can open no path, so this is where
 * it keeps its settings, its downloads and anything else of its own. Names are relative paths of `[A-Za-z0-9._-]`
 * segments; nothing outside the folder can be named. Writes stop at [LIMIT_BYTES] (docs/plugin-api.md 8).
 */
class PluginDataStore(val root: File) {
    /** The file [name] means inside this plugin's folder; throws INVALID_ARGS for a name that is not one. */
    fun resolve(name: String): File {
        val segments = name.split('/')
        val valid = name.length in 1..MAX_NAME &&
            segments.size <= MAX_DEPTH &&
            segments.all { it.isNotEmpty() && it != "." && it != ".." && SEGMENT.matches(it) }
        if (!valid) throw BrokerException(PluginErrorCode.INVALID_ARGS, "a data name is 1 to $MAX_NAME characters of letters, digits, '.', '_' and '-', in at most $MAX_DEPTH folders")
        return File(root, name)
    }

    /** Bytes the folder holds now. */
    fun usage(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** Refuses a write that would take the folder past [LIMIT_BYTES]. */
    fun requireRoom(adding: Long, replacing: File? = null) {
        val after = usage() - (replacing?.takeIf { it.isFile }?.length() ?: 0L) + adding
        if (after > LIMIT_BYTES) throw BrokerException(PluginErrorCode.RATE_LIMITED, "this plugin's data would pass ${LIMIT_BYTES / (1024 * 1024)} MiB")
    }

    fun write(name: String, bytes: ByteArray, append: Boolean): Long {
        val file = resolve(name)
        if (file.isDirectory) throw BrokerException(PluginErrorCode.INVALID_ARGS, "$name is a folder")
        requireRoom(bytes.size.toLong(), if (append) null else file)
        file.parentFile?.mkdirs()
        if (append) {
            file.appendBytes(bytes)
        } else {
            // Through a temporary file: a crash never leaves half a file under the plugin's name.
            val tmp = File(file.parentFile, ".${file.name}.tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(file)) {
                tmp.delete()
                throw BrokerException(PluginErrorCode.FAILED, "$name could not be written")
            }
        }
        return file.length()
    }

    fun read(name: String, offset: Long, length: Int): Pair<ByteArray, Long> {
        val file = resolve(name)
        if (!file.isFile) throw BrokerException(PluginErrorCode.NOT_FOUND, "no data named $name")
        val size = file.length()
        val start = offset.coerceIn(0L, size)
        val count = minOf(length.toLong(), size - start).toInt().coerceAtLeast(0)
        val bytes = ByteArray(count)
        java.io.RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            raf.readFully(bytes)
        }
        return bytes to size
    }

    fun list(prefix: String): JSONArray {
        val out = JSONArray()
        if (!root.isDirectory) return out
        root.walkTopDown().filter { it.isFile && !it.name.startsWith(".") }.forEach { file ->
            val name = file.relativeTo(root).path.replace(File.separatorChar, '/')
            if (name.startsWith(prefix)) out.put(JSONObject().put("name", name).put("size", file.length()).put("modified", file.lastModified()))
        }
        return out
    }

    fun delete(name: String): Boolean {
        val file = resolve(name)
        return file.isFile && file.delete()
    }

    /** Renames [from] to [to] inside the folder, replacing [to]: how a finished download or extraction takes its final name. */
    fun move(from: String, to: String): Boolean {
        val source = resolve(from)
        val target = resolve(to)
        if (!source.isFile) throw BrokerException(PluginErrorCode.NOT_FOUND, "no data named $from")
        if (target.isDirectory) throw BrokerException(PluginErrorCode.INVALID_ARGS, "$to is a folder")
        target.parentFile?.mkdirs()
        return source.renameTo(target)
    }

    companion object {
        const val LIMIT_BYTES = 512L * 1024 * 1024
        const val MAX_NAME = 255
        const val MAX_DEPTH = 8
        private val SEGMENT = Regex("^[A-Za-z0-9._-]+$")
    }
}

internal object HostDataApis {
    /** The most one `data.read` returns; it travels as text inside the 256 KiB reply cap. Larger files are opened (`data.open`). */
    const val MAX_CHUNK = 128 * 1024

    private fun invalid(message: String): Nothing = throw BrokerException(PluginErrorCode.INVALID_ARGS, message)

    fun storeOf(env: BrokerEnvironment, record: PluginRecord): PluginDataStore =
        env.dataStore(record.manifest.id) ?: throw BrokerException(PluginErrorCode.UNSUPPORTED, "this droidtop keeps no plugin data")

    private fun bytesOf(args: JSONObject): ByteArray = when {
        args.has("base64") -> runCatching { Base64.getDecoder().decode(args.optString("base64")) }.getOrElse { invalid("base64 is not base64") }
        args.has("text") -> args.optString("text").toByteArray(Charsets.UTF_8)
        else -> invalid("give text or base64")
    }.also { if (it.size > MAX_CHUNK) invalid("one write is at most ${MAX_CHUNK / 1024} KiB; append the rest, or open the file") }

    // No permission: a plugin's own data is always available (docs/plugin-api.md 4.1).
    val ops: List<HostOp> = listOf(
        HostOp("data", "write", target = { it.optString("name") }) { env, record, args ->
            val size = storeOf(env, record).write(args.optString("name"), bytesOf(args), args.optBoolean("append", false))
            JSONObject().put("size", size)
        },
        HostOp("data", "read", target = { it.optString("name") }) { env, record, args ->
            val length = args.optInt("length", MAX_CHUNK).coerceIn(0, MAX_CHUNK)
            val offset = args.optLong("offset", 0L)
            val (bytes, size) = storeOf(env, record).read(args.optString("name"), offset, length)
            val out = JSONObject().put("size", size).put("eof", offset + bytes.size >= size)
            if (args.optString("as") == "base64") out.put("base64", Base64.getEncoder().encodeToString(bytes)) else out.put("text", bytes.toString(Charsets.UTF_8))
        },
        HostOp("data", "list") { env, record, args ->
            JSONObject().put("files", storeOf(env, record).list(args.optString("prefix")))
        },
        HostOp("data", "delete", target = { it.optString("name") }) { env, record, args ->
            JSONObject().put("deleted", storeOf(env, record).delete(args.optString("name")))
        },
        HostOp("data", "move", target = { it.optString("from") + " -> " + it.optString("to") }) { env, record, args ->
            JSONObject().put("moved", storeOf(env, record).move(args.optString("from"), args.optString("to")))
        },
        // Where a file of the plugin's lives, as a path droidtop resolved: only to hand to a provider plugin (a root helper
        // copying it somewhere), which runs with full access. The contained plugin itself can open no path.
        HostOp("data", "path", target = { it.optString("name") }) { env, record, args ->
            val file = storeOf(env, record).resolve(args.optString("name"))
            if (!file.isFile) throw BrokerException(PluginErrorCode.NOT_FOUND, "no data named ${args.optString("name")}")
            JSONObject().put("path", file.absolutePath)
        },
        HostOp("data", "usage") { env, record, _ ->
            JSONObject().put("bytes", storeOf(env, record).usage()).put("limit", PluginDataStore.LIMIT_BYTES)
        },
        // The file itself, for anything larger than a chunk: mode r (read), w (replace), a (append) or rw.
        HostOp(
            "data", "open", target = { it.optString("name") },
            open = { env, record, args ->
                val store = storeOf(env, record)
                val file = store.resolve(args.optString("name"))
                val mode = args.optString("mode", "r")
                val flags = when (mode) {
                    "r" -> {
                        if (!file.isFile) throw BrokerException(PluginErrorCode.NOT_FOUND, "no data named ${args.optString("name")}")
                        android.os.ParcelFileDescriptor.MODE_READ_ONLY
                    }
                    "w" -> android.os.ParcelFileDescriptor.MODE_WRITE_ONLY or android.os.ParcelFileDescriptor.MODE_CREATE or android.os.ParcelFileDescriptor.MODE_TRUNCATE
                    "a" -> android.os.ParcelFileDescriptor.MODE_WRITE_ONLY or android.os.ParcelFileDescriptor.MODE_CREATE or android.os.ParcelFileDescriptor.MODE_APPEND
                    "rw" -> android.os.ParcelFileDescriptor.MODE_READ_WRITE or android.os.ParcelFileDescriptor.MODE_CREATE
                    else -> invalid("mode is r, w, a or rw")
                }
                // A file opened for writing grows outside the broker's sight; the limit is checked when it is opened.
                if (mode != "r") store.requireRoom(0L)
                file.parentFile?.mkdirs()
                android.os.ParcelFileDescriptor.open(file, flags)
            },
        ),
    )
}
