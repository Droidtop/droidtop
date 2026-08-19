// JNI entry points for dev.droidtop.hostbridge.HostBridge. See
// wayland_client.h/.cpp for the actual Wayland client logic — this file is
// just the JNI marshaling layer, deliberately kept thin.

#include "wayland_client.h"

#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>

#include <unordered_map>
#include <memory>
#include <mutex>

#define LOG_TAG "hostbridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {

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

} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_droidtop_hostbridge_HostBridge_nativeConnect(JNIEnv* env, jobject thiz, jstring waylandSocketPath) {
    const char* path = env->GetStringUTFChars(waylandSocketPath, nullptr);

    auto client = std::make_unique<hostbridge::WaylandClient>();
    bool ok = client->connect(path);

    env->ReleaseStringUTFChars(waylandSocketPath, path);

    if (ok) {
        std::lock_guard<std::mutex> lock(g_clientsMutex);
        g_clients[identityHash(env, thiz)] = std::move(client);
    }

    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_droidtop_hostbridge_HostBridge_nativeDisconnect(JNIEnv* env, jobject thiz) {
    std::lock_guard<std::mutex> lock(g_clientsMutex);
    jint key = identityHash(env, thiz);
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

    releasePresentedWindowLocked(key); // drop any previous target first
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
