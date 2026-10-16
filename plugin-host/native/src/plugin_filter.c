/*
 * The system-call filter of every plugin process droidtop loads from
 * descriptors (docs/plugin-api.md 5.3). It is the sandbox library's
 * lockdown (vendor/sandbox, bi0shacker001/sandbox, Droidtop/tracker#470),
 * shared with Enginehost, installed for every thread
 * (SECCOMP_FILTER_FLAG_TSYNC) before any of the plugin's code is loaded;
 * nothing can lift it afterwards.
 *
 * Every plugin process refuses (an open process's network hole aside):
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
 * Every call that names a path traps to the library's handler, which
 * answers the plugin's own files (the descriptors :app handed over, at
 * their virtual paths: local files, sandbox_hooks.c registers them) and
 * sends the rest to the process's broker in :app (plugin_broker.c) over
 * broker_fd, or to the kernel:
 *
 * - The isolated sandbox locks down in passthrough: only droidtop's
 *   private folder, by its names (claims), goes to the broker, which maps
 *   it to the plugin's own folder; every other path is the kernel's, where
 *   the isolated UID and SELinux keep droidtop's files out as before.
 * - An open process (docs/plugin-api.md 5.3, "Open processes: one hole per
 *   permission") runs under droidtop's UID, so every path goes to the
 *   broker, whose rules hold none of droidtop's private data. With the
 *   gpu.render hole the GPU and hwbinder device nodes, which the kernel ties
 *   to the process that opens them, are opened here first (own_opens) and
 *   duplicated later. With the net.sockets hole the lockdown takes
 *   SBX_LOCK_INET: inet sockets, bind and connect, and no other socket; the
 *   library refuses that flag with the log's Unix socket (a Unix socket
 *   could bind a name), so a networked process has no logd socket. The process
 *   is made dumpable first so the broker, under the same UID, can answer
 *   for its own /proc entries; ptrace stays refused to every plugin
 *   process. What it still cannot refuse is a binder call to a system
 *   service under droidtop's UID, which is why that permission's approval
 *   line warns the person.
 */
#include <jni.h>
#include <errno.h>
#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <unistd.h>

#include "sbx.h"

static int installed = 0;
static char report[240];

/* Up to max strings of array as C strings in out (released by release_strings); returns how many. */
static unsigned take_strings(JNIEnv *env, jobjectArray array, const char **out, jstring *refs, unsigned max) {
    unsigned count = 0;
    jsize n = array ? (*env)->GetArrayLength(env, array) : 0;
    for (jsize i = 0; i < n && count < max; i++) {
        refs[count] = (jstring) (*env)->GetObjectArrayElement(env, array, i);
        out[count] = (*env)->GetStringUTFChars(env, refs[count], NULL);
        count++;
    }
    return count;
}

static void release_strings(JNIEnv *env, const char **strings, jstring *refs, unsigned count) {
    for (unsigned i = 0; i < count; i++) {
        (*env)->ReleaseStringUTFChars(env, refs[i], strings[i]);
        (*env)->DeleteLocalRef(env, refs[i]);
    }
}

JNIEXPORT jstring JNICALL
Java_dev_droidtop_pluginhost_PluginSyscallFilter_nativeInstall(JNIEnv *env, jclass clazz, jint broker_fd,
                                                               jobjectArray own_opens, jobjectArray claims,
                                                               jboolean network, jlong file_limit) {
    (void) clazz;
    if (installed) {
        /* A second plugin in a process already locked down: the first one's broker serves it. */
        if (broker_fd >= 0) close(broker_fd);
        return (*env)->NewStringUTF(env, report);
    }
    if (broker_fd < 0) {
        snprintf(report, sizeof(report), "error: droidtop did not hand over its file broker");
        return (*env)->NewStringUTF(env, report);
    }
    const char *opens[SBX_OWN_OPENS_MAX];
    jstring open_refs[SBX_OWN_OPENS_MAX];
    const char *claimed[SBX_CLAIMS_MAX];
    jstring claim_refs[SBX_CLAIMS_MAX];
    unsigned open_count = take_strings(env, own_opens, opens, open_refs, SBX_OWN_OPENS_MAX);
    unsigned claim_count = take_strings(env, claims, claimed, claim_refs, SBX_CLAIMS_MAX);
    int contained = claims != NULL;
    /* The network hole only in an open process; the isolated sandbox is outside the inet group anyway. */
    int inet = network && !contained;
    struct sbx_lockdown_options options = {
        .flags = (inet ? SBX_LOCK_INET : SBX_LOCK_LOG_SOCKET) | (contained ? SBX_LOCK_PASSTHROUGH : 0),
        .own_opens = opens,
        .own_open_count = open_count,
        .claims = claimed,
        .claim_count = claim_count,
    };
    if (!contained) prctl(PR_SET_DUMPABLE, 1, 0, 0, 0);
    if (contained && file_limit > 0) {
        /*
         * A contained plugin's writes on a file it holds never reach the broker, so no one file may pass its whole
         * quota (docs/plugin-api.md 5.3): such a write gets EFBIG, with SIGXFSZ ignored rather than killing the process.
         * An open process keeps no limit: it may write shared storage it was granted (a sync engine's large files).
         */
        signal(SIGXFSZ, SIG_IGN);
        struct rlimit limit = {(rlim_t) file_limit, (rlim_t) file_limit};
        setrlimit(RLIMIT_FSIZE, &limit);
    }
    int result = sbx_lockdown(broker_fd, &options);
    release_strings(env, opens, open_refs, open_count);
    release_strings(env, claimed, claim_refs, claim_count);
    if (result != 0) {
        close(broker_fd);
        if (result == -EBUSY) snprintf(report, sizeof(report), "error: a thread could not take the filter");
        else snprintf(report, sizeof(report), "error: the sandbox lockdown was refused (%s)", strerror(-result));
        return (*env)->NewStringUTF(env, report);
    }
    installed = 1;
    snprintf(report, sizeof(report), "on: %s, %s, no programs, no io_uring, no ptrace, "
             "no namespaces, no signals to other processes (every thread)",
             contained ? "its own folder through droidtop's broker" : "files only through droidtop's broker",
             inet ? "internet sockets (net.sockets) and no other socket" : "no sockets but local datagrams");
    return (*env)->NewStringUTF(env, report);
}
