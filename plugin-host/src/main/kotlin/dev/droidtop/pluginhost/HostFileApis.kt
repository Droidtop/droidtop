package dev.droidtop.pluginhost

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** A document the person picked for a plugin in the system picker (docs/plugin-api.md 3 D4). */
data class PickedDocument(val uri: String, val name: String, val size: Long?, val writable: Boolean)

/**
 * The documents each plugin was handed through `files.pick` (docs/plugin-api.md 3 D4): a random token per pick, kept
 * per plugin so a pick survives a restart, and deleted with the plugin. The plugin holds only the token; droidtop holds
 * the URI and opens it.
 */
class PluginFileTokens(private val dir: File) {
    private fun fileFor(pluginId: String) = File(dir, "$pluginId.json")

    private fun read(pluginId: String): JSONObject =
        runCatching { JSONObject(fileFor(pluginId).readText()) }.getOrDefault(JSONObject())

    private fun write(pluginId: String, json: JSONObject) {
        dir.mkdirs()
        val tmp = File(dir, "$pluginId.json.tmp")
        tmp.writeText(json.toString())
        Files.move(tmp.toPath(), fileFor(pluginId).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    fun add(pluginId: String, document: PickedDocument): String = synchronized(LOCK) {
        val json = read(pluginId)
        if (json.length() >= MAX_TOKENS) throw BrokerException(PluginErrorCode.RATE_LIMITED, "a plugin keeps at most $MAX_TOKENS picked files; forget one first")
        val token = "f-" + UUID.randomUUID().toString()
        json.put(token, JSONObject().put("uri", document.uri).put("name", document.name).put("writable", document.writable))
        write(pluginId, json)
        token
    }

    fun get(pluginId: String, token: String): PickedDocument? = synchronized(LOCK) {
        val entry = read(pluginId).optJSONObject(token) ?: return@synchronized null
        PickedDocument(entry.optString("uri"), entry.optString("name"), null, entry.optBoolean("writable"))
    }

    fun forget(pluginId: String, token: String): Boolean = synchronized(LOCK) {
        val json = read(pluginId)
        if (json.remove(token) == null) return@synchronized false
        write(pluginId, json)
        true
    }

    fun delete(pluginId: String) = synchronized(LOCK) {
        fileFor(pluginId).delete()
    }

    companion object {
        const val MAX_TOKENS = 256
        private val LOCK = Any()

        fun forPluginsRoot(pluginsRoot: File): PluginFileTokens = PluginFileTokens(File(pluginsRoot.parentFile ?: pluginsRoot, "plugin-files"))
    }
}

/**
 * Shared storage, scoped (docs/plugin-api.md 3 D5): a plugin names a path relative to a storage volume, and it must lie
 * under one of the folders its `files.shared.read` / `files.shared.write` entry declared (`paths`, `"*"` for the whole
 * volume). Other apps' private folders (`Android/data`, `Android/obb`) are never reachable, and a path that leaves the
 * volume, or a declared folder, through `..` or a link is refused.
 */
object SharedFiles {
    const val READ = "files.shared.read"
    const val WRITE = "files.shared.write"
    private val PRIVATE = listOf("android/data", "android/obb")

    fun declaredPaths(declared: DeclaredPermission): List<String> {
        val list = runCatching { JSONObject(declared.extra).optJSONArray("paths") }.getOrNull() ?: return emptyList()
        return List(list.length()) { list.optString(it).trim().trim('/') }.filter { it.isNotEmpty() }
    }

    /** Why [path] is outside what [declared] covers, or null. */
    fun scopeRefusal(declared: DeclaredPermission, path: String): String? {
        val clean = normalise(path) ?: return "$path is not a path inside a storage volume"
        val scopes = declaredPaths(declared)
        if ("*" in scopes) return null
        return if (scopes.any { clean == it || clean.startsWith("$it/") }) null else "$path is outside the folders this plugin declared"
    }

    /** [path] without leading or trailing slashes, or null when it climbs out with `..` or names an app's private folder. */
    fun normalise(path: String): String? {
        val parts = path.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) return null
        val clean = parts.joinToString("/")
        if (PRIVATE.any { clean.lowercase() == it || clean.lowercase().startsWith("$it/") }) return null
        return clean
    }

    /** The permissions an open process's file rules are built from: changing one retires its process ([PluginGrants.set]). */
    val PERMISSIONS: Set<String> = setOf(READ, WRITE)

    /**
     * The broker rules for shared storage under [roots]: each folder in [read] (granted `files.shared.read`, null when
     * not) readable and each in [write] writable, `"*"` being the whole volume. Pure.
     *
     * The same folders `files.shared` would reach: a path that climbs out or names `Android/data` or `Android/obb` is
     * left out ([normalise]), and so is `Android` on its own. A whole volume denies `Android` in every
     * spelling of its case, since shared storage matches names without case and the broker's rules match them exactly:
     * other apps' folders, and droidtop's own there, stay out. A deeper folder there (`Android/media/<app>`) can still
     * be declared, and its rule, longer, decides for it.
     */
    fun brokerRules(roots: Collection<String>, read: List<String>?, write: List<String>?): Map<String, Int> {
        val modes = LinkedHashMap<String, Int>()
        fun add(paths: List<String>?, mode: Int) = paths.orEmpty().forEach { declared ->
            val clean = if (declared == "*") "" else normalise(declared)?.takeIf { it.isNotEmpty() } ?: return@forEach
            if (clean.equals("android", ignoreCase = true)) return@forEach
            modes[clean] = (modes[clean] ?: 0) or mode
        }
        add(read, BROKER_READ)
        add(write, BROKER_WRITE)
        // The longest rule decides alone, so a folder keeps what any folder above it was given ("*" read, "Saves" write).
        val merged = modes.mapValues { (relative, mode) ->
            modes.entries.fold(mode) { acc, (other, otherMode) ->
                if (other == "" || relative.startsWith("$other/")) acc or otherMode else acc
            }
        }
        val rules = LinkedHashMap<String, Int>()
        for (root in roots.map { it.trimEnd('/') }.distinct()) {
            for ((relative, mode) in merged) rules[if (relative.isEmpty()) root else "$root/$relative"] = mode
            if ("" in modes) caseSpellings("Android").forEach { rules["$root/$it"] = BROKER_DENY }
        }
        return rules
    }

    /** The broker's rule modes (`sbx.h`): deny, read, write. */
    private const val BROKER_DENY = 0
    private const val BROKER_READ = 1
    private const val BROKER_WRITE = 2

    /** Every spelling of [word] in upper and lower case: 2^letters of them. */
    internal fun caseSpellings(word: String): List<String> = word.fold(listOf("")) { acc, c ->
        if (c.isLetter()) acc.flatMap { listOf(it + c.lowercaseChar(), it + c.uppercaseChar()) } else acc.map { it + c }
    }

    /** Whether droidtop itself may reach all of shared storage (all-files access; the storage permission before API 30). */
    fun allowed(context: android.content.Context): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            android.os.Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }

