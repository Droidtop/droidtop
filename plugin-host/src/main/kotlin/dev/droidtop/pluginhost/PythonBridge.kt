package dev.droidtop.pluginhost

import org.json.JSONObject

/**
 * Thrown by the native bridge (`native/src/droidtoppy_jni.c`) when a
 * Python-level call fails -- an exception raised inside the plugin's own
 * `on_load`/`invoke`/`on_unload`, or the bootstrap module reporting "no
 * such function"/"module not loaded". Caught the same way any other
 * plugin exception is (docs/SPEC.md 12a: "throwing is fine, expected way
 * to report a failure") -- [PluginRuntimeService] wraps every call to a
 * [DroidtopPlugin] in the same `catch (t: Throwable)`, so a
 * [PythonCallException] here needs no special handling there.
 */
class PythonCallException(message: String) : RuntimeException(message)

/**
 * The JNI surface onto `libdroidtoppy.so` (`plugin-host/native`). One
 * instance per process, matching the native side's own process-wide
 * interpreter (see that file's header comment for why CPython isn't
 * embedded per-plugin): [nativeInit] is idempotent and safe to call once
 * per plugin load, [nativeLoadModule]/[nativeCallFunction]/
 * [nativeUnloadModule] are keyed by [uniqueName] (the plugin id) so two
 * python-kind plugins loaded into the same `:pluginhost` process never
 * collide in the interpreter's own `sys.modules`.
 *
 * Every native entry point here throws [PythonCallException] on a
 * Python-side failure rather than returning a sentinel -- deliberately
 * the same shape [DroidtopPlugin.invoke] itself already uses.
 */
object PythonBridge {
    init {
        System.loadLibrary("droidtoppy")
    }

    /**
     * Initializes the process-wide interpreter the first time it's
     * called; every later call (from a second, third, ... python-kind
     * plugin loaded into this same `:pluginhost` process) is a cheap
     * no-op that returns true. [pythonHome] is the directory
     * [PythonRuntimeManager] extracted the runtime into -- the one that
     * directly contains `lib/python3.14` -- set as `PYTHONHOME` before
     * `Py_Initialize` runs, not passed through a `PyConfig` (see the
     * native file's header for why). [libpythonPath] is the absolute
     * path to that same extraction's `libpython3.14.so`.
     */
    external fun nativeInit(pythonHome: String, libpythonPath: String): Boolean

    /**
     * Loads [path] (a single `.py` file) as a module named [uniqueName]
     * and calls its `on_load(data_dir)` if present. Throws
     * [PythonCallException] on any Python-side failure (a syntax error,
     * an exception raised from the module's own top level or its
     * `on_load`).
     */
    external fun nativeLoadModule(uniqueName: String, path: String, dataDir: String)

    /**
     * Calls `<module>.funcName(argJson)` and returns its string result.
     * The plugin's own function is responsible for both JSON-decoding
     * [argJson] and JSON-encoding its return value -- the bridge never
     * parses either, exactly like the binder boundary a native_bundle
     * plugin crosses (JSON in, JSON out, [PluginRuntimeService]).
     */
    external fun nativeCallFunction(uniqueName: String, funcName: String, argJson: String): String

    /** Calls `on_load`'s counterpart if the module defines one, then drops it from `sys.modules`. Best-effort: never throws. */
    external fun nativeUnloadModule(uniqueName: String)

    /**
     * [nativeLoadModule] for a contained plugin (docs/plugin-api.md 5.3), whose process can open no path: [source] is the
     * text of its `plugin.py`, executed as the module [uniqueName].
     */
    external fun nativeLoadSource(uniqueName: String, source: String, dataDir: String)

    /**
     * Starts the interpreter in a contained plugin's isolated process from descriptors only (docs/plugin-api.md 5.3).
     * [depFds] are the runtime's other libraries (loaded first, in any order: each is retried until none loads), then
     * libpython itself; [zipFd] is the standard library zip, which goes on `sys.path` as `/proc/self/fd/<n>` and is read
     * through the descriptor; each of [extFds] is a `lib-dynload` module registered as a built-in under its
     * [extNames] entry before the interpreter starts. Every descriptor becomes the bridge's own. Returns a JSON report:
     * `ok`, `error`, and how each library loaded (`fd`, or `memfd` when mapping the file's own descriptor was refused and
     * a private copy worked), which is the spike's evidence.
     */
    external fun nativeInitContained(
        libpythonFd: Int,
        libpythonName: String,
        zipFd: Int,
        depFds: IntArray,
        depNames: Array<String>,
        extFds: IntArray,
        extNames: Array<String>,
        extFiles: Array<String>,
    ): String

