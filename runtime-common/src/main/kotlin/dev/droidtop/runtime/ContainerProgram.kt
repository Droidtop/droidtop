package dev.droidtop.runtime

import java.io.File
import java.io.RandomAccessFile

/**
 * An app container's own program, run the way Docker runs it and kept by droidtop (docs/SPEC.md 3 "The image's own
 * program", Droidtop/tracker#555 D6b): started and stopped from the container page, its output in a log of capped
 * size, restarted when it fails if the person wants that, and started with the desktop if they want that.
 */
object ContainerProgram {
    /** Where the logs live, under the app storage every container sees (`files/container-logs`). */
    const val LOG_DIR_NAME = "container-logs"

    /** The log is trimmed when it passes this... */
    const val LOG_MAX_BYTES = 1L * 1024 * 1024

    /** ...to its last this many bytes. */
    const val LOG_KEEP_BYTES = 512L * 1024

    /** How the waits between restarts grow: 2 s, 4 s, 8 s ... up to a minute. */
    fun restartDelayMs(failuresInARow: Int): Long = (2_000L shl (failuresInARow - 1).coerceIn(0, 5)).coerceAtMost(60_000L)

    /** A program that ran this long before it failed counts as having started, and its failure count starts over. */
    const val RAN_LONG_ENOUGH_MS = 30_000L

    /** After this many failures in a row, each sooner than [RAN_LONG_ENOUGH_MS], it is left stopped and says so. */
    const val MAX_FAILURES_IN_A_ROW = 5

    /**
     * Whether to start the program again after it ended with [exitCode], [failuresInARow] quick failures so far, under
     * [policy]. Every policy gives up after [MAX_FAILURES_IN_A_ROW] quick exits in a row (or the count `on-failure:N`
     * names), clean ones too for `always`: a program that exits at once would otherwise restart for ever.
     */
    fun restarts(policy: RestartPolicy, exitCode: Int, failuresInARow: Int): Boolean = when (policy) {
        RestartPolicy.No -> false
        is RestartPolicy.OnFailure -> exitCode != 0 && failuresInARow < (policy.maxRetries ?: MAX_FAILURES_IN_A_ROW)
        RestartPolicy.Always, RestartPolicy.UnlessStopped -> failuresInARow < MAX_FAILURES_IN_A_ROW
    }

    /**
     * When a container's own program is started again (docs/SPEC.md 3d, "Restart policies"; Droidtop/tracker#555 D12):
     * Docker's and Podman's `--restart` words, and the page's "Restart when it fails" ([OnFailure] on, [No] off). The
     * one setting the program runner reads.
     */
    sealed interface RestartPolicy {
        /** Never started again. `docker run`'s and `podman run`'s default. */
        data object No : RestartPolicy

        /** Started again after a failing exit, up to [maxRetries] quick failures in a row (droidtop's cap when null). */
        data class OnFailure(val maxRetries: Int? = null) : RestartPolicy

        /** Started again after any exit, and with the desktop. */
        data object Always : RestartPolicy

        /** As [Always], except that a program the person stopped stays stopped when the desktop starts again. */
        data object UnlessStopped : RestartPolicy

        /** The policy in Docker's words: `no`, `on-failure`, `on-failure:3`, `always`, `unless-stopped`. */
        fun words(): String = when (this) {
            No -> "no"
            is OnFailure -> if (maxRetries == null) "on-failure" else "on-failure:$maxRetries"
            Always -> "always"
            UnlessStopped -> "unless-stopped"
        }

        /** Whether the program starts with the desktop under this policy. */
        val withDesktop: Boolean get() = this == Always || this == UnlessStopped

        companion object {
            /** droidtop's own default for a container made on its page: a server is expected to stay up. */
            val PAGE_DEFAULT: RestartPolicy = OnFailure()

            /** [words] read back, or null for anything else. Pure. */
            fun parse(words: String): RestartPolicy? = when {
                words == "no" -> No
                words == "always" -> Always
                words == "unless-stopped" -> UnlessStopped
                words == "on-failure" -> OnFailure()
                words.startsWith("on-failure:") -> words.substringAfter(':').toIntOrNull()?.takeIf { it in 0..1000 }?.let { OnFailure(it) }
                else -> null
            }

            /** What is stored for a container: these words, or the page's old true and false. Pure. */
            fun stored(value: String?): RestartPolicy = when (value) {
                null -> PAGE_DEFAULT
                "true" -> OnFailure()
                "false" -> No
                else -> parse(value) ?: PAGE_DEFAULT
            }
        }
    }

    fun hostLogFile(appStorageDir: File, containerId: String): File = File(File(appStorageDir, LOG_DIR_NAME), "$containerId.log")

