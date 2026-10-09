/*
 * The screen bridge of a contained plugin's own screen (ui.main, docs/plugin-api.md 5.3, "ui.main in the sandbox").
 *
 * An isolated process cannot present into any Surface: a Surface's buffers come from the graphics allocator HAL, whose
 * fds only its clients may use, and an isolated process is never one ("avc: denied { use } ... tcontext=
 * u:r:hal_graphics_allocator_default:s0 tclass=fd", then "dequeueBuffer: IGraphicBufferProducer::requestBuffer failed",
 * emulator-5560 Android 14). So the Flutter engine's software rasteriser is given a stand-in window instead: in the
 * plugin's process the engine's own ANativeWindow imports (and the same names it resolves through dlsym) are answered
 * here for that window, and every other window still goes to the NDK. lock() hands out one of two frames in shared
 * memory droidtop allocated and passed over; unlockAndPost() flips to the other frame and tells droidtop, whose own
 * process copies the frame into the real Surface. Pixels are RGBA_8888, stride = width.
 *
 * Both processes load this library: droidtop's to create and map the shared frames, the plugin's to draw into them.
 */
#include <jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/sharedmem.h>
#include <android/log.h>
#include <dlfcn.h>
#include <errno.h>
#include <pthread.h>
#include <stdint.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

#define LOG_TAG "droidtop.screen"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

static struct {
    int active;
    int width;
    int height;
    uint8_t *base;
    size_t capacity;
    int index; /* the frame droidtop shows; the engine draws the other one */
} g_screen;

static pthread_mutex_t g_screen_lock = PTHREAD_MUTEX_INITIALIZER;
static JavaVM *g_vm;
static jclass g_bridge;
static jmethodID g_on_frame;

/* Any unique address will do: nothing ever dereferences it, every call that receives it is one of the functions below. */
static char g_stand_in_tag;
#define STAND_IN ((ANativeWindow *) &g_stand_in_tag)

static size_t frame_bytes(void) { return (size_t) g_screen.width * (size_t) g_screen.height * 4; }

static ANativeWindow *b_fromSurface(JNIEnv *env, jobject surface) {
    if (g_screen.active) return STAND_IN;
    return ANativeWindow_fromSurface(env, surface);
}

static void b_acquire(ANativeWindow *w) {
    if (w != STAND_IN) ANativeWindow_acquire(w);
}

static void b_release(ANativeWindow *w) {
    if (w != STAND_IN) ANativeWindow_release(w);
}

static int32_t b_getWidth(ANativeWindow *w) { return w == STAND_IN ? g_screen.width : ANativeWindow_getWidth(w); }

static int32_t b_getHeight(ANativeWindow *w) { return w == STAND_IN ? g_screen.height : ANativeWindow_getHeight(w); }

static int32_t b_getFormat(ANativeWindow *w) { return w == STAND_IN ? WINDOW_FORMAT_RGBA_8888 : ANativeWindow_getFormat(w); }

static int32_t b_lock(ANativeWindow *w, ANativeWindow_Buffer *out, ARect *dirty) {
    if (w != STAND_IN) return ANativeWindow_lock(w, out, dirty);
    pthread_mutex_lock(&g_screen_lock);
    if (!g_screen.active || g_screen.base == NULL || frame_bytes() * 2 > g_screen.capacity) {
        pthread_mutex_unlock(&g_screen_lock);
        return -EINVAL;
    }
    int back = g_screen.index ^ 1;
    out->width = g_screen.width;
    out->height = g_screen.height;
    out->stride = g_screen.width;
    out->format = WINDOW_FORMAT_RGBA_8888;
    out->bits = g_screen.base + (size_t) back * frame_bytes();
    if (dirty != NULL) {
        dirty->left = 0;
        dirty->top = 0;
        dirty->right = g_screen.width;
        dirty->bottom = g_screen.height;
    }
    /* Held until unlockAndPost, so a resize never changes the frame under the engine's brush. */
    return 0;
}

static int32_t b_unlockAndPost(ANativeWindow *w) {
    if (w != STAND_IN) return ANativeWindow_unlockAndPost(w);
    g_screen.index ^= 1;
    int shown = g_screen.index, width = g_screen.width, height = g_screen.height;
    pthread_mutex_unlock(&g_screen_lock);
    if (g_vm == NULL || g_bridge == NULL || g_on_frame == NULL) return 0;
    JNIEnv *env = NULL;
    if ((*g_vm)->GetEnv(g_vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK) {
        /* The engine's raster thread: attached once and left attached for the life of the thread. */
        if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) != JNI_OK) return 0;
    }
    (*env)->CallStaticVoidMethod(env, g_bridge, g_on_frame, shown, width, height);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    return 0;
}

