/*
 * The system-call filter of every plugin process droidtop loads from
 * descriptors (docs/plugin-api.md 5.3). It is the sandbox library's
 * lockdown (vendor/sandbox, bi0shacker001/sandbox, Droidtop/tracker#470),
 * shared with Enginehost, installed for every thread
 * (SECCOMP_FILTER_FLAG_TSYNC) before any of the plugin's code is loaded;
 * nothing can lift it afterwards.
 *
 * Both kinds of plugin process refuse:
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
 *
 * The isolated sandbox takes it unbrokered: path calls stay the kernel's,
 * where its own UID and SELinux keep droidtop's files out of reach and the
 * guarded hooks map the plugin's code in.
 *
 * A gpu.render process runs under droidtop's UID, so it takes it with a
 * broker: every call that names a path goes over broker_fd to :app
 * (plugin_broker.c), which answers from rules that hold none of droidtop's
 * private data. The GPU and hwbinder device nodes, which the kernel ties to
 * the process that opens them, are opened here first and duplicated later.
 * The process is made dumpable first so the broker, under the same UID, can
 * answer for its own /proc entries; ptrace stays refused to every plugin
 * process. What it still cannot refuse is a binder call to a system service
 * under droidtop's UID, which is why that permission's approval line warns
 * the person.
 */
#include <jni.h>
#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <sys/prctl.h>
#include <unistd.h>

#include "sbx.h"

static int installed = 0;
static char report[220];

JNIEXPORT jstring JNICALL
Java_dev_droidtop_pluginhost_PluginSyscallFilter_nativeInstall(JNIEnv *env, jclass clazz, jint broker_fd,
                                                               jobjectArray own_opens) {
    (void) clazz;
    if (installed) {
        /* A second plugin in a process already locked down: the first one's broker serves it. */
        if (broker_fd >= 0) close(broker_fd);
        return (*env)->NewStringUTF(env, report);
    }
    struct sbx_lockdown_options options = {.flags = SBX_LOCK_LOG_SOCKET};
    const char *paths[SBX_OWN_OPENS_MAX];
    jstring strings[SBX_OWN_OPENS_MAX];
    unsigned count = 0;
    if (broker_fd < 0) {
        options.flags |= SBX_LOCK_UNBROKERED;
    } else {
        jsize n = own_opens ? (*env)->GetArrayLength(env, own_opens) : 0;
        for (jsize i = 0; i < n && count < SBX_OWN_OPENS_MAX; i++) {
            strings[count] = (jstring) (*env)->GetObjectArrayElement(env, own_opens, i);
            paths[count] = (*env)->GetStringUTFChars(env, strings[count], NULL);
            count++;
        }
        options.own_opens = paths;
        options.own_open_count = count;
        prctl(PR_SET_DUMPABLE, 1, 0, 0, 0);
    }
    int result = sbx_lockdown(broker_fd, &options);
    for (unsigned i = 0; i < count; i++) {
        (*env)->ReleaseStringUTFChars(env, strings[i], paths[i]);
        (*env)->DeleteLocalRef(env, strings[i]);
    }
    if (result != 0) {
        if (result == -EBUSY) snprintf(report, sizeof(report), "error: a thread could not take the filter");
        else snprintf(report, sizeof(report), "error: the sandbox lockdown was refused (%s)", strerror(-result));
        return (*env)->NewStringUTF(env, report);
    }
    installed = 1;
    snprintf(report, sizeof(report), "on: %sno sockets but local datagrams, no programs, no io_uring, no ptrace, "
             "no namespaces, no signals to other processes (every thread)",
             broker_fd < 0 ? "" : "files only through droidtop's broker, ");
    return (*env)->NewStringUTF(env, report);
}
