/*
 * droidtop's Python plugin bridge (docs/SPEC.md 12a). Loaded into
 * :pluginhost (this module's own process) as libdroidtoppy.so, itself
 * bundled in the base APK like any other JNI library -- what is NEVER
 * bundled is CPython: PythonRuntimeManager downloads the official
 * python.org Android build (PEP 738) on first use of a python-kind
 * plugin, verifies it, and hands this bridge a path to the resulting
 * libpython*.so, which this file dlopen()s at runtime.
 *
 * Only the small, genuinely ABI-stable slice of the C API is used --
 * the same functions any embedder could rely on across CPython patch
 * and minor versions without recompiling against that exact build's
 * headers (Py_Initialize, PyRun_SimpleString, PyImport_ImportModule,
 * PyObject_CallFunction, PyUnicode_From/AsUTF8, PyErr_Fetch). PyConfig
 * (the modern, struct-based init API the CPython Android testbed's own
 * main_activity.c uses) is deliberately NOT used here: its field layout
 * is tied to the exact CPython build it was compiled against, which is
 * fine for the testbed (built and linked against one pinned CPython
 * checkout) but wrong for a bridge that dlsym()s into a runtime chosen
 * and downloaded independently of this .so's own build.
 *
 * Process-wide, not per-plugin: CPython does not offer a clean way to
 * run fully independent interpreters through the stable C API alone
 * (that is the sub-interpreter API, itself not part of the stable ABI
 * either), so this bridge initializes ONE interpreter per :pluginhost
 * process and loads every python-kind plugin as its own module inside
 * it, keyed by a unique name (the plugin id) so two plugins' module
 * globals never collide. This is consistent with the existing
 * crash-containment story: a Python-level exception in one plugin's
 * code is caught and reported like any other plugin failure; a genuine
 * native fault inside libpython.so takes down :pluginhost, never :app,
 * exactly like a native_bundle plugin's own .so crashing would.
 */
#include <jni.h>
#include <dlfcn.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

#define LOG_TAG "droidtoppy"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

typedef struct _object PyObject;

/* Function-pointer typedefs for the stable-ABI subset used here. */
typedef void (*Py_InitializeEx_t)(int);
typedef int (*Py_IsInitialized_t)(void);
typedef int (*Py_FinalizeEx_t)(void);
typedef int (*PyRun_SimpleString_t)(const char *);
typedef PyObject *(*PyImport_ImportModule_t)(const char *);
typedef PyObject *(*PyObject_GetAttrString_t)(PyObject *, const char *);
typedef PyObject *(*PyObject_CallFunction_t)(PyObject *, const char *, ...);
typedef PyObject *(*PyUnicode_FromString_t)(const char *);
typedef const char *(*PyUnicode_AsUTF8_t)(PyObject *);
typedef void (*Py_DecRef_t)(PyObject *);
typedef void (*Py_IncRef_t)(PyObject *);
typedef PyObject *(*PyErr_Occurred_t)(void);
typedef void (*PyErr_Fetch_t)(PyObject **, PyObject **, PyObject **);
typedef void (*PyErr_NormalizeException_t)(PyObject **, PyObject **, PyObject **);
typedef PyObject *(*PyObject_Str_t)(PyObject *);
typedef void (*PyErr_Clear_t)(void);

typedef struct {
    void *libpython;
    Py_InitializeEx_t Py_InitializeEx;
    Py_IsInitialized_t Py_IsInitialized;
    Py_FinalizeEx_t Py_FinalizeEx;
    PyRun_SimpleString_t PyRun_SimpleString;
    PyImport_ImportModule_t PyImport_ImportModule;
    PyObject_GetAttrString_t PyObject_GetAttrString;
    PyObject_CallFunction_t PyObject_CallFunction;
    PyUnicode_FromString_t PyUnicode_FromString;
    PyUnicode_AsUTF8_t PyUnicode_AsUTF8;
    Py_DecRef_t Py_DecRef;
    Py_IncRef_t Py_IncRef;
    PyErr_Occurred_t PyErr_Occurred;
    PyErr_Fetch_t PyErr_Fetch;
    PyErr_NormalizeException_t PyErr_NormalizeException;
    PyObject_Str_t PyObject_Str;
    PyErr_Clear_t PyErr_Clear;

    /* The bootstrap module's three helpers, resolved once after init. */
    PyObject *fn_load;
    PyObject *fn_call;
    PyObject *fn_unload;
} DroidtopPy;

/* One process-wide interpreter (see file header). */
static DroidtopPy g_py = {0};

/* This module is executed once via PyRun_SimpleString right after
 * Py_InitializeEx. importlib.util is stdlib (present in every official
 * CPython Android build -- confirmed against the 3.14.7 archive's own
 * prefix/lib/python3.14/importlib tree), so no extra packaging is
 * needed for it. */
