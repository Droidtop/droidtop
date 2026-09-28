// JNI entry points for dev.droidtop.hostbridge.HostBridge. See
// wayland_client.h/.cpp for the actual Wayland client logic — this file is
// just the JNI marshaling layer, deliberately kept thin.

#include "wayland_client.h"

#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>

#include <cstdint>
#include <memory>
#include <mutex>
#include <unordered_map>

#define LOG_TAG "hostbridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {

// Captured in JNI_OnLoad. Needed because clipboard text arrives on a native
// worker thread that Java knows nothing about (see wayland_client.cpp's
// transfer threads) and has to attach itself before it can call back up.
JavaVM* g_vm = nullptr;

/**
 * The Kotlin end of the container -> Android clipboard direction: a global
 * ref to one HostBridge instance plus the method to hand text to.
 *
 * Held by shared_ptr and looked up under its OWN mutex, deliberately. The
 * upcall happens on a detached worker that may still be draining a transfer
 * when nativeDisconnect() runs; copying the shared_ptr out under the lock and
 * releasing the lock before calling into Java means the global ref cannot be
 * deleted mid-call, and the upcall itself never holds a lock that Kotlin
 * could re-enter.
 */
struct ClipboardSink {
    jobject bridgeRef = nullptr;
    jmethodID method = nullptr;

    ~ClipboardSink() {
        if (!bridgeRef || !g_vm) return;
        JNIEnv* env = nullptr;
        bool attached = false;
        if (g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
            if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
            attached = true;
        }
        env->DeleteGlobalRef(bridgeRef);
        if (attached) g_vm->DetachCurrentThread();
    }
};

std::mutex g_sinksMutex;
std::unordered_map<jint, std::shared_ptr<ClipboardSink>> g_clipboardSinks;

/**
 * The Kotlin end of the toplevel-list-changed notification: a global ref to
 * one HostBridge instance plus its no-arg onToplevelsChanged() method. Same
 * shared_ptr-under-its-own-lock shape as ClipboardSink and for the same
 * reason — the native callback can fire from the dispatch thread at any
 * point up to disconnect().
 */
struct ToplevelsSink {
    jobject bridgeRef = nullptr;
    jmethodID method = nullptr;

    ~ToplevelsSink() {
        if (!bridgeRef || !g_vm) return;
        JNIEnv* env = nullptr;
        bool attached = false;
        if (g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
            if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
            attached = true;
        }
        env->DeleteGlobalRef(bridgeRef);
        if (attached) g_vm->DetachCurrentThread();
    }
};

std::unordered_map<jint, std::shared_ptr<ToplevelsSink>> g_toplevelsSinks;

// One WaylandClient per HostBridge.kt instance. In practice there's only
// ever one (the DesktopSessionService's connection to the primary
// container), but keying by the Kotlin object's identity hash rather than
// assuming a singleton avoids baking that assumption into native code.
std::mutex g_clientsMutex;
std::unordered_map<jint, std::unique_ptr<hostbridge::WaylandClient>> g_clients;

// The ANativeWindow currently backing each client's screencopy target, so
// nativeStopPresenting/nativeDisconnect can ANativeWindow_release it —
// ANativeWindow_fromSurface() in nativePresentOutput acquires a reference
// this map is responsible for eventually releasing.
std::unordered_map<jint, ANativeWindow*> g_presentedWindows;

jint identityHash(JNIEnv* env, jobject obj) {
    return env->CallIntMethod(obj, env->GetMethodID(env->GetObjectClass(obj), "hashCode", "()I"));
}

hostbridge::WaylandClient* findClient(jint key) {
    auto it = g_clients.find(key);
    return it != g_clients.end() ? it->second.get() : nullptr;
}

void releasePresentedWindowLocked(jint key) {
    auto it = g_presentedWindows.find(key);
    if (it != g_presentedWindows.end()) {
        ANativeWindow_release(it->second);
        g_presentedWindows.erase(it);
    }
}

/**
 * Hands one selection's worth of text up to HostBridge.onContainerClipboardText.
 * `userData` is the client's identity-hash key, not a pointer — see
 * ClipboardSink for why the sink is found by lookup rather than dereferenced.
 */
