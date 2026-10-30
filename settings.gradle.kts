pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Needed by :shell-default (the forked-in Murine Launcher source —
        // see shell-default/README.md): chickenhook-restrictionbypass and
        // a couple of its other deps are only published there.
        maven { url = uri("https://jitpack.io") }
        // Needed by :plugin-host (docs/SPEC.md 12a, flutter_embed kind):
        // Flutter's own Maven repo for the `io.flutter:flutter_embedding_release`
        // artifact -- the Java-only embedding classes (FlutterEngine,
        // FlutterJNI, MethodChannel, ...) compiled straight into
        // :plugin-host's own APK, the same "small glue code ships in the
        // base APK" call made for PythonBridge's native glue. Confirmed
        // real and live (2026-09-26): storage.googleapis.com/download.flutter.io
        // is the exact repo Flutter's own Gradle plugin
        // (FlutterPlugin.kt) adds for this artifact. The much larger
        // Dart/Skia/Impeller engine binary (libflutter.so) is NEVER a
        // build-time dependency -- that is downloaded at runtime by
        // FlutterRuntimeManager from a different, official Flutter CDN
        // bucket (flutter_infra_release), verified against
        // plugin-host/src/main/assets/flutter-runtimes.json.
        maven { url = uri("https://storage.googleapis.com/download.flutter.io") }
        // Needed by :net-core (docs/SPEC.md 12a, "One download engine"): the aria2 download engine's AAR, released
        // by bi0shacker001/aria2-engine and put here, checked against net-core/aria2-engine.pin, by
        // build-scripts/fetch-aria2-engine.sh (CI runs it before Gradle). A local repository that holds only that group.
        exclusiveContent {
            forRepository { maven { url = uri(File(settingsDir, "net-core/aria2-engine/repo")) } }
            filter { includeGroup("io.github.bi0shacker001") }
        }
        // Needed by :app (docs/SPEC.md 7a, "Remote streaming is windowcast's library"): windowcast's Android library,
        // attached to each Droidtop/windowcast release and put here, checked against app/windowcast.pin, by
        // build-scripts/fetch-windowcast-lib.sh (CI runs it before Gradle). A local repository that holds only that group.
        exclusiveContent {
            forRepository { maven { url = uri(File(settingsDir, "app/windowcast-lib/repo")) } }
            filter { includeGroup("dev.windowcast") }
        }
    }
    // gradle/libs.versions.toml is picked up by convention -- do not also
    // declare it via versionCatalogs { create("libs") { from(...) } },
    // that double-registers it and fails with "you can only call 'from' a
    // single time" (caught building against a real Gradle 8.9 install).
}

rootProject.name = "droidtop"

// Application shell — hosts the default touch UI and wires the other modules together.
include(":app")
include(":net-core")

// Qubes-style host bridge: Android's ONLY privileged surface. Does not implement a
// compositor itself — the real desktop compositor (vendor/sway, headless-output build)
// runs *inside* the primary Linux container (see :runtime-linux-root / -noroot). This
// module just pulls rendered frames out of that container's virtual output(s) onto
// Android Surfaces (one per physical/virtual Display), and injects Android input
// (touch, gamepad, second-screen trackpad, lapdock peripherals) into the container as
// a virtual input device. Native (C/C++, JNI) + a thin Kotlin bridge.
include(":host-bridge")

// Shared interfaces: container lifecycle, the primary-container-vs-sibling-container
// distinction, display/output assignment, library entry model. Everything else depends
// on this; it depends on nothing else in this repo.
include(":runtime-common")

// Windows compatibility runtime (docs/SPEC.md 5b, 9): Winlator's runtime as GameNative
// ships it, lifted into droidtop with the GameNative pieces it uses. Wine runs as the
// app's own uid through linker64 against an ImageFs root, no root and no container.
include(":runtime-windows")

