cmake_minimum_required(VERSION 3.22)
project(remotestream)

# vendor/moonlight-common-c ships its own working CMakeLists.txt (builds a
# `moonlight-common-c` target, statically linking its pinned enet fork —
# see vendor/moonlight-common-c/enet, cloned as an exact submodule, NOT a
# system libenet; its README states plainly that swapping in a generic
# ENet build breaks connectivity to recent GFE/Sunshine hosts). We consume
# that CMakeLists.txt as-is via add_subdirectory rather than reimplementing
# its build rules.
#
# TLS backend: OpenSSL is moonlight-common-c's default, but it needs a
# separate Android cross-compile (Perl + its own Configure script) outside
# this CMake build entirely. mbedTLS is CMake-native and cross-compiles
# through the exact same NDK toolchain file Gradle already passes to this
# build, so USE_MBEDTLS is set here instead — genuinely easier for this
# specific pipeline, not a "second best" fallback.
#
# vendor/mbedtls currently tracks its `development` branch (default branch
# at the time this was vendored) — pin to a numbered release tag (e.g.
# v3.6.x) before shipping anything; tracking development for actual TLS
# code is a real thing to fix, not just a formality.

set(USE_MBEDTLS ON CACHE BOOL "" FORCE)
add_subdirectory(
    ${CMAKE_CURRENT_SOURCE_DIR}/../../vendor/mbedtls
    ${CMAKE_CURRENT_BINARY_DIR}/mbedtls
)

set(BUILD_SHARED_LIBS OFF)
add_subdirectory(
    ${CMAKE_CURRENT_SOURCE_DIR}/../../vendor/moonlight-common-c
    ${CMAKE_CURRENT_BINARY_DIR}/moonlight-common-c
)

add_library(remotestream SHARED
    src/remotestream_jni.cpp
)
target_link_libraries(remotestream moonlight-common-c)

find_library(log-lib log)
target_link_libraries(remotestream ${log-lib})
