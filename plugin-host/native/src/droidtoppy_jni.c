/*
 * droidtop's Python plugin bridge (docs/SPEC.md 12a). Loaded into a plugin
 * process as libdroidtoppy.so, itself bundled in the base APK like any
 * other JNI library -- what is NEVER bundled is CPython: PythonRuntimeManager
 * downloads the official python.org Android build (PEP 738) on first use of
 * a python-kind plugin, verifies it, and hands this bridge the resulting
 * libpython*.so, which this file loads at runtime.
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
 * Two ways in (docs/plugin-api.md 5.3):
 *
 * - Full trust (nativeInit): libpython is dlopen()ed by path and PYTHONHOME
 *   points at the extracted runtime. One interpreter per process; each
 *   plugin is its own module in it, keyed by its id.
 * - Contained (nativeInitContained): the process is isolated and can open no
 *   file of droidtop's, so everything arrives as a descriptor. libpython and
 *   the runtime's other libraries are mapped with android_dlopen_ext and
 *   ANDROID_DLEXT_USE_LIBRARY_FD (or, when mapping the file's own descriptor
 *   is refused, from a private memfd copy); each lib-dynload extension module
 *   is mapped the same way and registered as a built-in before the
 *   interpreter starts, because the import system can only load an extension
 *   by path; the standard library is one zip on sys.path as /proc/self/fd/N,
 *   and an open-code hook answers that path with the descriptor itself, so
 *   nothing is ever re-opened by path. One plugin per contained process.
 *
 * A Python-level exception in a plugin is caught and reported like any other
 * plugin failure; a native fault inside libpython.so takes down the plugin's
 * own process, never :app.
 */
#include <jni.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <android/dlext.h>
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

/* PyGILState_STATE is a plain enum (PyGILState_LOCKED=0, PyGILState_UNLOCKED=1)
 * in every CPython version that has shipped it -- stable ABI since 3.2,
 * safe to redeclare as int here the same way every other stable-ABI type
 * in this file is redeclared rather than pulled from a header. */
typedef int PyGILState_STATE;
typedef PyGILState_STATE (*PyGILState_Ensure_t)(void);
typedef void (*PyGILState_Release_t)(PyGILState_STATE);
typedef void *(*PyEval_SaveThread_t)(void);
typedef void (*PyEval_RestoreThread_t)(void *);

/* The public PyMethodDef layout (stable ABI): the one struct needed, to hand Python a C function. */
typedef PyObject *(*PyCFunction)(PyObject *, PyObject *);
typedef struct {
    const char *ml_name;
    PyCFunction ml_meth;
    int ml_flags;
    const char *ml_doc;
} PyMethodDef;
#define METH_VARARGS 0x0001
typedef PyObject *(*PyCFunction_NewEx_t)(PyMethodDef *, PyObject *, PyObject *);
typedef int (*PyObject_SetAttrString_t)(PyObject *, const char *, PyObject *);
typedef PyObject *(*PyTuple_GetItem_t)(PyObject *, long);

/* Only the contained path uses these two: a module init function as a built-in, and the hook that opens the zip. */
typedef PyObject *(*PyInitFunc)(void);
typedef int (*PyImport_AppendInittab_t)(const char *, PyInitFunc);
typedef PyObject *(*Py_OpenCodeHookFunction)(PyObject *, void *);
typedef int (*PyFile_SetOpenCodeHook_t)(Py_OpenCodeHookFunction, void *);

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
    PyGILState_Ensure_t PyGILState_Ensure;
    PyGILState_Release_t PyGILState_Release;
    PyEval_SaveThread_t PyEval_SaveThread;
    PyEval_RestoreThread_t PyEval_RestoreThread;
    PyCFunction_NewEx_t PyCFunction_NewEx;
    PyObject_SetAttrString_t PyObject_SetAttrString;
    PyTuple_GetItem_t PyTuple_GetItem;

    /* The bootstrap module's helpers, resolved once after init. */
    PyObject *fn_load;
    PyObject *fn_load_source;
    PyObject *fn_call;
    PyObject *fn_unload;
} DroidtopPy;

/* One process-wide interpreter (see file header). */
static DroidtopPy g_py = {0};

/* droidtop.host.call and droidtop.host.open (docs/plugin-api.md 1.3): Python calls these with (plugin id, request
 * JSON) and gets the broker's reply JSON back. The Java ends are PythonBridge.hostCall and hostOpen, which route to the
 * calling plugin's own broker, so every permission check, quota and audit is the one a native plugin's call meets. A
 * call can arrive on any Python thread (a job worker, a thread the plugin started), so the JVM is reached through a
 * cached JavaVM and the thread is attached for the duration if it is not a Java thread already. The GIL is released
 * across the Java call: a broker call can block on the user (the first-use sheet) and other plugins and jobs must keep
 * running meanwhile. */
static JavaVM *g_vm = NULL;
static jclass g_bridge_class = NULL;
static jmethodID g_host_call = NULL;
static jmethodID g_host_open = NULL;

static const char HOST_CALL_FAILED[] =
    "{\"ok\":false,\"error\":{\"code\":\"FAILED\",\"message\":\"droidtop could not be reached\"}}";

