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
# Requires OpenSSL >= 1.0.2 (or USE_MBEDTLS) available for the target ABI —
# neither cross-compiled for Android in this environment. UNVERIFIED here;
# see runtime-remote-stream/README.md.

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
