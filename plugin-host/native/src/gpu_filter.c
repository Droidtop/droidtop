/*
 * The system-call filter of a gpu.render plugin's process (docs/plugin-api.md
 * 5.3, "The graphics tier"). That process is not isolated (an isolated process
 * may never open the GPU), so it runs under droidtop's UID, which holds
 * INTERNET. Android lets an app add a seccomp-bpf filter to its own process,
 * as Chrome does for its renderers (Chromium docs/security/android-sandbox.md,
 * "Seccomp-BPF"; sandbox/linux/seccomp-bpf-helpers/seccomp_starter_android.cc):
 * filters stack on the one the zygote gives every app and can only narrow it.
 * This one is installed for every thread (SECCOMP_FILTER_FLAG_TSYNC) before
 * any of the plugin's code is loaded, and nothing can lift it afterwards.
 *
 * What it refuses, by number and integer arguments only (seccomp cannot read
 * a path or a buffer):
 * - socket() for any family but AF_UNIX, and AF_UNIX for anything but
 *   datagrams (liblog's socket to logd): no internet, no DNS (netd's
 *   dnsproxyd is a stream socket), no stream socket to a root daemon such as
 *   Magisk's or to a relay droidtop runs. EACCES, as an isolated process gets.
 * - io_uring, whose operations open files and sockets without passing
 *   through the filter. ENOSYS.
 * - ptrace and process_vm_readv/writev: no reaching into droidtop's other
 *   processes. EPERM.
 * - any other system-call ABI (x86_64's x32 numbers, a foreign architecture).
 *   EPERM.
 * Everything else is allowed: the graphics driver, binder and the guarded
 * hooks' memfd mapping work as before. What it cannot refuse (opening a file
 * by path, binder calls to system services under droidtop's UID) is why the
 * permission's approval line warns the person.
 */
#include <jni.h>
#include <errno.h>
#include <stddef.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/syscall.h>

#if defined(__aarch64__)
#define FILTER_ARCH AUDIT_ARCH_AARCH64
#elif defined(__x86_64__)
#define FILTER_ARCH AUDIT_ARCH_X86_64
#else
#error "droidtop ships arm64-v8a and x86_64 only"
#endif

/* The same numbers on both ABIs; older NDK headers may not name them. */
#ifndef __NR_io_uring_setup
#define __NR_io_uring_setup 425
#endif
#ifndef __NR_io_uring_enter
#define __NR_io_uring_enter 426
#endif
#ifndef __NR_io_uring_register
#define __NR_io_uring_register 427
#endif
#ifndef SECCOMP_FILTER_FLAG_TSYNC
#define SECCOMP_FILTER_FLAG_TSYNC 1
#endif

#define RET(v) BPF_STMT(BPF_RET | BPF_K, (v))
#define ERR(e) (SECCOMP_RET_ERRNO | ((e) & SECCOMP_RET_DATA))
#define LOAD(off) BPF_STMT(BPF_LD | BPF_W | BPF_ABS, (off))
/* Jump to the instruction [t] ahead when the accumulator equals [k], else fall through. */
#define IF_EQ(k, t) BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, (k), (t), 0)

static int installed = 0;
static char report[160];

JNIEXPORT jstring JNICALL
Java_dev_droidtop_pluginhost_GpuSyscallFilter_nativeInstall(JNIEnv *env, jclass clazz) {
    (void) clazz;
    if (installed) return (*env)->NewStringUTF(env, report);

    struct sock_filter code[] = {
        /* 0 */ LOAD(offsetof(struct seccomp_data, arch)),
        /* 1 */ BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, FILTER_ARCH, 1, 0),
        /* 2 */ RET(ERR(EPERM)),
        /* 3 */ LOAD(offsetof(struct seccomp_data, nr)),
#if defined(__x86_64__)
        /* x32 system calls carry bit 30 (__X32_SYSCALL_BIT). */
        BPF_JUMP(BPF_JMP | BPF_JGE | BPF_K, 0x40000000, 0, 1),
        RET(ERR(EPERM)),
#endif
        IF_EQ(__NR_socket, 9),
        IF_EQ(__NR_io_uring_setup, 6),
        IF_EQ(__NR_io_uring_enter, 5),
        IF_EQ(__NR_io_uring_register, 4),
        IF_EQ(__NR_ptrace, 4),
        IF_EQ(__NR_process_vm_readv, 3),
        IF_EQ(__NR_process_vm_writev, 2),
        RET(SECCOMP_RET_ALLOW),
        /* io_uring */ RET(ERR(ENOSYS)),
        /* ptrace, process_vm_* */ RET(ERR(EPERM)),
        /* socket(domain, type, protocol): the low 32 bits of each argument (both ABIs are little-endian). */
        LOAD(offsetof(struct seccomp_data, args[0])),
        IF_EQ(AF_UNIX, 1),
        RET(ERR(EACCES)),
        LOAD(offsetof(struct seccomp_data, args[1])),
        /* SOCK_CLOEXEC and SOCK_NONBLOCK sit above the type's low four bits. */
        BPF_STMT(BPF_ALU | BPF_AND | BPF_K, 0xf),
        IF_EQ(SOCK_DGRAM, 1),
        RET(ERR(EACCES)),
        RET(SECCOMP_RET_ALLOW),
    };
    struct sock_fprog prog = { .len = (unsigned short) (sizeof(code) / sizeof(code[0])), .filter = code };

    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0) {
        snprintf(report, sizeof(report), "error: no_new_privs refused (%s)", strerror(errno));
        return (*env)->NewStringUTF(env, report);
    }
    long rc = syscall(__NR_seccomp, SECCOMP_SET_MODE_FILTER, SECCOMP_FILTER_FLAG_TSYNC, &prog);
    if (rc != 0) {
        if (rc > 0) snprintf(report, sizeof(report), "error: thread %ld could not take the filter", rc);
        else snprintf(report, sizeof(report), "error: seccomp refused (%s)", strerror(errno));
        return (*env)->NewStringUTF(env, report);
    }
    installed = 1;
    snprintf(report, sizeof(report), "on: no sockets but local datagrams, no io_uring, no ptrace (%u rules, every thread)", (unsigned) prog.len);
    return (*env)->NewStringUTF(env, report);
}
