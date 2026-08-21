# droidtop — Architecture Specification

Status: pre-implementation. This document is the source of truth for the
design; module READMEs point back here rather than restating it.

## 1. Product vision

Turn an Android handheld (initial target: Retroid Pocket 5 + its Dual-Screen
Add-On, designed generically enough to run on any Android device) into a
real desktop/laptop-class machine — "PC-in-a-box." One shared desktop, not a
folder of separate emulator-style apps:

- Windows software runs via Wine/Box64 binary translation (no real
  virtualization is feasible on this class of hardware — see §5).
- Linux software runs via containers, using the same host-integration model
  distrobox uses on real Linux: containers share the desktop's display and
  audio sockets rather than getting their own isolated ones.
- Every running window defaults to appearing on one merged desktop. Any
  individual window can *optionally* be given its own display (the device's
  second screen, or an external lapdock monitor) without relaunching it —
  this is an opt-in per-window placement, never the default.
- Paired with a lapdock, the device should feel like using a Linux PC with
  the above compatibility layers, not like juggling separate apps.
- A gamepad-driven console-style launcher is a **future, optional, toggleable
  UI** on top of the same library data — not the assumed default experience,
  and not something to build before the default touch UI exists. But the
  data model underneath must be launcher-ready from the start (see §7),
  because retrofitting that later would mean a rearchitecture, not a new
  module.

## 2. Core architectural decision: Qubes-style split, not a hand-rolled compositor

The single biggest design decision in this project: **Android does not host
the desktop compositor.** Early drafts of this plan had Android's native
code implementing a shared Wayland server directly (via wlroots) that Wine
and Linux processes would connect to. That's a large amount of novel,
security-sensitive compositor code to write from scratch on a platform
(Android NDK) that isn't designed for it.

Instead, mirror Qubes OS's dom0/AppVM split:

- **The primary container** is a real Linux container running a real
  desktop compositor — [vendor/sway](../vendor/sway) (wlroots-based, MIT),
  built with wlroots' headless backend so its outputs are virtual and
  capturable rather than tied to real hardware. This container *is* the
  desktop. Ordinary window management, multi-output configuration, etc. are
  all sway's problem, not ours — we're consuming a mature project instead of
  building a compositor.
- **Android's app code (`:host-bridge`) is a thin, privileged bridge only**
  — analogous to dom0's narrow GUI-daemon role in Qubes. It is a Wayland
  *client* of the primary container's compositor, doing exactly two things:
  pulling frames off the container's headless output(s) via
  `wlr-screencopy-unstable-v1` onto Android `Surface`s (one per physical/
  virtual display), and injecting normalized input events back in via
  `wlr-virtual-pointer-v1`/`virtual-keyboard-v1`. It implements no window
  management, no compositing, no protocol server logic.
- **Everything else is a sibling container** — a Linux distro (Ubuntu,
  Debian, Alpine, whatever the user picks) or a Wine prefix, each bind-
  mounting the primary container's `WAYLAND_DISPLAY` and PulseAudio socket
  in, exactly like distrobox does on desktop Linux. To the compositor, a
  sibling's window and a window from an app running inside the primary
  container itself are indistinguishable — same protocol, same socket.

This is also why Wine needs no Android-specific display code at all: a Wine
prefix (`:runtime-windows`) just runs as an ordinary Linux process inside a
container (primary or sibling) using Wine's native Wayland driver against
that container's socket — the same way Wine runs on any Linux desktop.
Winlator/GameNative's own Android SurfaceView XServer is explicitly *not*
ported; it's superseded entirely by this model.

## 3. Containers

One `ContainerRuntime` interface (`runtime-common`), two interchangeable
backends selected automatically by root availability:

| | Rooted (`:runtime-linux-root`) | No root (`:runtime-linux-noroot`) |
|---|---|---|
| Fork base | [vendor/droidspaces](../vendor/droidspaces) (GPL-3.0) | none — new code |
| Isolation | Real kernel namespaces + cgroups | ptrace-based (proot), no true isolation |
| Requires | KernelSU / APatch / Magisk (Daemon Mode) | nothing |
| Pattern | DroidSpaces' own LXC-like model | Termux `proot-distro` / Box64Droid pattern |

Both expose the same `ContainerRole` split — exactly one `PRIMARY` container
per device (boots the compositor + base desktop), everything else `SIBLING`
— and both must stop doing what their upstream/reference patterns do by
default (DroidSpaces auto-launching a private Termux:X11 per container;
proot-distro/Box64Droid having no shared-socket concept at all) in favor of
the socket-sharing model in §2.