/* The reply as a malloc'd C string, or NULL if the JVM could not be reached. */
static char *call_java_host(jmethodID method, const char *plugin, const char *request) {
    if (g_vm == NULL || g_bridge_class == NULL || method == NULL) return NULL;
    JNIEnv *env = NULL;
    int attached = 0;
    jint state = (*g_vm)->GetEnv(g_vm, (void **) &env, JNI_VERSION_1_6);
    if (state == JNI_EDETACHED) {
        if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) != JNI_OK) return NULL;
        attached = 1;
    } else if (state != JNI_OK) {
        return NULL;
    }
    char *out = NULL;
    jstring jplugin = (*env)->NewStringUTF(env, plugin);
    jstring jrequest = (*env)->NewStringUTF(env, request);
    if (jplugin != NULL && jrequest != NULL) {
        jstring jreply = (jstring) (*env)->CallStaticObjectMethod(env, g_bridge_class, method, jplugin, jrequest);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
        } else if (jreply != NULL) {
            const char *utf8 = (*env)->GetStringUTFChars(env, jreply, NULL);
            if (utf8 != NULL) {
                out = strdup(utf8);
                (*env)->ReleaseStringUTFChars(env, jreply, utf8);
            }
            (*env)->DeleteLocalRef(env, jreply);
        }
    } else {
        (*env)->ExceptionClear(env);
    }
    if (jplugin != NULL) (*env)->DeleteLocalRef(env, jplugin);
    if (jrequest != NULL) (*env)->DeleteLocalRef(env, jrequest);
    if (attached) (*g_vm)->DetachCurrentThread(g_vm);
    return out;
}

static PyObject *host_bridge(jmethodID method, PyObject *args) {
    PyObject *plugin = g_py.PyTuple_GetItem(args, 0);
    PyObject *request = plugin != NULL ? g_py.PyTuple_GetItem(args, 1) : NULL;
    if (plugin == NULL || request == NULL) return NULL; /* IndexError already set */
    const char *plugin_utf8 = g_py.PyUnicode_AsUTF8(plugin);
    const char *request_utf8 = plugin_utf8 != NULL ? g_py.PyUnicode_AsUTF8(request) : NULL;
    if (plugin_utf8 == NULL || request_utf8 == NULL) return NULL; /* TypeError already set */
    /* Copies: the borrowed buffers belong to objects another thread could drop once the GIL is released. */
    char *plugin_copy = strdup(plugin_utf8);
    char *request_copy = strdup(request_utf8);
    char *reply = NULL;
    if (plugin_copy != NULL && request_copy != NULL) {
        void *tstate = g_py.PyEval_SaveThread();
        reply = call_java_host(method, plugin_copy, request_copy);
        g_py.PyEval_RestoreThread(tstate);
    }
    free(plugin_copy);
    free(request_copy);
    PyObject *result = g_py.PyUnicode_FromString(reply != NULL ? reply : HOST_CALL_FAILED);
    free(reply);
    return result;
}

static PyObject *host_call(PyObject *self, PyObject *args) { return host_bridge(g_host_call, args); }
static PyObject *host_open(PyObject *self, PyObject *args) { return host_bridge(g_host_open, args); }

static PyMethodDef host_call_def = {"droidtop_host_call", host_call, METH_VARARGS, "the native end of droidtop.host.call"};
static PyMethodDef host_open_def = {"droidtop_host_open", host_open, METH_VARARGS, "the native end of droidtop.host.open"};

/* The contained path's standard library: the zip's descriptor and the /proc/self/fd path it sits on sys.path as. */
static int g_zip_fd = -1;
static char g_zip_path[64] = {0};
/* Set by the contained bootstrap once the interpreter runs: a Python callable(fd) -> file object reading with pread,
 * so threads importing at once never share a file offset. Before it is set (only while the interpreter starts, on one
 * thread) the hook hands out a dup of the descriptor. */
static PyObject *g_fd_opener = NULL;

static PyObject *set_opener(PyObject *self, PyObject *args) {
    PyObject *opener = g_py.PyTuple_GetItem(args, 0);
    if (opener == NULL) return NULL;
    /* PyTuple_GetItem lends the reference: the bridge keeps its own for the life of the process. */
    g_py.Py_IncRef(opener);
    g_fd_opener = opener;
    return g_py.PyUnicode_FromString("ok");
}

static PyMethodDef set_opener_def = {"droidtop_set_opener", set_opener, METH_VARARGS, "the contained bridge's zip opener"};

/* io.open_code for the contained process (PEP 578): the standard library zip is answered from its descriptor, never
 * re-opened by path (an isolated process may not open droidtop's files); any other path is opened as usual. */