    /**
     * What `docker run` asked for beyond the image (Droidtop/tracker#555 D6d): [args] replace the image's `Cmd` (never
     * its entrypoint, as Docker does) and [env] (`NAME=VALUE`) is added after the image's own, so it wins.
     * [entrypoint] (`--entrypoint`) replaces the image's, and its `Cmd` too unless [args] are given, and [workingDir]
     * (`-w`) the folder it starts in (Droidtop/tracker#715). Kept beside the container's log, so the page's Start it
     * and the start with the desktop run the same program as the command.
     */
    data class RunOverrides(
        val args: List<String>? = null,
        val env: List<String> = emptyList(),
        /** The labels it was made with (`--label`, the Engine API's `Labels`): what `docker compose` finds it by. */
        val labels: Map<String, String> = emptyMap(),
        val entrypoint: List<String>? = null,
        val workingDir: String? = null,
        /** `-u`, the APIs' User: replaces the image's own `User` (Droidtop/tracker#715). */
        val user: String? = null,
    ) {
        /** [config] as this container runs it. Pure. */
        fun applyTo(config: ImageRunConfig): ImageRunConfig = config.copy(
            entrypoint = entrypoint ?: config.entrypoint,
            // Docker: a new entrypoint drops the image's Cmd, which was written for the old one.
            cmd = args ?: if (entrypoint != null) emptyList() else config.cmd,
            env = config.env + env,
            workingDir = workingDir ?: config.workingDir,
            user = user ?: config.user,
        )

        fun encode(): String = kotlinx.serialization.json.buildJsonObject {
            args?.let { a -> put("args", kotlinx.serialization.json.JsonArray(a.map { kotlinx.serialization.json.JsonPrimitive(it) })) }
            put("env", kotlinx.serialization.json.JsonArray(env.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            if (labels.isNotEmpty()) put("labels", kotlinx.serialization.json.JsonObject(labels.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) }))
            entrypoint?.let { e -> put("entrypoint", kotlinx.serialization.json.JsonArray(e.map { kotlinx.serialization.json.JsonPrimitive(it) })) }
            workingDir?.let { put("workingDir", kotlinx.serialization.json.JsonPrimitive(it)) }
            user?.let { put("user", kotlinx.serialization.json.JsonPrimitive(it)) }
        }.toString()

        companion object {
            fun decode(text: String): RunOverrides? = runCatching {
                val o = kotlinx.serialization.json.Json.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject
                fun list(key: String) = (o[key] as? kotlinx.serialization.json.JsonArray)
                    ?.map { (it as kotlinx.serialization.json.JsonPrimitive).content }
                val labels = (o["labels"] as? kotlinx.serialization.json.JsonObject)
                    ?.mapValues { (it.value as kotlinx.serialization.json.JsonPrimitive).content }.orEmpty()
                val workingDir = (o["workingDir"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                val user = (o["user"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                RunOverrides(list("args"), list("env").orEmpty(), labels, list("entrypoint"), workingDir, user)
            }.getOrNull()
        }
    }

    fun overridesFile(appStorageDir: File, containerId: String): File = File(File(appStorageDir, LOG_DIR_NAME), "$containerId.run.json")

    /** [containerId]'s overrides, or null when it has none (a container made on its page, or before `docker run` had them). */
    fun overrides(appStorageDir: File, containerId: String): RunOverrides? =
        overridesFile(appStorageDir, containerId).takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }?.let(RunOverrides::decode)

    fun saveOverrides(appStorageDir: File, containerId: String, overrides: RunOverrides) {
        val file = overridesFile(appStorageDir, containerId)
        file.parentFile?.mkdirs()
        file.writeText(overrides.encode())
    }

    /** The program [containerId] runs: its image's config with what `docker run` asked for applied. */
    fun effectiveConfig(appStorageDir: File, containerId: String, image: ImageRunConfig): ImageRunConfig =
        overrides(appStorageDir, containerId)?.applyTo(image) ?: image

    fun containerLogPath(containerId: String): String = "${ContainerLayout.APP_STORAGE_DIR}/$LOG_DIR_NAME/$containerId.log"

    /**
     * What runs in the container: the program in its working folder, with its output appended to the log. Through
     * `/bin/sh`, which an image without a shell (a distroless one) does not have; such an image cannot be run here yet.
     * The program and its arguments are argv entries, never shell text.
     */
    fun command(config: ImageRunConfig, containerId: String, args: List<String>? = null): List<String> =
        listOf(
            "/bin/sh",
            "-c",
            "log=\$1; shift; [ -n \"\$DROIDTOP_WORKDIR\" ] && cd \"\$DROIDTOP_WORKDIR\"; " +
                "echo \"--- started \$(date) ---\" >> \"\$log\"; exec >> \"\$log\" 2>&1; " + ContainerUser.EXEC_TAIL,
            "sh",
            containerLogPath(containerId),
        ) + config.program(args)

    /** The program's environment: the image's own `Env`, and its working folder for [command]. */
    fun environment(config: ImageRunConfig): Map<String, String> =
        config.environment() + (config.workingDir?.let { mapOf("DROIDTOP_WORKDIR" to it) } ?: emptyMap())

    /**
     * Keeps [log] under [LOG_MAX_BYTES]: past it, the file keeps its last [LOG_KEEP_BYTES], cut at a line start. The
     * file is rewritten in place, never replaced, because the running program holds it open for appending, and an
     * append always lands at the file's current end. Returns how many bytes it took off the start, 0 for none, for
     * the log's times to move with ([LogTimes.shift]).
     */
    fun trim(log: File, maxBytes: Long = LOG_MAX_BYTES, keepBytes: Long = LOG_KEEP_BYTES): Long {
        if (!log.isFile || log.length() <= maxBytes) return 0L
        RandomAccessFile(log, "rw").use { file ->
            val length = file.length()
            val tail = ByteArray(keepBytes.coerceAtMost(length).toInt())
            file.seek(length - tail.size)
            file.readFully(tail)
            val lineStart = tail.indexOf('\n'.code.toByte()).let { if (it < 0) 0 else it + 1 }
            file.seek(0)
            file.write(tail, lineStart, tail.size - lineStart)
            file.setLength((tail.size - lineStart).toLong())
            return length - (tail.size - lineStart)
        }
    }

    /** The log's last [lines] lines, for the container page. */
    fun lastLines(log: File, lines: Int = 40): List<String> {
        if (!log.isFile) return emptyList()
        val bytes = RandomAccessFile(log, "r").use { file ->
            val take = file.length().coerceAtMost(64L * 1024).toInt()
            ByteArray(take).also {
                file.seek(file.length() - take)
                file.readFully(it)
            }
        }
        return String(bytes, Charsets.UTF_8).trimEnd('\n').lines().takeLast(lines)
    }
}
