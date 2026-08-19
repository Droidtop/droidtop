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

// Default shell: normal touch/mouse-first library grid. This is what ships first.
include(":shell-default")

// Optional gamepad-console-style launcher shell. Stub only for now — depends on
// :library-core's plugin interface being stable before this gets built out.
include(":shell-gamepad")