static PyObject *open_code_hook(PyObject *path, void *user_data) {
    const char *p = g_py.PyUnicode_AsUTF8(path);
    if (p != NULL && g_zip_fd >= 0 && strcmp(p, g_zip_path) == 0) {
        if (g_fd_opener != NULL) return g_py.PyObject_CallFunction(g_fd_opener, "i", g_zip_fd);
        int fd = dup(g_zip_fd);
        if (fd < 0) return NULL;
        PyObject *io = g_py.PyImport_ImportModule("_io");
        if (io == NULL) {
            close(fd);
            return NULL;
        }
        PyObject *fileio = g_py.PyObject_GetAttrString(io, "FileIO");
        PyObject *buffered = g_py.PyObject_GetAttrString(io, "BufferedReader");
        g_py.Py_DecRef(io);
        PyObject *raw = fileio != NULL ? g_py.PyObject_CallFunction(fileio, "is", fd, "r") : NULL;
        if (raw == NULL) close(fd);
        PyObject *file = (raw != NULL && buffered != NULL) ? g_py.PyObject_CallFunction(buffered, "O", raw) : NULL;
        if (fileio != NULL) g_py.Py_DecRef(fileio);
        if (buffered != NULL) g_py.Py_DecRef(buffered);
        if (raw != NULL) g_py.Py_DecRef(raw);
        return file;
    }
    PyObject *io = g_py.PyImport_ImportModule("_io");
    if (io == NULL) return NULL;
    PyObject *open = g_py.PyObject_GetAttrString(io, "open");
    g_py.Py_DecRef(io);
    if (open == NULL) return NULL;
    PyObject *file = g_py.PyObject_CallFunction(open, "Os", path, "rb");
    g_py.Py_DecRef(open);
    return file;
}

/* This module is executed once via PyRun_SimpleString right after
 * Py_InitializeEx. importlib.util is stdlib (present in every official
 * CPython Android build -- confirmed against the 3.14.7 archive's own
 * prefix/lib/python3.14/importlib tree), so no extra packaging is
 * needed for it. */
static const char *BOOTSTRAP_SOURCE =
    "import sys, importlib.util\n"
    "import json, threading\n"
    "_droidtop_modules = {}\n"
    "_droidtop_jobs = {}\n"
    "_droidtop_jobs_lock = threading.Lock()\n"
    "import types\n"
    "def _droidtop_caller():\n"
    "    frame = sys._getframe(1)\n"
    "    while frame is not None:\n"
    "        name = frame.f_globals.get('__name__')\n"
    "        if name in _droidtop_modules:\n"
    "            return name\n"
    "        frame = frame.f_back\n"
    "    raise RuntimeError('droidtop.host.call must be called from a plugin')\n"
    "def _droidtop_request(api, op, args, version):\n"
    "    return json.dumps({'api': api, 'version': version, 'op': op, 'args': {} if args is None else args})\n"
    "def _droidtop_host_call(api, op, args=None, version=1):\n"
    "    return json.loads(_droidtop_native_call(_droidtop_caller(), _droidtop_request(api, op, args, version)))\n"
    "def _droidtop_host_open(api, op, args=None, version=1):\n"
    "    return json.loads(_droidtop_native_open(_droidtop_caller(), _droidtop_request(api, op, args, version)))\n"
    "_droidtop_host = types.ModuleType('droidtop.host')\n"
    "_droidtop_host.call = _droidtop_host_call\n"
    "_droidtop_host.open = _droidtop_host_open\n"
    "_droidtop_pkg = types.ModuleType('droidtop')\n"
    "_droidtop_pkg.__path__ = []\n"
    "_droidtop_pkg.host = _droidtop_host\n"
    "sys.modules['droidtop'] = _droidtop_pkg\n"
    "sys.modules['droidtop.host'] = _droidtop_host\n"
    "def _droidtop_run(unique_name, module, data_dir, body):\n"
    "    module.__droidtop_data_dir__ = data_dir\n"
    "    sys.modules[unique_name] = module\n"
    "    _droidtop_modules[unique_name] = module\n"
    "    try:\n"
    "        body()\n"
    "        if hasattr(module, \"on_load\"):\n"
    "            module.on_load(data_dir)\n"
    "    except BaseException:\n"
    "        _droidtop_modules.pop(unique_name, None)\n"
    "        sys.modules.pop(unique_name, None)\n"
    "        raise\n"
    "    return \"ok\"\n"
    "def _droidtop_load(unique_name, path, data_dir):\n"
    "    spec = importlib.util.spec_from_file_location(unique_name, path)\n"
    "    module = importlib.util.module_from_spec(spec)\n"
    "    return _droidtop_run(unique_name, module, data_dir, lambda: spec.loader.exec_module(module))\n"
    "def _droidtop_load_source(unique_name, source, data_dir):\n"
    "    module = types.ModuleType(unique_name)\n"
    "    module.__file__ = 'plugin.py'\n"
    "    code = compile(source, 'plugin.py', 'exec')\n"
    "    return _droidtop_run(unique_name, module, data_dir, lambda: exec(code, module.__dict__))\n"
    "def _droidtop_call(unique_name, func_name, arg_json):\n"
    "    module = _droidtop_modules.get(unique_name)\n"
    "    if module is None:\n"
    "        raise RuntimeError(\"plugin module not loaded: \" + unique_name)\n"
    "    if func_name == 'start_job':\n"
    "        func = getattr(module, func_name, None)\n"
    "        if func is None: raise RuntimeError(\"plugin has no function start_job\")\n"
    "        request = json.loads(arg_json)\n"
    "        job_id, call = request['job_id'], request.get('call', {})\n"
    "        job = {'progress': [], 'done': False, 'result': None}\n"
    "        def report(percent=-1, status=''):\n"
    "            with _droidtop_jobs_lock: job['progress'].append({'percent': int(percent), 'status': str(status)})\n"
    "        def run():\n"
    "            try: job['result'] = func(job_id, call, report)\n"
    "            except BaseException as error: job['result'] = json.dumps({'ok': False, 'error': str(error)})\n"
    "            finally:\n"
    "                with _droidtop_jobs_lock: job['done'] = True\n"
    "        with _droidtop_jobs_lock: _droidtop_jobs[job_id] = job\n"
    "        threading.Thread(target=run, daemon=True).start()\n"
    "        return '{}'\n"
    "    if func_name == 'poll_job':\n"
    "        job_id = json.loads(arg_json)['job_id']\n"
    "        with _droidtop_jobs_lock:\n"
    "            job = _droidtop_jobs.get(job_id)\n"
    "            if job is None: return json.dumps({'done': True, 'result': json.dumps({'ok': False, 'error': 'unknown job'})})\n"
    "            reports, job['progress'] = job['progress'], []\n"
    "            reply = {'done': job['done'], 'progress': reports}\n"
    "            if job['done']:\n"
    "                reply['result'] = job['result'] or json.dumps({'ok': False, 'error': 'job returned no result'})\n"
    "                _droidtop_jobs.pop(job_id, None)\n"
    "            return json.dumps(reply)\n"
    "    if func_name == 'cancel_job':\n"
    "        func = getattr(module, func_name, None)\n"
    "        if func is None: raise RuntimeError(\"plugin has no function cancel_job\")\n"
    "        job_id = json.loads(arg_json)['job_id']\n"
    "        return func(job_id) or '{}'\n"
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

