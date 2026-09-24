// libvortekrenderer for x86_64 (docs/SPEC.md 10b). On arm64 Vortek carries
// the box64 guest's Vulkan calls to Android's Vulkan. x86_64 runs real
// x86_64 Wine, which reaches Vulkan directly, so there is no guest to
// serve: createVkContext returns 0 and VortekRendererComponent closes the
// connection (VortekRendererComponent.java, requestCode 1).
#include <android/log.h>
#include <jni.h>

#define TAG "VortekRenderer"

JNIEXPORT void JNICALL
Java_com_winlator_xenvironment_components_VortekRendererComponent_initVulkanWrapper(
        JNIEnv *env, jobject thiz, jstring nativeLibraryDir, jstring libvulkanPath) {
    __android_log_print(ANDROID_LOG_INFO, TAG, "x86_64: Vortek is not used, Wine reaches Vulkan directly");
}

JNIEXPORT jlong JNICALL
Java_com_winlator_xenvironment_components_VortekRendererComponent_createVkContext(
        JNIEnv *env, jobject thiz, jint fd, jobject options) {
    __android_log_print(ANDROID_LOG_WARN, TAG, "x86_64: refusing a Vortek client on fd %d", fd);
    return 0;
}

JNIEXPORT void JNICALL
Java_com_winlator_xenvironment_components_VortekRendererComponent_destroyVkContext(
        JNIEnv *env, jobject thiz, jlong context) {
}

JNIEXPORT jboolean JNICALL
Java_com_winlator_xenvironment_components_VortekRendererComponent_handleExtraDataRequest(
        JNIEnv *env, jobject thiz, jlong context, jint requestId, jint requestLength) {
    return JNI_FALSE;
}
