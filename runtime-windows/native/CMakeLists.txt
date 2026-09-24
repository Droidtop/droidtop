cmake_minimum_required(VERSION 3.22.1)

# The x86_64 half of gamenative's native libraries (docs/SPEC.md 10b, "Fat
# APKs only" and "The x86_64 Windows runtime"). Upstream ships these
# prebuilt for arm64-v8a alone, and arm64 keeps upstream's binaries;
# build.gradle.kts restricts this project to x86_64 and to the targets it
# names. Every library of the arm64 set gets an x86_64 build that does
# its job on x86_64's own stack:
#
#   the fork's own CMake projects, unmodified:
#     virglrenderer, patchelf, asurface_renderer, ahbimage, xconnectorpatch
#   the fork's sources, for the functions gamenative's Java calls:
#     winlator, winlator_11, extras, vulkan_renderer
#   shims/, where the arm64 job does not exist on x86_64:
#     adrenotools (static, system Vulkan), hook_impl, main_hook, kgslshim,
#     vortekrenderer, steambootstrap
#   upstream projects:
#     lsfg-vk (the fork's submodule), evshim (the fork's source against
#     vendor/SDL2's headers), openxr_loader (vendor/OpenXR-SDK)
project(gamenative_x86_64 C CXX)

set(GAMENATIVE_CPP ${CMAKE_CURRENT_SOURCE_DIR}/../../vendor/gamenative/app/src/main/cpp)
set(SHIMS ${CMAKE_CURRENT_SOURCE_DIR}/shims)

add_subdirectory(${GAMENATIVE_CPP}/virglrenderer virglrenderer)
add_subdirectory(${GAMENATIVE_CPP}/patchelf patchelf)
add_subdirectory(${GAMENATIVE_CPP}/asurfacerenderer asurfacerenderer)
add_subdirectory(${GAMENATIVE_CPP}/xconnectorpatch xconnectorpatch)