/* The replacement for each name, for the import rewrite and for dlsym (sandbox_hooks.c uses both). */
typedef struct {
    const char *symbol;
    void *replacement;
} BridgeHook;

static const BridgeHook BRIDGE_HOOKS[] = {
    {"ANativeWindow_fromSurface", (void *) b_fromSurface},
    {"ANativeWindow_acquire", (void *) b_acquire},
    {"ANativeWindow_release", (void *) b_release},
    {"ANativeWindow_getWidth", (void *) b_getWidth},
    {"ANativeWindow_getHeight", (void *) b_getHeight},
    {"ANativeWindow_getFormat", (void *) b_getFormat},
    {"ANativeWindow_lock", (void *) b_lock},
    {"ANativeWindow_unlockAndPost", (void *) b_unlockAndPost},
};

/* The bridge's replacement for [symbol], or NULL. */
void *screen_bridge_replacement(const char *symbol) {
    for (size_t i = 0; i < sizeof(BRIDGE_HOOKS) / sizeof(BRIDGE_HOOKS[0]); i++) {
        if (strcmp(symbol, BRIDGE_HOOKS[i].symbol) == 0) return BRIDGE_HOOKS[i].replacement;
    }
    return NULL;
}

/* ---- JNI: ScreenBridge ---- */

JNIEXPORT jint JNICALL
Java_dev_droidtop_pluginhost_ScreenBridge_nativeCreate(JNIEnv *env, jclass clazz, jlong capacity) {
    /* droidtop's side: a shared memory region of its own (appdomain_tmpfs, which an isolated process may map). */
    return ASharedMemory_create("droidtop-plugin-screen", (size_t) capacity);
}

JNIEXPORT jobject JNICALL
Java_dev_droidtop_pluginhost_ScreenBridge_nativeMap(JNIEnv *env, jclass clazz, jint fd, jlong capacity) {
    void *base = mmap(NULL, (size_t) capacity, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    if (base == MAP_FAILED) {
        LOGW("could not map the screen frames: %s", strerror(errno));
        return NULL;
    }
    return (*env)->NewDirectByteBuffer(env, base, capacity);
}

JNIEXPORT void JNICALL
Java_dev_droidtop_pluginhost_ScreenBridge_nativeUnmap(JNIEnv *env, jclass clazz, jobject buffer) {
    void *base = buffer != NULL ? (*env)->GetDirectBufferAddress(env, buffer) : NULL;
    jlong capacity = buffer != NULL ? (*env)->GetDirectBufferCapacity(env, buffer) : 0;
    if (base != NULL && capacity > 0) munmap(base, (size_t) capacity);
}

JNIEXPORT jboolean JNICALL
Java_dev_droidtop_pluginhost_ScreenBridge_nativeAttach(JNIEnv *env, jclass clazz, jint fd, jlong capacity, jint width, jint height) {
    /* The plugin's side: the frames droidtop handed over, and the class whose onFrame tells droidtop. */
    void *base = mmap(NULL, (size_t) capacity, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    close(fd);
    if (base == MAP_FAILED) {
        LOGW("could not map the screen frames: %s", strerror(errno));
        return JNI_FALSE;
    }
    if (g_vm == NULL) (*env)->GetJavaVM(env, &g_vm);
    if (g_bridge == NULL) {
        g_bridge = (jclass) (*env)->NewGlobalRef(env, clazz);
        g_on_frame = (*env)->GetStaticMethodID(env, clazz, "onFrame", "(III)V");
    }
    pthread_mutex_lock(&g_screen_lock);
    if (g_screen.base != NULL) munmap(g_screen.base, g_screen.capacity);
    g_screen.base = (uint8_t *) base;
    g_screen.capacity = (size_t) capacity;
    g_screen.width = width;
    g_screen.height = height;
    g_screen.index = 0;
    g_screen.active = 1;
    pthread_mutex_unlock(&g_screen_lock);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_dev_droidtop_pluginhost_ScreenBridge_nativeResize(JNIEnv *env, jclass clazz, jint width, jint height) {
    pthread_mutex_lock(&g_screen_lock);
    g_screen.width = width;
    g_screen.height = height;
    pthread_mutex_unlock(&g_screen_lock);
}

JNIEXPORT void JNICALL
Java_dev_droidtop_pluginhost_ScreenBridge_nativeDetach(JNIEnv *env, jclass clazz) {
    pthread_mutex_lock(&g_screen_lock);
    g_screen.active = 0;
    if (g_screen.base != NULL) munmap(g_screen.base, g_screen.capacity);
    g_screen.base = NULL;
    g_screen.capacity = 0;
    pthread_mutex_unlock(&g_screen_lock);
}
