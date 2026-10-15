package dev.droidtop.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * How an image says its own program runs, from the image config's `config` object (OCI image spec,
 * "Properties": `Env`, `Entrypoint`, `Cmd`, `WorkingDir`, `User`). docs/SPEC.md 3 "The image's own program",
 * Droidtop/tracker#555 D6a: a Docker image's program is started the way Docker starts it.
 */
data class ImageRunConfig(
    val env: List<String> = emptyList(),
    val entrypoint: List<String> = emptyList(),
    val cmd: List<String> = emptyList(),
    val workingDir: String? = null,
    val user: String? = null,
    /** `ExposedPorts`: the ports the image's program says it listens on, as notes (Droidtop/tracker#555 D6). */
    val ports: List<ExposedPort> = emptyList(),
) {
    /**
     * The program and its arguments, as Docker runs them: the entrypoint followed by [args] when given, else by
     * the image's `Cmd` (an argument list replaces `Cmd`, never the entrypoint). Empty when the image names no
     * program, which is a base image (alpine, debian) meant to be entered, not run.
     */
    fun program(args: List<String>? = null): List<String> = entrypoint + (args ?: cmd)

    /** `Env` as a map; an entry without `=` is a name with an empty value, as Docker reads it. Later entries win. */
    fun environment(): Map<String, String> = env.associate { line ->
        val eq = line.indexOf('=')
        if (eq < 0) line to "" else line.substring(0, eq) to line.substring(eq + 1)
    }

    /** Whether the image runs as root, which is the only user droidtop's containers run as ([user] empty, `root` or `0`). */
    val runsAsRoot: Boolean get() = user.isNullOrBlank() || user.substringBefore(':') in setOf("root", "0")

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** [words] as one line a person reads (and a shell would take): a word with spaces or quotes is single-quoted. */
        fun shellWords(words: List<String>): String = words.joinToString(" ") { word ->
            if (word.isNotEmpty() && word.all { it.isLetterOrDigit() || it in "-_./:=,@%+" }) word else "'" + word.replace("'", "'\\''") + "'"
        }

        /** Reads an image config blob (the whole document); the run settings are its `config` object. */
        fun fromImageConfig(text: String): ImageRunConfig {
            val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return ImageRunConfig()
            val config = root["config"] as? JsonObject ?: return ImageRunConfig()
            return ImageRunConfig(
                env = config.strings("Env"),
                entrypoint = config.strings("Entrypoint"),
                cmd = config.strings("Cmd"),
                workingDir = config.string("WorkingDir")?.takeIf { it.isNotBlank() },
                user = config.string("User")?.takeIf { it.isNotBlank() },
                ports = (config["ExposedPorts"] as? JsonObject)?.keys?.mapNotNull(ExposedPort::parse)?.distinct()?.sorted().orEmpty(),
            )
        }

        /** The compact form a container keeps beside its own config. */
        fun encode(config: ImageRunConfig): String = buildJsonObject {
            put("config", buildJsonObject {
                putJsonArray("Env") { config.env.forEach { add(JsonPrimitive(it)) } }
                putJsonArray("Entrypoint") { config.entrypoint.forEach { add(JsonPrimitive(it)) } }
                putJsonArray("Cmd") { config.cmd.forEach { add(JsonPrimitive(it)) } }
                config.workingDir?.let { put("WorkingDir", it) }
                config.user?.let { put("User", it) }
                if (config.ports.isNotEmpty()) putJsonObject("ExposedPorts") { config.ports.forEach { put(it.toString(), buildJsonObject {}) } }
            })
        }.toString()

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

        /** A JSON array of strings; `null` (Docker writes `"Cmd": null`) or anything else is empty. */
        private fun JsonObject.strings(key: String): List<String> =
            (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
    }
}

/**
 * One `ExposedPorts` key (`8080/tcp`, `53/udp`; no protocol means tcp, as Docker reads it). There are no network
 * namespaces here: the program listens on the device's own port, so a port is where to reach it, not a mapping.
 */
data class ExposedPort(val number: Int, val protocol: String = TCP) : Comparable<ExposedPort> {
    /** Where a browser on the device reaches it; only a tcp port is a web address. */
    val url: String? get() = if (protocol == TCP) "http://localhost:$number" else null

    /** Below 1024 a port needs root to listen on, which a proot container does not have. */
    val needsRoot: Boolean get() = number < FIRST_UNPRIVILEGED

    override fun compareTo(other: ExposedPort): Int = compareValuesBy(this, other, { it.number }, { it.protocol })

    override fun toString(): String = "$number/$protocol"

    companion object {
        const val TCP = "tcp"
        private const val FIRST_UNPRIVILEGED = 1024

        fun parse(key: String): ExposedPort? {
            val number = key.substringBefore('/').toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            val protocol = key.substringAfter('/', TCP).lowercase().takeIf { it in setOf(TCP, "udp", "sctp") } ?: return null
            return ExposedPort(number, protocol)
        }

        /**
         * Why [name]'s program must not start while the programs in [running] (container name to its ports) are up:
         * the first port it shares with one of them, as a sentence naming both, or null when none clash. Every
         * program listens on the device's own ports, so two on one port would have the second fail, or worse, the
         * person reach the wrong one.
         */
        fun clash(name: String, ports: List<ExposedPort>, running: Map<String, List<ExposedPort>>): String? {
            for ((other, theirs) in running) {
                if (other == name) continue
                val shared = ports.firstOrNull { it in theirs } ?: continue
                return "$name and $other both use port ${shared.number}; stop $other's program first"
            }
            return null
        }
    }
}