/* Run after BOOTSTRAP_SOURCE in a contained process only: the zip is read with pread from here on, so two threads
 * importing at once never move each other's file offset. */
static const char *CONTAINED_SOURCE =
    "import io, os\n"
    "class _DroidtopFdFile(io.RawIOBase):\n"
    "    def __init__(self, fd):\n"
    "        self._fd = fd\n"
    "        self._pos = 0\n"
    "    def readable(self):\n"
    "        return True\n"
    "    def seekable(self):\n"
    "        return True\n"
    "    def readinto(self, b):\n"
    "        data = os.pread(self._fd, len(b), self._pos)\n"
    "        n = len(data)\n"
    "        b[:n] = data\n"
    "        self._pos += n\n"
    "        return n\n"
    "    def seek(self, offset, whence=0):\n"
    "        if whence == 0:\n"
    "            self._pos = offset\n"
    "        elif whence == 1:\n"
    "            self._pos += offset\n"
    "        else:\n"
    "            self._pos = os.fstat(self._fd).st_size + offset\n"
    "        return self._pos\n"
    "    def tell(self):\n"
    "        return self._pos\n"
    "_droidtop_set_opener(lambda fd: io.BufferedReader(_DroidtopFdFile(fd)))\n";

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
     * enough (torn down with its process) that this is a bounded, not
     * accumulating, leak per error rather than a real per-call leak in
     * steady-state (no errors) operation. */
    return text;
}

