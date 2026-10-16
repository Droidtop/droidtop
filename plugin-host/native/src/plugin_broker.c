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
#include <fcntl.h>
#include <ftw.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <android/log.h>

#include "sbx.h"

#define TAG "droidtop.sandbox"

/*
 * One plugin's broker: its tag for the log, and its data folder's quota (docs/plugin-api.md 5.3, "What a contained
 * plugin's own folder holds"). The folder is the data store's, the app-data stand-in for droidtop's private folder
 * inside it, so one limit covers both.
 */
struct plugin {
    char *tag;
    char *root;        /* the plugin's data folder, real path; NULL for no quota */
    size_t root_len;
    long long limit;   /* bytes */
    pthread_mutex_t lock;
    long long used;    /* bytes, as last measured */
    long long measured_ms;
};

static long long now_ms(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (long long) t.tv_sec * 1000 + t.tv_nsec / 1000000;
}

static __thread long long walked;

static int add_size(const char *path, const struct stat *st, int type, struct FTW *ftw) {
    (void) path;
    (void) ftw;
    if (type == FTW_F) walked += (long long) st->st_size;
    return 0;
}

/* The folder's size, measured at most once a second (a create is common; a walk of the folder is not cheap). */
static long long usage(struct plugin *p) {
    pthread_mutex_lock(&p->lock);
    long long now = now_ms();
    if (p->measured_ms == 0 || now - p->measured_ms >= 1000) {
        walked = 0;
        nftw(p->root, add_size, 16, FTW_PHYS);
        p->used = walked;
        p->measured_ms = now;
    }
    long long used = p->used;
    pthread_mutex_unlock(&p->lock);
    return used;
}

/* A new file or folder in the plugin's data folder (an open that creates a name not there yet, or a mkdir). */
static int creates_there(const struct plugin *p, const struct sbx_access *access) {
    if (p->root == NULL || access->resolved[0] == '\0') return 0;
    if (strncmp(access->resolved, p->root, p->root_len) != 0) return 0;
    char next = access->resolved[p->root_len];
    if (next != '/' && next != '\0') return 0;
    if (strcmp(access->op, "mkdir") == 0) return 1;
    if (strcmp(access->op, "open") != 0 || !(access->flags & O_CREAT)) return 0;
    struct stat st;
    return lstat(access->resolved, &st) != 0 && errno == ENOENT;
}

static int decide(void *context, const struct sbx_access *access) {
    struct plugin *p = context;
    /* /dev/ashmem<boot id> (libcutils names the device after the boot): any name that starts so is the same device. */
    if (strncmp(access->resolved, "/dev/ashmem", 11) == 0 && strchr(access->resolved + 11, '/') == NULL) return 0;
    /* At or past the quota a new file or folder is "No space left on device", which every caller handles, never a crash. */
    if (access->rule_verdict == 0 && creates_there(p, access) && usage(p) >= p->limit) return -ENOSPC;
    return SBX_KEEP;
}

static void audit(void *context, const struct sbx_access *access, int result) {
    const struct plugin *p = context;
    /* Refusals only: an allowed call that fails on its own (ENOENT for a file not made yet) is no refusal. */
    if (result != -EACCES && result != -EPERM && result != -ENOSPC) return;
    __android_log_print(ANDROID_LOG_WARN, TAG, "%s: refused %s %s (%s): %s", p->tag, access->op,
                        access->asked, access->resolved[0] ? access->resolved : "unresolved", strerror(-result));
}

/* Starts a broker under the given rules on a new socket pair; returns the plugin process's end, or -errno. */
JNIEXPORT jint JNICALL
Java_dev_droidtop_pluginhost_SandboxBroker_nativeServe(JNIEnv *env, jclass clazz, jstring plugin, jobjectArray paths,
                                                      jintArray modes, jobjectArray alias_from, jobjectArray alias_to,
                                                      jstring quota_root, jlong quota_bytes) {
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
    /* Lives as long as the process: one per plugin start, and the broker thread may outlive any caller. */
    struct plugin *context = calloc(1, sizeof(*context));
    if (!context) {
        sbx_policy_free(policy);
        return -ENOMEM;
    }
    const char *id = (*env)->GetStringUTFChars(env, plugin, NULL);
    context->tag = strdup(id ? id : "plugin");
    (*env)->ReleaseStringUTFChars(env, plugin, id);
    if (quota_root != NULL && quota_bytes > 0) {
        const char *root = (*env)->GetStringUTFChars(env, quota_root, NULL);
        context->root = realpath(root, NULL);
        (*env)->ReleaseStringUTFChars(env, quota_root, root);
        context->root_len = context->root ? strlen(context->root) : 0;
        context->limit = quota_bytes;
    }
    pthread_mutex_init(&context->lock, NULL);
    sbx_policy_set_decider(policy, decide, context);
    sbx_policy_set_audit(policy, audit, context);

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
