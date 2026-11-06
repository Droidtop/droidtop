# droidtop's JNI over librime (Droidtop/tracker#903 H8b; docs/SPEC.md 6a, "Chinese through RIME").
#
# librime.so and rime_api.h come from rime-engine.pin's release, put in input-chinese/rime (git-ignored) by
# build-scripts/fetch-rime-engine.sh, which the android-setup action runs before Gradle. librime.so is an IMPORTED
# library: AGP packages it with the APK beside libdroidtop_rime.so (so it is not also a jniLibs directory, which would
# package it twice), and strips it with the rest. libdroidtop_rime.so calls librime through rime_get_api() alone.

cmake_minimum_required(VERSION 3.18.1)
project(droidtop_rime CXX)

set(RIME_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../rime")
set(RIME_LIBRARY "${RIME_DIR}/jniLibs/${ANDROID_ABI}/librime.so")
if(NOT EXISTS "${RIME_LIBRARY}" OR NOT EXISTS "${RIME_DIR}/include/rime_api.h")
  message(FATAL_ERROR "No librime for ${ANDROID_ABI} in ${RIME_DIR}: run build-scripts/fetch-rime-engine.sh")
endif()

add_library(rime SHARED IMPORTED)
set_target_properties(rime PROPERTIES IMPORTED_LOCATION "${RIME_LIBRARY}")

add_library(droidtop_rime SHARED rime_jni.cc)
set_target_properties(droidtop_rime PROPERTIES CXX_STANDARD 17 CXX_STANDARD_REQUIRED ON)
target_include_directories(droidtop_rime PRIVATE "${RIME_DIR}/include")
# RIME_STRUCT zero-fills with {0}, which -Wextra calls missing initializers.
target_compile_options(droidtop_rime PRIVATE -Wall -Wextra -Wno-missing-field-initializers -fvisibility=hidden)
find_library(log-lib log)
target_link_libraries(droidtop_rime PRIVATE rime ${log-lib})
target_link_options(droidtop_rime PRIVATE
  "LINKER:--no-undefined"
  # 16 KB pages (Android 15 devices), as librime.so is linked; NDK 27 still defaults to 4 KB.
  "LINKER:-z,max-page-size=16384")
