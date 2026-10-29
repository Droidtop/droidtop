cmake_minimum_required(VERSION 3.22)
project(droidtoppy C)

# droidtop's python plugin kind (docs/SPEC.md 12a): the ONLY native code
# this module ships is this small bridge. CPython itself (libpython*.so +
# the stdlib) is never linked or bundled -- PythonRuntimeManager downloads
# the official python.org Android build on first use and this library
# dlopen()s the resulting libpython*.so at runtime, calling only the
# small, ABI-stable subset of the C API (Py_Initialize, PyRun_SimpleString,
# PyObject_CallFunction, PyUnicode_*, PyErr_*) rather than anything that
# depends on a struct layout tied to one CPython build (PyConfig's fields
# are NOT part of the stable ABI; the functions used here are).
#
# No Python.h here on purpose -- the whole point is that this .so is
# built and shipped in the base APK WITHOUT ever having a copy of CPython
# to link or compile against. Every Python C-API symbol used is declared
# by hand as a function-pointer typedef and resolved with dlsym() against
# the interpreter PythonRuntimeManager downloaded, at PythonBridge init
# time.
add_library(droidtoppy SHARED
    src/droidtoppy_jni.c
    # The guarded hooks of a contained plugin's isolated process (docs/plugin-api.md 5.3).
    src/sandbox_hooks.c
    # The system-call filter of every plugin process (docs/plugin-api.md 5.3): the sandbox library's lockdown.
    src/plugin_filter.c
    # A gpu.render plugin's file broker, in :app (docs/plugin-api.md 5.3, "The graphics tier").
    src/plugin_broker.c
    # The stand-in window a contained plugin's own screen draws into (docs/plugin-api.md 5.3, "ui.main in the sandbox").
    src/screen_bridge.c
    # A plugin's SQLite, droidtop's own (docs/plugin-api.md 5.3, "Preferences and SQLite are droidtop's").
    src/bundled_sqlite.c
)

# The SQLite amalgamation at plugin-host/sqlite.pin, put in place by build-scripts/fetch-sqlite.sh (CI runs it before
# Gradle). Its symbols stay inside this library, so nothing here ever binds to Android's own libsqlite.
set(SQLITE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/sqlite)
if(NOT EXISTS ${SQLITE_DIR}/sqlite3.c)
    message(FATAL_ERROR "The bundled SQLite is missing: run build-scripts/fetch-sqlite.sh")
endif()
add_library(droidtop_sqlite STATIC ${SQLITE_DIR}/sqlite3.c)
target_include_directories(droidtop_sqlite PUBLIC ${SQLITE_DIR})
target_compile_definitions(droidtop_sqlite PUBLIC
    SQLITE_THREADSAFE=1
    # A plugin process has no temporary folder of its own: temporary tables and indices stay in memory.
    SQLITE_TEMP_STORE=3
    SQLITE_DEFAULT_MEMSTATUS=0
    SQLITE_OMIT_LOAD_EXTENSION
    SQLITE_OMIT_DEPRECATED
    SQLITE_ENABLE_FTS3
    SQLITE_ENABLE_FTS3_PARENTHESIS
    SQLITE_ENABLE_FTS4
    SQLITE_ENABLE_FTS5
    SQLITE_ENABLE_RTREE
    SQLITE_ENABLE_MATH_FUNCTIONS
    HAVE_USLEEP=1
)
# Hidden: SQLite's functions are this library's alone, never bound to Android's libsqlite or exported to a plugin.
target_compile_options(droidtop_sqlite PRIVATE -fvisibility=hidden)
set_property(TARGET droidtop_sqlite PROPERTY POSITION_INDEPENDENT_CODE ON)

# The sandbox library droidtop shares with Enginehost (bi0shacker001/sandbox, Droidtop/tracker#470), pinned as a
# submodule; only its static library is built here.
set(SBX_TESTS OFF CACHE BOOL "" FORCE)
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../vendor/sandbox ${CMAKE_CURRENT_BINARY_DIR}/sandbox)

target_link_libraries(droidtoppy sbx droidtop_sqlite dl log android)
