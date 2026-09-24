// libkgslshim for x86_64 (docs/SPEC.md 10b). On arm64 GameNative preloads
// it into the Wine processes (BionicProgramLauncherComponent) to rewrite
// Qualcomm KGSL memory ioctls; its only export is ioctl. x86_64 has no
// KGSL device, so every call goes to libc's ioctl unchanged.
#define _GNU_SOURCE
#include <dlfcn.h>
#include <stdarg.h>
#include <stddef.h>

typedef int (*ioctl_fn)(int, int, ...);

__attribute__((visibility("default")))
int ioctl(int fd, int request, ...) {
    static ioctl_fn next;
    if (next == NULL) next = (ioctl_fn) dlsym(RTLD_NEXT, "ioctl");
    va_list ap;
    va_start(ap, request);
    void *arg = va_arg(ap, void *);
    va_end(ap);
    return next(fd, request, arg);
}