    /**
     * Each mounted volume's root by id ("primary", or the volume's own id). Found from droidtop's own app folder on each
     * volume, which exists on every API level this build runs on, rather than from a call only API 30 has.
     */
    fun roots(context: android.content.Context): Map<String, File> {
        val storage = context.getSystemService(android.os.storage.StorageManager::class.java)
        val out = LinkedHashMap<String, File>()
        @Suppress("DEPRECATION")
        out["primary"] = android.os.Environment.getExternalStorageDirectory()
        context.getExternalFilesDirs(null).filterNotNull().forEach { dir ->
            val root = dir.absolutePath.substringBefore("/Android/data/").takeIf { it != dir.absolutePath }?.let { File(it) } ?: return@forEach
            val volume = storage?.getStorageVolume(dir) ?: return@forEach
            if (volume.isPrimary) return@forEach
            out[volume.uuid ?: root.name] = root
        }
        return out
    }

    /** The file [path] names under [root], checked again after links are followed. */
    fun resolve(root: File, path: String): File {
        val clean = normalise(path) ?: throw BrokerException(PluginErrorCode.PERMISSION_DENIED, "$path is not reachable")
        val file = File(root, clean)
        val rootPath = root.canonicalPath
        val canonical = file.canonicalPath
        if (canonical != rootPath && !canonical.startsWith(rootPath + File.separator)) {
            throw BrokerException(PluginErrorCode.PERMISSION_DENIED, "$path leads outside the storage volume")
        }
        val relative = canonical.removePrefix(rootPath).trim(File.separatorChar)
        if (normalise(relative.replace(File.separatorChar, '/')) == null) throw BrokerException(PluginErrorCode.PERMISSION_DENIED, "$path is not reachable")
        return file
    }
}

