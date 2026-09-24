// adrenotools for x86_64 (docs/SPEC.md 10b). The fork's libextras and
// libvulkan_renderer make one adrenotools call, adrenotools_open_libvulkan,
// which on arm64 loads a custom Adreno driver or, failing that, the
// system one. x86_64 devices have no Adreno driver to load, so this opens
// the system Vulkan loader and nothing else. The real adrenotools
// refuses every ABI but arm64-v8a (its CMakeLists.txt).
#include <android/log.h>
#include <dlfcn.h>
#include <stddef.h>

#include "adrenotools/driver.h"

void *adrenotools_open_libvulkan(int dlopenMode, int featureFlags, const char *tmpLibDir,
                                 const char *hookLibDir, const char *customDriverDir,
                                 const char *customDriverName, const char *fileRedirectDir,
                                 void **userMappingHandle) {
    (void) featureFlags;
    (void) tmpLibDir;
    (void) hookLibDir;
    (void) customDriverDir;
    (void) fileRedirectDir;
    if (customDriverName != NULL && customDriverName[0] != '\0') {
        __android_log_print(ANDROID_LOG_INFO, "adrenotools",
                            "x86_64: custom driver %s ignored, using the system libvulkan.so",
                            customDriverName);
    }
    if (userMappingHandle != NULL) *userMappingHandle = NULL;
    return dlopen("libvulkan.so", dlopenMode);
}