static const char *BOOTSTRAP_SOURCE =
    "import sys, importlib.util\n"
    "_droidtop_modules = {}\n"
    "def _droidtop_load(unique_name, path, data_dir):\n"
    "    spec = importlib.util.spec_from_file_location(unique_name, path)\n"
    "    module = importlib.util.module_from_spec(spec)\n"
    "    module.__droidtop_data_dir__ = data_dir\n"
    "    sys.modules[unique_name] = module\n"
    "    spec.loader.exec_module(module)\n"
    "    _droidtop_modules[unique_name] = module\n"
    "    if hasattr(module, \"on_load\"):\n"
    "        module.on_load(data_dir)\n"
    "    return \"ok\"\n"
    "def _droidtop_call(unique_name, func_name, arg_json):\n"
    "    module = _droidtop_modules.get(unique_name)\n"
    "    if module is None:\n"
    "        raise RuntimeError(\"plugin module not loaded: \" + unique_name)\n"
    "    func = getattr(module, func_name, None)\n"
    "    if func is None:\n"
    "        raise RuntimeError(\"plugin has no function \" + func_name)\n"
    "    return func(arg_json)\n"
    "def _droidtop_unload(unique_name):\n"
    "    module = _droidtop_modules.pop(unique_name, None)\n"
    "    if module is not None and hasattr(module, \"on_unload\"):\n"
    "        module.on_unload()\n"
    "    sys.modules.pop(unique_name, None)\n"
    "    return \"ok\"\n";

static void throw_java(JNIEnv *env, const char *message) {
    jclass cls = (*env)->FindClass(env, "dev/droidtop/pluginhost/PythonCallException");
    if (cls == NULL) {
        (*env)->ExceptionClear(env);
        cls = (*env)->FindClass(env, "java/lang/RuntimeException");
    }
    (*env)->ThrowNew(env, cls, message);
}

/* Fetches the current Python exception as a one-line C string (valid
 * until the next Python call on this thread) and clears it, or NULL if
 * there is none. */
static const char *fetch_python_error(void) {
    if (!g_py.PyErr_Occurred()) return NULL;
    PyObject *type = NULL, *value = NULL, *tb = NULL;
    g_py.PyErr_Fetch(&type, &value, &tb);
    g_py.PyErr_NormalizeException(&type, &value, &tb);
    const char *text = "python call failed (no message)";
    if (value != NULL) {
        PyObject *s = g_py.PyObject_Str(value);
        if (s != NULL) {
            const char *utf8 = g_py.PyUnicode_AsUTF8(s);
            if (utf8 != NULL) text = utf8;
        }
    }
    /* Deliberately not DECREF'd here: fetch_python_error's caller copies
     * the text into a JNI exception before this thread makes another
     * Python call, and this whole bridge is single-interpreter/short-lived
     * enough (torn down with :pluginhost) that this is a bounded, not
     * accumulating, leak per error rather than a real per-call leak in
     * steady-state (no errors) operation. */
    return text;
}

