/*
 * Guarded hooks for a contained plugin's isolated process (docs/plugin-api.md
 * 5.3, "Guarded hooks"). The process can open none of droidtop's files: the
 * isolated_app SELinux domain is never allowed to open app_data_file, and
 * never allowed to execute any data file at all (AOSP sepolicy,
 * private/isolated_app_all.te and private/app.te). Code that loads by path
 * (ART's System.loadLibrary, a Flutter engine opening libapp.so, Android's
 * AssetManager opening an asset zip) would therefore fail.
 *
 * :app hands every file a plugin needs over as a descriptor, and this file
 * gives each one a virtual path under /droidtop-sandbox/. It then rewrites
 * the import slots (PLT/GOT) of the few system libraries that open those
 * paths, so a call that names a virtual path is answered from its
 * descriptor and every other call goes straight to libc as before:
 *
 * - open/openat and the stat/access family: a duplicate of the descriptor;
 * - dlopen/android_dlopen_ext: the library is mapped from a private memfd
 *   copy (the isolated domain may execute its own tmpfs files,
 *   appdomain_tmpfs, never droidtop's files), with ANDROID_DLEXT_USE_LIBRARY_FD
 *   added to whatever the caller passed, so ART still registers the library
 *   with the plugin's class loader and its JNI methods bind as usual.
 *
 * Only registered paths are reachable: a virtual path nobody registered is
 * ENOENT, and writing to one is EACCES. Nothing outside /droidtop-sandbox/
 * is changed. The rewrite is the bytehook technique (ByteDance, MIT),
 * written here for the two 64-bit ABIs droidtop ships.
 */
#define _GNU_SOURCE
/* The replacements call libc's own open/stat by address; FORTIFY's overloaded inline wrappers have none. */
#undef _FORTIFY_SOURCE
#include <jni.h>
#include <dlfcn.h>
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <link.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <android/dlext.h>
#include <android/log.h>

#define TAG "droidtop.sandbox"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

#define VROOT "/droidtop-sandbox/"
#define MAX_FILES 512

typedef struct {
    char *path;
    char *base; /* the file name, for a bare-name dlopen ("libapp.so") */
    int fd;     /* the descriptor :app handed over */
    int exec_fd; /* a memfd copy, made the first time the file is mapped as code */
} VFile;

static VFile g_files[MAX_FILES];
static int g_count = 0;
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;

static int is_virtual(const char *path) {
    return path != NULL && strncmp(path, VROOT, sizeof(VROOT) - 1) == 0;
}

/* The entry for [path], or for a bare library name registered as an alias; -1 when none. */
static int find(const char *path, int by_base) {
    if (path == NULL) return -1;
    pthread_mutex_lock(&g_lock);
    int found = -1;
    for (int i = 0; i < g_count && found < 0; i++) {
        if (strcmp(g_files[i].path, path) == 0) found = i;
        else if (by_base && strchr(path, '/') == NULL && g_files[i].base != NULL && strcmp(g_files[i].base, path) == 0) found = i;
    }
    pthread_mutex_unlock(&g_lock);
    return found;
}

static int dup_at_start(int i) {
    int fd = fcntl(g_files[i].fd, F_DUPFD_CLOEXEC, 0);
    if (fd >= 0) lseek(fd, 0, SEEK_SET);
    return fd;
}

/* A private memfd copy of entry [i], made once: what the isolated domain may map as code. */
static int exec_fd(int i) {
    pthread_mutex_lock(&g_lock);
    int out = g_files[i].exec_fd;
    if (out < 0) {
#ifdef __NR_memfd_create
        int copy = (int) syscall(__NR_memfd_create, g_files[i].base != NULL ? g_files[i].base : "droidtop-lib", 1 /* MFD_CLOEXEC */);
        if (copy >= 0) {
            char buf[65536];
            off_t offset = 0;
            for (;;) {
                ssize_t n = pread(g_files[i].fd, buf, sizeof buf, offset);
                if (n < 0 && errno == EINTR) continue;
                if (n <= 0) break;
                ssize_t written = 0;
                while (written < n) {
                    ssize_t k = write(copy, buf + written, (size_t) (n - written));
                    if (k < 0 && errno == EINTR) continue;
                    if (k < 0) break;
                    written += k;
                }
                offset += n;
            }
            g_files[i].exec_fd = copy;
            out = copy;
        }
#endif
    }
    pthread_mutex_unlock(&g_lock);
    return out;
}

/* ---- The replacements ---- */