// Linux compatibility runtime, rooted path — built on DroidSpaces (vendor/droidspaces),
// namespace/cgroup containers. Adds a "primary container" bootstrap profile that installs
// and supervises the in-container compositor (vendor/sway) plus a base desktop environment;
// ordinary app/game containers are siblings that share its Wayland socket, matching
// distrobox's host-integration model rather than DroidSpaces' current per-container X11.
include(":runtime-linux-root")

// Linux compatibility runtime, non-root fallback — proot-based, no existing fork target,
// built new, following the Termux proot-distro / Box64Droid pattern. Same primary/sibling
// container model as :runtime-linux-root, different execution engine underneath.
include(":runtime-linux-noroot")

// Unified input seat: reads touch, gamepad-as-pointer, second-screen trackpad/keyboard,
// and lapdock peripheral events on the Android side, normalizes them, and hands them to
// :host-bridge for injection into the primary container's compositor.
include(":input-seat")
include(":input-mapping")

// Unified library/metadata layer (Playnite-style plugin model). Designed so native Android
// apps, Wine profiles, and Linux containers are equally first-class entries — this is the
// layer a future launcher shell (:shell-gamepad) will read from.
include(":library-core")

// The PC stores droidtop runs itself (docs/SPEC.md 7g, "Stores"): Epic, GOG,
// Amazon Games and itch.io, lifted out of GameNative behind
// library-core's StoreLibrary.
// :stores' Steam client is JavaSteam, built from source: droidtop's fork (vendor/javasteam, Droidtop/javasteam,
// branch droidtop, from GameNative's JavaSteam), which hands depot chunks, web files and Workshop files to droidtop's
// download engine (its Fetcher; docs/SPEC.md 12a, "Everything moves"). Its two modules stand in for the coordinates
// :stores names.
includeBuild("vendor/javasteam") {
    dependencySubstitution {
        substitute(module("io.github.joshuatam:javasteam")).using(project(":"))
        substitute(module("io.github.joshuatam:javasteam-depotdownloader")).using(project(":javasteam-depotdownloader"))
    }
}
include(":stores")

// The plugin host (docs/SPEC.md 12a): manifest validation, install/
// uninstall/enable/approve, and the isolated :pluginhost process that
// runs native_bundle plugin code, reached over its own AIDL binder
// contract (IPluginRuntime). No dependency on :library-core or :app --
// a plugin call site hands it plain values, never a database handle.
include(":plugin-host")

// The sandbox library's program-tree binding (docs/SPEC.md 5b, Droidtop/tracker#584): the broker listener Wine's
// processes attach to, with libsbx_child.so and sbx-exec. No dependency on droidtop's other modules.
include(":sandbox-host")

// Test-only certificate builder for the plugin, catalog and revocation signature tests.
include(":test-pki")

// Test-only fake host and conformance cases for plugins (docs/plugin-api.md 3 I2); plugin-host's own tests use it.
include(":plugin-host-testing")

// Test-only throwaway app the priv.packages rig check (app androidTest) installs and removes on a console.
include(":rigtest-throwaway")

// Secondary-display behaviour for EVERY mode, in one place: the single
// SECONDARY_HOME activity plus the mode registry that selects what it
// renders. Exists because shell-default's forked Launcher3 and the
// Handheld shell each had their own second-screen handling and competed
// for the same display -- see docs/SPEC.md section 4c. Depends only on
// :runtime-common, so no shell owns the type the other shells read.
include(":display")

// "Standard" shell: forked-in Murine Launcher (github.com/alesimula/Murine-launcher,
// itself a de-privileged, standalone-Gradle-buildable fork of AOSP Launcher3),
// stripped/patched for droidtop rather than kept as a passive vendor/ reference —
// its whole value is UI code we need to own and edit directly, unlike
// runtime-windows/runtime-linux-root's relationship to their vendor/ sources.
// See shell-default/README.md. Its own module graph (originally a separate
// root Gradle project) is included here rather than flattened, to keep future
// upstream syncing tractable.
include(":shell-default")

include(":IconLoader")
project(":IconLoader").projectDir = file("shell-default/iconloaderlib")

