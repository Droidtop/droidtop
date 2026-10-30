/*
 * The program-tree binding of the sandbox library (docs/SPEC.md 5b "Containment and copy-on-write layers for Wine",
 * piece 0; Droidtop/tracker#584): one broker listener that a whole tree of processes (Wine, its wineserver, the
 * translators, the game) attaches to through libsbx_child.so, served on the broker's own threads.
 *
 * Piece 0 is learning only: the decider allows every request, and the audit writes each distinct one, once, to the
 * learn log the host named. It gives the processes NO protection; the host says so wherever it is switched on.
 *
 * A listener is handed to Kotlin as a slot number, never as a pointer: Android tags heap pointers in the top byte, so a
 * pointer in a jlong can read as negative, which is how this file reports an error.
 */
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include <android/log.h>

#include "sbx.h"

#define TAG "droidtop.sandbox"
#define SLOTS 4
/* Distinct records remembered per listener; past this the log stops growing and says so once. */
#define SEEN_MAX 16384

struct learn {
    pthread_mutex_t lock;
    int fd;
    size_t seen_count;
    int full_noted;
    uint64_t seen[SEEN_MAX * 2]; /* open addressing, 0 is empty */
};

struct slot {
    struct sbx_listener *listener;
    struct learn *learn;
};

static pthread_mutex_t slots_lock = PTHREAD_MUTEX_INITIALIZER;
static struct slot slots[SLOTS];

static uint64_t fnv(uint64_t h, const char *s) {
    if (!s) s = "";
    for (; *s; s++) {
        h ^= (unsigned char)*s;
        h *= 1099511628211ull;
    }
    h ^= 0xff; /* a separator, so "ab"+"c" and "a"+"bc" differ */
    h *= 1099511628211ull;
    return h;
}

/* True when key was not seen before (and is now). Called under learn->lock. */
static int first_time(struct learn *learn, uint64_t key) {
    if (key == 0) key = 1;
    size_t cap = SEEN_MAX * 2;
    size_t i = (size_t)(key % cap);
    for (size_t n = 0; n < cap; n++, i = (i + 1) % cap) {
        if (learn->seen[i] == key) return 0;
        if (learn->seen[i] == 0) {
            if (learn->seen_count >= SEEN_MAX) return -1;
            learn->seen[i] = key;
            learn->seen_count++;
            return 1;
        }
    }
    return -1;
}

static int decide_allow(void *context, const struct sbx_access *access) {
    (void)context;
    (void)access;
    return 0;
}

static void audit_learn(void *context, const struct sbx_access *access, int result) {
    struct learn *learn = context;
    /* A "syscall" record names the call in asked; a path request is told apart by what it resolved to. */
    const char *what = strcmp(access->op, "syscall") == 0 || !access->resolved || !*access->resolved
                           ? access->asked
                           : access->resolved;
    char need[16];
    snprintf(need, sizeof need, "%u", access->need);
    uint64_t key = fnv(fnv(fnv(fnv(1469598103934665603ull, access->op), what), need),
                       access->allowed_by ? access->allowed_by : "");
    pthread_mutex_lock(&learn->lock);
    int fresh = first_time(learn, key);
    if (fresh == 1 && learn->fd >= 0) {
        char line[1024];
        int n = snprintf(line, sizeof line, "%s\t%s\tneed=%u\trule=%d\tresult=%d\t%s\n", access->op,
                         what ? what : "", access->need,
                         access->rule_verdict, result, access->allowed_by ? access->allowed_by : "-");
        if (n > 0) {
            if ((size_t)n >= sizeof line) n = (int)sizeof line - 1;
            ssize_t ignored = write(learn->fd, line, (size_t)n);
            (void)ignored;
        }
    } else if (fresh < 0 && !learn->full_noted && learn->fd >= 0) {
        learn->full_noted = 1;
        static const char full[] = "# the learn log holds its most distinct records; later new ones are not written\n";
        ssize_t ignored = write(learn->fd, full, sizeof full - 1);
        (void)ignored;
    }
    pthread_mutex_unlock(&learn->lock);
}

/*
 * Starts a learning listener at socketPath (which must not exist, in a folder only droidtop's UID enters) for
 * processes presenting token, writing distinct records to logPath. Returns the slot (0 or more) or a negative errno.
 */
JNIEXPORT jint JNICALL Java_dev_droidtop_sandboxhost_SandboxHost_nativeListen(JNIEnv *env, jclass cls, jstring jsocket,
                                                                              jstring jtoken, jstring jlog) {
    (void)cls;
    const char *socket_path = (*env)->GetStringUTFChars(env, jsocket, NULL);
    const char *token = (*env)->GetStringUTFChars(env, jtoken, NULL);
    const char *log_path = (*env)->GetStringUTFChars(env, jlog, NULL);
    int result = -ENOMEM;
    struct learn *learn = NULL;
    struct sbx_policy *policy = NULL;

    pthread_mutex_lock(&slots_lock);
    int slot = -1;
    for (int i = 0; i < SLOTS; i++) {
        if (!slots[i].listener) {
            slot = i;
            break;
        }
    }
    if (slot < 0) {
        result = -EBUSY;
        goto out;
    }
    learn = calloc(1, sizeof *learn);
    if (!learn) goto out;
    pthread_mutex_init(&learn->lock, NULL);
    learn->fd = open(log_path, O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0600);
    if (learn->fd < 0) __android_log_print(ANDROID_LOG_WARN, TAG, "learn log %s: %s", log_path, strerror(errno));

    policy = sbx_policy_new();
    if (!policy) goto out;
    sbx_policy_set_decider(policy, decide_allow, learn);
    sbx_policy_set_audit(policy, audit_learn, learn);

    struct sbx_listener *listener = NULL;
    result = sbx_broker_listen(policy, socket_path, token, &listener);
    if (result < 0) goto out;
    policy = NULL; /* the listener owns it now */
    slots[slot].listener = listener;
    slots[slot].learn = learn;
    learn = NULL;
    result = slot;
    __android_log_print(ANDROID_LOG_INFO, TAG, "learning listener %d at %s (no protection)", slot, socket_path);

out:
    pthread_mutex_unlock(&slots_lock);
    if (policy) sbx_policy_free(policy);
    if (learn) {
        if (learn->fd >= 0) close(learn->fd);
        pthread_mutex_destroy(&learn->lock);
        free(learn);
    }
    if (result < 0) __android_log_print(ANDROID_LOG_WARN, TAG, "listener at %s: %s", socket_path, strerror(-result));
    (*env)->ReleaseStringUTFChars(env, jsocket, socket_path);
    (*env)->ReleaseStringUTFChars(env, jtoken, token);
    (*env)->ReleaseStringUTFChars(env, jlog, log_path);
    return result;
}

/*
 * Stops accepting on slot and removes its socket. Connections already made keep being served until their processes
 * end, and their records keep reaching the log, so the learn state is kept (one per launch, a few hundred KB) rather
 * than freed under a broker thread that may still use it.
 */
JNIEXPORT void JNICALL Java_dev_droidtop_sandboxhost_SandboxHost_nativeClose(JNIEnv *env, jclass cls, jint slot) {
    (void)env;
    (void)cls;
    if (slot < 0 || slot >= SLOTS) return;
    pthread_mutex_lock(&slots_lock);
    struct sbx_listener *listener = slots[slot].listener;
    slots[slot].listener = NULL;
    slots[slot].learn = NULL;
    pthread_mutex_unlock(&slots_lock);
    if (listener) sbx_listener_close(listener);
}