static int h_open_common(const char *path, int flags, mode_t mode, int (*real)(const char *, int, ...)) {
    if (!is_virtual(path)) return real(path, flags, mode);
    int i = find(path, 0);
    if (i < 0) {
        errno = ENOENT;
        return -1;
    }
    if ((flags & O_ACCMODE) != O_RDONLY || (flags & (O_CREAT | O_TRUNC)) != 0) {
        errno = EACCES;
        return -1;
    }
    return dup_at_start(i);
}

static int h_open(const char *path, int flags, ...) {
    mode_t mode = 0;
    if (flags & O_CREAT) {
        va_list ap;
        va_start(ap, flags);
        mode = (mode_t) va_arg(ap, int);
        va_end(ap);
    }
    return h_open_common(path, flags, mode, open);
}

static int h_open_2(const char *path, int flags) {
    return h_open_common(path, flags, 0, open);
}

static int h_openat(int dirfd, const char *path, int flags, ...) {
    mode_t mode = 0;
    if (flags & O_CREAT) {
        va_list ap;
        va_start(ap, flags);
        mode = (mode_t) va_arg(ap, int);
        va_end(ap);
    }
    if (is_virtual(path)) return h_open_common(path, flags, mode, open);
    return openat(dirfd, path, flags, mode);
}

static int h_openat_2(int dirfd, const char *path, int flags) {
    if (is_virtual(path)) return h_open_common(path, flags, 0, open);
    return openat(dirfd, path, flags, 0);
}

static int vstat(const char *path, struct stat *st) {
    int i = find(path, 0);
    if (i < 0) {
        errno = ENOENT;
        return -1;
    }
    return fstat(g_files[i].fd, st);
}

static int h_stat(const char *path, struct stat *st) {
    return is_virtual(path) ? vstat(path, st) : stat(path, st);
}

static int h_lstat(const char *path, struct stat *st) {
    return is_virtual(path) ? vstat(path, st) : lstat(path, st);
}

static int h_fstatat(int dirfd, const char *path, struct stat *st, int flags) {
    return is_virtual(path) ? vstat(path, st) : fstatat(dirfd, path, st, flags);
}

static int vaccess(const char *path, int mode) {
    if (find(path, 0) < 0) {
        errno = ENOENT;
        return -1;
    }
    if (mode & W_OK) {
        errno = EACCES;
        return -1;
    }
    return 0;
}

static int h_access(const char *path, int mode) {
    return is_virtual(path) ? vaccess(path, mode) : access(path, mode);
}

static int h_faccessat(int dirfd, const char *path, int mode, int flags) {
    return is_virtual(path) ? vaccess(path, mode) : faccessat(dirfd, path, mode, flags);
}

static void *vdlopen(int i, const char *name, int flags, const android_dlextinfo *caller) {
    int fd = exec_fd(i);
    if (fd < 0) return NULL;
    android_dlextinfo info;
    if (caller != NULL) info = *caller;
    else memset(&info, 0, sizeof info);
    info.flags &= ~(uint64_t) ANDROID_DLEXT_USE_LIBRARY_FD_OFFSET;
    info.flags |= ANDROID_DLEXT_USE_LIBRARY_FD;
    info.library_fd = fd;
    void *handle = android_dlopen_ext(name, flags, &info);
    if (handle == NULL) LOGW("could not map %s from its descriptor: %s", name, dlerror());
    return handle;
}

static void *h_dlopen(const char *path, int flags) {
    int i = find(path, 1);
    if (i < 0) {
        if (is_virtual(path)) return NULL;
        return dlopen(path, flags);
    }
    return vdlopen(i, g_files[i].path, flags, NULL);
}

static void *h_android_dlopen_ext(const char *path, int flags, const android_dlextinfo *info) {
    int i = find(path, 1);
    if (i < 0) {
        if (is_virtual(path)) return NULL;
        return android_dlopen_ext(path, flags, info);
    }
    return vdlopen(i, g_files[i].path, flags, info);
}

typedef struct {
    const char *symbol;
    void *replacement;
} Hook;

/* Symbols a hooked library may not find through dlsym ([nativeHideSymbols]). The one use: in an isolated process the
 * Flutter engine must not take AChoreographer, whose display-event connection needs SurfaceFlinger, a service an
 * isolated process may not look up ("avc: denied { find } ... SurfaceFlingerAIDL", emulator-5560); without
 * AChoreographer_getInstance it waits for vsync through FlutterJNI, which droidtop drives. */
#define MAX_HIDDEN 8
static char *g_hidden[MAX_HIDDEN];
static int g_hidden_count = 0;

