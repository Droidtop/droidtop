package com.google.android.apps.inputmethod.libs.mozc.session;

/**
 * The Java side of libmozc.so (Droidtop/tracker#903 H7c). The library binds to this class by name, so its package and
 * name are Mozc's and must not change (consumer-rules.pro keeps them through R8). From google/mozc
 * src/android/jni/mozcjni.cc at 0136c35782536cb43d710575c311cd4185982738 (3.35.6354):
 *
 * <ul>
 *   <li>{@link #initialize} is the one exported symbol, Java_com_google_android_apps_inputmethod_libs_mozc_session_MozcJni_initialize,
 *       found by name after the library loads. It registers the three below with RegisterNatives and says whether it
 *       could.
 *   <li>{@link #onPostLoad} ("(Ljava/lang/String;Ljava/lang/String;)Z") sets the user profile directory, where Mozc keeps
 *       what it learns, and makes the session handler from the dictionary at the path. A dictionary it cannot read
 *       leaves a minimal engine. Once made, later calls return true and change nothing.
 *   <li>{@link #evalCommand} ("([B)[B") takes a serialized mozc.commands.Command and returns it with its Output.
 *       Synchronous; not safe from two threads at once (MozcRuntime holds a lock around it).
 *   <li>{@link #getDataVersion} ("()Ljava/lang/String;") is the loaded dictionary's version, empty before onPostLoad.
 * </ul>
 *
 * Nothing here loads the library: MozcRuntime does, off the main thread, before any call.
 */
public final class MozcJni {
    private MozcJni() {}

    public static native boolean initialize();

    public static native boolean onPostLoad(String userProfileDirectory, String dataFilePath);

    public static native byte[] evalCommand(byte[] command);

    public static native String getDataVersion();
}
