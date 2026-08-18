// JNI entry points for dev.droidtop.hostbridge.HostBridge. See
// wayland_client.h/.cpp for the actual Wayland client logic — this file is
// just the JNI marshaling layer, deliberately kept thin.

#include "wayland_client.h"

#include <jni.h>
#include <android/log.h>

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

jint identityHash(JNIEnv* env, jobject obj) {
    return env->CallIntMethod(obj, env->GetMethodID(env->GetObjectClass(obj), "hashCode", "()I"));
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
    g_clients.erase(identityHash(env, thiz));
}

// TODO (see host-bridge/README.md status): nativePresentOutput(outputId, Surface)
// and nativeInject{Pointer,Key}Event(...) — depend on WaylandClient exposing a
// screencopy capture loop and virtual-pointer/virtual-keyboard request wrappers,
// neither of which exist yet beyond the registry-binding step in wayland_client.cpp.