/* screen_bridge.c: the stand-in window's replacement for an ANativeWindow function, or NULL. */
void *screen_bridge_replacement(const char *symbol);
static int g_bridge_on = 0;

static void *h_dlsym(void *handle, const char *symbol) {
    if (symbol != NULL) {
        for (int i = 0; i < g_hidden_count; i++) {
            if (strcmp(symbol, g_hidden[i]) == 0) return NULL;
        }
        /* The engine resolves some window functions by name too (Impeller's proc table); those get the bridge's. */
        if (g_bridge_on) {
            void *bridged = screen_bridge_replacement(symbol);
            if (bridged != NULL) return bridged;
        }
    }
    return dlsym(handle, symbol);
}

static const Hook SYMBOL_HOOKS[] = {
    {"dlsym", (void *) h_dlsym},
};

static const Hook HOOKS[] = {
    {"open", (void *) h_open},
    {"open64", (void *) h_open},
    {"__open_2", (void *) h_open_2},
    {"openat", (void *) h_openat},
    {"openat64", (void *) h_openat},
    {"__openat_2", (void *) h_openat_2},
    {"stat", (void *) h_stat},
    {"stat64", (void *) h_stat},
    {"lstat", (void *) h_lstat},
    {"lstat64", (void *) h_lstat},
    {"fstatat", (void *) h_fstatat},
    {"fstatat64", (void *) h_fstatat},
    {"access", (void *) h_access},
    {"faccessat", (void *) h_faccessat},
    {"dlopen", (void *) h_dlopen},
    {"android_dlopen_ext", (void *) h_android_dlopen_ext},
};

/* ---- Rewriting a library's import slots ---- */

#if defined(__aarch64__)
#define R_JUMP_SLOT R_AARCH64_JUMP_SLOT
#define R_GLOB_DAT R_AARCH64_GLOB_DAT
#define R_ABS64 R_AARCH64_ABS64
#elif defined(__x86_64__)
#define R_JUMP_SLOT R_X86_64_JUMP_SLOT
#define R_GLOB_DAT R_X86_64_GLOB_DAT
#define R_ABS64 R_X86_64_64
#else
#error "droidtop ships arm64-v8a and x86_64 only"
#endif

typedef struct {
    const char *suffix; /* "/libjavacore.so" */
    int libraries;
    int slots;
    const Hook *hooks;
    size_t hook_count;
} Pass;

static uintptr_t abs_addr(uintptr_t bias, uintptr_t value) {
    /* bionic leaves the dynamic section's pointers unrelocated: below the load bias, they are offsets. */
    return value < bias ? value + bias : value;
}

static int patch_slot(uintptr_t slot, void *replacement) {
    long page = sysconf(_SC_PAGESIZE);
    uintptr_t start = slot & ~((uintptr_t) page - 1);
    if (mprotect((void *) start, (size_t) page, PROT_READ | PROT_WRITE) != 0) return 0;
    /* Left writable: the page may hold data the library itself writes, and its original protection is not
     * known without reading /proc/self/maps. Only this sandbox process is affected. */
    *(void **) slot = replacement;
    return 1;
}

static int patch_relocations(uintptr_t bias, const ElfW(Rela) *rel, size_t count, const ElfW(Sym) *symtab, const char *strtab, const Hook *hooks, size_t hook_count) {
    int patched = 0;
    for (size_t r = 0; r < count; r++) {
        unsigned long type = ELF64_R_TYPE(rel[r].r_info);
        if (type != R_JUMP_SLOT && type != R_GLOB_DAT && type != R_ABS64) continue;
        unsigned long sym = ELF64_R_SYM(rel[r].r_info);
        if (sym == 0) continue;
        const char *name = strtab + symtab[sym].st_name;
        for (size_t h = 0; h < hook_count; h++) {
            if (strcmp(name, hooks[h].symbol) == 0) {
                patched += patch_slot(bias + rel[r].r_offset, hooks[h].replacement);
                break;
            }
        }
    }
    return patched;
}

/* True when the loaded library [name] is the one [suffix] ("/libflutter.so") names. A library this file mapped from its
 * memfd copy is known to the linker by the memfd's own path, "/memfd:libflutter.so (deleted)", not by the virtual path
 * it was asked for, so that form matches on the name after "/memfd:" (emulator-5560: the engine was never hooked, its
 * dlopen of the plugin's libapp.so went unanswered and Dart found no snapshot). */
