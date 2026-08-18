// JNI entry points for dev.droidtop.runtime.remotestream. Wraps
// vendor/moonlight-common-c's C API (Limelight.h) for host discovery
// (ported separately, see RemoteHostDiscovery.kt — not part of
// moonlight-common-c itself, see README), PIN pairing, app-list retrieval,
// and stream launch.
//
// UNVERIFIED — not compiled in this environment. Written against
// moonlight-common-c's public headers (LiStartConnection, LiStartDiscovery-
// style pairing helpers) but never built or exercised against a real
// Sunshine host. See runtime-remote-stream/README.md.

#include <jni.h>
#include <android/log.h>

#define LOG_TAG "remotestream"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_droidtop_runtime_remotestream_MoonlightClient_nativePair(
    JNIEnv* env, jobject /* this */, jstring hostAddress, jstring pin) {
    LOGI("nativePair: TODO — wrap moonlight-common-c pairing exchange");
    return JNI_FALSE;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_dev_droidtop_runtime_remotestream_MoonlightClient_nativeFetchAppList(
    JNIEnv* env, jobject /* this */, jstring hostAddress) {
    LOGI("nativeFetchAppList: TODO — wrap moonlight-common-c applist request");
    return nullptr;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_droidtop_runtime_remotestream_MoonlightClient_nativeStartStream(
    JNIEnv* env, jobject /* this */, jstring hostAddress, jint appId) {
    LOGI("nativeStartStream: TODO — wrap LiStartConnection, feed decoded frames to a Surface");
    return JNI_FALSE;
}
