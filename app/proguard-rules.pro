# R8 keep rules for the release build (docs/SPEC.md 10b, Droidtop/tracker#283).
#
# Posture: R8 shrinks and does not obfuscate or optimise. proguard-android.txt
# (the base file in build.gradle.kts) carries -dontoptimize; -dontobfuscate is
# below. The point is to drop code nothing reaches, not to rename it: this app
# links against native code that looks classes and methods up by name,
# vendored trees that load classes from strings, and plugin bundles that call
# into it by name, and a renamed class is a crash on a device nobody can
# attach a debugger to. Stack traces stay readable as a side effect.
#
# The vendored launcher's own rules (shell-default/proguard.pro and
# proguard.flags, Lawnchair's, keeping com.android.**) are added next to this
# file in build.gradle.kts, so the launcher tree is kept the way its authors
# shipped it. The rules here are for everything else.

-dontobfuscate
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, Exceptions, SourceFile, LineNumberTable, RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, AnnotationDefault

# JNI. Any class with a native method keeps all of it: the native side resolves
# its methods and fields by name (RegisterNatives or the JNI symbol name).
-keepclasseswithmembers class * {
    native <methods>;
}

# Classes the native code calls back into or constructs by name (FindClass,
# GetMethodID). Whole packages, because the lookups are scattered through C
# that is not ours.
#   com.winlator.**       gamenative's Winlator tree: xconnector, renderers, GPU helpers
#   host-bridge           Toplevel, the clipboard and toplevel sinks (hostbridge_jni.cpp)
#   plugin-host           PythonCallException (droidtoppy_jni.c) and the bridge
#   TunnelNative          hev-socks5-tunnel's JNI surface
#   pckeyboard            BinaryDictionary registers its natives by class name
-keep class com.winlator.** { *; }
-keep class dev.droidtop.hostbridge.** { *; }
-keep class dev.droidtop.pluginhost.** { *; }
-keep class dev.droidtop.app.vpn.TunnelNative { *; }
-keep class org.pocketworkstation.pckeyboard.** { *; }

# The plugin API. Plugin bundles are dex loaded at run time against these
# types (docs/plugin-api.md), so they are the contract and nothing in them may
# be dropped because droidtop itself does not call it. The AIDL interfaces
# (IPluginHostBroker, IPluginRuntime, IPluginRuntimeCallback) and their Stub
# classes are in dev.droidtop.pluginhost above.
-keep class dev.droidtop.runtime.tasks.** { *; }

# Flutter plugin host: the engine calls FlutterJNI by name, and the registrant
# is found with Class.forName (FlutterEngineHost.kt).
-keep class io.flutter.** { *; }
-dontwarn io.flutter.**
-dontwarn com.google.android.play.core.**

# Shizuku: ShizukuProvider is named in the manifest (kept by AAPT's generated
# rules), the API reaches its binder classes by name.
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
-dontwarn rikka.**
-dontwarn moe.shizuku.**

# Restriction bypass and the hidden-API refine runtime (HiddenApi module).
-keep class org.chickenhook.restrictionbypass.** { *; }
-keep class dev.rikka.tools.refine.** { *; }

# gamenative, from its own app/proguard-rules.pro (it ships minified with
# these): JavaSteam builds services and messages by reflection, the crypto
# provider is registered by name, Timber's release tree, XR and Samsung SDK
# glue.
-keep class in.dragonbra.javasteam.** { *; }
-keep class * extends in.dragonbra.javasteam.steam.handlers.steamunifiedmessages.UnifiedService { *; }
-keep class org.spongycastle.**
-dontwarn org.spongycastle.jce.provider.X509LDAPCertStoreSpi
-dontwarn org.spongycastle.x509.util.LDAPStoreHelper
-keep class timber.log.Timber { *; }
-keep class app.gamenative.ReleaseTree { *; }
-keep class horizon.** { *; }
-keep class com.meta.horizon.** { *; }
-dontwarn horizon.**
-dontwarn com.meta.horizon.**
-keep class com.samsung.sdk.sperf.** { *; }
-dontwarn com.samsung.sdk.sperf.**

# Protobuf. Two runtimes meet in this app (app/build.gradle.kts substitutes the
# full one for the lite one) and generated messages are reached by reflection
# on their accessor methods and fields: Launcher3's lite protos, JavaSteam's
# full ones.
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageV3 {
    *;
}
-keepclassmembers class * extends com.google.protobuf.GeneratedMessage {
    *;
}
-keep class * implements com.google.protobuf.MessageOrBuilder { *; }
-keep class com.google.protobuf.Timestamp { *; }

# JGit loads its message bundle into public fields by reflection and finds
# transports, hooks and config through ServiceLoader-style lookups; it is the
# theme downloader's git client (ThemeDownloader.kt) and small beside the rest.
-keep class org.eclipse.jgit.** { *; }
-dontwarn org.eclipse.jgit.**

# Platform classes the vendored launcher and SettingsLib name but android.jar
# does not carry: BackgroundBlurDrawable is Android 12+ internal and only
# reached behind a version check, SearchIndexablesProvider/Contract are the
# framework's own and exist on the device (the SettingsLib provider is the
# one that extends them). Missing at R8 time, present or guarded at run time.
-dontwarn android.provider.SearchIndexablesContract
-dontwarn android.provider.SearchIndexablesProvider
-dontwarn com.android.internal.graphics.drawable.BackgroundBlurDrawable