static int names_library(const char *name, const char *suffix) {
    char buf[512];
    snprintf(buf, sizeof buf, "%s", name);
    size_t n = strlen(buf);
    static const char deleted[] = " (deleted)";
    size_t d = sizeof deleted - 1;
    if (n >= d && strcmp(buf + n - d, deleted) == 0) buf[n -= d] = '\0';
    const char *effective = buf;
    char memfd[512];
    if (strncmp(buf, "/memfd:", 7) == 0) {
        snprintf(memfd, sizeof memfd, "/%s", buf + 7);
        effective = memfd;
        n = strlen(memfd);
    }
    size_t s = strlen(suffix);
    return n >= s && strcmp(effective + n - s, suffix) == 0;
}

static int patch_library(struct dl_phdr_info *info, size_t size, void *data) {
    Pass *pass = (Pass *) data;
    const char *name = info->dlpi_name;
    if (name == NULL || !names_library(name, pass->suffix)) return 0;
    uintptr_t bias = info->dlpi_addr;
    const ElfW(Dyn) *dyn = NULL;
    for (int p = 0; p < info->dlpi_phnum; p++) {
        if (info->dlpi_phdr[p].p_type == PT_DYNAMIC) dyn = (const ElfW(Dyn) *) (bias + info->dlpi_phdr[p].p_vaddr);
    }
    if (dyn == NULL) return 0;
    const ElfW(Sym) *symtab = NULL;
    const char *strtab = NULL;
    const ElfW(Rela) *jmprel = NULL, *rela = NULL;
    size_t jmprel_size = 0, rela_size = 0;
    for (const ElfW(Dyn) *d = dyn; d->d_tag != DT_NULL; d++) {
        switch (d->d_tag) {
            case DT_SYMTAB: symtab = (const ElfW(Sym) *) abs_addr(bias, d->d_un.d_ptr); break;
            case DT_STRTAB: strtab = (const char *) abs_addr(bias, d->d_un.d_ptr); break;
            case DT_JMPREL: jmprel = (const ElfW(Rela) *) abs_addr(bias, d->d_un.d_ptr); break;
            case DT_PLTRELSZ: jmprel_size = d->d_un.d_val; break;
            case DT_RELA: rela = (const ElfW(Rela) *) abs_addr(bias, d->d_un.d_ptr); break;
            case DT_RELASZ: rela_size = d->d_un.d_val; break;
            default: break;
        }
    }
    if (symtab == NULL || strtab == NULL) return 0;
    int patched = 0;
    if (jmprel != NULL) patched += patch_relocations(bias, jmprel, jmprel_size / sizeof(ElfW(Rela)), symtab, strtab, pass->hooks, pass->hook_count);
    if (rela != NULL) patched += patch_relocations(bias, rela, rela_size / sizeof(ElfW(Rela)), symtab, strtab, pass->hooks, pass->hook_count);
    pass->libraries++;
    pass->slots += patched;
    return 0;
}

/* ---- JNI: SandboxFiles ---- */

JNIEXPORT jboolean JNICALL
Java_dev_droidtop_pluginhost_SandboxFiles_nativeRegister(JNIEnv *env, jclass clazz, jstring jPath, jint fd) {
    const char *path = (*env)->GetStringUTFChars(env, jPath, NULL);
    if (path == NULL) return JNI_FALSE;
    jboolean ok = JNI_FALSE;
    pthread_mutex_lock(&g_lock);
    if (is_virtual(path) && g_count < MAX_FILES) {
        int replaced = 0;
        for (int i = 0; i < g_count; i++) {
            if (strcmp(g_files[i].path, path) == 0) {
                close(g_files[i].fd);
                g_files[i].fd = fd;
                replaced = 1;
            }
        }
        if (!replaced) {
            const char *slash = strrchr(path, '/');
            g_files[g_count].path = strdup(path);
            g_files[g_count].base = strdup(slash != NULL ? slash + 1 : path);
            g_files[g_count].fd = fd;
            g_files[g_count].exec_fd = -1;
            g_count++;
        }
        ok = JNI_TRUE;
    }
    pthread_mutex_unlock(&g_lock);
    (*env)->ReleaseStringUTFChars(env, jPath, path);
    if (!ok) close(fd);
    return ok;
}

