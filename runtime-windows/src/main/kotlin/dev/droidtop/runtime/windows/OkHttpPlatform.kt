package dev.droidtop.runtime.windows

import android.content.Context
import okhttp3.internal.platform.PlatformRegistry

/**
 * Hands OkHttp the application context its own startup initializer would.
 *
 * OkHttp 5 on Android reads its public suffix list from the APK's assets
 * (AssetPublicSuffixList, `assets/PublicSuffixDatabase.list`), through the
 * context its `PlatformInitializer` stores. That initializer is an
 * androidx.startup component, and the launcher's manifest
 * (shell-default/src/main/AndroidManifest.xml) removes androidx.startup's
 * InitializationProvider for the whole app, so it never ran: every
 * DNS-over-HTTPS lookup the Windows runtime makes
 * (dev.droidtop.runtime.windows.utils.Net) failed with "Unable to load
 * PublicSuffixDatabase.list resource" and fell back to system DNS
 * (BlueStacks rig, Droidtop/tracker#242). Setting the context is exactly what
 * PlatformInitializer.create does; nothing else is started.
 */
object OkHttpPlatform {
    fun install(context: Context) {
        if (PlatformRegistry.applicationContext == null) {
            PlatformRegistry.applicationContext = context.applicationContext
        }
    }
}
