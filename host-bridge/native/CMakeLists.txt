cmake_minimum_required(VERSION 3.22)
project(hostbridge)

# host-bridge is a Wayland CLIENT of the primary container's compositor — it
# does not build or link wlroots/sway (those run inside the container,
# cross-compiled separately for the container's userland, not for Android).
# It links against wayland-client itself and generates C bindings for three
# non-core protocols via wayland-scanner:
#
#   - wlr-screencopy-unstable-v1   (vendor/wlroots/protocol)   — frame capture
#   - wlr-virtual-pointer-unstable-v1 (vendor/wlroots/protocol) — pointer injection
#   - virtual-keyboard-unstable-v1  (vendor/wlroots/protocol)   — keyboard injection
#
# (all three live under vendor/wlroots/protocol, not vendor/wayland-protocols
# — confirmed against the actual repo layout, corrected after the first real
# build attempt referenced the wrong path)
#
# wayland-scanner is a HOST build tool (codegen only, runs at build time on
# the machine building this, not on-device) — it does not need to be built
# for Android. libwayland-client itself DOES need to be cross-compiled for
# Android and made available to this CMake project; see README.md, that
# hasn't been set up in this environment (no NDK/meson/ninja available here
# — see host-bridge/README.md's "Status" section for what's verified vs not).

find_program(WAYLAND_SCANNER_EXECUTABLE wayland-scanner)
if(NOT WAYLAND_SCANNER_EXECUTABLE)
    message(WARNING "wayland-scanner not found on host — protocol codegen will be skipped. "
                     "hostbridge will build with stub bindings only.")
endif()

set(PROTO_DIR ${CMAKE_CURRENT_SOURCE_DIR}/protocol)
set(GEN_DIR ${CMAKE_CURRENT_BINARY_DIR}/generated)
file(MAKE_DIRECTORY ${GEN_DIR})

# (protocol_name, xml_relative_path) pairs
set(HOSTBRIDGE_PROTOCOLS
    "wlr-screencopy-unstable-v1;${CMAKE_CURRENT_SOURCE_DIR}/../../vendor/wlroots/protocol/wlr-screencopy-unstable-v1.xml"
    "wlr-virtual-pointer-unstable-v1;${CMAKE_CURRENT_SOURCE_DIR}/../../vendor/wlroots/protocol/wlr-virtual-pointer-unstable-v1.xml"
    "virtual-keyboard-unstable-v1;${CMAKE_CURRENT_SOURCE_DIR}/../../vendor/wlroots/protocol/virtual-keyboard-unstable-v1.xml"
)

set(GENERATED_SOURCES "")
if(WAYLAND_SCANNER_EXECUTABLE)
    list(LENGTH HOSTBRIDGE_PROTOCOLS _proto_count)
    math(EXPR _proto_pairs "${_proto_count} / 2 - 1")
    foreach(i RANGE 0 ${_proto_pairs})
        math(EXPR _name_idx "${i} * 2")
        math(EXPR _xml_idx "${_name_idx} + 1")
        list(GET HOSTBRIDGE_PROTOCOLS ${_name_idx} _name)
        list(GET HOSTBRIDGE_PROTOCOLS ${_xml_idx} _xml)

        set(_header ${GEN_DIR}/${_name}-client-protocol.h)
        set(_source ${GEN_DIR}/${_name}-client-protocol.c)

        add_custom_command(OUTPUT ${_header}
            COMMAND ${WAYLAND_SCANNER_EXECUTABLE} client-header ${_xml} ${_header}
            DEPENDS ${_xml})
        add_custom_command(OUTPUT ${_source}
            COMMAND ${WAYLAND_SCANNER_EXECUTABLE} private-code ${_xml} ${_source}
            DEPENDS ${_xml} ${_header})

        list(APPEND GENERATED_SOURCES ${_source})
    endforeach()
endif()

add_library(hostbridge SHARED
    src/hostbridge_jni.cpp
    src/wayland_client.cpp
    ${GENERATED_SOURCES}
)

target_include_directories(hostbridge PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/src
    ${GEN_DIR}
)

# libwayland-client is cross-compiled separately (Meson + a cross file; see
# host-bridge/README.md and build-scripts/build-vendor-deps.sh) — CMake
# doesn't build it. ANDROID_DEPS_PREFIX points at where that build installed
# its output, per-ABI (matches vendor/wayland's Meson --prefix convention:
# <prefix>/<ANDROID_ABI>). Overridable via -DANDROID_DEPS_PREFIX=... for
# devs who put their cross-compiled deps somewhere else.
if(NOT ANDROID_DEPS_PREFIX)
    set(ANDROID_DEPS_PREFIX "/opt/android-deps")
endif()
set(ANDROID_DEPS_DIR "${ANDROID_DEPS_PREFIX}/${ANDROID_ABI}")

# NO_CMAKE_FIND_ROOT_PATH matters here: the NDK's CMake toolchain file sets
# CMAKE_FIND_ROOT_PATH to its own sysroot and, by default, re-roots every
# find_path/find_library PATHS entry underneath it — silently turning our
# explicit ANDROID_DEPS_DIR into <ndk-sysroot>/opt/android-deps/..., which
# doesn't exist. Caught when the .so was confirmed present on disk but
# find_library still reported NOTFOUND.
find_path(WAYLAND_CLIENT_INCLUDE_DIR wayland-client.h
    PATHS "${ANDROID_DEPS_DIR}/include"
    NO_DEFAULT_PATH NO_CMAKE_FIND_ROOT_PATH)
find_library(WAYLAND_CLIENT_LIB wayland-client
    PATHS "${ANDROID_DEPS_DIR}/lib"
    NO_DEFAULT_PATH NO_CMAKE_FIND_ROOT_PATH)

if(WAYLAND_CLIENT_LIB AND WAYLAND_CLIENT_INCLUDE_DIR)
    target_include_directories(hostbridge PRIVATE ${WAYLAND_CLIENT_INCLUDE_DIR})
    target_link_libraries(hostbridge ${WAYLAND_CLIENT_LIB})
else()
    message(WARNING "libwayland-client not found under ${ANDROID_DEPS_DIR} — "
                     "hostbridge will fail to link until a cross-compiled build "
                     "is provided there (see README.md / build-scripts/build-vendor-deps.sh).")
endif()

find_library(log-lib log)
target_link_libraries(hostbridge ${log-lib})
