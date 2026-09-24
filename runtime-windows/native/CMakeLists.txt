cmake_minimum_required(VERSION 3.22.1)

# The x86_64 build of gamenative's native libraries (docs/SPEC.md 10b, "Fat
# APKs only"). Upstream ships these prebuilt for arm64-v8a alone; this hooks
# the fork's own CMake projects, unmodified, and build.gradle.kts restricts
# it to x86_64 and to the targets below.
#
# Built here: every library of the arm64 set whose source is in the tree and
# is the source of what ships. Not built, and why (read from the fork at
# the pinned commit):
#   libwinlator            winlator/patchelf_wrapper.cpp is empty "TODO"
#                          bodies, and the fork's own CMakeLists names a
#                          winlator/drawable.c that does not exist: the
#                          shipped arm64 binary came from other source.
#   libextras, libvulkan_renderer, libhook_impl, libmain_hook
#                          link adrenotools, whose CMakeLists refuses every
#                          ABI but arm64-v8a (Adreno driver loading).
#   libkgslshim, libsteambootstrap
#                          source withheld by GameNative (THIRD_PARTY_NOTICES).
#   libvortekrenderer, libwinlator_11
#                          Winlator 11 binaries; no source in the fork.
#   libevshim              needs the SDL2 headers the fork's third_party dir
#                          does not carry.
#   libpulse*, libsndfile, libltdl, libopenxr_loader, liblsfg-vk-layer
#                          upstream projects outside this tree.
project(gamenative_x86_64 C CXX)

set(GAMENATIVE_CPP ${CMAKE_CURRENT_SOURCE_DIR}/../../vendor/gamenative/app/src/main/cpp)

add_subdirectory(${GAMENATIVE_CPP}/virglrenderer virglrenderer)
add_subdirectory(${GAMENATIVE_CPP}/patchelf patchelf)
add_subdirectory(${GAMENATIVE_CPP}/asurfacerenderer asurfacerenderer)
add_subdirectory(${GAMENATIVE_CPP}/xconnectorpatch xconnectorpatch)
