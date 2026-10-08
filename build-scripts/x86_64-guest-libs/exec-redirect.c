/*
 * exec-redirect: lets an x86_64 Wine guest start its own children on Android
 * versions that refuse exec() of app-private files.
 *
 * Android 10+ forbids an app targeting SDK 29 or later from exec()ing a file
 * in its own data directory (SELinux: no execute_no_trans on app_data_file).
 * The first guest process is started as `/system/bin/linker64 <wine> ...`
 * (ProcessHelper does that), which the policy allows, but Wine then execs
 * wineserver and further wine processes itself, and those calls fail with
 * EACCES. On arm64 the closed-source libredirect-bionic-wx.so handles this
 * inside box64's process; it has no x86_64 build and no source, so this is
 * the x86_64 counterpart, written for exactly what Wine calls.
 *
 * Three jobs, all narrow:
 *
 *  1. execve/execv/execvp/execvpe/posix_spawn/posix_spawnp: the real call is
 *     made first. Only when it fails with EACCES on an ELF file outside
 *     /system is it retried as `/system/bin/linker64 <path> <args...>`, so on
 *     a device that allows the exec (BlueStacks runs with SELinux off) this
 *     changes nothing.
 *  2. readlink/readlinkat/realpath of /proc/self/exe: a program started through
 *     the linker sees the linker as its own executable, and Wine derives its
 *     install directory from that: the loader's load_ntdll() takes
 *     realpath("/proc/self/exe") and opens ../lib/wine/x86_64-unix/ntdll.so
 *     beside it, and ntdll's init_paths() takes the same realpath as bin_dir,
 *     where it finds wineserver and the loader it starts again (Wine 9.0
 *     loader/main.c, dlls/ntdll/unix/loader.c). When the real answer is the
 *     linker, under either of its names (is_linker), and
 *     REDIRECT_EXEC__PROC_SELF_EXE is set, that value is returned instead. The
 *     launcher sets it for the first process (BionicProgramLauncherComponent);
 *     this file sets it for every process it redirects.
 *  3. fdsan: bionic aborts a process that closes a file descriptor some other
 *     owner still holds (a DIR*, a FILE*), and since Android 11 that is the
 *     default for every process that is not an app (bionic
 *     __libc_init_fdsan, ANDROID_FDSAN_ERROR_LEVEL_FATAL). Wine and the
 *     libraries it loads were written for glibc, where such a close is a
 *     harmless bug, and one of them killed a game on the BlueStacks rig the
 *     moment DXVK made its swapchain ("fdsan: attempted to close file
 *     descriptor 409, expected to be unowned, actually owned by DIR*",
 *     Droidtop/tracker#242). Every process this preload is loaded into
 *     reports the first such close in logcat and carries on.
 *
 * No allocation on the exec paths: they can run in a forked child.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <spawn.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define LINKER "/system/bin/linker64"
#define SELF_EXE_VAR "REDIRECT_EXEC__PROC_SELF_EXE"
#define SELF_EXE_PREFIX SELF_EXE_VAR "="
#define PROC_SELF_EXE "/proc/self/exe"

extern char **environ;

typedef int (*execve_fn)(const char *, char *const[], char *const[]);
typedef int (*spawn_fn)(pid_t *, const char *, const posix_spawn_file_actions_t *,
                        const posix_spawnattr_t *, char *const[], char *const[]);
typedef ssize_t (*readlink_fn)(const char *, char *, size_t);
typedef ssize_t (*readlinkat_fn)(int, const char *, char *, size_t);
typedef char *(*realpath_fn)(const char *, char *);

static void *next(const char *name) { return dlsym(RTLD_NEXT, name); }

static int is_elf(const char *path) {
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return 0;
    unsigned char magic[4];
    ssize_t n = read(fd, magic, sizeof magic);
    close(fd);
    return n == 4 && magic[0] == 0x7f && magic[1] == 'E' && magic[2] == 'L' && magic[3] == 'F';
}

/* Only app-private ELF files are refused; anything under /system is not ours to redirect. */
static int should_redirect(const char *path) {
    return path && strncmp(path, "/system/", 8) != 0 && is_elf(path);
}

static size_t count(char *const v[]) {
    size_t n = 0;
    if (v) while (v[n]) n++;
    return n;
}

/*
 * Fills nargv/nenvp (caller-provided, sized argc + 3 and envc + 2) for
 * `linker64 <abs> argv[1..]` with SELF_EXE_VAR=<abs> replacing any old value.
 */
