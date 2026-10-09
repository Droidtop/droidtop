/*
 * The sample's native library: one JNI method, bound the ordinary way (by
 * its Java_ symbol name, after the plugin's own System.loadLibrary). It is
 * here to show a native_bundle plugin with a .so running contained: the
 * library reaches the isolated process as a descriptor and is mapped through
 * droidtop's guarded hooks (droidtop docs/plugin-api.md 5.3).
 */
#include <jni.h>

JNIEXPORT jstring JNICALL
Java_dev_droidtop_samples_statustile_NativeGreeting_hello(JNIEnv *env, jclass clazz) {
    return (*env)->NewStringUTF(env, "Hello from the sample's native library");
}
