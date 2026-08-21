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
    }
    // gradle/libs.versions.toml is picked up by convention — do not also
    // declare it via versionCatalogs { create("libs") { from(...) } },
    // that double-registers it and fails with "you can only call 'from' a
    // single time" (caught building against a real Gradle 8.9 install).
}

rootProject.name = "droidtop"

// Application shell — hosts the default touch UI and wires the other modules together.
include(":app")

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

// Windows compatibility runtime — Wine + Box64 from vendor/gamenative, stripped of
// Winlator's own Android SurfaceView XServer. Runs as ordinary Linux processes inside
// a container (primary or sibling), using Wine's native Wayland driver against the
// primary container's compositor socket — same as running Wine on any Linux desktop.
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

// Remote PC game streaming (GameStream/Moonlight-protocol compatible, targeting Sunshine
// hosts). Vendors vendor/moonlight-common-c (GPL-3.0 — see docs/SPEC.md licensing notes)
// for host discovery, PIN pairing, app-list retrieval, and stream launch. Surfaces a remote
// host's configured apps as REMOTE_STREAM LibraryEntry items, same as any local one.
include(":runtime-remote-stream")

// Unified library/metadata layer (Playnite-style plugin model). Designed so native Android
// apps, Wine profiles, and Linux containers are equally first-class entries — this is the
// layer a future launcher shell (:shell-gamepad) will read from.
include(":library-core")

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

// compatLib (+ its 7 per-Android-version variants) and androidx-lib are
// deliberately NOT included — see shell-default/build.gradle for why
// (quickstep/recents-animation-only code that can't compile outside a real
// AOSP platform source tree, confirmed by a real compile attempt, and
// unreferenced by the actual launcher code). Source is still physically
// present under shell-default/ for reference.
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

// Optional gamepad-console-style launcher shell. Stub only for now — depends on
// :library-core's plugin interface being stable before this gets built out.
include(":shell-gamepad")

// Second-screen persistent keyboard (docs/SPEC.md §4/§6) — forked from
// Hacker's Keyboard (Apache-2.0), not built from scratch. Not wired up to
// the second screen / :input-seat yet; just made to compile as its own
// module so far.
include(":input-keyboard")

// Optional desktop-style shell: taskbar + start-menu chrome around the
// primary container's compositor output (presented via :host-bridge).
// Depends on :host-bridge for HostBridge/HostBridgeInput and :runtime-common
// for DisplayOutput.
include(":shell-desktop")
