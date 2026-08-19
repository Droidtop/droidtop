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

This layer must carry metadata (artwork, playtime, last-played) and a
uniform launch interface *now*, even though the only shell being built
initially (`:shell-default`) is a plain touch grid — because the point of
building it this way is that a later gamepad-console shell
(`:shell-gamepad`) is a new module reading the same data, not a
rearchitecture. `:shell-gamepad` stays optional and toggleable; it is never
the assumed default experience.

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
go-containerregistry` is Apache-2.0. `pc-helper` is a separate program, not
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
shell-default           → default touch UI; depends on library-core
shell-gamepad           → optional gamepad console UI; depends on library-core; build last

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

A local dev environment exists: a dedicated WSL2 distro (`droidtop-dev`),
entirely on non-OS storage, with Android SDK/NDK 27.0.12077973, Gradle 8.9,
Go, and the Meson/CMake/wayland-scanner toolchain installed. Primary CI is
GitHub Actions (not yet set up); this local environment is for exactly the
kind of hands-on de-risking §10/§11 call for.

First real build pass against it already found and fixed three bugs no
amount of reading would have caught: a duplicate version-catalog
registration in `settings.gradle.kts`, Kotlin 2.0's Compose Compiler plugin
being required-but-missing on every Compose module, and `default` being an
invalid Java package segment (`shell-default`'s package is now
`dev.droidtop.shell.standard`). All pure-Kotlin modules now build clean.
`host-bridge` and `runtime-remote-stream` both build correctly up to their
one real native dependency gap each (`libwayland-client`, OpenSSL — neither
cross-compiled for Android yet) and fail at exactly that point, confirming
those really are the next blockers, not a config mistake — see each
module's README for specifics.

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
