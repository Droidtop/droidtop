/*
 * The file broker of a plugin process, in :app (docs/plugin-api.md 5.3,
 * "Files by path"). Once the process is locked down (plugin_filter.c), the
 * path calls it makes that are the broker's come here, over a socket :app
 * handed it with the plugin's descriptors, and are answered from the rules
 * and aliases [SandboxBroker] gives: droidtop's private folder, by any of
 * its names, is the plugin's own folder; a gpu.render process (droidtop's
 * UID, every path brokered) also gets the system's read-only code and
 * data, droidtop's APK and the device nodes a graphics driver opens later.
 * Nothing of droidtop's own private data is in them. Every refusal is
 * logged under droidtop.sandbox with the plugin's id, so an open the rules
 * missed is named on the rig.
 *
 * The broker is the sandbox library's (vendor/sandbox, Droidtop/tracker#470).
 */
#include <jni.h>
#include <errno.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/socket.h>
#include <android/log.h>

#include "sbx.h"

#define TAG "droidtop.sandbox"

/* /dev/ashmem<boot id> (libcutils names the device after the boot): any name that starts so is the same device. */
static int decide(void *context, const struct sbx_access *access) {
    (void) context;
    if (strncmp(access->resolved, "/dev/ashmem", 11) == 0 && strchr(access->resolved + 11, '/') == NULL) return 0;
    return SBX_KEEP;
}

static void audit(void *context, const struct sbx_access *access, int result) {
    /* Refusals only: an allowed call that fails on its own (ENOENT for a file not made yet) is no refusal. */
    if (result != -EACCES && result != -EPERM) return;
    __android_log_print(ANDROID_LOG_WARN, TAG, "%s: refused %s %s (%s): %s", (const char *) context, access->op,
                        access->asked, access->resolved[0] ? access->resolved : "unresolved", strerror(-result));
}

/* Starts a broker under the given rules on a new socket pair; returns the plugin process's end, or -errno. */
JNIEXPORT jint JNICALL
Java_dev_droidtop_pluginhost_SandboxBroker_nativeServe(JNIEnv *env, jclass clazz, jstring plugin, jobjectArray paths,
                                                      jintArray modes, jobjectArray alias_from, jobjectArray alias_to) {
    (void) clazz;
    jsize count = (*env)->GetArrayLength(env, paths);
    if (count != (*env)->GetArrayLength(env, modes)) return -EINVAL;
    jsize aliases = (*env)->GetArrayLength(env, alias_from);
    if (aliases != (*env)->GetArrayLength(env, alias_to)) return -EINVAL;
    struct sbx_policy *policy = sbx_policy_new();
    if (!policy) return -ENOMEM;
    jint *mode = (*env)->GetIntArrayElements(env, modes, NULL);
    for (jsize i = 0; i < count; i++) {
        jstring path = (jstring) (*env)->GetObjectArrayElement(env, paths, i);
        const char *chars = (*env)->GetStringUTFChars(env, path, NULL);
        int added = sbx_policy_add(policy, chars, (unsigned) mode[i]);
        if (added != 0) __android_log_print(ANDROID_LOG_WARN, TAG, "rule %s not added: %s", chars, strerror(-added));
        (*env)->ReleaseStringUTFChars(env, path, chars);
        (*env)->DeleteLocalRef(env, path);
    }
    (*env)->ReleaseIntArrayElements(env, modes, mode, JNI_ABORT);
    for (jsize i = 0; i < aliases; i++) {
        jstring from = (jstring) (*env)->GetObjectArrayElement(env, alias_from, i);
        jstring to = (jstring) (*env)->GetObjectArrayElement(env, alias_to, i);
        const char *from_chars = (*env)->GetStringUTFChars(env, from, NULL);
        const char *to_chars = (*env)->GetStringUTFChars(env, to, NULL);
        int added = sbx_policy_alias(policy, from_chars, to_chars);
        if (added != 0) __android_log_print(ANDROID_LOG_WARN, TAG, "alias %s not added: %s", from_chars, strerror(-added));
        (*env)->ReleaseStringUTFChars(env, from, from_chars);
        (*env)->ReleaseStringUTFChars(env, to, to_chars);
        (*env)->DeleteLocalRef(env, from);
        (*env)->DeleteLocalRef(env, to);
    }
    const char *id = (*env)->GetStringUTFChars(env, plugin, NULL);
    char *tag = strdup(id ? id : "plugin");  /* lives as long as the process: one per plugin start */
    (*env)->ReleaseStringUTFChars(env, plugin, id);
    sbx_policy_set_decider(policy, decide, NULL);
    sbx_policy_set_audit(policy, audit, tag);

    int pair[2];
    if (socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC, 0, pair) != 0) {
        int error = errno;
        sbx_policy_free(policy);
        return -error;
    }
    int started = sbx_broker_start(policy, pair[0]);
    if (started != 0) {
        sbx_policy_free(policy);
        close(pair[0]);
        close(pair[1]);
        return started;
    }
    return pair[1];
}