static void build(const char *abs, char *const argv[], char *const envp[],
                  char **nargv, char **nenvp, char *selfvar, size_t selfvar_size) {
    size_t argc = count(argv), envc = count(envp), j = 0;
    nargv[0] = (char *)LINKER;
    nargv[1] = (char *)abs;
    for (size_t i = 1; i < argc; i++) nargv[i + 1] = argv[i];
    nargv[argc < 1 ? 2 : argc + 1] = NULL;

    size_t plen = strlen(SELF_EXE_PREFIX);
    strncpy(selfvar, SELF_EXE_PREFIX, selfvar_size - 1);
    strncpy(selfvar + plen, abs, selfvar_size - plen - 1);
    selfvar[selfvar_size - 1] = '\0';
    for (size_t i = 0; i < envc; i++) {
        if (strncmp(envp[i], SELF_EXE_PREFIX, plen) != 0) nenvp[j++] = envp[i];
    }
    nenvp[j++] = selfvar;
    nenvp[j] = NULL;
}

/* An absolute path for the self-exe variable; falls back to the path as given. */
static const char *absolute(const char *path, char *buf) {
    static realpath_fn real_realpath;
    if (!real_realpath) real_realpath = (realpath_fn)next("realpath");
    if (real_realpath && real_realpath(path, buf)) return buf;
    return path;
}

int execve(const char *path, char *const argv[], char *const envp[]) {
    static execve_fn real;
    if (!real) real = (execve_fn)next("execve");
    real(path, argv, envp);
    int err = errno;
    if (err != EACCES || !should_redirect(path)) { errno = err; return -1; }

    char absbuf[PATH_MAX];
    const char *abs = absolute(path, absbuf);
    char *nargv[count(argv) + 3];
    char *nenvp[count(envp) + 2];
    char selfvar[PATH_MAX + sizeof(SELF_EXE_PREFIX)];
    build(abs, argv, envp, nargv, nenvp, selfvar, sizeof selfvar);
    real(LINKER, nargv, nenvp);
    errno = err;
    return -1;
}

int execv(const char *path, char *const argv[]) {
    return execve(path, argv, environ);
}

/* PATH search the way execvp does it, but every candidate goes through execve above. */
int execvpe(const char *file, char *const argv[], char *const envp[]) {
    if (!file || !*file) { errno = ENOENT; return -1; }
    if (strchr(file, '/')) return execve(file, argv, envp);
    const char *path = getenv("PATH");
    if (!path) path = "/system/bin";
    int seen_eacces = 0;
    char candidate[PATH_MAX];
    for (const char *p = path;; ) {
        const char *end = strchr(p, ':');
        size_t len = end ? (size_t)(end - p) : strlen(p);
        if (len == 0) { candidate[0] = '.'; len = 1; } else if (len < sizeof candidate) memcpy(candidate, p, len);
        if (len + 1 + strlen(file) < sizeof candidate) {
            candidate[len] = '/';
            strcpy(candidate + len + 1, file);
            execve(candidate, argv, envp);
            if (errno == EACCES) seen_eacces = 1;
            else if (errno != ENOENT && errno != ENOTDIR) return -1;
        }
        if (!end) break;
        p = end + 1;
    }
    errno = seen_eacces ? EACCES : ENOENT;
    return -1;
}

int execvp(const char *file, char *const argv[]) {
    return execvpe(file, argv, environ);
}

static int spawn_redirect(spawn_fn real, pid_t *pid, const char *path,
                          const posix_spawn_file_actions_t *actions, const posix_spawnattr_t *attr,
                          char *const argv[], char *const envp[]) {
    char absbuf[PATH_MAX];
    const char *abs = absolute(path, absbuf);
    char *const *env = envp ? envp : environ;
    char *nargv[count(argv) + 3];
    char *nenvp[count(env) + 2];
    char selfvar[PATH_MAX + sizeof(SELF_EXE_PREFIX)];
    build(abs, argv, env, nargv, nenvp, selfvar, sizeof selfvar);
    return real(pid, LINKER, actions, attr, nargv, nenvp);
}

int posix_spawn(pid_t *pid, const char *path, const posix_spawn_file_actions_t *actions,
                const posix_spawnattr_t *attr, char *const argv[], char *const envp[]) {
    static spawn_fn real;
    if (!real) real = (spawn_fn)next("posix_spawn");
    int rc = real(pid, path, actions, attr, argv, envp);
    if (rc != EACCES || !should_redirect(path)) return rc;
    return spawn_redirect(real, pid, path, actions, attr, argv, envp);
}