    /** [nativeInitContained] from the descriptors :app handed over, by [ContainedFiles] name. Never throws. */
    fun initContained(files: Map<String, android.os.ParcelFileDescriptor>): JSONObject = try {
        val libpython = files.entries.firstOrNull { it.key.startsWith(ContainedFiles.PYTHON_LIBPYTHON) }
            ?: throw IllegalStateException("libpython was not handed over")
        val zip = files[ContainedFiles.PYTHON_STDLIB] ?: throw IllegalStateException("the standard library zip was not handed over")
        val deps = files.entries.filter { it.key.startsWith(ContainedFiles.PYTHON_DEP) }
        // `python/ext/<module>/<file>`
        val exts = files.entries.filter { it.key.startsWith(ContainedFiles.PYTHON_EXT) }
        JSONObject(
            nativeInitContained(
                libpythonFd = libpython.value.detachFd(),
                libpythonName = libpython.key.removePrefix(ContainedFiles.PYTHON_LIBPYTHON),
                zipFd = zip.detachFd(),
                depFds = deps.map { it.value.detachFd() }.toIntArray(),
                depNames = deps.map { it.key.removePrefix(ContainedFiles.PYTHON_DEP) }.toTypedArray(),
                extFds = exts.map { it.value.detachFd() }.toIntArray(),
                extNames = exts.map { it.key.removePrefix(ContainedFiles.PYTHON_EXT).substringBefore('/') }.toTypedArray(),
                extFiles = exts.map { it.key.substringAfterLast('/') }.toTypedArray(),
            ),
        )
    } catch (t: Throwable) {
        JSONObject().put("ok", false).put("error", t.message ?: t::class.java.simpleName)
    }

    /**
     * The Java end of `droidtop.host.call` (docs/plugin-api.md 1.3), called by
     * `native/src/droidtoppy_jni.c` from whichever thread Python made the call on.
     * Never throws: a JNI caller cannot do anything with an exception, so every
     * failure is an error reply.
     */
    @JvmStatic
    fun hostCall(pluginId: String, requestJson: String): String = PythonHostCalls.call(pluginId, requestJson)

    /** The Java end of `droidtop.host.open`: the broker reply, with the descriptor number under `fd` when a file was handed over. Never throws. */
    @JvmStatic
    fun hostOpen(pluginId: String, requestJson: String): String = PythonHostCalls.open(pluginId, requestJson)
}

/**
 * The broker path of the python kind: every loaded python plugin's [PluginContext] by plugin
 * id, so the one interpreter-wide `droidtop.host.call` reaches the broker of the plugin that
 * made the call and no other. The context's `call` is the same one a native_bundle plugin
 * gets, so the declared-and-granted permission checks, the first-use sheet, the quota and the
 * audit are the broker's and identical for both kinds. The point checks (a point the plugin
 * never declared, or the user switched off) guard the other direction and run in
 * [PluginCrashPolicy] before `handle` is entered.
 */
internal object PythonHostCalls {
    private val contexts = java.util.concurrent.ConcurrentHashMap<String, PluginContext>()

    fun register(pluginId: String, context: PluginContext) {
        contexts[pluginId] = context
    }

    fun unregister(pluginId: String) {
        contexts.remove(pluginId)
    }

    fun call(pluginId: String, requestJson: String): String {
        val context = contexts[pluginId]
            ?: return PluginReply.error(PluginErrorCode.FAILED, "plugin is not loaded").encode()
        val request = try {
            HostCallRequest.parse(requestJson)
        } catch (e: Exception) {
            return PluginReply.error(PluginErrorCode.INVALID_ARGS, e.message ?: "malformed host call").encode()
        }
        val reply = try {
            context.call(request.api, request.version, request.op, request.argsJson)
        } catch (t: Throwable) {
            PluginReply.error(PluginErrorCode.FAILED, t.message ?: "broker call failed").encode()
        }
        return asciiJson(reply)
    }

    /** `droidtop.host.open`: the same request through [PluginContext.openFile]; a file handed over is detached and its number put in the reply as `fd`, the plugin's to close. */
    fun open(pluginId: String, requestJson: String): String {
        val context = contexts[pluginId]
            ?: return PluginReply.error(PluginErrorCode.FAILED, "plugin is not loaded").encode()
        val request = try {
            HostCallRequest.parse(requestJson)
        } catch (e: Exception) {
            return PluginReply.error(PluginErrorCode.INVALID_ARGS, e.message ?: "malformed host call").encode()
        }
        val reply = try {
            val file = context.openFile(request.api, request.version, request.op, request.argsJson)
            val json = runCatching { JSONObject(file.reply) }.getOrElse { PluginReply.error(PluginErrorCode.FAILED, "malformed reply").toJson() }
            file.fd?.let { json.put("fd", it.detachFd()) }
            json.toString()
        } catch (t: Throwable) {
            PluginReply.error(PluginErrorCode.FAILED, t.message ?: "broker call failed").encode()
        }
        return asciiJson(reply)
    }

    /**
     * The reply with every non-ASCII character written as a JSON unicode escape. JNI hands a Java string to
     * C in "modified UTF-8", which CPython cannot decode for a character outside the BMP (an emoji in a
     * game's name is two surrogates there); JSON text written as ASCII carries the same characters and
     * `json.loads` puts them back. Valid JSON only has non-ASCII inside strings, where the escape is legal.
     */
    internal fun asciiJson(json: String): String {
        if (json.all { it.code < 0x80 }) return json
        val out = StringBuilder(json.length + 16)
        for (c in json) if (c.code < 0x80) out.append(c) else out.append("\\u").append("%04x".format(c.code))
        return out.toString()
    }
}
