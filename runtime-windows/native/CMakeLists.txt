cmake_minimum_required(VERSION 3.22.1)

# The Windows runtime's native libraries, both ABIs of the fat APK
# (docs/SPEC.md 9, "The runtime's native code and prebuilt binaries";
# 10b, "Fat APKs only" and "The x86_64 Windows runtime"). Every shared
# library this project defines ships, on both ABIs under the same file name
# (Android's ABI picker compares names):
#
#   GameNative's native sources, copied into upstream/ unmodified:
#     virglrenderer, xconnectorpatch (their own CMake projects),
#     asurface_renderer and ahbimage (as upstream/asurfacerenderer defines
#     them), winlator, winlator_11, extras, vulkan_renderer, evshim
#     (against vendor/SDL2's headers)
#   arm64-v8a only, from vendor/libadrenotools (Pipetto-crypto's
#   libadrenotools at the commit GameNative pins): the static adrenotools
#   that extras and vulkan_renderer link, and the Adreno hook libraries
#   hook_impl and main_hook
#   x86_64 only, shims/, where the arm64 job does not exist on x86_64:
#     adrenotools (static, system Vulkan), hook_impl, main_hook, kgslshim,
#     vortekrenderer
#
# The arm64 libkgslshim and libvortekrenderer have no source anywhere; they
# come from Droidtop/droidtop-components (runtime-windows/components.pin).
project(windows_runtime_natives C CXX)

set(UPSTREAM ${CMAKE_CURRENT_SOURCE_DIR}/upstream)
set(SHIMS ${CMAKE_CURRENT_SOURCE_DIR}/shims)
set(ADRENOTOOLS ${CMAKE_CURRENT_SOURCE_DIR}/../../vendor/libadrenotools)

