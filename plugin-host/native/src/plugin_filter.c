/*
 * The system-call filter of every plugin process droidtop loads from
 * descriptors, the isolated sandbox and a gpu.render process alike
 * (docs/plugin-api.md 5.3). It is the sandbox library's lockdown
 * (vendor/sandbox, bi0shacker001/sandbox), shared with Enginehost, in its
 * unbrokered form: path calls stay the kernel's (an isolated process's UID
 * and SELinux guard its files, and the guarded hooks map the plugin's own
 * code in), and everything that reaches past the process is refused.
 *
 * Installed for every thread (SECCOMP_FILTER_FLAG_TSYNC) before any of the
 * plugin's code is loaded; nothing can lift it afterwards. It refuses:
 * - socket() for any family but AF_UNIX, and AF_UNIX for anything but
 *   datagrams (liblog's socket to logd, which connect may still reach):
 *   no internet, no DNS (netd's dnsproxyd is a stream socket), no stream
 *   socket to a root daemon or to a relay droidtop runs. EACCES, as an
 *   isolated process gets. bind is refused too.
 * - execve, ptrace, process_vm_readv/writev, io_uring, bpf,
 *   perf_event_open: EACCES.
 * - new namespaces and mounts, the kernel keyring, pidfds, and a signal to
 *   any process but its own: EPERM.
 * - any other system-call ABI (x86_64's x32 numbers, a foreign
 *   architecture).
 * What it cannot refuse (opening a file by path in a gpu.render process,
 * binder calls to system services under droidtop's UID) is why that
 * permission's approval line warns the person.
 */
#include <jni.h>
#include <errno.h>
#include <stdio.h>
#include <string.h>

#include "sbx.h"

static int installed = 0;
static char report[200];

JNIEXPORT jstring JNICALL
Java_dev_droidtop_pluginhost_PluginSyscallFilter_nativeInstall(JNIEnv *env, jclass clazz) {
    (void) clazz;
    if (installed) return (*env)->NewStringUTF(env, report);
    struct sbx_lockdown_options options = {.flags = SBX_LOCK_UNBROKERED | SBX_LOCK_LOG_SOCKET};
    int result = sbx_lockdown(-1, &options);
    if (result != 0) {
        if (result == -EBUSY) snprintf(report, sizeof(report), "error: a thread could not take the filter");
        else snprintf(report, sizeof(report), "error: the sandbox lockdown was refused (%s)", strerror(-result));
        return (*env)->NewStringUTF(env, report);
    }
    installed = 1;
    snprintf(report, sizeof(report),
             "on: no sockets but local datagrams, no programs, no io_uring, no ptrace, no namespaces, "
             "no signals to other processes (every thread)");
    return (*env)->NewStringUTF(env, report);
}
