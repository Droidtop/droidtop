// JNI entry points for dev.droidtop.runtime.remotestream. Wraps
// vendor/moonlight-common-c's C API (Limelight.h) for stream launch only —
// pairing and app-list retrieval turned out NOT to be part of
// moonlight-common-c (that library is streaming-protocol/RTP/RTSP only);
// they're a plain HTTPS+XML REST layer, now implemented directly in Kotlin
// (see MoonlightPairing.kt/GameStreamHttpClient.kt, ported from
// moonlight-android's real NvHTTP/PairingManager). See
// runtime-remote-stream/README.md.
//
// UNVERIFIED — not compiled in this environment. Written against
// moonlight-common-c's public headers (LiStartConnection) but never built
// or exercised against a real Sunshine host.

#include <jni.h>
#include <android/log.h>

#define LOG_TAG "remotestream"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_droidtop_runtime_remotestream_MoonlightClient_nativeStartStream(
    JNIEnv* env, jobject /* this */, jstring hostAddress, jint appId) {
    LOGI("nativeStartStream: TODO — wrap LiStartConnection, feed decoded frames to a Surface");
    return JNI_FALSE;
}