int posix_spawnp(pid_t *pid, const char *file, const posix_spawn_file_actions_t *actions,
                 const posix_spawnattr_t *attr, char *const argv[], char *const envp[]) {
    static spawn_fn real, real_abs;
    if (!real) real = (spawn_fn)next("posix_spawnp");
    if (!real_abs) real_abs = (spawn_fn)next("posix_spawn");
    int rc = real(pid, file, actions, attr, argv, envp);
    if (rc != EACCES || !file) return rc;
    if (strchr(file, '/')) {
        return should_redirect(file) ? spawn_redirect(real_abs, pid, file, actions, attr, argv, envp) : rc;
    }
    const char *path = getenv("PATH");
    if (!path) return rc;
    char candidate[PATH_MAX];
    for (const char *p = path;; ) {
        const char *end = strchr(p, ':');
        size_t len = end ? (size_t)(end - p) : strlen(p);
        if (len > 0 && len + 1 + strlen(file) < sizeof candidate) {
            memcpy(candidate, p, len);
            candidate[len] = '/';
            strcpy(candidate + len + 1, file);
            if (should_redirect(candidate)) {
                return spawn_redirect(real_abs, pid, candidate, actions, attr, argv, envp);
            }
        }
        if (!end) break;
        p = end + 1;
    }
    return rc;
}

/*
 * Whether a /proc/self/exe answer is the linker. The kernel reports the
 * resolved file, and from Android 10 on /system/bin/linker64 is a symlink to
 * the runtime APEX's copy (/apex/com.android.runtime/bin/linker64), so the
 * answer is that path, not LINKER. Comparing with LINKER alone never matched
 * there, and Wine looked for ntdll.so under /apex/com.android.runtime/lib
 * (Droidtop/tracker#242, BlueStacks Android 13).
 */
static int is_linker(const char *path) {
    static char canonical[PATH_MAX];
    static int resolved;
    if (strcmp(path, LINKER) == 0) return 1;
    if (!resolved) {
        realpath_fn real_realpath = (realpath_fn)next("realpath");
        if (!real_realpath || !real_realpath(LINKER, canonical)) canonical[0] = '\0';
        resolved = 1;
    }
    return canonical[0] && strcmp(path, canonical) == 0;
}

/* The executable a redirected process should see, or NULL to keep the real answer. */
static const char *self_exe_override(const char *real_answer) {
    if (!real_answer || !is_linker(real_answer)) return NULL;
    const char *v = getenv(SELF_EXE_VAR);
    return v && *v ? v : NULL;
}

static ssize_t fill_link(const char *value, char *buf, size_t size) {
    size_t len = strlen(value);
    if (len > size) len = size;
    memcpy(buf, value, len); /* readlink does not NUL-terminate */
    return (ssize_t)len;
}

ssize_t readlink(const char *path, char *buf, size_t size) {
    static readlink_fn real;
    if (!real) real = (readlink_fn)next("readlink");
    ssize_t n = real(path, buf, size);
    if (n < 0 || !path || strcmp(path, PROC_SELF_EXE) != 0) return n;
    char got[PATH_MAX];
    size_t m = (size_t)n < sizeof got - 1 ? (size_t)n : sizeof got - 1;
    memcpy(got, buf, m);
    got[m] = '\0';
    const char *v = self_exe_override(got);
    return v ? fill_link(v, buf, size) : n;
}

ssize_t readlinkat(int dirfd, const char *path, char *buf, size_t size) {
    static readlinkat_fn real;
    if (!real) real = (readlinkat_fn)next("readlinkat");
    ssize_t n = real(dirfd, path, buf, size);
    if (n < 0 || !path || strcmp(path, PROC_SELF_EXE) != 0) return n;
    char got[PATH_MAX];
    size_t m = (size_t)n < sizeof got - 1 ? (size_t)n : sizeof got - 1;
    memcpy(got, buf, m);
    got[m] = '\0';
    const char *v = self_exe_override(got);
    return v ? fill_link(v, buf, size) : n;
}

char *realpath(const char *path, char *resolved) {
    static realpath_fn real;
    if (!real) real = (realpath_fn)next("realpath");
    char *r = real(path, resolved);
    if (!r || !path || strcmp(path, PROC_SELF_EXE) != 0) return r;
    const char *v = self_exe_override(r);
    if (!v) return r;
    if (resolved) {
        strncpy(resolved, v, PATH_MAX - 1);
        resolved[PATH_MAX - 1] = '\0';
        return resolved;
    }
    free(r);
    return strdup(v);
}

/*
 * Job 3 above. android_fdsan_set_error_level is libc's from API 29; this file
 * is built for API 26, so it is looked up, and an older device (where fdsan
 * does not exist) simply has nothing to change.
 */
__attribute__((constructor)) static void report_fdsan_once(void) {
    typedef int (*set_level_fn)(int);
    set_level_fn set_level = (set_level_fn)dlsym(RTLD_DEFAULT, "android_fdsan_set_error_level");
    if (set_level) set_level(1 /* ANDROID_FDSAN_ERROR_LEVEL_WARN_ONCE */);
}