include(":Animation")
project(":Animation").projectDir = file("shell-default/animationlib")

include(":Shared")
project(":Shared").projectDir = file("shell-default/shared")

include(":WMShared")
project(":WMShared").projectDir = file("shell-default/wm_shared")

include(":msdl")
project(":msdl").projectDir = file("shell-default/msdllib")

include(":flags")
project(":flags").projectDir = file("shell-default/flagslib")

include(":HiddenApi")
project(":HiddenApi").projectDir = file("shell-default/hidden-api")

include(":SettingsLib-SettingsTheme")
project(":SettingsLib-SettingsTheme").projectDir = file("shell-default/SettingsLib/SettingsTheme")
include(":SettingsLib-DataStore")
project(":SettingsLib-DataStore").projectDir = file("shell-default/SettingsLib/DataStore")
include(":SettingsLib-Metadata")
project(":SettingsLib-Metadata").projectDir = file("shell-default/SettingsLib/Metadata")
include(":SettingsLib-Preference")
project(":SettingsLib-Preference").projectDir = file("shell-default/SettingsLib/Preference")
include(":SettingsLib-SliderPreference")
project(":SettingsLib-SliderPreference").projectDir = file("shell-default/SettingsLib/SliderPreference")
include(":SettingsLib-SelectorWithWidgetPreference")
project(":SettingsLib-SelectorWithWidgetPreference").projectDir = file("shell-default/SettingsLib/SelectorWithWidgetPreference")
include(":SettingsLib-ExpandablePreference")
project(":SettingsLib-ExpandablePreference").projectDir = file("shell-default/SettingsLib/ExpandablePreference")
include(":SettingsLib-SegmentedButtonPreference")
project(":SettingsLib-SegmentedButtonPreference").projectDir = file("shell-default/SettingsLib/SegmentedButtonPreference")
include(":SettingsLib-MainSwitchPreference")
project(":SettingsLib-MainSwitchPreference").projectDir = file("shell-default/SettingsLib/MainSwitchPreference")

// Upstream's compatLib (+ its 7 per-Android-version variants) and
// androidx-lib are not part of this fork: quickstep/recents-animation-only
// code that can't compile outside a real AOSP platform source tree,
// confirmed by a real compile attempt, and unreferenced by the launcher
// code. They are upstream's to consult, not kept here.
//
// systemUIPluginCore, unlike those, genuinely IS needed — it provides the
// base Plugin/PluginListener/ProvidesInterface classes every interface in
// shell-default/src_plugins/ extends, and Launcher.java/FloatingHeaderView.
// java/etc. reference directly. Wrongly excluded alongside compatLib on a
// first pass (grepped for the wrong package name); re-added once the real
// compile errors ("cannot find symbol: class Plugin") showed it was
// load-bearing after all.
include(":systemUIPluginCore")
project(":systemUIPluginCore").projectDir = file("shell-default/systemUIPluginCore")

// The Compose pieces every surface draws alike (docs/SPEC.md 7k, "Notices"):
// the notice card and its palette. Gaming, Desktop, the companion and
// Standard all draw them; Standard (the forked Launcher3, a separate module
// graph) cannot depend on :shell-gamepad, which is why this is its own module.
include(":ui-shared")

// Gaming mode's shell (docs/SPEC.md §7f): real ES-DE theme rendering,
// gamepad navigation, and the full library/settings surface.
include(":shell-gamepad")

// Second-screen persistent keyboard (docs/SPEC.md §4/§6) — forked from
// Hacker's Keyboard (Apache-2.0), not built from scratch. Ships as a real
// Android IME, and also as the second-screen keyboard hosted by :app as an
// ordinary window on the second screen (docs/SPEC.md §6c; see its own
// build.gradle.kts).
include(":input-keyboard")

// Desktop mode's Android-side half (docs/SPEC.md §2a): the cross-container
// task manager and frame passthrough for the primary container's compositor
// output (presented via :host-bridge). The taskbar and app launcher are
// container-side, not here.
include(":shell-desktop")
