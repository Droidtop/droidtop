cmake_minimum_required(VERSION 3.22.1)

# The x86_64 half of the Windows runtime's native libraries (docs/SPEC.md
# 10b, "Fat APKs only" and "The x86_64 Windows runtime"). The arm64 set is
# GameNative's prebuilt one, re-hosted by droidtop (windows-runtime-prebuilt,
# docs/SPEC.md 9); build.gradle.kts restricts this project to x86_64 and to
# the targets it names. Every library of that set gets an x86_64 build that
# does its job on x86_64's own stack, with the same file name (Android's ABI
# picker compares names):
#
#   GameNative's native sources, copied into upstream/ from droidtop's fork
#   (Droidtop/gamenative-tux at 0f08762e), unmodified:
#     virglrenderer, asurface_renderer, ahbimage, xconnectorpatch (their own
#     CMake projects), winlator, winlator_11, extras, vulkan_renderer, evshim
#     (against vendor/SDL2's headers)
#   shims/, where the arm64 job does not exist on x86_64:
#     adrenotools (static, system Vulkan), hook_impl, main_hook, kgslshim,
#     vortekrenderer
project(windows_runtime_x86_64 C CXX)

set(UPSTREAM ${CMAKE_CURRENT_SOURCE_DIR}/upstream)
set(SHIMS ${CMAKE_CURRENT_SOURCE_DIR}/shims)

add_subdirectory(${UPSTREAM}/virglrenderer virglrenderer)
add_subdirectory(${UPSTREAM}/asurfacerenderer asurfacerenderer)
add_subdirectory(${UPSTREAM}/xconnectorpatch xconnectorpatch)

function(page_16k target)
    target_link_options(${target} PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")
endfunction()

# libwinlator: XConnector sockets, SysV shared memory and the ALSA client
# (XConnectorEpoll.java, ClientSocket.java, SysVSharedMemory.java).
add_library(winlator SHARED
        ${UPSTREAM}/winlator/alsa_client.c
        ${UPSTREAM}/winlator/sysvshared_memory.c
        ${UPSTREAM}/winlator/xconnector_epoll.c)
target_compile_options(winlator PRIVATE -O2 -Wno-unused-function)
target_link_libraries(winlator log android aaudio)
page_16k(winlator)

# libwinlator_11: Java loads it for GPUHelper (Vulkan version and
# extensions) and Drawable/Pixmap, and calls nothing else in it.
add_library(winlator_11 SHARED
        ${UPSTREAM}/winlator/gpu_helper.c
        ${UPSTREAM}/asurfacerenderer/drawable.c)
target_compile_options(winlator_11 PRIVATE -O3 -ffast-math -Wno-unused-parameter)
target_link_libraries(winlator_11 log android jnigraphics dl m)
page_16k(winlator_11)

# adrenotools' one call these libraries make, against the system loader.
add_library(adrenotools_x86_64 STATIC ${SHIMS}/adrenotools_x86_64.c)
target_include_directories(adrenotools_x86_64 PUBLIC ${UPSTREAM}/extras/adrenotools/include)
set_target_properties(adrenotools_x86_64 PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_link_libraries(adrenotools_x86_64 log dl)

add_library(extras SHARED
        ${UPSTREAM}/extras/gpu_image.c
        ${UPSTREAM}/extras/vulkan.c)
target_compile_options(extras PRIVATE -O2 -Wno-unused-function)
target_link_libraries(extras log android EGL GLESv2 GLESv3 adrenotools_x86_64)
page_16k(extras)

# libvulkan_renderer, as the fork's top-level CMakeLists.txt defines it,
# shaders compiled the same way (glslangValidator, from the apt toolchain).
set(WINLATOR_DIR ${UPSTREAM}/winlator)
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

# libevshim: evshim.c includes <SDL2/SDL.h> from a third_party
# directory the fork does not carry, and dlopens libSDL2 at run time, so
# only the headers are needed: vendor/SDL2's, under the SDL2/ prefix.
set(SDL2_HEADERS ${CMAKE_CURRENT_BINARY_DIR}/sdl2-headers)
file(MAKE_DIRECTORY ${SDL2_HEADERS})
file(CREATE_LINK ${CMAKE_CURRENT_SOURCE_DIR}/../../vendor/SDL2/include ${SDL2_HEADERS}/SDL2 SYMBOLIC)
add_library(evshim SHARED ${UPSTREAM}/evshim/evshim.c)
set_target_properties(evshim PROPERTIES C_STANDARD 11)
target_include_directories(evshim PRIVATE ${SDL2_HEADERS})
target_compile_options(evshim PRIVATE -fvisibility=hidden -O3 -Wall -Wextra)
target_link_libraries(evshim log dl)
page_16k(evshim)
