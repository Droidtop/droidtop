package dev.droidtop.sandboxhost

import java.io.Closeable
import java.io.File
import java.security.SecureRandom

/**
 * The sandbox library's program-tree binding (docs/SPEC.md 5b "Containment and copy-on-write layers for Wine",
 * piece 0; Droidtop/tracker#584).
 *
 * A [Session] is one broker listener for one tree of processes: droidtop starts it before the tree, puts [environment]
 * into the tree's first process and [libsbx_child.so][CHILD_LIBRARY] first on its LD_PRELOAD ([withChildPreload]),
 * and closes it when the tree is done. Every process of the tree attaches before main and stays attached across its
 * forks and execs.
 *
 * Piece 0 is learning only ([startLearning]): every request is allowed and each distinct one is written once to the
 * learn log. It gives NO protection; whoever switches it on says so.
 */
object SandboxHost {
    const val CHILD_LIBRARY = "libsbx_child.so"
    const val EXEC_HELPER = "libsbx_exec.so"

    // The lockdown flags (vendor/sandbox include/sbx.h), the ones a host passes in SBX_FLAGS.
    const val LOCK_LOG_SOCKET = 0x1
    const val LOCK_LEARN = 0x20
    const val LOCK_PATHOPS = 0x100
    const val LOCK_INOTIFY = 0x200
    const val LOCK_SOCKETS = 0x400
    const val LOCK_SIGNALS = 0x800
    const val LOCK_EXEC = 0x1000

    /** SBX_LOCK_CONTAIN: the containment preset Wine's tree runs under (SPEC 5b, "Wine gets a containment sandbox"). */
    const val LOCK_CONTAIN = LOCK_PATHOPS or LOCK_INOTIFY or LOCK_SOCKETS or LOCK_SIGNALS or LOCK_EXEC

    /** Containment, Android's log socket, and learning: records what a real lockdown would refuse, refuses nothing. */
    const val LEARNING_FLAGS = LOCK_CONTAIN or LOCK_LOG_SOCKET or LOCK_LEARN

    /** A Unix socket's path is at most 107 bytes. */
    private const val SOCKET_PATH_MAX = 107

    private val loaded: Boolean by lazy { runCatching { System.loadLibrary("droidtopsbx") }.isSuccess }

    @JvmStatic private external fun nativeListen(socket: String, token: String, log: String): Int

    @JvmStatic private external fun nativeClose(slot: Int)

    class Session internal constructor(val socket: File, val token: String, val learnLog: File, private val slot: Int) : Closeable {
        /** What the tree's first process needs to attach, for a learning tree. */
        fun environment(nativeLibraryDir: File, ownOpens: List<String>): Map<String, String> =
            SandboxHost.environment(socket.path, token, LEARNING_FLAGS, nativeLibraryDir, ownOpens)

        /** Stops accepting; processes already attached are served until they end. */
        override fun close() = nativeClose(slot)
    }

    /**
     * Starts a learning listener with its socket in [socketDir] (droidtop's own, which no other app can enter) and its
     * records in [learnLog]; null when the native library is missing or the broker could not listen (it logs why).
     * Disk: off the main thread.
     */
    fun startLearning(socketDir: File, learnLog: File): Session? {
        if (!loaded) return null
        socketDir.mkdirs()
        learnLog.parentFile?.mkdirs()
        val socket = File(socketDir, "b-${hex(4)}.sock")
        if (socket.path.toByteArray().size > SOCKET_PATH_MAX) return null
        val token = hex(24)
        val slot = nativeListen(socket.path, token, learnLog.path)
        return if (slot < 0) null else Session(socket, token, learnLog, slot)
    }

    /**
     * The environment libsbx_child.so attaches from (sbx.h, sbx_attach_from_environment). [ownOpens] are device nodes
     * the process opens itself rather than through the broker: a GPU driver's (Turnip, the wrapper) must be Wine's own
     * descriptor, not one opened in droidtop's process. Pure.
     */
    fun environment(socket: String, token: String, flags: Int, nativeLibraryDir: File, ownOpens: List<String>): Map<String, String> =
        buildMap {
            put("SBX_BROKER", socket)
            put("SBX_TOKEN", token)
            put("SBX_FLAGS", "0x" + Integer.toHexString(flags))
            if (ownOpens.isNotEmpty()) put("SBX_OWN_OPENS", ownOpens.joinToString(":"))
            if (flags and LOCK_EXEC != 0) {
                put("SBX_EXEC_HELPER", File(nativeLibraryDir, EXEC_HELPER).path)
                put("SBX_CHILD_LIBRARY", File(nativeLibraryDir, CHILD_LIBRARY).path)
            }
        }

    /**
     * [existing] LD_PRELOAD with libsbx_child.so first, so the process attaches before any other preload runs; the
     * others are kept in their order. Bionic separates entries with ':' or a space. Pure.
     */
    fun withChildPreload(existing: String?, nativeLibraryDir: File): String {
        val child = File(nativeLibraryDir, CHILD_LIBRARY).path
        val rest = existing.orEmpty().split(':', ' ').filter { it.isNotBlank() && it != child }
        return (listOf(child) + rest).joinToString(":")
    }

    private val random = SecureRandom()

    private fun hex(bytes: Int): String = ByteArray(bytes).also(random::nextBytes).joinToString("") { "%02x".format(it) }
}