void clipboardTrampoline(void* userData, const char* utf8Text, size_t length) {
    if (!g_vm) return;
    auto key = static_cast<jint>(reinterpret_cast<intptr_t>(userData));

    std::shared_ptr<ClipboardSink> sink;
    {
        std::lock_guard<std::mutex> lock(g_sinksMutex);
        auto it = g_clipboardSinks.find(key);
        if (it != g_clipboardSinks.end()) sink = it->second;
    }
    if (!sink) return;

    JNIEnv* env = nullptr;
    bool attached = false;
    if (g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        attached = true;
    }

    // A byte[] rather than NewStringUTF: that call speaks modified UTF-8 and
    // mangles anything outside the BMP (emoji, most obviously). Kotlin
    // decodes real UTF-8 on the other side.
    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(length));
    if (bytes) {
        env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(length),
                                 reinterpret_cast<const jbyte*>(utf8Text));
        env->CallVoidMethod(sink->bridgeRef, sink->method, bytes);
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
        env->DeleteLocalRef(bytes);
    }

    if (attached) g_vm->DetachCurrentThread();
}

/**
 * Fires whenever the container-side window list changes. `userData` is the
 * client's identity-hash key, same convention as clipboardTrampoline. Takes
 * no data itself — HostBridge.kt's onToplevelsChanged() is expected to call
 * back into nativeGetToplevels() for the current snapshot, matching the
 * native side's fetch-on-notify shape (see ToplevelsChangedCallback in
 * wayland_client.h).
 */
