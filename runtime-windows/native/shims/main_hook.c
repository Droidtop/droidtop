// adrenotools' main_hook for x86_64 (docs/SPEC.md 10b): the same two
// exports as upstream's src/hook/main_hook.c, forwarding to hook_impl.c,
// which passes them to the real linker.
#include <android/dlext.h>

void *hook_android_dlopen_ext(const char *filename, int flags, const android_dlextinfo *extinfo);
void *hook_android_load_sphal_library(const char *filename, int flags);

__attribute__((visibility("default")))
void *android_dlopen_ext(const char *filename, int flags, const android_dlextinfo *extinfo) {
    return hook_android_dlopen_ext(filename, flags, extinfo);
}

__attribute__((visibility("default")))
void *android_load_sphal_library(const char *filename, int flags) {
    return hook_android_load_sphal_library(filename, flags);
}
