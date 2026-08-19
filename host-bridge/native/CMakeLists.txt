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

# NOT YET WIRED UP: needs a cross-compiled libwayland-client for the target
# Android ABI. See host-bridge/README.md for what this requires (build
# vendor/wayland's libwayland-client with a Meson Android cross file) —
# unverified in this environment, no meson/ninja/NDK present.
find_library(WAYLAND_CLIENT_LIB wayland-client)
if(WAYLAND_CLIENT_LIB)
    target_link_libraries(hostbridge ${WAYLAND_CLIENT_LIB})
else()
    message(WARNING "libwayland-client not found — hostbridge will fail to link "
                     "until a cross-compiled build is provided (see README.md).")
endif()

find_library(log-lib log)
target_link_libraries(hostbridge ${log-lib})
