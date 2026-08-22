package dev.droidtop.library

import android.content.Context

/**
 * Kirikiroid2/krkr2 (`org.github.krkr2`) -- a real, actively-maintained
 * (283 commits, real CI, Android arm64-v8a/x86_64 + Windows/Linux/macOS
 * targets as of this session's research) modern Android port of the
 * Kirikiri/KAG3 visual novel engine. Corrects an earlier, wrong
 * conclusion from this same project's own prior research ("Kirikiri has
 * no official Linux/Android port") -- it does, this is it
 * (github.com/2468785842/krkr2), confirmed by reading its real,
 * current source rather than re-asserting the old claim.
 *
 * **Real, honest limitation, not a guess**: unlike JoiPlay/RetroArch/
 * PPSSPP/etc., krkr2's own `MainActivity` has its `ACTION_VIEW`
 * intent-filter commented out in its real, current `AndroidManifest.xml`,
 * and `MainActivity.kt`'s real source (both fetched directly from the
 * project's GitHub this session, not assumed) handles no Intent extra for
 * "open this specific game" either -- only storage-permission setup, then
 * delegates to its native cocos2dx/krkr2 engine layer, which is presumed
 * (not confirmed -- would need the native/JNI source or a real installed
 * copy to trace further) to scan its own fixed directory and present an
 * in-app game picker. **There is currently no way to launch a specific
 * Kirikiri game from outside krkr2 itself.** This object only opens the
 * app generically (same as tapping its icon) -- real, working, useful
 * (strictly better than the total dead end Kirikiri entries were routed
 * into before this fix, see [EngineGameProvider.launch]'s own history),
 * but not per-game launch. Revisit if krkr2 ever re-enables its VIEW
 * filter, or if tracing its native code turns up a real fixed-scan-folder
 * convention worth writing games into.
 */
object Kirikiroid2 {
    const val PACKAGE_NAME = "org.github.krkr2"

    /** Needs `<queries><package android:name="org.github.krkr2" /></queries>` in the caller's manifest on API 30+ (package visibility). */
    fun isInstalled(context: Context): Boolean =
        try {
            context.packageManager.getPackageInfo(PACKAGE_NAME, 0)
            true
        } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
            false
        }

    /** Opens krkr2's own UI -- the user picks the specific game themselves; see this class's own doc comment for why. */
    fun open(context: Context) {
        check(isInstalled(context)) { "Kirikiroid2 ($PACKAGE_NAME) isn't installed" }
        val intent = context.packageManager.getLaunchIntentForPackage(PACKAGE_NAME)
            ?: error("$PACKAGE_NAME has no launch intent")
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