internal object HostFileApis {
    private fun invalid(message: String): Nothing = throw BrokerException(PluginErrorCode.INVALID_ARGS, message)

    private fun tokens(env: BrokerEnvironment): PluginFileTokens =
        env.fileTokens() ?: throw BrokerException(PluginErrorCode.UNSUPPORTED, "this droidtop hands plugins no picked files")

    private fun root(env: BrokerEnvironment, args: JSONObject): File {
        if (!env.sharedFilesAllowed()) {
            throw BrokerException(PluginErrorCode.PERMISSION_DENIED, "droidtop itself has not been given access to all files; allow it in Android's settings first")
        }
        val roots = env.storageRoots()
        val volume = args.optString("volume").ifBlank { roots.keys.firstOrNull() ?: invalid("no storage volume is mounted") }
        return roots[volume] ?: throw BrokerException(PluginErrorCode.NOT_FOUND, "no storage volume $volume")
    }

    private val sharedScope: (DeclaredPermission, JSONObject) -> String? = { declared, args -> SharedFiles.scopeRefusal(declared, args.optString("path")) }

    private fun bytesOf(args: JSONObject): ByteArray = when {
        args.has("base64") -> runCatching { Base64.getDecoder().decode(args.optString("base64")) }.getOrElse { invalid("base64 is not base64") }
        args.has("text") -> args.optString("text").toByteArray(Charsets.UTF_8)
        else -> invalid("give text or base64")
    }.also { if (it.size > HostDataApis.MAX_CHUNK) invalid("one write is at most ${HostDataApis.MAX_CHUNK / 1024} KiB; append the rest, or open the file") }

    private fun modeFlags(mode: String, file: File): Int = when (mode) {
        "r" -> {
            if (!file.isFile) throw BrokerException(PluginErrorCode.NOT_FOUND, "no file at that path")
            android.os.ParcelFileDescriptor.MODE_READ_ONLY
        }
        "w" -> android.os.ParcelFileDescriptor.MODE_WRITE_ONLY or android.os.ParcelFileDescriptor.MODE_CREATE or android.os.ParcelFileDescriptor.MODE_TRUNCATE
        "a" -> android.os.ParcelFileDescriptor.MODE_WRITE_ONLY or android.os.ParcelFileDescriptor.MODE_CREATE or android.os.ParcelFileDescriptor.MODE_APPEND
        "rw" -> android.os.ParcelFileDescriptor.MODE_READ_WRITE or android.os.ParcelFileDescriptor.MODE_CREATE
        else -> invalid("mode is r, w, a or rw")
    }

