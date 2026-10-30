cmake_minimum_required(VERSION 3.22)
project(droidtopsbx C)

# The program-tree binding of the sandbox library (docs/SPEC.md 5b, "Containment and copy-on-write layers for
# Wine", piece 0; Droidtop/tracker#584): the broker listener a tree of processes attaches to, and the two pieces
# of the library such a tree runs, packaged for arm64-v8a and x86_64:
#   - libsbx_child.so, the library's own shared target, put first on LD_PRELOAD so every process attaches before main;
#   - sbx-exec, the library's brokered-exec helper, an executable, named libsbx_exec.so and written beside the
#     shared libraries so Android installs it in nativeLibraryDir, the one place an app may run a file from.
# The library is the pinned submodule, built as its own CMake project (bi0shacker001/sandbox, Droidtop/tracker#470).
set(SBX_TESTS OFF CACHE BOOL "" FORCE)
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../vendor/sandbox ${CMAKE_CURRENT_BINARY_DIR}/sandbox)

add_library(droidtopsbx SHARED src/sandbox_host_jni.c)
target_link_libraries(droidtopsbx sbx log)

if(TARGET sbx-exec)
  set_target_properties(sbx-exec PROPERTIES
    OUTPUT_NAME "libsbx_exec.so"
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}")
endif()
