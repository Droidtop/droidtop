// adrenotools' hook_impl for x86_64 (docs/SPEC.md 10b). On arm64 it
// redirects the Vulkan loader's driver loads to a custom Adreno driver;
// x86_64 has none, so each hook calls the real linker function unchanged.
#include <android/dlext.h>
#include <dlfcn.h>
#include <stddef.h>

typedef void *(*dlopen_ext_fn)(const char *, int, const android_dlextinfo *);
typedef void *(*load_sphal_fn)(const char *, int);

static void *real(const char *lib, const char *name) {
    void *handle = dlopen(lib, RTLD_NOW | RTLD_LOCAL);
    return handle != NULL ? dlsym(handle, name) : NULL;
}

__attribute__((visibility("default")))
void *hook_android_dlopen_ext(const char *filename, int flags, const android_dlextinfo *extinfo) {
    dlopen_ext_fn fn = (dlopen_ext_fn) real("libdl.so", "android_dlopen_ext");
    return fn != NULL ? fn(filename, flags, extinfo) : dlopen(filename, flags);
}

__attribute__((visibility("default")))
void *hook_android_load_sphal_library(const char *filename, int flags) {
    load_sphal_fn fn = (load_sphal_fn) real("libvndksupport.so", "android_load_sphal_library");
    return fn != NULL ? fn(filename, flags) : dlopen(filename, flags);
}