/* The host-call ends need the JVM and PythonBridge's static methods from any thread. */
static int cache_bridge(JNIEnv *env, jobject thiz) {
    jclass bridge_class = (*env)->GetObjectClass(env, thiz);
    g_bridge_class = (jclass) (*env)->NewGlobalRef(env, bridge_class);
    g_host_call = (*env)->GetStaticMethodID(env, bridge_class, "hostCall", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
    if (g_host_call != NULL) {
        g_host_open = (*env)->GetStaticMethodID(env, bridge_class, "hostOpen", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
    }
    (*env)->DeleteLocalRef(env, bridge_class);
    (*env)->GetJavaVM(env, &g_vm);
    if (g_host_call == NULL || g_host_open == NULL) {
        (*env)->ExceptionClear(env);
        LOGE("PythonBridge.hostCall or hostOpen not found");
        return 0;
    }
    return 1;
}

/* Every stable-ABI function the bridge calls, from [handle]. 0 with [missing] set to the first absent symbol. */
static int resolve_symbols(void *handle, const char **missing) {
#define RESOLVE(field, symbol) \
    g_py.field = (field##_t) dlsym(handle, symbol); \
    if (g_py.field == NULL) { *missing = symbol; return 0; }

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
    RESOLVE(PyGILState_Ensure, "PyGILState_Ensure");
    RESOLVE(PyGILState_Release, "PyGILState_Release");
    RESOLVE(PyEval_SaveThread, "PyEval_SaveThread");
    RESOLVE(PyEval_RestoreThread, "PyEval_RestoreThread");
    RESOLVE(PyCFunction_NewEx, "PyCFunction_NewEx");
    RESOLVE(PyObject_SetAttrString, "PyObject_SetAttrString");
    RESOLVE(PyTuple_GetItem, "PyTuple_GetItem");
#undef RESOLVE
    return 1;
}

static int put_function(PyObject *module, PyMethodDef *def, const char *name) {
    PyObject *fn = g_py.PyCFunction_NewEx(def, NULL, NULL);
    if (fn == NULL || g_py.PyObject_SetAttrString(module, name, fn) != 0) return 0;
    g_py.Py_DecRef(fn);
    return 1;
}

/* After Py_InitializeEx: the native ends in __main__, the bootstrap, and its helpers. NULL on success, else why. */
static const char *finish_init(int contained) {
    /* The bootstrap runs in __main__, so the native ends of droidtop.host go in there first. */
    PyObject *main_module = g_py.PyImport_ImportModule("__main__");
    if (main_module == NULL) return "could not import __main__";
    if (!put_function(main_module, &host_call_def, "_droidtop_native_call") ||
        !put_function(main_module, &host_open_def, "_droidtop_native_open") ||
        (contained && !put_function(main_module, &set_opener_def, "_droidtop_set_opener"))) {
        g_py.Py_DecRef(main_module);
        return "could not register droidtop.host";
    }
    if (g_py.PyRun_SimpleString(BOOTSTRAP_SOURCE) != 0) {
        g_py.Py_DecRef(main_module);
        return "the bootstrap script failed to execute";
    }
    if (contained && g_py.PyRun_SimpleString(CONTAINED_SOURCE) != 0) {
        g_py.Py_DecRef(main_module);
        return "the contained bootstrap failed to execute";
    }
    g_py.fn_load = g_py.PyObject_GetAttrString(main_module, "_droidtop_load");
    g_py.fn_load_source = g_py.PyObject_GetAttrString(main_module, "_droidtop_load_source");
    g_py.fn_call = g_py.PyObject_GetAttrString(main_module, "_droidtop_call");
    g_py.fn_unload = g_py.PyObject_GetAttrString(main_module, "_droidtop_unload");
    g_py.Py_DecRef(main_module);
    if (g_py.fn_load == NULL || g_py.fn_load_source == NULL || g_py.fn_call == NULL || g_py.fn_unload == NULL) {
        return "bootstrap helpers missing after exec";
    }
    return NULL;
}

JNIEXPORT jboolean JNICALL
Java_dev_droidtop_pluginhost_PythonBridge_nativeInit(JNIEnv *env, jobject thiz,
                                                       jstring jPythonHome, jstring jLibpythonPath) {
    if (g_py.libpython != NULL) return JNI_TRUE; /* already initialized for this process */
    if (!cache_bridge(env, thiz)) return JNI_FALSE;

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

    const char *missing = NULL;
    if (!resolve_symbols(handle, &missing)) {
        LOGE("missing symbol %s", missing);
        dlclose(handle);
        return JNI_FALSE;
    }
    g_py.libpython = handle;
    if (!g_py.Py_IsInitialized()) {
        g_py.Py_InitializeEx(0); /* 0: skip signal handler registration -- a plugin process is not the main process's UI thread owner */
    }
    const char *problem = finish_init(0);
    if (problem != NULL) {
        LOGE("%s", problem);
        return JNI_FALSE;
    }
    LOGI("python runtime initialized (home=%s)", getenv("PYTHONHOME"));
    g_py.PyEval_SaveThread();
    return JNI_TRUE;
}

/* ---- The contained path (docs/plugin-api.md 5.3) ---- */

/* A JSON report built as the libraries load: the spike's evidence of how each one got in. */
typedef struct {
    char *text;
    size_t len;
    size_t cap;
} Report;

static void report_raw(Report *r, const char *s) {
    size_t n = strlen(s);
    if (r->len + n + 1 > r->cap) {
        size_t cap = r->cap == 0 ? 4096 : r->cap;
        while (r->len + n + 1 > cap) cap *= 2;
        char *grown = realloc(r->text, cap);
        if (grown == NULL) return;
        r->text = grown;
        r->cap = cap;
    }
    memcpy(r->text + r->len, s, n + 1);
    r->len += n;
}

/* Appends s as a JSON string literal. */
static void report_string(Report *r, const char *s) {
    report_raw(r, "\"");
    char one[8];
    for (const unsigned char *p = (const unsigned char *) (s != NULL ? s : ""); *p != 0; p++) {
        if (*p == '"' || *p == '\\') {
            one[0] = '\\';
            one[1] = (char) *p;
            one[2] = 0;
        } else if (*p < 0x20) {
            snprintf(one, sizeof one, "\\u%04x", *p);
        } else {
            one[0] = (char) *p;
            one[1] = 0;
        }
        report_raw(r, one);
    }
    report_raw(r, "\"");
}

static void report_field(Report *r, const char *key, const char *value) {
    if (r->len > 1) report_raw(r, ",");
    report_string(r, key);
    report_raw(r, ":");
    report_string(r, value);
}

/* A private copy of [src] in anonymous memory, for a library whose own descriptor the linker could not map. */
static int copy_to_memfd(const char *name, int src) {
#ifdef __NR_memfd_create
    int dst = (int) syscall(__NR_memfd_create, name, 1 /* MFD_CLOEXEC */);
    if (dst < 0) return -1;
    char buf[65536];
    off_t offset = 0;
    for (;;) {
        ssize_t n = pread(src, buf, sizeof buf, offset);
        if (n < 0 && errno == EINTR) continue;
        if (n < 0) {
            close(dst);
            return -1;
        }
        if (n == 0) break;
        ssize_t written = 0;
        while (written < n) {
            ssize_t k = write(dst, buf + written, (size_t) (n - written));
            if (k < 0 && errno == EINTR) continue;
            if (k < 0) {
                close(dst);
                return -1;
            }
            written += k;
        }
        offset += n;
    }
    return dst;
#else
    errno = ENOSYS;
    return -1;
#endif
}

/* Maps the library behind [fd] as [name]: its own descriptor first, then a memfd copy. [how] says which worked, or why
 * neither did. A library whose soname is already loaded is that one: the linker matches DT_NEEDED entries by soname. */
static void *load_from_fd(const char *name, int fd, char *how, size_t how_len) {
    android_dlextinfo info;
    memset(&info, 0, sizeof info);
    info.flags = ANDROID_DLEXT_USE_LIBRARY_FD;
    info.library_fd = fd;
    void *handle = android_dlopen_ext(name, RTLD_NOW | RTLD_GLOBAL, &info);
    if (handle != NULL) {
        snprintf(how, how_len, "fd");
        return handle;
    }
    char first[512];
    const char *e1 = dlerror();
    snprintf(first, sizeof first, "%s", e1 != NULL ? e1 : "unknown");
    int copy = copy_to_memfd(name, fd);
    if (copy < 0) {
        snprintf(how, how_len, "fd refused (%s); memfd copy failed (%s)", first, strerror(errno));
        return NULL;
    }
    info.library_fd = copy;
    handle = android_dlopen_ext(name, RTLD_NOW | RTLD_GLOBAL, &info);
    if (handle != NULL) {
        snprintf(how, how_len, "memfd (fd refused: %s)", first);
    } else {
        const char *e2 = dlerror();
        snprintf(how, how_len, "fd refused (%s); memfd refused (%s)", first, e2 != NULL ? e2 : "unknown");
    }
    close(copy);
    return handle;
}

static char *jstring_copy(JNIEnv *env, jstring s) {
    if (s == NULL) return strdup("");
    const char *utf8 = (*env)->GetStringUTFChars(env, s, NULL);
    char *copy = strdup(utf8 != NULL ? utf8 : "");
    if (utf8 != NULL) (*env)->ReleaseStringUTFChars(env, s, utf8);
    return copy;
}

static jstring finish_report(JNIEnv *env, Report *r, int ok, const char *error) {
    if (r->len > 1) report_raw(r, ",");
    report_raw(r, ok ? "\"ok\":true" : "\"ok\":false");
    if (error != NULL) report_field(r, "error", error);
    report_raw(r, "}");
    jstring out = (*env)->NewStringUTF(env, r->text != NULL ? r->text : "{\"ok\":false}");
    free(r->text);
    return out;
}

JNIEXPORT jstring JNICALL
Java_dev_droidtop_pluginhost_PythonBridge_nativeInitContained(JNIEnv *env, jobject thiz,
                                                                jint libpythonFd, jstring jLibpythonName, jint zipFd,
                                                                jintArray jDepFds, jobjectArray jDepNames,
                                                                jintArray jExtFds, jobjectArray jExtNames,
                                                                jobjectArray jExtFiles) {
    Report r = {0};
    report_raw(&r, "{");
    if (g_py.libpython != NULL) return finish_report(env, &r, 1, NULL); /* one plugin per contained process */
    if (!cache_bridge(env, thiz)) return finish_report(env, &r, 0, "PythonBridge's host methods are missing");
    char how[1024];

    /* 1. The runtime's own libraries (OpenSSL, SQLite, ...), in any order: retried until a pass loads none, so a
     *    library whose DT_NEEDED sibling comes later in the list still loads once that sibling is in. */
    jsize deps = jDepFds != NULL ? (*env)->GetArrayLength(env, jDepFds) : 0;
    jint *depFds = deps > 0 ? (*env)->GetIntArrayElements(env, jDepFds, NULL) : NULL;
    int *depDone = deps > 0 ? calloc((size_t) deps, sizeof(int)) : NULL;
    for (int progress = 1; progress && deps > 0;) {
        progress = 0;
        for (jsize i = 0; i < deps; i++) {
            if (depDone[i]) continue;
            char *name = jstring_copy(env, (jstring) (*env)->GetObjectArrayElement(env, jDepNames, i));
            if (load_from_fd(name, depFds[i], how, sizeof how) != NULL) {
                depDone[i] = 1;
                progress = 1;
                char key[300];
                snprintf(key, sizeof key, "lib %s", name);
                report_field(&r, key, how);
            }
            free(name);
        }
    }
    for (jsize i = 0; i < deps; i++) {
        if (!depDone[i]) {
            char *name = jstring_copy(env, (jstring) (*env)->GetObjectArrayElement(env, jDepNames, i));
            load_from_fd(name, depFds[i], how, sizeof how);
            char key[300];
            snprintf(key, sizeof key, "lib %s", name);
            report_field(&r, key, how);
            free(name);
        }
        close(depFds[i]);
    }
    if (depFds != NULL) (*env)->ReleaseIntArrayElements(env, jDepFds, depFds, JNI_ABORT);
    free(depDone);

    /* 2. libpython itself. */
    char *libpythonName = jstring_copy(env, jLibpythonName);
    void *handle = load_from_fd(libpythonName, libpythonFd, how, sizeof how);
    free(libpythonName);
    close(libpythonFd);
    report_field(&r, "libpython", how);
    if (handle == NULL) return finish_report(env, &r, 0, "libpython could not be mapped from its descriptor");
    const char *missing = NULL;
    if (!resolve_symbols(handle, &missing)) return finish_report(env, &r, 0, missing);
    PyImport_AppendInittab_t appendInittab = (PyImport_AppendInittab_t) dlsym(handle, "PyImport_AppendInittab");
    PyFile_SetOpenCodeHook_t setOpenCodeHook = (PyFile_SetOpenCodeHook_t) dlsym(handle, "PyFile_SetOpenCodeHook");
    if (appendInittab == NULL || setOpenCodeHook == NULL) return finish_report(env, &r, 0, "libpython lacks PyImport_AppendInittab or PyFile_SetOpenCodeHook");

    /* 3. The standard library zip: on sys.path as /proc/self/fd/N. zipimport stats that path (which follows the link
     *    to the file without opening it) and reads it through the open-code hook below, never by opening the path. If
     *    the isolated process may not even stat droidtop's file, a private memfd copy of it is used instead. */
    struct stat st;
    snprintf(g_zip_path, sizeof g_zip_path, "/proc/self/fd/%d", zipFd);
    if (stat(g_zip_path, &st) == 0) {
        g_zip_fd = zipFd;
        report_field(&r, "stdlib", "fd");
    } else {
        char first[128];
        snprintf(first, sizeof first, "%s", strerror(errno));
        int copy = copy_to_memfd("python-stdlib.zip", zipFd);
        close(zipFd);
        if (copy < 0) return finish_report(env, &r, 0, "the standard library zip cannot be read in the contained process");
        snprintf(g_zip_path, sizeof g_zip_path, "/proc/self/fd/%d", copy);
        if (stat(g_zip_path, &st) != 0) {
            snprintf(how, sizeof how, "stat refused on the descriptor (%s) and on a memfd copy (%s)", first, strerror(errno));
            report_field(&r, "stdlib", how);
            return finish_report(env, &r, 0, "the standard library zip cannot be put on sys.path");
        }
        g_zip_fd = copy;
        snprintf(how, sizeof how, "memfd (stat refused on the descriptor: %s)", first);
        report_field(&r, "stdlib", how);
    }

    /* 4. Every lib-dynload extension module as a built-in: the import system loads an extension only by path, which an
     *    isolated process cannot open, but a built-in's init function is called from the table directly. */
    jsize exts = jExtFds != NULL ? (*env)->GetArrayLength(env, jExtFds) : 0;
    jint *extFds = exts > 0 ? (*env)->GetIntArrayElements(env, jExtFds, NULL) : NULL;
    int loadedExts = 0;
    Report failed = {0};
    report_raw(&failed, "{");
    for (jsize i = 0; i < exts; i++) {
        char *module = jstring_copy(env, (jstring) (*env)->GetObjectArrayElement(env, jExtNames, i));
        char *file = jstring_copy(env, (jstring) (*env)->GetObjectArrayElement(env, jExtFiles, i));
        void *ext = load_from_fd(file, extFds[i], how, sizeof how);
        close(extFds[i]);
        char symbol[256];
        snprintf(symbol, sizeof symbol, "PyInit_%s", module);
        PyInitFunc init = ext != NULL ? (PyInitFunc) dlsym(ext, symbol) : NULL;
        if (init != NULL && appendInittab(module, init) == 0) {
            loadedExts++;
            /* The table keeps the name pointer: it stays allocated for the life of the process. */
        } else {
            report_field(&failed, module, ext == NULL ? how : "no init function");
            free(module);
        }
        free(file);
    }
    if (extFds != NULL) (*env)->ReleaseIntArrayElements(env, jExtFds, extFds, JNI_ABORT);
    report_raw(&failed, "}");
    char count[32];
    snprintf(count, sizeof count, "%d", loadedExts);
    report_field(&r, "extensions", count);
    report_raw(&r, ",\"extensionsFailed\":");
    report_raw(&r, failed.text != NULL ? failed.text : "{}");
    free(failed.text);

    /* 5. Start: the hook before Py_InitializeEx (it is consulted during start-up), a home that does not exist so nothing
     *    is searched for by path, and the zip first on the path. */
    if (setOpenCodeHook(open_code_hook, NULL) != 0) return finish_report(env, &r, 0, "the open-code hook could not be set");
    setenv("PYTHONHOME", "/droidtop-contained", 1);
    setenv("PYTHONPATH", g_zip_path, 1);
    setenv("PYTHONNOUSERSITE", "1", 1);
    setenv("PYTHONDONTWRITEBYTECODE", "1", 1);
    g_py.libpython = handle;
    g_py.Py_InitializeEx(0);
    const char *problem = finish_init(1);
    if (problem != NULL) {
        const char *detail = fetch_python_error();
        report_field(&r, "bootstrap", detail != NULL ? detail : problem);
        g_py.PyEval_SaveThread();
        return finish_report(env, &r, 0, problem);
    }
    g_py.PyEval_SaveThread();
    LOGI("contained python runtime initialized");
    return finish_report(env, &r, 1, NULL);
}

static void load_module(JNIEnv *env, PyObject *fn, jstring jUniqueName, jstring jText, jstring jDataDir) {
    const char *uniqueName = (*env)->GetStringUTFChars(env, jUniqueName, NULL);
    const char *text = (*env)->GetStringUTFChars(env, jText, NULL);
    const char *dataDir = (*env)->GetStringUTFChars(env, jDataDir, NULL);

    /* This call arrives on whichever binder thread the AIDL dispatcher
     * picked, not necessarily nativeInit's own thread, so every Python
     * call here is bracketed in the GIL rather than assuming it is held. */
    PyGILState_STATE gstate = g_py.PyGILState_Ensure();
    PyObject *result = g_py.PyObject_CallFunction(fn, "sss", uniqueName, text, dataDir);
    const char *msg = (result == NULL) ? fetch_python_error() : NULL;
    if (result != NULL) g_py.Py_DecRef(result);
    g_py.PyGILState_Release(gstate);

    (*env)->ReleaseStringUTFChars(env, jUniqueName, uniqueName);
    (*env)->ReleaseStringUTFChars(env, jText, text);
    (*env)->ReleaseStringUTFChars(env, jDataDir, dataDir);

    if (result == NULL) {
        throw_java(env, msg != NULL ? msg : "on_load failed");
    }
}

JNIEXPORT void JNICALL
Java_dev_droidtop_pluginhost_PythonBridge_nativeLoadModule(JNIEnv *env, jobject thiz,
                                                             jstring jUniqueName, jstring jPath, jstring jDataDir) {
    load_module(env, g_py.fn_load, jUniqueName, jPath, jDataDir);
}

JNIEXPORT void JNICALL
Java_dev_droidtop_pluginhost_PythonBridge_nativeLoadSource(JNIEnv *env, jobject thiz,
                                                             jstring jUniqueName, jstring jSource, jstring jDataDir) {
    load_module(env, g_py.fn_load_source, jUniqueName, jSource, jDataDir);
}

JNIEXPORT jstring JNICALL
Java_dev_droidtop_pluginhost_PythonBridge_nativeCallFunction(JNIEnv *env, jobject thiz,
                                                                jstring jUniqueName, jstring jFuncName, jstring jArgJson) {
    const char *uniqueName = (*env)->GetStringUTFChars(env, jUniqueName, NULL);
    const char *funcName = (*env)->GetStringUTFChars(env, jFuncName, NULL);
    const char *argJson = (*env)->GetStringUTFChars(env, jArgJson, NULL);

    /* See load_module's own comment: this is a fresh binder thread,
     * not nativeInit's, and must hold the GIL for every Python call --
     * held for NewStringUTF too, since utf8 below is a borrowed pointer
     * into result's own buffer and Py_DecRef must run before releasing
     * the GIL. */
    PyGILState_STATE gstate = g_py.PyGILState_Ensure();
    PyObject *result = g_py.PyObject_CallFunction(g_py.fn_call, "sss", uniqueName, funcName, argJson);
    const char *msg = NULL;
    jstring jresult = NULL;
    jboolean badReturn = JNI_FALSE;
    if (result == NULL) {
        msg = fetch_python_error();
    } else {
        const char *utf8 = g_py.PyUnicode_AsUTF8(result);
        if (utf8 == NULL) {
            badReturn = JNI_TRUE;
        } else {
            jresult = (*env)->NewStringUTF(env, utf8);
        }
        g_py.Py_DecRef(result);
    }
    g_py.PyGILState_Release(gstate);

    (*env)->ReleaseStringUTFChars(env, jUniqueName, uniqueName);
    (*env)->ReleaseStringUTFChars(env, jFuncName, funcName);
    (*env)->ReleaseStringUTFChars(env, jArgJson, argJson);

    if (result == NULL) {
        throw_java(env, msg != NULL ? msg : "plugin call failed");
        return NULL;
    }
    if (badReturn) {
        throw_java(env, "plugin function did not return a str");
        return NULL;
    }
    return jresult;
}

JNIEXPORT void JNICALL
Java_dev_droidtop_pluginhost_PythonBridge_nativeUnloadModule(JNIEnv *env, jobject thiz, jstring jUniqueName) {
    const char *uniqueName = (*env)->GetStringUTFChars(env, jUniqueName, NULL);

    /* Same reasoning as load_module/nativeCallFunction: a fresh
     * binder thread, must hold the GIL for every Python call here. */
    PyGILState_STATE gstate = g_py.PyGILState_Ensure();
    PyObject *result = g_py.PyObject_CallFunction(g_py.fn_unload, "s", uniqueName);
    if (result == NULL) {
        g_py.PyErr_Clear();
    } else {
        g_py.Py_DecRef(result);
    }
    g_py.PyGILState_Release(gstate);

    (*env)->ReleaseStringUTFChars(env, jUniqueName, uniqueName);
}