function(page_16k target)
    target_link_options(${target} PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")
endfunction()

# libwinlator: XConnector sockets, SysV shared memory and the ALSA client
# (XConnectorEpoll.java, ClientSocket.java, SysVSharedMemory.java). The
# fork's winlator/patchelf_wrapper.cpp is empty TODO bodies and nothing
# constructs PatchElf, so it is left out rather than shipped broken.
add_library(winlator SHARED
        ${GAMENATIVE_CPP}/winlator/alsa_client.c
        ${GAMENATIVE_CPP}/winlator/sysvshared_memory.c
        ${GAMENATIVE_CPP}/winlator/xconnector_epoll.c)
target_compile_options(winlator PRIVATE -O2 -Wno-unused-function)
target_link_libraries(winlator log android aaudio)
page_16k(winlator)

# libwinlator_11: Java loads it for GPUHelper (Vulkan version and
# extensions) and Drawable/Pixmap, and calls nothing else in it.
add_library(winlator_11 SHARED
        ${GAMENATIVE_CPP}/winlator/gpu_helper.c
        ${GAMENATIVE_CPP}/asurfacerenderer/drawable.c)
target_compile_options(winlator_11 PRIVATE -O3 -ffast-math -Wno-unused-parameter)
target_link_libraries(winlator_11 log android jnigraphics dl m)
page_16k(winlator_11)

# adrenotools' one call these libraries make, against the system loader.
add_library(adrenotools_x86_64 STATIC ${SHIMS}/adrenotools_x86_64.c)
target_include_directories(adrenotools_x86_64 PUBLIC ${GAMENATIVE_CPP}/extras/adrenotools/include)
set_target_properties(adrenotools_x86_64 PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_link_libraries(adrenotools_x86_64 log dl)

add_library(extras SHARED
        ${GAMENATIVE_CPP}/extras/gpu_image.c
        ${GAMENATIVE_CPP}/extras/vulkan.c)
target_compile_options(extras PRIVATE -O2 -Wno-unused-function)
target_link_libraries(extras log android EGL GLESv2 GLESv3 adrenotools_x86_64)
page_16k(extras)

# libvulkan_renderer, as the fork's top-level CMakeLists.txt defines it,
# shaders compiled the same way (glslangValidator, from the apt toolchain).
set(WINLATOR_DIR ${GAMENATIVE_CPP}/winlator)
add_custom_command(
        OUTPUT ${CMAKE_CURRENT_BINARY_DIR}/window_vert.h
        COMMAND glslangValidator -V --vn window_vert_code -x
                -o ${CMAKE_CURRENT_BINARY_DIR}/window_vert.h ${WINLATOR_DIR}/window.vert
        DEPENDS ${WINLATOR_DIR}/window.vert)
add_custom_command(
        OUTPUT ${CMAKE_CURRENT_BINARY_DIR}/window_frag.h
        COMMAND glslangValidator -V --vn window_frag_code -x
                -o ${CMAKE_CURRENT_BINARY_DIR}/window_frag.h ${WINLATOR_DIR}/window.frag
        DEPENDS ${WINLATOR_DIR}/window.frag)
add_library(vulkan_renderer SHARED
        ${WINLATOR_DIR}/vulkan_jni.cpp
        ${WINLATOR_DIR}/VulkanRendererContext.cpp
        ${WINLATOR_DIR}/VulkanRendererScanout.cpp
        ${CMAKE_CURRENT_BINARY_DIR}/window_vert.h
        ${CMAKE_CURRENT_BINARY_DIR}/window_frag.h)
target_include_directories(vulkan_renderer PRIVATE ${CMAKE_CURRENT_BINARY_DIR})
target_compile_options(vulkan_renderer PRIVATE -Wall -Wextra -fvisibility=hidden -std=c++17)
target_link_libraries(vulkan_renderer log android vulkan adrenotools_x86_64 dl atomic)
page_16k(vulkan_renderer)

add_library(hook_impl SHARED ${SHIMS}/hook_impl.c)
target_link_libraries(hook_impl log android dl)
page_16k(hook_impl)

add_library(main_hook SHARED ${SHIMS}/main_hook.c)
target_link_libraries(main_hook hook_impl android dl log)
page_16k(main_hook)

add_library(kgslshim SHARED ${SHIMS}/kgslshim.c)
target_link_libraries(kgslshim dl)
page_16k(kgslshim)

add_library(vortekrenderer SHARED ${SHIMS}/vortekrenderer.c)
target_link_libraries(vortekrenderer log)
page_16k(vortekrenderer)

# A program, like upstream's: SteamBootstrap.kt runs it out of
# nativeLibraryDir, which only holds files named lib*.so.
add_executable(steambootstrap ${SHIMS}/steambootstrap.c)
set_target_properties(steambootstrap PROPERTIES PREFIX "lib" OUTPUT_NAME "steambootstrap" SUFFIX ".so")
page_16k(steambootstrap)

# liblsfg-vk-layer: the fork's lsfg-vk-android submodule, built the way
# its CMakeLists.txt builds it for GameNative. Upstream ships the result
# as liblsfg-vk-layer.so (LsfgVkManager.kt's LIB_FILENAME).
set(LSFGVK_ANDROID_WINE ON CACHE BOOL "" FORCE)
# framegen's AHardwareBuffer import (src/core/image.cpp) needs the
# VK_ANDROID_* types, which vulkan.h declares only under the Android
# platform define. volk takes its platform defines from this variable and
# passes them on PUBLIC to lsfg-vk-framegen and lsfg-vk.
set(VOLK_STATIC_DEFINES VK_USE_PLATFORM_ANDROID_KHR CACHE STRING "" FORCE)
add_subdirectory(${GAMENATIVE_CPP}/lsfg-vk-android lsfg-vk EXCLUDE_FROM_ALL)
set_target_properties(lsfg-vk PROPERTIES OUTPUT_NAME "lsfg-vk-layer")
page_16k(lsfg-vk)

# libevshim: the fork's evshim.c includes <SDL2/SDL.h> from a third_party
# directory the fork does not carry, and dlopens libSDL2 at run time, so
# only the headers are needed: vendor/SDL2's, under the SDL2/ prefix.
set(SDL2_HEADERS ${CMAKE_CURRENT_BINARY_DIR}/sdl2-headers)
file(MAKE_DIRECTORY ${SDL2_HEADERS})
file(CREATE_LINK ${CMAKE_CURRENT_SOURCE_DIR}/../../vendor/SDL2/include ${SDL2_HEADERS}/SDL2 SYMBOLIC)
add_library(evshim SHARED ${GAMENATIVE_CPP}/evshim/evshim.c)
set_target_properties(evshim PROPERTIES C_STANDARD 11)
target_include_directories(evshim PRIVATE ${SDL2_HEADERS})
target_compile_options(evshim PRIVATE -fvisibility=hidden -O3 -Wall -Wextra)
target_link_libraries(evshim log dl)
page_16k(evshim)

# libopenxr_loader: Khronos' loader, vendor/OpenXR-SDK at a release tag.
set(BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(BUILD_API_LAYERS OFF CACHE BOOL "" FORCE)
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../vendor/OpenXR-SDK openxr EXCLUDE_FROM_ALL)
