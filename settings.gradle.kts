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
        // Needed by :runtime-windows once it compiles the whole
        // app.gamenative tree: gamenative pins a JavaSteam SNAPSHOT
        // (io.github.joshuatam:javasteam), published only here — the
        // same repository gamenative's own settings.gradle declares.
        maven { url = uri("https://central.sonatype.com/repository/maven-snapshots/") }
    }
    // gradle/libs.versions.toml is picked up by convention — do not also
    // declare it via versionCatalogs { create("libs") { from(...) } },
    // that double-registers it and fails with "you can only call 'from' a
    // single time" (caught building against a real Gradle 8.9 install).
    //
    // gamenative's own catalog, registered as a SECOND catalog ("gn")
    // rather than copied: :runtime-windows compiles the whole vendored
    // app.gamenative tree, and its dependency versions should track the
    // fork through the ordinary vendor sync, not a hand-maintained
    // duplicate list that drifts.
    versionCatalogs {
        create("gn") {
            from(files("vendor/gamenative/gradle/libs.versions.toml"))
            // One deliberate divergence from the fork's toml: the fork
            // pins Dagger 2.55, whose bundled (SHADED, under dagger.spi.
            // internal.shaded) kotlin-metadata reader tops out at Kotlin
            // 2.1 metadata and dies on droidtop's 2.2.21-compiled
            // classes at :app:hiltJavaCompileDebug. No resolutionStrategy
            // force can reach a shaded copy; the only real fix is a
            // Dagger whose reader knows 2.2, and 2.57 is the first such
            // release. Overridden here at the settings level so the
            // vendored toml itself stays tracked, not hand-edited.
            version("dagger-hilt", "2.57.2")
        }
    }
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

// Unified library/metadata layer (Playnite-style plugin model). Designed so native Android
// apps, Wine profiles, and Linux containers are equally first-class entries — this is the
// layer a future launcher shell (:shell-gamepad) will read from.
include(":library-core")

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

// Optional gamepad-console-style launcher shell — the Handheld shell.
// The best-developed module in the repo (~8,000 lines): real ES-DE theme
// rendering, gamepad navigation, and the full library/settings surface.
include(":shell-gamepad")

// Second-screen persistent keyboard (docs/SPEC.md §4/§6) — forked from
// Hacker's Keyboard (Apache-2.0), not built from scratch. Shipping
// (~17,600 lines) as a real Android IME, surfaced as an optional step in
// :app's own onboarding (OnboardingActivity's KeyboardStep). The
// second-screen persistent surface / :input-seat integration this module
// was originally forked in for is still TODO — see its own
// build.gradle.kts.
include(":input-keyboard")

// Optional desktop-style shell: taskbar + start-menu chrome around the
// primary container's compositor output (presented via :host-bridge).
// Depends on :host-bridge for HostBridge/HostBridgeInput and :runtime-common
// for DisplayOutput.
include(":shell-desktop")
