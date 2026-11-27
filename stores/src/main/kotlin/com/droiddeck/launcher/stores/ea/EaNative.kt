package com.droiddeck.launcher.stores.ea

import com.droiddeck.launcher.stores.StoresNative

/** JNI names and callback signatures from DroidDeck stores/ea/EaNative.kt at cc63b403872b (GPL-3.0; NOTICE.md).
 * The Rust crate calls these methods by name; all policy and coroutine handling stays in droidtop's adapter.
 */
object EaNative {
    fun ready(): Boolean = StoresNative.ensureLoaded()

    interface InstallListener {
        fun onStatus(text: String)
        fun onCheck(doneBytes: Long, totalBytes: Long, files: Int, filesTotal: Int)
        fun onSizes(pendingBytes: Long, totalBytes: Long)
        fun onProgress(done: Long, total: Long, files: Int, filesTotal: Int)
        fun onLog(line: String)
        fun beforeReplace(): Boolean
        fun onComplete(success: Boolean, cancelled: Boolean, error: String, result: String)
    }

    @JvmStatic external fun nativeCall(op: String, request: String): String?
    @JvmStatic external fun nativeInstall(request: String, listener: InstallListener): Long
    @JvmStatic external fun nativeCancel(handle: Long)
    @JvmStatic external fun nativeRelease(handle: Long)
}