    val ops: List<HostOp> = listOf(
        // D3: the storage volumes and their free space.
        HostOp("storage", "list_volumes", permission = "storage.volumes") { env, _, _ -> JSONObject().put("volumes", env.storageVolumes()) },

        // D4: the person picks a file in Android's own picker; the pick is the consent, and the plugin gets a token.
        HostOp(
            "files", "pick", permission = "files.picker", userOnly = true, alwaysAudit = true,
            target = { it.optString("mode", "open") },
        ) { env, record, args ->
            val mode = args.optString("mode", "open")
            if (mode != "open" && mode != "create") invalid("mode is open or create")
            val mime = args.optString("mime", "*/*").ifBlank { "*/*" }
            val name = args.optString("name").takeIf { it.isNotBlank() }
            if (mode == "create" && name == null) invalid("name is required to create a file")
            val picked = env.pickDocument(record.manifest.label, mode, mime, name)
                ?: return@HostOp JSONObject().put("picked", false)
            val token = tokens(env).add(record.manifest.id, picked)
            JSONObject().put("picked", true).put("token", token).put("name", picked.name).put("writable", picked.writable)
                .apply { picked.size?.let { put("size", it) } }
        },
        HostOp(
            "files", "open", permission = "files.picker", alwaysAudit = true,
            target = { it.optString("token") },
            open = { env, record, args ->
                val document = tokens(env).get(record.manifest.id, args.optString("token"))
                    ?: throw BrokerException(PluginErrorCode.NOT_FOUND, "no picked file with that token")
                val mode = args.optString("mode", "r")
                if (mode != "r" && !document.writable) throw BrokerException(PluginErrorCode.PERMISSION_DENIED, "${document.name} was picked for reading only")
                env.openDocument(document.uri, mode) ?: throw BrokerException(PluginErrorCode.NOT_FOUND, "${document.name} can no longer be opened")
            },
        ),
        HostOp("files", "forget", permission = "files.picker") { env, record, args ->
            JSONObject().put("forgotten", tokens(env).forget(record.manifest.id, args.optString("token")))
        },

        // D5: shared storage, inside the folders the plugin declared.
        HostOp(
            "files.shared", "list", permission = SharedFiles.READ, scope = sharedScope,
            target = { it.optString("path") }, alwaysAudit = true,
        ) { env, _, args ->
            val dir = SharedFiles.resolve(root(env, args), args.optString("path"))
            if (!dir.isDirectory) throw BrokerException(PluginErrorCode.NOT_FOUND, "no folder at that path")
            val entries = JSONArray()
            dir.listFiles().orEmpty().sortedBy { it.name.lowercase() }.take(MAX_LIST).forEach { f ->
                entries.put(JSONObject().put("name", f.name).put("folder", f.isDirectory).put("size", if (f.isFile) f.length() else 0L).put("modified", f.lastModified()))
            }
            JSONObject().put("entries", entries)
        },
        HostOp(
            "files.shared", "read", permission = SharedFiles.READ, scope = sharedScope,
            target = { it.optString("path") }, alwaysAudit = true,
        ) { env, _, args ->
            val file = SharedFiles.resolve(root(env, args), args.optString("path"))
            if (!file.isFile) throw BrokerException(PluginErrorCode.NOT_FOUND, "no file at that path")
            val size = file.length()
            val offset = args.optLong("offset", 0L).coerceIn(0L, size)
            val count = minOf(args.optInt("length", HostDataApis.MAX_READ).coerceIn(0, HostDataApis.MAX_READ).toLong(), size - offset).toInt()
            val bytes = ByteArray(count)
            java.io.RandomAccessFile(file, "r").use { it.seek(offset); it.readFully(bytes) }
            val out = JSONObject().put("size", size).put("eof", offset + count >= size)
            if (args.optString("as") == "base64") out.put("base64", Base64.getEncoder().encodeToString(bytes)) else out.put("text", bytes.toString(Charsets.UTF_8))
        },
        HostOp(
            "files.shared", "write", permission = SharedFiles.WRITE, scope = sharedScope,
            target = { it.optString("path") }, alwaysAudit = true,
        ) { env, _, args ->
            val file = SharedFiles.resolve(root(env, args), args.optString("path"))
            if (file.isDirectory) invalid("that path is a folder")
            file.parentFile?.mkdirs()
            val bytes = bytesOf(args)
            if (args.optBoolean("append", false)) file.appendBytes(bytes) else file.writeBytes(bytes)
            JSONObject().put("size", file.length())
        },
        HostOp(
            "files.shared", "mkdir", permission = SharedFiles.WRITE, scope = sharedScope,
            target = { it.optString("path") }, alwaysAudit = true,
        ) { env, _, args ->
            val dir = SharedFiles.resolve(root(env, args), args.optString("path"))
            JSONObject().put("created", dir.isDirectory || dir.mkdirs())
        },
        HostOp(
            "files.shared", "delete", permission = SharedFiles.WRITE, scope = sharedScope,
            target = { it.optString("path") }, alwaysAudit = true,
        ) { env, _, args ->
            val file = SharedFiles.resolve(root(env, args), args.optString("path"))
            // One file or one empty folder: a plugin cannot empty a tree in one call.
            JSONObject().put("deleted", file.exists() && file.delete())
        },
        HostOp(
            "files.shared", "open", permission = SharedFiles.READ, scope = sharedScope,
            permissionFor = { _, args, _ -> if (args.optString("mode", "r") == "r") SharedFiles.READ else SharedFiles.WRITE },
            target = { it.optString("path") + " (" + it.optString("mode", "r") + ")" }, alwaysAudit = true,
            open = { env, _, args ->
                val file = SharedFiles.resolve(root(env, args), args.optString("path"))
                val flags = modeFlags(args.optString("mode", "r"), file)
                file.parentFile?.mkdirs()
                android.os.ParcelFileDescriptor.open(file, flags)
            },
        ),
    )

    private const val MAX_LIST = 2_000
}
