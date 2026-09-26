package dev.droidtop.pluginhost

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
}