void toplevelsChangedTrampoline(void* userData) {
    if (!g_vm) return;
    auto key = static_cast<jint>(reinterpret_cast<intptr_t>(userData));

    std::shared_ptr<ToplevelsSink> sink;
    {
        std::lock_guard<std::mutex> lock(g_sinksMutex);
        auto it = g_toplevelsSinks.find(key);
        if (it != g_toplevelsSinks.end()) sink = it->second;
    }
    if (!sink) return;

    JNIEnv* env = nullptr;
    bool attached = false;
    if (g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        attached = true;
    }

    env->CallVoidMethod(sink->bridgeRef, sink->method);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }

    if (attached) g_vm->DetachCurrentThread();
}

} // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_droidtop_hostbridge_HostBridge_nativeConnect(JNIEnv* env, jobject thiz, jstring waylandSocketPath) {
    const char* path = env->GetStringUTFChars(waylandSocketPath, nullptr);

    jint key = identityHash(env, thiz);

    auto client = std::make_unique<hostbridge::WaylandClient>();

    // Registered BEFORE connect(), not after: connect() round-trips the
    // data-control device, which is what pulls in the selection the
    // compositor is already holding. A sink attached afterwards would miss it.
    auto sink = std::make_shared<ClipboardSink>();
    sink->bridgeRef = env->NewGlobalRef(thiz);
    sink->method = env->GetMethodID(env->GetObjectClass(thiz), "onContainerClipboardText", "([B)V");
    if (sink->method) {
        {
            std::lock_guard<std::mutex> lock(g_sinksMutex);
            g_clipboardSinks[key] = sink;
        }
        client->setClipboardListener(clipboardTrampoline, reinterpret_cast<void*>(static_cast<intptr_t>(key)));
    } else {
        env->ExceptionClear();
        LOGI("onContainerClipboardText not found — container clipboard will not reach Android");
    }

    // Same registered-before-connect() reasoning as the clipboard sink
    // above: connect() is what pulls in the toplevel manager's initial
    // burst of `toplevel` events for windows that already existed.
    auto toplevelsSink = std::make_shared<ToplevelsSink>();
    toplevelsSink->bridgeRef = env->NewGlobalRef(thiz);
    toplevelsSink->method = env->GetMethodID(env->GetObjectClass(thiz), "onToplevelsChanged", "()V");
    if (toplevelsSink->method) {
        {
            std::lock_guard<std::mutex> lock(g_sinksMutex);
            g_toplevelsSinks[key] = toplevelsSink;
        }
        client->setToplevelsListener(toplevelsChangedTrampoline, reinterpret_cast<void*>(static_cast<intptr_t>(key)));
    } else {
        env->ExceptionClear();
        LOGI("onToplevelsChanged not found — the taskbar's window list will not update");
    }

    bool ok = client->connect(path);

    env->ReleaseStringUTFChars(waylandSocketPath, path);

    if (ok) {
        std::lock_guard<std::mutex> lock(g_clientsMutex);
        g_clients[key] = std::move(client);
    } else {
        std::lock_guard<std::mutex> lock(g_sinksMutex);
        g_clipboardSinks.erase(key);
        g_toplevelsSinks.erase(key);
    }

    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_droidtop_hostbridge_HostBridge_nativeOfferClipboardText(JNIEnv* env, jobject thiz, jbyteArray utf8) {
    jsize length = env->GetArrayLength(utf8);
    jbyte* bytes = env->GetByteArrayElements(utf8, nullptr);
    if (!bytes) return JNI_FALSE;

    bool ok = false;
    {
        std::lock_guard<std::mutex> lock(g_clientsMutex);
        if (auto* client = findClient(identityHash(env, thiz))) {
            ok = client->offerClipboardText(reinterpret_cast<const char*>(bytes),
                                             static_cast<size_t>(length));
        }
    }

    env->ReleaseByteArrayElements(utf8, bytes, JNI_ABORT); // read-only, nothing to copy back
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_droidtop_hostbridge_HostBridge_nativeDisconnect(JNIEnv* env, jobject thiz) {
    jint key = identityHash(env, thiz);
    {
        std::lock_guard<std::mutex> lock(g_sinksMutex);
        g_clipboardSinks.erase(key);
        g_toplevelsSinks.erase(key);
    }
    std::lock_guard<std::mutex> lock(g_clientsMutex);
    // Capture stops before its window is released: the dispatch thread
    // draws into that window until stopPresenting() returns.
    if (auto* client = findClient(key)) client->stopPresenting();
    releasePresentedWindowLocked(key);
    g_clients.erase(key);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_droidtop_hostbridge_HostBridge_nativePresentOutput(JNIEnv* env, jobject thiz, jobject surface) {
    std::lock_guard<std::mutex> lock(g_clientsMutex);
    jint key = identityHash(env, thiz);
    auto* client = findClient(key);
    if (!client) {
        LOGI("nativePresentOutput: no connected client");
        return JNI_FALSE;
    }

    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (!window) {
        LOGI("nativePresentOutput: ANativeWindow_fromSurface failed");
        return JNI_FALSE;
    }

    // Stop drawing into the previous target before dropping it.
    client->stopPresenting();
    releasePresentedWindowLocked(key);
    bool ok = client->presentPrimaryOutput(window);
    if (ok) {
        g_presentedWindows[key] = window; // ownership of the acquired reference moves here
    } else {
        ANativeWindow_release(window);
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_droidtop_hostbridge_HostBridge_nativeStopPresenting(JNIEnv* env, jobject thiz) {
    std::lock_guard<std::mutex> lock(g_clientsMutex);
    jint key = identityHash(env, thiz);
    if (auto* client = findClient(key)) {
        client->stopPresenting();
    }
    releasePresentedWindowLocked(key);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_droidtop_hostbridge_HostBridge_nativeInjectPointerMotion(JNIEnv* env, jobject thiz, jdouble dx, jdouble dy) {
    std::lock_guard<std::mutex> lock(g_clientsMutex);
    if (auto* client = findClient(identityHash(env, thiz))) {
        client->injectPointerMotion(dx, dy);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_dev_droidtop_hostbridge_HostBridge_nativeInjectPointerMotionAbsolute(
    JNIEnv* env, jobject thiz, jdouble x, jdouble y, jint extentWidth, jint extentHeight) {
    std::lock_guard<std::mutex> lock(g_clientsMutex);
    if (auto* client = findClient(identityHash(env, thiz))) {
        client->injectPointerMotionAbsolute(x, y, static_cast<uint32_t>(extentWidth), static_cast<uint32_t>(extentHeight));
    }
}

extern "C" JNIEXPORT void JNICALL
Java_dev_droidtop_hostbridge_HostBridge_nativeInjectPointerButton(
    JNIEnv* env, jobject thiz, jint linuxButtonCode, jboolean pressed) {
    std::lock_guard<std::mutex> lock(g_clientsMutex);
    if (auto* client = findClient(identityHash(env, thiz))) {
        client->injectPointerButton(static_cast<uint32_t>(linuxButtonCode), pressed == JNI_TRUE);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_dev_droidtop_hostbridge_HostBridge_nativeInjectPointerAxis(
    JNIEnv* env, jobject thiz, jdouble horizontal, jdouble vertical) {
    std::lock_guard<std::mutex> lock(g_clientsMutex);
    if (auto* client = findClient(identityHash(env, thiz))) {
        client->injectPointerAxis(horizontal, vertical);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_dev_droidtop_hostbridge_HostBridge_nativeInjectKey(
    JNIEnv* env, jobject thiz, jint evdevKeyCode, jboolean pressed) {
    std::lock_guard<std::mutex> lock(g_clientsMutex);
    if (auto* client = findClient(identityHash(env, thiz))) {
        client->injectKey(static_cast<uint32_t>(evdevKeyCode), pressed == JNI_TRUE);
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_droidtop_hostbridge_HostBridge_nativeSetOutputSize(JNIEnv* env, jobject thiz, jint width, jint height) {
    std::lock_guard<std::mutex> lock(g_clientsMutex);
    if (auto* client = findClient(identityHash(env, thiz))) {
        return client->setOutputSize(width, height) ? JNI_TRUE : JNI_FALSE;
    }
    return JNI_FALSE;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_dev_droidtop_hostbridge_HostBridge_nativeGetToplevels(JNIEnv* env, jobject thiz) {
    std::vector<hostbridge::ToplevelInfo> snapshot;
    {
        std::lock_guard<std::mutex> lock(g_clientsMutex);
        if (auto* client = findClient(identityHash(env, thiz))) {
            snapshot = client->snapshotToplevels();
        }
    }

    jclass cls = env->FindClass("dev/droidtop/hostbridge/Toplevel");
    if (!cls) {
        env->ExceptionClear();
        return env->NewObjectArray(0, env->FindClass("java/lang/Object"), nullptr);
    }
    jmethodID ctor = env->GetMethodID(cls, "<init>", "(JLjava/lang/String;Ljava/lang/String;ZZZZ)V");
    jobjectArray result = env->NewObjectArray(static_cast<jsize>(snapshot.size()), cls, nullptr);
    for (size_t i = 0; i < snapshot.size(); i++) {
        const auto& e = snapshot[i];
        jstring title = env->NewStringUTF(e.title.c_str());
        jstring appId = env->NewStringUTF(e.appId.c_str());
        jobject obj = env->NewObject(cls, ctor, static_cast<jlong>(e.id), title, appId,
                                      e.activated ? JNI_TRUE : JNI_FALSE,
                                      e.minimized ? JNI_TRUE : JNI_FALSE,
                                      e.maximized ? JNI_TRUE : JNI_FALSE,
                                      e.fullscreen ? JNI_TRUE : JNI_FALSE);
        env->SetObjectArrayElement(result, static_cast<jsize>(i), obj);
        env->DeleteLocalRef(obj);
        env->DeleteLocalRef(title);
        env->DeleteLocalRef(appId);
    }
    env->DeleteLocalRef(cls);
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_droidtop_hostbridge_HostBridge_nativeActivateToplevel(JNIEnv* env, jobject thiz, jlong id) {
    std::lock_guard<std::mutex> lock(g_clientsMutex);
    if (auto* client = findClient(identityHash(env, thiz))) {
        return client->activateToplevel(static_cast<uint64_t>(id)) ? JNI_TRUE : JNI_FALSE;
    }
    return JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_droidtop_hostbridge_HostBridge_nativeSetToplevelMinimized(JNIEnv* env, jobject thiz, jlong id, jboolean minimized) {
    std::lock_guard<std::mutex> lock(g_clientsMutex);
    if (auto* client = findClient(identityHash(env, thiz))) {
        return client->setToplevelMinimized(static_cast<uint64_t>(id), minimized == JNI_TRUE) ? JNI_TRUE : JNI_FALSE;
    }
    return JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_droidtop_hostbridge_HostBridge_nativeCloseToplevel(JNIEnv* env, jobject thiz, jlong id) {
    std::lock_guard<std::mutex> lock(g_clientsMutex);
    if (auto* client = findClient(identityHash(env, thiz))) {
        return client->closeToplevel(static_cast<uint64_t>(id)) ? JNI_TRUE : JNI_FALSE;
    }
    return JNI_FALSE;
}