Rootfs images for both backends are OCI image references
(`docker.io/...`, `ghcr.io/...`), pulled and unpacked via
[vendor/crane](../vendor/go-containerregistry) — no Docker daemon, just an
OCI registry client fetching layer blobs. This applies to the primary
container's base+sway image and to any sibling's distro image alike, and
means users aren't limited to a bespoke image format we maintain — any OCI
image works. Pulled layers are cached on-device by digest via `ImageCache`,
**as an explicit user-facing setting** (on/off, size cap, clear-cache
action) rather than an invisible always-on cache, since it trades storage
for avoiding re-downloads when containers are recreated.

## 4. Display

- One `DisplayOutput` per Android `Display` the device currently has: the
  built-in screen, the Retroid-style second screen (via Android's
  `DisplayManager`/`Presentation` API — the standard, currently-supported
  mechanism; verify the actual accessory enumerates as a normal secondary
  `Display` before building against it), or an external lapdock monitor over
  USB-C DisplayPort alt mode (also a standard secondary `Display` from
  Android's point of view).
- Each `DisplayOutput` maps to one headless output inside the primary
  container's sway instance.
- **Default**: every window is placed on the primary screen's output —
  `WindowPlacement.merged()` — one shared desktop, nothing hidden away.
- **Opt-in**: any window can be reassigned to a different `DisplayOutput` at
  runtime (fullscreen or windowed) without touching the process/container
  that owns it — this is sway reassigning a surface to a different headless
  output, a compositor-side operation, not something `:host-bridge` or the
  owning app needs to know about.
- **Configurable per-output role, KDE KScreen-modeled**: which screen shows
  what isn't hardcoded — the user configures, per `DisplayOutput`, whether
  it mirrors the primary, presents an independent `SecondaryDisplayLauncher`
  instance (see below), or is a dedicated compositor output. Named/labeled
  identity per output ("the Retroid's second screen," "the lapdock
  monitor") is a first-class, persisted setting, not just an enumerated
  `Display` id — matching how KDE's System Settings → Display & Monitor
  lets a user name and assign roles to each physical output rather than
  just listing them by number. This configuration lives in the Standard
  shell's own settings menu (`com.android.launcher3.settings.
  SettingsActivity`, forked in with `:shell-default` — see §9); droidtop
  does not have or want a separate standalone settings app (§7).
- **Real hook already exists, not hypothetical**: AOSP Launcher3 (and so
  `:shell-default`, its fork) already ships
  `com.android.launcher3.secondarydisplay.SecondaryDisplayLauncher` — a
  `SECONDARY_HOME`-category `Activity`, Android's own standard mechanism
  for a launcher to provide a home screen on a secondary `Display`. The
  droidtop-specific multi-display patch work is wiring this existing
  Activity to our `DisplayOutput`/mirror-vs-independent configuration
  model, not building secondary-display launcher support from nothing.
- **Dual-screen input/output split** (Retroid-style second-screen
  accessory): the built-in screen is the primary visual output; the second
  screen defaults to a trackpad/keyboard *input* surface for whatever's
  showing on the primary, per §6's `AbsoluteTouchContext`/
  `RelativeTouchContext` split — not automatically a second desktop. Making
  it an independent output (its own `SecondaryDisplayLauncher` or mirrored
  desktop) is one of the per-output roles above, opt-in like everything
  else in this section.
- **General framing**: droidtop's display/shell/settings model takes KDE
  Plasma as its broader reference point, not just for KScreen specifically
  — the goal (§1) is a real general-purpose compute device, and KDE is the
  most complete existing example of "one coherent desktop shell with
  modular, discoverable settings" to learn conventions from as more of
  this gets built out (workspace switching, per-app window rules, etc.),
  not a component to fork code from.

## 5. Windows compatibility — no real virtualization

Confirmed via research, treat as settled: genuine hardware-accelerated x86
virtualization for Windows is not feasible on Snapdragon 865-class hardware
(the Retroid Pocket 5's chip). KVM-backed ARM virtualization (Gunyah)
doesn't ship until Snapdragon 8 Gen 2+; even where it exists it only
accelerates ARM64 guests, not x86 — running Windows would still mean QEMU
software CPU emulation, which is worse than Box64/Wine translation. Google's
AVF/pKVM is Pixel-only in practice and built for paravirtualized Linux
guests, not general-purpose Windows VMs.

**Conclusion: Wine + Box64 binary translation (`:runtime-windows`, forked
from [vendor/gamenative](../vendor/gamenative)) is the only Windows path.**
Revisit hardware virtualization only if targeting Snapdragon 8 Gen 2+/
Dimensity 9000+ devices specifically, and even then only as a path to
running a *Linux* guest, not Windows.

## 6. Input

One Wayland seat (`:input-seat`), fed from every physical source: touch,
gamepad-as-pointer, the second-screen trackpad/keyboard, or a lapdock's
physical peripherals. All normalized before reaching `:host-bridge`, so the
compositor only ever sees one logical pointer and keyboard.

- Second-screen trackpad interaction model: borrow Moonlight Android's
  `AbsoluteTouchContext` (primary screen = absolute cursor position) /
  `RelativeTouchContext` (trackpad = relative deltas) split.
- Keyboard forwarding: reference KDE Connect Android's Remote Input plugin.
- **Do not assume Winlator/GameNative's input code is a safe base** —
  Winlator has a known, open, acknowledged gap in native/Bluetooth mouse
  pointer capture (issue #1555). This needs real design and testing effort,
  not inherited code.

## 7. Library / launcher-readiness

`:library-core` models every runnable thing — native Android app, Wine
profile, Linux-container app — as an equal `LibraryEntry` from a
`LibraryProvider`, aggregated by a `Library`. Modeled on Playnite (no direct
Android equivalent exists; this is a real gap being filled, not a fork).

This layer carries metadata (artwork, playtime, last-played) and a uniform
launch interface, read by all three shells alike — `dev.droidtop.app.
ShellPreference` (in `:app`) is the actual user-facing switch between them,
not a build-time choice:

- **`:shell-default` ("Standard")** — not a from-scratch touch grid; a real
  fork of [Murine Launcher](https://github.com/alesimula/Murine-launcher)
  (Apache-2.0, itself an already-de-privileged, standalone-Gradle-buildable
  fork of AOSP Launcher3). This is also what renders when droidtop is
  chosen as the device's actual Android home screen
  (`com.android.launcher3.Launcher`, `SECONDARY_HOME`/`HOME` intent
  filters). Brought in wholesale — ~20 of Murine's own sub-modules
  (IconLoader, SettingsLib-\*, etc.) as real Gradle modules under
  `shell-default/`, not kept as a passive `vendor/` reference the way
  `runtime-windows`/`runtime-linux-root` relate to their `vendor/` sources:
  a launcher's whole value is its UI code, which needs to be owned and
  edited directly. The one deliberately-excluded piece is quickstep/
  recents-animation support (`compatLib` + its per-Android-version
  variants) — it needs system-signature permissions no non-privileged app
  can hold, confirmed by real compile errors (local reimplementations of
  AOSP's internal Transitions-framework classes needing package-private
  `android.annotation` visibility only available inside a real platform
  source tree compile), not assumed upfront. Source stays in the repo for
  reference; just not part of the active build.
  **droidtop has no separate settings app** — the Standard shell's own
  forked-in settings menu (`com.android.launcher3.settings.
  SettingsActivity`) is where display configuration (§4), shell
  preferences, and everything else configurable lives, matching KDE's
  "one coherent shell, modular settings" model rather than a
  bolted-on companion app.
- **`:shell-desktop` ("Desktop")** — taskbar + start menu wrapped around
  the primary container's compositor output via `:host-bridge`'s
  `HostBridge`. Real UI chrome; the live desktop connection itself is
  blocked on `DesktopSessionService` (still a TODO — see `:app`).
- **`:shell-gamepad` ("Handheld")** — full-screen, D-pad-navigable, reading
  the same `Library`; optional and toggleable, never the assumed default
  experience.

## 7a. Remote PC streaming (GameStream/Moonlight/Sunshine)

A remote gaming PC's library should show up in the same unified library as
local Windows/Linux apps — same `LibraryEntry` model, a new
`LibraryEntryKind.REMOTE_STREAM`. `:runtime-remote-stream` vendors
[vendor/moonlight-common-c](../vendor/moonlight-common-c) (GPL-3.0, does not
change the project's overall GPL-3.0 position — see §8) for the protocol
work, built against its exact pinned ENet fork (`vendor/moonlight-common-c/
enet`) — moonlight-common-c's own README states a generic ENet breaks
connectivity to recent GFE/Sunshine hosts, so this is not optional.

Two things are **not** provided by moonlight-common-c and had to be
designed here:
- **LAN host discovery** — ported from a platform client's approach
  (moonlight-android is the reference), not part of the vendored library.
- **Pairing** — the classic flow needs the PIN typed into the host's web UI,
  but Sunshine's own REST API accepts `POST /api/pin` directly, letting
  pairing complete entirely from the DroidTop app's side on Sunshine hosts
  specifically.

### PC-side helper (`pc-helper/`)

A separate Go service (not an Android module — runs natively on the gaming
PC) with two capabilities of deliberately different confidence, confirmed
via research rather than assumed:

- **Auto-registering a newly-installed game with Sunshine — solid.**
  Sunshine's `POST /api/apps` REST endpoint does exactly this; no manual
  `apps.json` editing. `pc-helper/internal/sunshine` wraps it directly.
- **Remotely triggering a Steam install — genuinely limited, state this
  honestly in product UI, don't oversell it.** `steam://install/<appid>`
  requires Steam already running and the user already logged in on that PC,
  and surfaces its own UI (not headless). SteamCmd can be scripted
  unattended, but only after a one-time interactive Steam Guard login on
  that specific machine, and getting the result recognized by the normal
  Steam client requires replicating its `steamapps/common/` layout, which
  isn't SteamCmd's default behavior. **There is no known mechanism for a
  true zero-touch first-time remote install** — every avenue researched
  requires either the user being at the PC or a one-time manual setup step
  on it. Design the feature around that constraint rather than promising
  "tap install on your phone" as fully automatic.

`pc-helper` also has no pairing/auth designed yet for its own local API
(the endpoint the Android app calls over LAN) — since every endpoint does
something consequential, this needs the same kind of one-time pairing-code
exchange as Sunshine itself before it can safely listen on anything but
localhost. See `pc-helper/README.md`.

## 8. Licensing

`vendor/gamenative`, `vendor/droidspaces`, and `vendor/moonlight-common-c`
are GPL-3.0. `vendor/winlator-upstream` (kept only as a diff reference) is
LGPL-2.1. `vendor/sway`, `vendor/wlroots` (protocol definitions only — not
compiled for Android, see `:host-bridge`), and `vendor/wayland`/`vendor/
wayland-protocols` (same — codegen/headers only) are MIT. `vendor/
go-containerregistry` is Apache-2.0. `shell-default`'s fork source (Murine
Launcher, itself derived from AOSP Launcher3) is also Apache-2.0. `pc-helper` is a separate program, not
statically linked into the Android app — its own license can be chosen
independently (default assumption: also GPL-3.0 for consistency, revisit if
that's not actually desired for a standalone PC service).

Combining GPL-3.0 sources with the rest is license-compatible, but it means
**the combined project must be distributed under GPL-3.0** — no closed-
source distribution of the merged app. Confirm this is acceptable before any
implementation work beyond scaffolding.

## 9. Module map

See [settings.gradle.kts](../settings.gradle.kts) for the authoritative list
and dependency rationale; each module also has its own README. Summary:

```
app                    → depends on everything; owns DesktopSessionService + MainActivity
host-bridge             → native Wayland client + JNI; frame passthrough + input injection
runtime-common          → shared types (Container, DisplayOutput, RootfsImage); no deps
runtime-windows         → Wine/Box64 (fork: vendor/gamenative), no display code of its own
runtime-linux-root      → DroidSpaces fork (vendor/droidspaces), namespaces/cgroups, needs root
runtime-linux-noroot    → proot-based, new code, no root required
input-seat              → unified seat; depends on host-bridge
runtime-remote-stream    → Moonlight/GameStream client (fork: vendor/moonlight-common-c)
library-core            → Playnite-style unified library/metadata; depends on runtime-common
shell-default           → "Standard" shell: forked-in Murine Launcher (real AOSP
                          Launcher3-derived UI, not from-scratch); depends on
                          library-core, host-bridge
shell-desktop           → "Desktop" shell: taskbar/start-menu chrome around the
                          shared desktop; depends on library-core, host-bridge,
                          runtime-common
shell-gamepad           → "Handheld" shell: optional gamepad console UI; depends
                          on library-core

pc-helper/               → separate Go program, runs on the remote gaming PC, not an
                            Android module — Sunshine REST API client + (limited) Steam
                            install trigger; see §7a
```

## 10. Suggested build order

1. **Prototype `:host-bridge` first**, before investing in the runtime
   modules — confirm wlroots' headless backend + `wlr-screencopy` can
   actually deliver frames to an Android `Surface` at acceptable latency,
   and that virtual-pointer/virtual-keyboard injection works round-trip.
   This is the one piece nothing in the prior-art research directly proves;
   everything else in the plan depends on it working.
2. `runtime-common` interfaces (already scaffolded) + a minimal
   `RootfsPuller` on `vendor/crane`, proven against one OCI image pull.
3. `runtime-linux-root`'s primary-container bootstrap (DroidSpaces + sway),
   since it's the backend with real prior art to fork from.
4. `runtime-windows`, once a container can already present a desktop —
   strip Winlator's XServer, confirm Wine's Wayland driver against sway.
5. `runtime-linux-noroot` — the largest genuinely-new piece; can proceed in
   parallel with 3-4 once the shared container contract is stable.
6. `input-seat` (second-screen trackpad/keyboard), in parallel with 3-5.
7. `library-core` + `shell-default` — first real end-to-end usable app.
8. `shell-gamepad` — last, deliberately, and only once `library-core` has a
   working `LibraryProvider` to build a real UI against.

## 10a. Build environment

Two build environments exist and are both real, not aspirational:

- **Local**: a dedicated WSL2 distro (`droidtop-dev`), entirely on non-OS
  storage, with Android SDK/NDK 27.0.12077973, Gradle 8.9, Go, and the
  Meson/CMake/autotools/musl-cross toolchains needed by
  `build-scripts/build-vendor-deps.sh`.
- **CI**: GitHub Actions (`.github/workflows/android-build.yml`), mirroring
  the local setup, uploading a debug APK artifact on every push to `main`.
  Getting this green took six distinct, real CI-only bugs (missing
  `libltdl-dev`, an `ANDROID_HOME`/`ANDROID_SDK_ROOT` conflict with the
  runner's preinstalled SDK, `ANDROID_DEPS_PREFIX` never actually reaching
  CMake, and a multi-attempt apt-lock hang that needed real diagnostics —
  not more guessing — to actually fix) — see the workflow file's own
  comments for the specifics of each.

Every module now builds and links for real:
- `:runtime-remote-stream` — mbedTLS (chosen over OpenSSL: CMake-native,
  cross-compiles through the same NDK toolchain file Gradle already passes
  in) + moonlight-common-c + its pinned ENet fork, all build end to end.
  JNI entry points still unimplemented.
- `:host-bridge` — cross-compiled `libffi` + `libwayland-client` (Meson, a
  native-scanner-then-cross-library two-step approach), confirmed linked
  for real (`readelf -d` shows `NEEDED libwayland-client.so`). Frame
  passthrough (`wlr-screencopy` capture loop → `ANativeWindow`) and input
  injection (virtual pointer/keyboard, with a statically embedded XKB
  keymap — see that module's README for why) are both implemented, not
  just scaffolded. Unverified against a live compositor (none exists yet
  to connect to).
- `:runtime-linux-root` — cross-compiles the `droidspaces` binary itself (a
  single static musl executable, ~430KB, genuinely simple compared to the
  above: no shared-library deps at all). `DroidSpacesRuntime` drives it as
  a subprocess (`su -c`, matching how droidspaces is actually designed to
  be used — a CLI tool, not a library), with the primary/sibling
  Wayland-socket-sharing bind-mount design from §3 implemented against
  droidspaces' real, documented `.config` format. Blocked on
  `RootfsPuller` having no implementation yet (can't pull an OCI image) and
  on no primary-container image (sway pre-installed) existing to pull.
  Unverified against a real device — no rooted hardware in this
  environment.

The APK is a single fat build covering both `arm64-v8a` (real hardware —
Retroid Pocket 5 and similar) and `x86_64` (emulators/x86 devices), rather
than separate per-ABI builds — one file, works on either. Every
cross-compiled artifact (libffi, libwayland-client, droidspaces) is built
twice by `build-scripts/build-vendor-deps.sh` (once per ABI) and
`DroidSpacesBinary` resolves which asset to extract at runtime against the
device's actual primary ABI (`Build.SUPPORTED_ABIS`), not a hardcoded one.
Verified: a real built APK contains distinct `lib/arm64-v8a/` and
`lib/x86_64/` native libraries plus both `droidspaces-arm64-v8a` and
`droidspaces-x86_64` assets (confirmed via `unzip -l`, not assumed).

## 11. Open risks to verify hands-on, not assume

- Whether sway's headless backend + `wlr-screencopy` performs well enough
  for gaming-relevant latency/framerate on Android hardware — untested by
  anyone as far as research surfaced; this is genuinely novel usage.
- Whether Wine's native Wayland driver (as opposed to Xwayland) is solid
  enough to depend on; Xwayland-inside-the-container is the fallback if not.
- Exact mechanism the Retroid Dual-Screen Add-On uses to expose its panel to
  Android (assumed to be a standard secondary `Display`, not confirmed
  against Retroid-specific documentation).
- Whether DroidSpaces' namespace/cgroup model tolerates two containers
  (primary + sibling) sharing a Wayland socket via bind mount cleanly, or
  whether that needs patching in DroidSpaces itself.