function(page_16k target)
    target_link_options(${target} PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")
endfunction()

add_subdirectory(${UPSTREAM}/virglrenderer virglrenderer)
add_subdirectory(${UPSTREAM}/xconnectorpatch xconnectorpatch)

# asurface_renderer and ahbimage, as upstream/asurfacerenderer/CMakeLists.txt
# defines them. Defined here rather than through add_subdirectory so that its
# third library, drawable, is not a target of this project: nothing loads
# libdrawable (winlator_11 compiles drawable.c itself).
set(ASR ${UPSTREAM}/asurfacerenderer)
add_library(asurface_renderer SHARED
        ${ASR}/asurface_jni.cpp
        ${ASR}/ASurfaceRendererContext.cpp
        ${ASR}/blit_converter.cpp)
set_target_properties(asurface_renderer PROPERTIES CXX_STANDARD 17 CXX_STANDARD_REQUIRED ON)
target_compile_options(asurface_renderer PRIVATE -O3 -Wall -Wextra -fvisibility=hidden)
target_link_libraries(asurface_renderer log android dl atomic EGL GLESv3)
page_16k(asurface_renderer)

add_library(ahbimage SHARED ${ASR}/ahbimage.c)
target_compile_definitions(ahbimage PRIVATE EGL_EGLEXT_PROTOTYPES GL_GLEXT_PROTOTYPES)
target_compile_options(ahbimage PRIVATE -Wall -Wextra)
target_link_libraries(ahbimage log android EGL GLESv2)
page_16k(ahbimage)

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
# extensions) and Drawable/Pixmap. Built here for x86_64 only. On arm64 the
# APK carries GameNative's prebuilt (components.pin), because
# libvortekrenderer.so needs it too: that copy has the SONAME
# "libwinlator.so", which vortek's NEEDED binds to once Java has loaded it,
# and it exports Winlator's ArrayList/ArrayDeque/IntArray/RingBuffer code,
# whose ring buffer is the shared-memory protocol vortek speaks and has no
# source. A copy built from gpu_helper.c and drawable.c alone shipped in
# 2967, and every vortek game died at its first Present on
# "cannot locate symbol ArrayList_add" (Droidtop/tracker#857).
# scripts/check-native-links.py now fails a build whose libraries cannot
# resolve their imports.
if(NOT ANDROID_ABI STREQUAL "arm64-v8a")
    add_library(winlator_11 SHARED
            ${UPSTREAM}/winlator/gpu_helper.c
            ${UPSTREAM}/asurfacerenderer/drawable.c)
    target_compile_options(winlator_11 PRIVATE -O3 -ffast-math -Wno-unused-parameter)
    target_link_libraries(winlator_11 log android jnigraphics dl m)
    page_16k(winlator_11)
endif()

if(ANDROID_ABI STREQUAL "arm64-v8a")
    # libadrenotools as its own CMakeLists.txt (and src/hook's) defines the
    # pieces GameNative ships; its file_redirect_hook and gsl_alloc_hook are
    # left out, as GameNative leaves them out. The hook libraries link libc++
    # statically, as GameNative's builds do (no libc++_shared.so among their
    # NEEDED entries): they are loaded into the driver's linker namespace.
    set(LNB ${ADRENOTOOLS}/lib/linkernsbypass)
    add_library(linkernsbypass STATIC ${LNB}/android_linker_ns.cpp ${LNB}/elf_soname_patcher.cpp)
    target_compile_options(linkernsbypass PRIVATE -Wall -Wextra)
    target_link_libraries(linkernsbypass android dl)
    target_include_directories(linkernsbypass PUBLIC ${LNB})
    set_target_properties(linkernsbypass PROPERTIES POSITION_INDEPENDENT_CODE ON)

    add_library(adrenotools STATIC ${ADRENOTOOLS}/src/bcenabler.cpp ${ADRENOTOOLS}/src/driver.cpp)
    target_include_directories(adrenotools PUBLIC ${ADRENOTOOLS}/include)
    target_include_directories(adrenotools PRIVATE ${ADRENOTOOLS})
    target_compile_options(adrenotools PRIVATE -Wall -Wextra)
    target_link_libraries(adrenotools android linkernsbypass)
    set_target_properties(adrenotools PROPERTIES POSITION_INDEPENDENT_CODE ON)
    set(ADRENOTOOLS_LIB adrenotools)

    add_library(hook_impl SHARED ${ADRENOTOOLS}/src/hook/hook_impl.cpp)
    target_compile_options(hook_impl PRIVATE -Wall -Wextra)
    target_include_directories(hook_impl PRIVATE ${ADRENOTOOLS}/include)
    target_link_libraries(hook_impl linkernsbypass log)
    target_link_options(hook_impl PRIVATE -static-libstdc++)
    set_target_properties(hook_impl PROPERTIES CXX_VISIBILITY_PRESET hidden)
    page_16k(hook_impl)

    add_library(main_hook SHARED ${ADRENOTOOLS}/src/hook/main_hook.c)
    target_compile_options(main_hook PRIVATE -Wall -Wextra)
    target_link_options(main_hook PRIVATE -z global)
    target_link_libraries(main_hook hook_impl)
    page_16k(main_hook)
else()
    # adrenotools' one call these libraries make, against the system loader.
    add_library(adrenotools_x86_64 STATIC ${SHIMS}/adrenotools_x86_64.c)
    target_include_directories(adrenotools_x86_64 PUBLIC ${ADRENOTOOLS}/include)
    set_target_properties(adrenotools_x86_64 PROPERTIES POSITION_INDEPENDENT_CODE ON)
    target_link_libraries(adrenotools_x86_64 log dl)
    set(ADRENOTOOLS_LIB adrenotools_x86_64)

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
endif()

add_library(extras SHARED
        ${UPSTREAM}/extras/gpu_image.c
        ${UPSTREAM}/extras/vulkan.c)
target_compile_options(extras PRIVATE -O2 -Wno-unused-function)
target_link_libraries(extras log android EGL GLESv2 GLESv3 ${ADRENOTOOLS_LIB})
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
target_link_libraries(vulkan_renderer log android vulkan ${ADRENOTOOLS_LIB} dl atomic)
page_16k(vulkan_renderer)

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

# libmimalloc: vendor/mimalloc (microsoft/mimalloc v2.2.7, MIT), the allocator a game can choose for its Wine processes
# instead of bionic's Scudo (docs/SPEC.md 9, "The allocator a game's Wine uses"; Droidtop/tracker#894). WineAllocator
# puts it first on LD_PRELOAD, so it overrides malloc and friends (MI_MALLOC_OVERRIDE). Built as mimalloc's own
# single-file build (src/static.c) with the defines and flags its CMakeLists gives a shared, overriding release
# library, and native ELF TLS (-fno-emulated-tls): emulated TLS allocates through malloc, which is this library.
# Native TLS needs Android 10, so WineAllocator offers it from API 29 only.
set(MIMALLOC ${CMAKE_CURRENT_SOURCE_DIR}/../../vendor/mimalloc)
add_library(mimalloc SHARED ${MIMALLOC}/src/static.c)
set_target_properties(mimalloc PROPERTIES C_STANDARD 11)
target_include_directories(mimalloc PRIVATE ${MIMALLOC}/include)
target_compile_definitions(mimalloc PRIVATE MI_SHARED_LIB MI_SHARED_LIB_EXPORT MI_MALLOC_OVERRIDE MI_DEBUG=0 NDEBUG)
target_compile_options(mimalloc PRIVATE -O3 -fvisibility=hidden -ftls-model=initial-exec -fno-emulated-tls
        -fno-builtin-malloc -Wno-unknown-pragmas -Wno-static-in-inline)
target_link_libraries(mimalloc dl)
page_16k(mimalloc)

# The Wine media handoff (docs/SPEC.md 9, "Video in Wine games"; Droidtop/tracker#601): a GStreamer element Wine's
# GStreamer loads, and the decode server it talks to, one process per stream. Slice 1: the server's decoder is a stub
# that returns the media converter's blank; slice 2 brings the enginehost-ffmpeg library.
set(MEDIADECODE ${CMAKE_CURRENT_SOURCE_DIR}/mediadecode)

# libdroidtopmediad.so: an executable, named like a library so Android installs it in nativeLibraryDir, the one place
# an app may run a file from (as libvirgl_vtest.so and libsbx_exec.so are).
add_executable(droidtopmediad ${MEDIADECODE}/mediad.c)
set_target_properties(droidtopmediad PROPERTIES
        C_STANDARD 11
        OUTPUT_NAME "libdroidtopmediad.so"
        RUNTIME_OUTPUT_DIRECTORY "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}")
target_compile_options(droidtopmediad PRIVATE -O2 -Wall -Wextra -fstack-protector-strong -D_FORTIFY_SOURCE=2)
target_link_options(droidtopmediad PRIVATE -pie -Wl,-z,relro,-z,now)
page_16k(droidtopmediad)

# libgstdroidtopff.so, against vendor/gstreamer and vendor/glib's headers (with the headers their builds generate,
# in mediadecode/generated) and the image's libraries' sonames, through link-only stubs.
# The stubs are built with the compiler directly, not as library targets: every shared library this project defines
# ships in the APK, and these must not.
set(GST_STUB_DIR ${CMAKE_CURRENT_BINARY_DIR}/gst-link-stubs)
set(GST_STUBS)
foreach(stub IN ITEMS "GST;gstreamer-1.0" "GOBJECT;gobject-2.0" "GLIB;glib-2.0")
    list(GET stub 0 key)
    list(GET stub 1 lib)
    set(out ${GST_STUB_DIR}/lib${lib}.so)
    add_custom_command(OUTPUT ${out}
            COMMAND ${CMAKE_COMMAND} -E make_directory ${GST_STUB_DIR}
            COMMAND ${CMAKE_C_COMPILER} --target=${CMAKE_C_COMPILER_TARGET} -nostdlib -shared -fPIC -DSTUB_${key}
                    -Wl,-soname,lib${lib}.so.0 -o ${out} ${MEDIADECODE}/link-stubs.c
            DEPENDS ${MEDIADECODE}/link-stubs.c
            VERBATIM)
    list(APPEND GST_STUBS ${out})
endforeach()
add_custom_target(gst_link_stubs DEPENDS ${GST_STUBS})

add_library(gstdroidtopff SHARED ${MEDIADECODE}/gstdroidtopff.c)
set_target_properties(gstdroidtopff PROPERTIES C_STANDARD 11)
target_include_directories(gstdroidtopff PRIVATE
        ${MEDIADECODE}
        ${MEDIADECODE}/generated
        ${MEDIADECODE}/generated/glib
        ${CMAKE_CURRENT_SOURCE_DIR}/../../vendor/glib
        ${CMAKE_CURRENT_SOURCE_DIR}/../../vendor/glib/glib
        ${CMAKE_CURRENT_SOURCE_DIR}/../../vendor/glib/gmodule
        ${CMAKE_CURRENT_SOURCE_DIR}/../../vendor/gstreamer/subprojects/gstreamer)
# The oldest API this uses, so a newer header never pulls in a symbol an older image lacks.
target_compile_definitions(gstdroidtopff PRIVATE
        GLIB_VERSION_MIN_REQUIRED=GLIB_VERSION_2_56 GLIB_VERSION_MAX_ALLOWED=GLIB_VERSION_2_56 GST_DISABLE_GST_DEBUG)
target_compile_options(gstdroidtopff PRIVATE -O2 -Wall -Wextra -fvisibility=hidden -fstack-protector-strong
        -D_FORTIFY_SOURCE=2)
add_dependencies(gstdroidtopff gst_link_stubs)
target_link_options(gstdroidtopff PRIVATE -Wl,--no-undefined -Wl,-z,relro,-z,now -L${GST_STUB_DIR})
target_link_libraries(gstdroidtopff -lgstreamer-1.0 -lgobject-2.0 -lglib-2.0)
page_16k(gstdroidtopff)