JNIEXPORT jboolean JNICALL
Java_dev_droidtop_pluginhost_PythonBridge_nativeInit(JNIEnv *env, jobject thiz,
                                                       jstring jPythonHome, jstring jLibpythonPath) {
    if (g_py.libpython != NULL) return JNI_TRUE; /* already initialized for this process */

    const char *pythonHome = (*env)->GetStringUTFChars(env, jPythonHome, NULL);
    const char *libpythonPath = (*env)->GetStringUTFChars(env, jLibpythonPath, NULL);

    /* PYTHONHOME, not PyConfig.home: a plain, fully-documented env var
     * CPython reads at Py_Initialize time to find prefix/lib/pythonX.Y --
     * exactly as stable across versions as the functions above, and it
     * needs no struct at all. */
    setenv("PYTHONHOME", pythonHome, 1);

    void *handle = dlopen(libpythonPath, RTLD_NOW | RTLD_GLOBAL);
    (*env)->ReleaseStringUTFChars(env, jPythonHome, pythonHome);
    if (handle == NULL) {
        LOGE("dlopen(%s) failed: %s", libpythonPath, dlerror());
        (*env)->ReleaseStringUTFChars(env, jLibpythonPath, libpythonPath);
        return JNI_FALSE;
    }
    (*env)->ReleaseStringUTFChars(env, jLibpythonPath, libpythonPath);

#define RESOLVE(field, symbol) \
    g_py.field = (field##_t) dlsym(handle, symbol); \
    if (g_py.field == NULL) { LOGE("missing symbol %s", symbol); dlclose(handle); return JNI_FALSE; }

    RESOLVE(Py_InitializeEx, "Py_InitializeEx");
    RESOLVE(Py_IsInitialized, "Py_IsInitialized");
    RESOLVE(Py_FinalizeEx, "Py_FinalizeEx");
    RESOLVE(PyRun_SimpleString, "PyRun_SimpleString");
    RESOLVE(PyImport_ImportModule, "PyImport_ImportModule");
    RESOLVE(PyObject_GetAttrString, "PyObject_GetAttrString");
    RESOLVE(PyObject_CallFunction, "PyObject_CallFunction");
    RESOLVE(PyUnicode_FromString, "PyUnicode_FromString");
    RESOLVE(PyUnicode_AsUTF8, "PyUnicode_AsUTF8");
    RESOLVE(Py_DecRef, "Py_DecRef");
    RESOLVE(Py_IncRef, "Py_IncRef");
    RESOLVE(PyErr_Occurred, "PyErr_Occurred");
    RESOLVE(PyErr_Fetch, "PyErr_Fetch");
    RESOLVE(PyErr_NormalizeException, "PyErr_NormalizeException");
    RESOLVE(PyObject_Str, "PyObject_Str");
    RESOLVE(PyErr_Clear, "PyErr_Clear");
#undef RESOLVE

    g_py.libpython = handle;
    if (!g_py.Py_IsInitialized()) {
        g_py.Py_InitializeEx(0); /* 0: skip signal handler registration -- :pluginhost is not the main process's UI thread owner */
    }

    if (g_py.PyRun_SimpleString(BOOTSTRAP_SOURCE) != 0) {
        LOGE("bootstrap script failed to execute");
        return JNI_FALSE;
    }
    PyObject *main_module = g_py.PyImport_ImportModule("__main__");
    if (main_module == NULL) {
        LOGE("could not import __main__ after bootstrap");
        return JNI_FALSE;
    }
    g_py.fn_load = g_py.PyObject_GetAttrString(main_module, "_droidtop_load");
    g_py.fn_call = g_py.PyObject_GetAttrString(main_module, "_droidtop_call");
    g_py.fn_unload = g_py.PyObject_GetAttrString(main_module, "_droidtop_unload");
    g_py.Py_DecRef(main_module);
    if (g_py.fn_load == NULL || g_py.fn_call == NULL || g_py.fn_unload == NULL) {
        LOGE("bootstrap helpers missing after exec");
        return JNI_FALSE;
    }
    LOGI("python runtime initialized (home=%s)", getenv("PYTHONHOME"));
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_dev_droidtop_pluginhost_PythonBridge_nativeLoadModule(JNIEnv *env, jobject thiz,
                                                             jstring jUniqueName, jstring jPath, jstring jDataDir) {
    const char *uniqueName = (*env)->GetStringUTFChars(env, jUniqueName, NULL);
    const char *path = (*env)->GetStringUTFChars(env, jPath, NULL);
    const char *dataDir = (*env)->GetStringUTFChars(env, jDataDir, NULL);

    PyObject *result = g_py.PyObject_CallFunction(g_py.fn_load, "sss", uniqueName, path, dataDir);
    (*env)->ReleaseStringUTFChars(env, jUniqueName, uniqueName);
    (*env)->ReleaseStringUTFChars(env, jPath, path);
    (*env)->ReleaseStringUTFChars(env, jDataDir, dataDir);

    if (result == NULL) {
        const char *msg = fetch_python_error();
        throw_java(env, msg != NULL ? msg : "on_load failed");
        return;
    }
    g_py.Py_DecRef(result);
}

JNIEXPORT jstring JNICALL
Java_dev_droidtop_pluginhost_PythonBridge_nativeCallFunction(JNIEnv *env, jobject thiz,
                                                                jstring jUniqueName, jstring jFuncName, jstring jArgJson) {
    const char *uniqueName = (*env)->GetStringUTFChars(env, jUniqueName, NULL);
    const char *funcName = (*env)->GetStringUTFChars(env, jFuncName, NULL);
    const char *argJson = (*env)->GetStringUTFChars(env, jArgJson, NULL);

    PyObject *result = g_py.PyObject_CallFunction(g_py.fn_call, "sss", uniqueName, funcName, argJson);
    (*env)->ReleaseStringUTFChars(env, jUniqueName, uniqueName);
    (*env)->ReleaseStringUTFChars(env, jFuncName, funcName);
    (*env)->ReleaseStringUTFChars(env, jArgJson, argJson);

    if (result == NULL) {
        const char *msg = fetch_python_error();
        throw_java(env, msg != NULL ? msg : "plugin call failed");
        return NULL;
    }
    const char *utf8 = g_py.PyUnicode_AsUTF8(result);
    if (utf8 == NULL) {
        g_py.Py_DecRef(result);
        throw_java(env, "plugin function did not return a str");
        return NULL;
    }
    jstring jresult = (*env)->NewStringUTF(env, utf8);
    g_py.Py_DecRef(result);
    return jresult;
}

JNIEXPORT void JNICALL
Java_dev_droidtop_pluginhost_PythonBridge_nativeUnloadModule(JNIEnv *env, jobject thiz, jstring jUniqueName) {
    const char *uniqueName = (*env)->GetStringUTFChars(env, jUniqueName, NULL);
    PyObject *result = g_py.PyObject_CallFunction(g_py.fn_unload, "s", uniqueName);
    (*env)->ReleaseStringUTFChars(env, jUniqueName, uniqueName);
    if (result == NULL) {
        g_py.PyErr_Clear();
        return;
    }
    g_py.Py_DecRef(result);
}
