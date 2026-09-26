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
)

target_link_libraries(droidtoppy dl log)