/* Rewrites the imports of every loaded library whose path ends in one of [suffixes]; returns "<suffix>=<libraries>/<slots>" lines. */
JNIEXPORT jstring JNICALL
Java_dev_droidtop_pluginhost_SandboxFiles_nativeHook(JNIEnv *env, jclass clazz, jobjectArray jSuffixes) {
    char report[2048] = {0};
    size_t used = 0;
    jsize n = (*env)->GetArrayLength(env, jSuffixes);
    for (jsize k = 0; k < n; k++) {
        jstring js = (jstring) (*env)->GetObjectArrayElement(env, jSuffixes, k);
        const char *suffix = (*env)->GetStringUTFChars(env, js, NULL);
        Pass pass = {suffix, 0, 0, HOOKS, sizeof(HOOKS) / sizeof(HOOKS[0])};
        dl_iterate_phdr(patch_library, &pass);
        int w = snprintf(report + used, sizeof report - used, "%s%s=%d/%d", used ? " " : "", suffix, pass.libraries, pass.slots);
        if (w > 0 && (size_t) w < sizeof report - used) used += (size_t) w;
        (*env)->ReleaseStringUTFChars(env, js, suffix);
        (*env)->DeleteLocalRef(env, js);
    }
    return (*env)->NewStringUTF(env, report);
}

/* Hides [jNames] from dlsym calls made by the libraries whose path ends in [jSuffix] (see [g_hidden]); returns
 * "<suffix>=<libraries>/<slots>" like nativeHook. Only the dlsym import is rewritten, and only in those libraries, so
 * no other library's symbol lookup changes. */
JNIEXPORT jstring JNICALL
Java_dev_droidtop_pluginhost_SandboxFiles_nativeHideSymbols(JNIEnv *env, jclass clazz, jstring jSuffix, jobjectArray jNames) {
    jsize n = (*env)->GetArrayLength(env, jNames);
    pthread_mutex_lock(&g_lock);
    for (jsize k = 0; k < n && g_hidden_count < MAX_HIDDEN; k++) {
        jstring js = (jstring) (*env)->GetObjectArrayElement(env, jNames, k);
        const char *name = (*env)->GetStringUTFChars(env, js, NULL);
        int known = 0;
        for (int i = 0; i < g_hidden_count; i++) known |= strcmp(g_hidden[i], name) == 0;
        if (!known) g_hidden[g_hidden_count++] = strdup(name);
        (*env)->ReleaseStringUTFChars(env, js, name);
        (*env)->DeleteLocalRef(env, js);
    }
    pthread_mutex_unlock(&g_lock);
    const char *suffix = (*env)->GetStringUTFChars(env, jSuffix, NULL);
    Pass pass = {suffix, 0, 0, SYMBOL_HOOKS, sizeof(SYMBOL_HOOKS) / sizeof(SYMBOL_HOOKS[0])};
    dl_iterate_phdr(patch_library, &pass);
    char report[256];
    snprintf(report, sizeof report, "%s=%d/%d (dlsym)", suffix, pass.libraries, pass.slots);
    (*env)->ReleaseStringUTFChars(env, jSuffix, suffix);
    return (*env)->NewStringUTF(env, report);
}

/* Gives the libraries whose path ends in [jSuffix] the screen bridge's stand-in window (screen_bridge.c): their own
 * ANativeWindow imports, and dlsym, which then also answers those names. Returns "<suffix>=<libraries>/<slots>". */
JNIEXPORT jstring JNICALL
Java_dev_droidtop_pluginhost_SandboxFiles_nativeBridgeScreen(JNIEnv *env, jclass clazz, jstring jSuffix) {
    static const char *NAMES[] = {
        "ANativeWindow_fromSurface", "ANativeWindow_acquire", "ANativeWindow_release", "ANativeWindow_getWidth",
        "ANativeWindow_getHeight", "ANativeWindow_getFormat", "ANativeWindow_lock", "ANativeWindow_unlockAndPost",
    };
    Hook hooks[sizeof(NAMES) / sizeof(NAMES[0]) + 1];
    size_t count = 0;
    for (size_t i = 0; i < sizeof(NAMES) / sizeof(NAMES[0]); i++) {
        hooks[count].symbol = NAMES[i];
        hooks[count].replacement = screen_bridge_replacement(NAMES[i]);
        count++;
    }
    hooks[count].symbol = "dlsym";
    hooks[count].replacement = (void *) h_dlsym;
    count++;
    g_bridge_on = 1;
    const char *suffix = (*env)->GetStringUTFChars(env, jSuffix, NULL);
    Pass pass = {suffix, 0, 0, hooks, count};
    dl_iterate_phdr(patch_library, &pass);
    char report[256];
    snprintf(report, sizeof report, "%s=%d/%d (window)", suffix, pass.libraries, pass.slots);
    (*env)->ReleaseStringUTFChars(env, jSuffix, suffix);
    return (*env)->NewStringUTF(env, report);
}
