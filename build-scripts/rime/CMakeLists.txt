# librime.so for the keyboard's Chinese input (Droidtop/tracker#903 H8; built by
# .github/workflows/rime-engine.yml, which publishes it as a prerelease).
#
# Trime (osfans/trime, GPL-3.0-or-later) already builds librime and its
# dependencies for Android with CMake: app/src/main/jni/CMakeLists.txt adds
# Boost (cmake/Boost.cmake), glog, yaml-cpp, snappy, leveldb, marisa-trie and
# OpenCC, then librime with the lua, octagram and predict plugins merged into
# the static target rime-static (cmake/Rime.cmake), and last of all
# add_subdirectory(librime_jni), its own JNI library. That JNI binds Trime's
# Kotlin classes (com.osfans.trime.core.*), so droidtop cannot load it.
#
# The workflow checks Trime out at its pin and puts this directory in place of
# app/src/main/jni/librime_jni, leaving the rest of Trime's tree as it is.
# Trime's cmake modules address its tree through CMAKE_SOURCE_DIR (the jni
# directory), which is why this replaces the last subdirectory rather than
# wrapping Trime's CMakeLists from outside. In place of Trime's JNI it builds
# one shared library, librime.so:
#
#  - every object of rime-static, the plugins' objects included, linked whole
#    (WHOLE_ARCHIVE), with the dependencies rime-static carries;
#  - rime_modules.cc, which names the plugins' rime_require_module_*
#    functions, as Trime's rime_jni.cc does for its static link;
#  - rime.map, a version script: only librime's C entry points are exported
#    (rime_get_api, rime_get_api_stdbool, RimeRegisterModule,
#    RimeFindModule). libc++ is static (ANDROID_STL=c++_static) and stays
#    local, so the keyboard's own JNI library talks to this one through the C
#    API alone.
#
# The library is left unstripped; the app's build strips it when it packages it.

add_library(rime-shared SHARED rime_modules.cc)
set_target_properties(rime-shared PROPERTIES OUTPUT_NAME rime)
target_link_libraries(rime-shared PRIVATE "$<LINK_LIBRARY:WHOLE_ARCHIVE,rime-static>")
target_link_options(rime-shared PRIVATE
  "LINKER:--version-script=${CMAKE_CURRENT_SOURCE_DIR}/rime.map"
  "LINKER:--no-undefined"
  # 16 KB pages (Android 15 devices); NDK 27 still defaults to 4 KB.
  "LINKER:-z,max-page-size=16384"
  "LINKER:-z,common-page-size=16384")
set_property(TARGET rime-shared APPEND PROPERTY LINK_DEPENDS
  "${CMAKE_CURRENT_SOURCE_DIR}/rime.map")
