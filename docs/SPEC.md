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
- **droidtop doesn't have to be *the* launcher, but it isn't a backend
  service either.** Being a real Android home screen (§7) is one way to
  use it, not the only one — someone who uses ES-DE or another dedicated
  emulation frontend should still be able to have droidtop's library stay in sync
  with what ES-DE knows about, via the bidirectional data-level import/
  export in §7b. **droidtop does not do emulation itself, and does not
  expose a launch/execution API other apps call into** — the sync
  relationship is data only (library entries, metadata, the platform
  taxonomy §7b settles on), not droidtop running things on another app's
  behalf or vice versa. The one real cross-launcher interop point: if the
  user's actual home screen is a *different* launcher and they open
  droidtop from there, it should default to the Desktop shell (not
  Standard, which doesn't make sense when droidtop isn't the home
  screen) — configurable in settings like everything else in §7b.

## 2. Core architectural decision: Qubes-style split, not a hand-rolled compositor

The single biggest design decision in this project: **Android does not host
the desktop compositor.** Early drafts of this plan had Android's native
code implementing a shared Wayland server directly (via wlroots) that Wine
and Linux processes would connect to. That's a large amount of novel,
security-sensitive compositor code to write from scratch on a platform
(Android NDK) that isn't designed for it.

Instead, mirror Qubes OS's dom0/AppVM split:

- **The primary container** is a real Linux container running a real
  desktop compositor, built with wlroots' headless backend so its outputs
  are virtual and capturable rather than tied to real hardware. This
  container *is* the desktop. Ordinary window management, multi-output
  configuration, etc. are the compositor's problem, not ours — we're
  consuming a mature project instead of building one.
  **Which compositor, and its floating-vs-tiling behavior, is a user
  config choice, not something droidtop hardcodes** — consistent with
  §2a's "we don't force what's in the container" principle. [vendor/sway]
  (../vendor/sway) (wlroots-based, MIT) is one reasonable preset — its
  default tiling behavior is real *policy* sitting on top of wlroots, not
  something inherent to what droidtop actually needs (that's wlroots'
  headless backend + the wlr-screencopy/virtual-pointer/virtual-keyboard
  protocols, which any wlroots compositor exposes access to) — and sway
  itself can be configured floating-only (`floating enable` catch-all
  rules) for a user who wants sway specifically but not its tiling.
  [labwc](https://github.com/labwc/labwc) (wlroots-based, GPL-2.0) is a
  second preset worth offering: a window-*stacking* compositor explicitly
  modeled on Openbox, ordinary floating windows with no tiling policy to
  configure around at all. Neither is vendored as a submodule the way
  sway's protocol headers/Android-side deps are — both run as ordinary
  packages inside the primary container's own Linux userland (whatever
  base distro's package manager), not something droidtop cross-compiles.
  **Not yet independently verified**: labwc's headless-backend support
  specifically — confirmed to be a real, actively-maintained wlroots
  stacking compositor, but headless-backend behavior wasn't confirmed by
  reading its actual backend-selection code, only inferred from being
  wlroots-based generally.
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

## 2a. Desktop shell architecture: launching, task management, native apps

Split placement, corrected after an initial pass that put everything on the
Android side — that broke the actual intent:

- **The taskbar + app launcher are container-side** — a real Wayland client
  running *inside* the primary container alongside the compositor, a peer
  to every Wine/Linux window on the exact same desktop (this is the actual
  Qubes parallel: dom0 runs a real desktop environment that AppVM windows
  integrate into as ordinary windows — droidtop's equivalent of "dom0's
  desktop environment" is this in-container launcher, not host-bridge).
  It shows an **injected list of applications** — `:library-core`'s
  `Library` contents, pushed in from the Android side by some channel
  (exact mechanism still open: a bind-mounted file the launcher
  watches for changes is the simplest first cut, not yet designed in
  detail) — and rendered inside the container, not composited by Android.
- **The task manager is Android-side** — host-bridge's own privileged
  position (able to see across the primary *and* every sibling, which no
  single container's own namespace can) is what makes a real cross-
  container task manager possible at all; this part stays as originally
  reasoned.
- **A helper process is what actually launches things**, bridging the two:
  when the user picks something in the container-side launcher, it asks
  the helper process to launch it; the helper is what execs the target
  process inside whichever container (primary or sibling) actually owns
  it — injected at launch time (bind-mount + exec via the container
  runtime's existing primitives, e.g. droidspaces' own exec support), not
  baked into any image. Its window then appears on the shared desktop
  through the same socket-sharing §2 already describes.
- **OCI images stay stock, never customized.** No droidtop-specific panel,
  launcher, or agent gets baked into a sibling's (or even the primary's)
  rootfs image — a plain `docker.io/library/debian:bookworm` should work
  unmodified; the in-container launcher and helper process are both
  injected at runtime, not part of any image. Whatever compositor/WM the
  *primary* container runs (sway is the current default — see §2) is
  itself meant to be swappable by a user who wants a different one;
  nothing in this design should assume sway specifically beyond "some
  wlroots-based compositor with a headless backend and wlr-screencopy/
  virtual-pointer/virtual-keyboard support."
- **Siblings are user-configurable, distrobox-style** — the user picks
  whichever distro they want per sibling (not a fixed droidtop-chosen
  image), and each sibling's actual distrobox-equivalent configuration
  (which host folders/data get mapped or mounted in, beyond the Wayland-
  socket/PulseAudio sharing §2/§3 already describe) is itself
  user-configurable per container, matching real distrobox's own
  `--volume`-style semantics rather than a fixed set of mounts.
- **Native Android apps join the same desktop via freeform windowing**
  (`android:resizeableActivity` + `ActivityOptions.setLaunchWindowingMode(
  WINDOWING_MODE_FREEFORM)`, the platform's real large-screen/desktop-mode
  API) — resizable, movable windows sitting alongside Wine/Linux windows
  in the same Desktop shell, not a separate "Android apps" mode.

Nothing in this section is implemented yet — the in-container launcher
doesn't exist as software, the injection channel isn't designed in detail,
and the helper process is a concept, not code.

**Chrome theming (decided 2026-08-30)**: droidtop's own Compose chrome
(Onboarding, Desktop shell panels, Console systems, etc.) follows the
system dark/light setting through one shared Material theme
(`app/.../ui/DroidtopTheme.kt`) — screens take colors from
`MaterialTheme.colorScheme` tokens, never literals (the previous state:
every screen hardcoded its palette, and no light mode existed at all).
Two deliberate exceptions stay always-dark regardless of the system
setting: surfaces living inside the Handheld shell's world (Console
systems opens from Handheld's Settings tab and matches its plain-black
ground) and ambient second-screen companion surfaces; ES-DE-themed
Handheld views take every color from the active ES-DE theme (§7f) and
are outside Material theming entirely.

## 2b. Desktop mode gets the library context, and Android apps as windows (directed 2026-09-01)

Desktop mode currently has a container and a shell and no library. The
whole gamenative/Wine context that Handheld now reaches through
`PcLibrary` — store library managers, installed games, owned-but-not-
installed titles, and the Wine container shortcuts — belongs in Desktop
too, as ordinary desktop entries.

Not a second implementation: `PcLibrary` already returns a
source-agnostic list (§7g) and `ContainerManager.loadShortcuts()` already
enumerates Wine-prefix shortcuts. Desktop's task manager and launcher
surface should read the same `LibraryEntry` stream `:shell-gamepad`
reads, differing only in presentation. Same rule as the secondary display
(§4c): one mechanism, the active mode selects what it looks like.

Two things this needs that Handheld did not:

- **Shortcuts as first-class desktop objects.** A Wine shortcut has an
  icon, a working directory and an executable — everything a `.desktop`
  entry has. Desktop should place them the way a Linux desktop does,
  rather than treating them as rows in a game list.
- **Windowed launching.** Handheld launches fullscreen to a display;
  Desktop launches into a window on the shared desktop
  (`WindowPlacement`, §4), which is a different launch path through the
  same entry.

### Emulators, and Android apps as windows

Directed as the same problem: droidtop should be able to wire an ANDROID
application in as a window on the desktop — an emulator being the
motivating case, since a native Android emulator is often the best way to
run a console game and there is no reason it should be unavailable in
Desktop mode just because it is not a Linux binary.

This is genuinely harder than the rest of this section and is **future
work, not scheduled here**. An Android Activity renders to an Android
`Display`, not to the container's Wayland compositor, so "as a window"
means either presenting that Activity onto a virtual display whose output
is composited into the desktop, or the reverse — hosting the desktop's
compositor output inside Android's window manager. `:host-bridge` already
does the second direction for the container (wlr-screencopy into an
Android `Surface`); the first direction, an Android app's own surface
appearing as a window INSIDE the container's desktop, has no existing
path in this repo and needs real design before any estimate is honest.

Recording it so the library and launch layers are not built in a way that
forecloses it: entries carry their `LibraryEntryKind` all the way to
launch, and nothing in Desktop's launch path should assume "a window
means a Linux process."

## 3. Containers

One `ContainerRuntime` interface (`runtime-common`), two interchangeable
backends selected automatically by root availability:

| | Rooted (`:runtime-linux-root`) | No root (`:runtime-linux-noroot`) |
|---|---|---|
| Fork base | [vendor/droidspaces](../vendor/droidspaces) (GPL-3.0) | [vendor/gamenative](../vendor/gamenative)'s own `DefaultProotContainerBackend.java` + bundled native proot (`libproot.so`/`libproot-loader.so`) |
| Isolation | Real kernel namespaces + cgroups | ptrace-based (proot), no true isolation |
| Requires | KernelSU / APatch / Magisk (Daemon Mode) | nothing |
| Pattern | DroidSpaces' own LXC-like model | gamenative's own real, working proot backend — port/adapt, not design from nothing |

`ProotRuntime` (`runtime-linux-noroot`) is not a from-scratch design: gamenative-tux
already ships a real, working ptrace-based container backend
(`app/src/main/java/com/winlator/linux/DefaultProotContainerBackend.java`,
plus its vendored native proot binaries under `app/src/legacy/jniLibs/`)
that `:runtime-windows` is already forking from for Wine support — the
same porting relationship extends to `ProotRuntime` itself rather than
writing a second, independent proot integration. gamenative-tux's `app`
module is `com.android.application`, not a library (same constraint hit
porting the enginehost KiriKiri plugin — see HANDOFF.md), so this is a
source port into `runtime-linux-noroot`, not a Gradle `project(...)`
dependency.

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

## 3a. Image index — populated live, not a pinned/prepopulated catalog

Picking an image (primary container's base+compositor, or a sibling's
distro) shouldn't force a user to already know an OCI reference by heart,
and it shouldn't be a hand-maintained list that goes stale the moment a
distro cuts a new release. **droidtop does not bake specific versions or
"verified" claims into a bundled manifest.** What it bundles is the
minimum unavoidable seed: a short list of *known OCI repositories* worth
showing (`KnownImageRepository` in `runtime-common`, backed by
`known-image-repositories.json`) — repository name + real metadata
(desktop environment, compositor family, headless-support verdict,
official-vs-third-party source, arm64 availability), deliberately with
**no version/tag baked in**. There is no OCI Distribution API for
"discover every distro image that exists" — `crane ls <repo>` needs an
already-known repository name — so some seed list is unavoidable, but it
stops at "which repositories," never "which versions."

- **Populated at runtime via `ImageCatalogResolver`**: for each known
  repository, `CraneImageCatalogResolver` (`runtime-linux-root`, built on
  the same [vendor/crane](../vendor/go-containerregistry) binary
  `CraneRootfsPuller` already uses) calls `crane ls` to list every tag
  currently published, then `crane digest` to resolve whichever tag gets
  picked to its immutable digest — real registry calls, not a cached
  snapshot. This is what makes the image-selection UI (§7c) sortable and
  filterable by live data (which versions exist right now, for real)
  instead of by whatever was true when droidtop last shipped.
- **Docker Hub (`docker.io`) is the default registry** a `KnownImageRepository`
  resolves against when it doesn't specify one — matches how `docker
  pull`/most tooling already behaves. A few entries need a different
  registry explicitly (e.g. Void Linux's current containers are at
  `ghcr.io/void-linux/void-*` — Docker Hub's own `voidlinux/voidlinux` is
  stale, unmaintained for years) — those set `registry` explicitly and
  must explain why via `notes`.
- **Real research findings baked into the seed metadata, not assumed**:
  wlroots-based compositors (sway, labwc) ship a headless backend as core
  infrastructure (`WLR_BACKENDS=headless`) — well-documented, and already
  droidtop's own compositor choice (§2). Hyprland forked off wlroots onto
  its own Aquamarine backend in 2024 and currently has multiple *open*
  upstream issues reporting headless/virtual outputs broken
  (hyprwm/Hyprland#7917, #8806) — included in the seed list anyway
  (`headlessSupport: REGRESSED`), specifically so it's visible and
  filterable rather than silently omitted. Separately: the *official*
  `archlinux/archlinux` Docker image is amd64-only — no arm64 build at all
  (Arch Linux's own tracking issue,
  gitlab.archlinux.org/archlinux/archlinux-docker#29) — which matters
  enormously since droidtop only targets ARM64 hardware; that entry is
  still listed (so it's visible/filterable) but marked
  `arm64Available: false` with a note, not silently included as if it
  would work.
- **Still not a backend droidtop calls into or is called by** — this is
  droidtop, as a client, talking to standard OCI registries for its own
  image selection, the same category of operation `CraneRootfsPuller`
  already performs. Doesn't touch the §7b "we are not a backend" boundary
  around other apps calling into droidtop.
- **UI surface**: this is what §7c's container-creation flow picks from —
  "Recommended" (the resolved, sortable/filterable list) vs. "Custom" (raw
  reference field) as the two entry points into the same `RootfsPuller`.
  Not designed in UI detail yet; §7c is still design-only overall. Sort/
  filter dimensions the model already supports: OS, desktop environment,
  role, `headlessSupport`, `arm64Available`, `officialSource` — picking
  which of those the UI actually exposes is still open.
- **PRIMARY does not need a droidtop-published image.** Settled, not
  open: PRIMARY resolves against the same recommended/off-the-shelf
  catalog as SIBLING — any known repository with a wlroots-based
  compositor already installed (sway, labwc; see the
  `headlessSupport`/compositor-family metadata above) is a real,
  resolvable PRIMARY candidate today, the same `crane ls`/`crane digest`
  live-resolution path as everything else in this section. There is no
  separate "droidtop's own base+compositor image" to build or publish —
  that was an earlier framing, now corrected.
- **droidtop never auto-picks an image — the user chooses (reaffirmed
  2026-08-30).** There is no default/fallback image selection anywhere:
  the desktop session's primary image is exclusively the user's own
  Desktop-setup choice (onboarding, re-enterable from Settings), and an
  unset or stale choice fails with guidance to make one, never a silent
  pick. (A "first PRIMARY-role seed entry" fallback briefly existed and
  silently selected alpine on the first live pipeline run — removed as
  a spec violation.) Within a chosen repository the registry's own
  `latest` tag is the default until Desktop setup grows a real tag
  picker.

## 3b. Optional: other architectures/OSes via QEMU/libvirt — a value-add, not core

`ContainerRuntime` (§3) covers droidtop's actual workflow — Linux
containers and Wine/Box64 for Windows, both same-architecture (ARM64
containers, x86 binaries translated, never a different-arch *guest
kernel*). None of that requires this section. What this section adds is
optional: let a user boot an **arbitrary-architecture VM** (x86_64,
RISC-V, another ARM variant, etc.) via QEMU, with libvirt-style management
around it if that proves worth building — genuinely useful (dev/testing
an unrelated arch, running something Wine/Box64 can't cover), and another
step toward the Qubes framing in §2/§2a (real VM-level isolation
alongside the container-level split), but never a requirement for
droidtop's normal desktop/gaming workflow to work.

- **Acceleration is conditional, and droidtop must say so up front rather
  than silently running slow** — per the hypervisor research this
  session: Android's pKVM (AVF) isn't generally reachable by third-party
  apps, and even where `/dev/kvm` is reachable, KVM only accelerates a
  guest whose architecture matches the host's (ARM64 guest on this
  device's ARM64 host) — an x86_64 or RISC-V guest is *always* software
  emulation (QEMU TCG) on ARM64 hardware regardless of `/dev/kvm`
  availability. Before starting a non-native-arch VM, check for `/dev/kvm`
  access and whether the requested guest arch matches host arch, and show
  a real performance warning (not a silent slow boot) whenever
  acceleration won't apply — which is the common case for anything other
  than an ARM64 guest on this hardware.
- **Root, used when it helps, not required**: with root (KernelSU/Magisk),
  `/dev/kvm` may be reachable on devices where the vendor kernel exposes
  it for AVF's own use (device/kernel-specific, not guaranteed), and root
  also gives real TUN/TAP networking and finer control over the QEMU
  environment even without acceleration. No-root still works — same
  root/no-root split as `ContainerRuntime` (§3), just software-emulation
  only in that case.
- **Not designed in detail** — whether this is a third `ContainerRuntime`-
  adjacent backend, a fully separate `VmRuntime` construct, and how it
  surfaces in the container-creation UI (§7c) alongside the image catalog
  (§3a) are all open. Flagging the shape and the constraints (acceleration
  conditionality, root-when-helpful) now so it isn't designed blind later,
  not committing to an implementation yet.

## 3c. FEX-Emu — x86/x86-64 emulation for Linux software in general, not just Wine

[vendor/gamenative](../vendor/gamenative) (`:runtime-windows`'s fork
source, §5) already has real, working FEX-Emu integration alongside
Box64 — `FEXCorePresetsDialog.kt`/`Box64PresetsDialog.kt` in its settings
UI, both selectable CPU-translation backends for the same Wine prefix.
Once `:runtime-windows`'s `WineSession.launch()` is actually ported from
gamenative (§10, still a `TODO()` stub), droidtop inherits FEX-as-a-Wine-
backend option for free — no new work needed there.

**What's genuinely new here**: FEX is useful independent of Wine
entirely, for running **x86/x86-64 Linux software** — Flatpaks, native
Linux apps, anything shipped only as an x86_64 ELF binary — inside
droidtop's own Alpine/Debian/etc. containers (§3), the same category of
value as §3b's QEMU/libvirt but scoped to userspace binary translation
instead of a full VM guest kernel:

- **Mechanism**: FEX ships real `binfmt_misc` registration files
  (`FEX-x86_64.conf.in`) so the kernel auto-invokes `FEXLoader` whenever an
  x86/x86-64 ELF is executed — genuinely transparent ("run it like a
  native binary") once registered, not a manual wrapper-script
  invocation. FEX also supports 32-bit x86, not just x86-64 — a real gap
  Box64 alone has (Box64 is x86-64-only; Steam's own tooling, for one
  concrete example, needs both).
- **binfmt_misc registration needs root** — writing to
  `/proc/sys/fs/binfmt_misc/register` is a host-kernel-level operation.
  This works cleanly for `DroidSpacesRuntime`'s root path (already
  requires root for namespaces/cgroups — no new privilege requirement),
  but the no-root `ProotRuntime` path can't register a kernel-level
  interpreter at all; FEX would still work there, just via explicit
  `FEXInterpreter <binary>` invocation instead of transparent execution —
  a real capability difference between the two backends, not just a
  performance one, that needs to be visible wherever this gets surfaced in
  the UI.
- **Official position, and why it doesn't block droidtop anyway**:
  FEX-Emu's own docs are explicit that Android is not a target and never
  will be, because Termux-style proot-over-Android environments have
  fundamental Linux-compatibility gaps FEX can't paper over. That caveat is
  about running FEX directly against Android's own userspace — it doesn't
  apply to droidtop's actual model, where FEX would run *inside* a real
  Linux container (namespaced under `DroidSpacesRuntime`, or prooted under
  `ProotRuntime` — either way a real Linux rootfs, not Android's own
  userspace), which is exactly the environment FEX is built for.
- **RootFS management**: FEX's own `FEXRootFSFetcher` needs host utilities
  (curl, squashfuse/unsquashfs or erofsfuse) to pull its translation
  rootfs — worth checking those are available/buildable in droidtop's
  container images before assuming this "just works," not verified yet.
- **Not designed in detail or implemented** — this is a real, grounded
  value-add candidate (unlike §3b's QEMU/libvirt VMs, which are genuinely
  optional, FEX for x86 Linux software is closer to a natural extension of
  §3's existing container model), but nothing here is built: no
  `binfmt_misc` registration code, no FEX binary bundling/cross-compile
  step (would follow the same pattern as `CraneBinary`/`DroidSpacesBinary`
  — bundled as an APK asset, extracted at first use), no UI surface.

## 3d. User-facing container/distro management (directed 2026-08-30)

Explicit direction: the user must be able to manage containers/distros
themselves, first-class — droidtop's containers are the user's machines,
not internal plumbing only the desktop session touches. Distrobox/
Podman-desktop are the interaction models to match, sitting directly on
the `ContainerRuntime` interface that already exists (§3):

- **Container manager surface** (in the same settings/shell UI family as
  §7c, not a separate app): list every container with live state
  (role, image + digest, running/stopped, disk usage), create a sibling
  from §3a's live catalog (Recommended) or a raw OCI reference (Custom),
  start/stop/restart, delete (with its storage), rename. Per-container
  settings: shared-socket opt-outs (Wayland/audio — §2's defaults, but
  inspectable and disable-able per container), bind-mounts (Android
  shared storage in/out), autostart-with-session.
- **A real terminal into any container** — a computer the user can't
  open a shell on isn't a computer. One terminal-emulator surface in
  droidtop (Compose-hosted), attached to `exec` in a chosen container
  (root backend: `droidspaces exec`-equivalent; noroot: proot exec).
  Which terminal-view implementation to adopt/fork is an open technical
  choice (Termux's terminal-view and Jackpal's Android-Terminal-Emulator
  are the two real prior-art candidates; license and embedding fit not
  yet compared) — the requirement itself is settled.
- The desktop session's PRIMARY container is listed like everything else
  but guarded (can't be deleted while it's the active desktop).

## 4a. Networking & VPN (directed 2026-08-30)

Explicit direction: containerized VPNs should be able to serve the WHOLE
device — a VPN client running inside a container (WireGuard, OpenVPN,
anything the distro packages) gets hooked into Android's own VPN
interface, so every Android app's traffic can route through it. The
standard, root-optional mechanism is Android's `VpnService`: droidtop
implements one `DroidtopVpnService` that owns the device tun fd and
bridges packets to/from the container's VPN:

- **Noroot path (the baseline)**: `VpnService` tun fd ↔ the container's
  VPN endpoint via a userspace packet bridge (tun2socks-style, or
  WireGuard's own userspace implementation consuming the fd directly —
  wireguard-android's backend does exactly this and is the reference
  implementation to study first). No root required; this is the same
  architecture every Android VPN app uses.
- **Root path (value-add)**: with real namespaces (`DroidSpacesRuntime`),
  the container's own tun device + routing rules can be wired to the
  device via iptables/NAT instead — finer-grained (per-container
  egress), but never required for the headline feature.
- Per-app routing (Android's own `VpnService.Builder.addAllowedApplication`)
  is a natural setting once the base works — "route only these apps
  through the container VPN."
- **Not designed in implementation detail yet**: which packet-bridge
  implementation (fork vs. write), config UX (import a .conf/.ovpn into
  the container vs. point droidtop at an already-running container VPN),
  and kill-switch semantics are open. The product decision — containers
  can be the device's VPN — is settled.

## 4b. PC-parity requirements: printing, USB peripherals, "open with droidtop"

Standing test, per direction (2026-08-30): **if the user ever has to
think "I'll need to pull out my computer for that," it's a failing.**
The three highest-frequency laptop moments with no droidtop story at
all, now required scope (mechanisms below are the grounded candidates,
not settled designs):

- **Printing.** Two real, complementary mechanisms: Android's own print
  framework already handles "print from an Android app" device-wide;
  for Linux/Wine software, CUPS runs as an ordinary package inside a
  container (primary or a sibling), which is exactly how printing works
  on any Linux desktop — droidtop's job is at most a settings pointer
  and making the container's CUPS socket shareable like the other
  sockets in §2. Open: whether a container's CUPS printers should also
  be exposed back to Android as an Android `PrintService`.
- **USB peripherals** (flash drives, serial adapters, scanners, audio
  interfaces). Root path: bind the real `/dev` nodes into containers
  (droidspaces `--hw-access`-style device sharing — its own existing
  mechanism, deliberately narrowed per-device rather than wholesale).
  Noroot path: Android's `UsbManager` APIs only, which don't produce
  device nodes a container can use — an honest capability gap to state
  in UI, not paper over. Open: per-device grant UX (a container-manager
  detail view is the natural surface).
- **"Open with droidtop" file associations.** Downloading an `.exe`,
  `.msi`, `.AppImage`, or `.deb` and tapping it should offer droidtop:
  an intent-filter Activity for those MIME/extensions that routes to
  the right runtime — `.exe`/`.msi` into a Wine prefix (§5), `.deb`
  into a chosen container's package manager, `.AppImage` into a chosen
  container — with a real "which container/prefix?" chooser. The
  runtime plumbing exists; the association surface and chooser are the
  missing pieces. This is the moment-of-friction fix: execution already
  works, the tap on the download is what currently dead-ends.

## 4c. Multi-display: what iiSU does, and why droidtop fights the platform (2026-09-01)

Read directly off the installed `com.iisulauncher` 0.1.6.1 APK
(`/root/re/iisu` in the dev container). The code is obfuscated; the
manifest and resources are not, and they were enough — class names,
intent filters and user-facing strings all survive.

### iiSU uses Android's own secondary-display home

```
com.iisulauncher.launcher.SecondaryHomeActivity
  MAIN + android.intent.category.SECONDARY_HOME + DEFAULT
  launchMode=singleTop  stateNotNeeded  excludeFromRecents
  configChanges=0x5a0   exported=true
```

`SECONDARY_HOME` is the platform's own mechanism for a launcher to own
the home surface on secondary displays. Android places that activity on
each secondary display itself and re-places it when whatever ran there
finishes. Two supporting pieces complete it:

- `com.iisulauncher.launcher.RootlessExternalBackstopActivity` — its own
  `taskAffinity` (`com.iisulauncher.rootless_backstop`), singleTask,
  `excludeFromRecents`, `autoRemoveFromRecents`. A throwaway task used to
  hold and reclaim the external display without root.
- `com.iisulauncher.launcher.dualdisplay.LauncherKeepAliveService` — a
  `specialUse` foreground service whose own manifest property reads
  "Keeps iiSU dual-display restore state alive while an externally
  launched app is active." That is exactly droidtop's parked-display
  problem, solved by outliving the Activity rather than by bookkeeping
  inside one.

It also holds `REORDER_TASKS`, which droidtop does not.

### droidtop already has the mechanism and does not use it

`shell-default`'s manifest declares Launcher3's own
`com.android.launcher3.secondarydisplay.SecondaryDisplayLauncher` with
`SECONDARY_HOME`, and that file's own comment says wiring it up "is the
real starting point for droidtop's multi-display patch work ... not
building multi-display support from nothing." That wiring was never done.

Instead the Handheld shell uses `Presentation` for the companion plus
manual `startActivity` + `setLaunchDisplayId` relocation for itself. So
on a live dual-screen device two things compete for the second display:
Android placing the SECONDARY_HOME activity there, and droidtop pushing
its own Presentation and relocated shell there. That competition is the
best available explanation for the symptoms already documented in
`MainActivity` as confirmed-live: a relaunch loop that needed a cooldown
guard, a companion that "landed behind the shell", and the built-in panel
winning regardless of preference.

**Correction (2026-09-01, same day): iiSU uses BOTH, and so must
droidtop.** An earlier version of this section was read as "Presentation
is unnecessary" and the Presentation path was deleted. That was wrong.
iiSU's own dex references `Landroid/app/Presentation` in both class files
and carries a full state model around it — `usingPresentation`,
`usingPresentationExternal`, `retainPresentation`,
`temporaryPresentationDisabled`, `currentPresentationDisplayId`,
`allowPresentation`, `updateSecondaryPresentation:start/resolved/configure`,
`HomePresentationLayoutState(hasDualDisplay=…)`. The two mechanisms answer
different questions:

- **`SECONDARY_HOME` is the IDLE surface** — what a secondary display
  shows when droidtop is not foreground: at boot, after a game on that
  display exits, while the user is in another app. The platform places
  and re-places it.
- **`Presentation` is the ACTIVE surface** — a window owned by the
  foreground shell, so companion content tracks shell focus without a
  second Activity competing for input focus.

Two things the Activity alone cannot do, which is why droidtop keeps
both: a `SECONDARY_HOME` activity is placed only while droidtop holds the
HOME role, so droidtop run as an ordinary app would show no companion at
all; and being a real Activity it can take input focus, which a
Presentation window never does. The handoff is the shell's own
foreground state — `onStop` drops the live window, `onStart` re-asserts
it — which is what `temporaryPresentationDisabled` encodes upstream.

**Direction: adopt `SECONDARY_HOME` as the idle surface** alongside the
existing Presentation, rather than continuing to have nothing underneath
the Presentation and relocating by hand into a display the platform is
also trying to fill. The role assignment wired in on 2026-09-01 stays useful — it is
still how a user says which panel is which — but it should drive which
activity Android hosts where, not a manual relocation.

**One module owns secondary-display behaviour; the active mode selects it
(directed 2026-09-01).** The forked launcher already has secondary-display
behaviour and the Handheld shell has its own. These must not become two
implementations of one job. A single module owns:

- the one `SECONDARY_HOME` activity in the merged manifest — Launcher3's
  `SecondaryDisplayLauncher` and any Handheld equivalent collapse into
  it, since two activities both claiming that category is precisely the
  duplication to remove;
- what that activity renders, chosen by the active mode: Standard gets
  the launcher's secondary-display UI, Handheld gets the companion
  surface, Desktop gets its input surface (§4);
- the panel role assignment and swap (`DualScreenCoordinator`);
- launch-target resolution, relative vocabulary first.

One controlling configuration read by every mode, rather than each shell
carrying its own display logic. `:app` hosts it; the shells contribute
only their own content.

### The launch-target vocabulary is relative, not absolute

iiSU's own strings, which are the more important lesson:

```
"Launch in this screen"        "Launch in the other screen"
"Always Launch in this screen" "Always Launch in the other screen"
"Always Launch in Top Screen"  "Always Launch in Bottom Screen"
"Choose preferred Screen"      "Choose preferred Screen (%1$s)"
"Delete preferred Screen"      "Default Launch Screen" / "Default display"
"Open on the internal display" "Open on the external display"
"Launch app on the opposite display"
"Select which display to open ROMs from this tab."
"Tune individual platforms"
```

Three things droidtop should copy:

1. **Relative targeting sidesteps detection entirely.** "The other
   screen" is always correct no matter which panel Android enumerated
   first. droidtop's current `GameLaunchTarget` vocabulary
   (`BUILT_IN`/`SECOND`) is absolute and therefore only as good as a
   guess that has no reliable signal behind it. Offer relative first,
   absolute as the explicit choice.
2. **A per-platform default, with a per-game override, and a way to clear
   it** — "Select which display to open ROMs from this tab", "Tune
   individual platforms", "Delete preferred Screen". The same
   default-plus-priority model already directed for emulator players
   (§7e2), applied to displays. Deleting a preference is a first-class
   action, not something buried.
3. **Say it in the user's terms**: top/bottom and internal/external, not
   display ids.

This supersedes the `ShellTarget`/`GameLaunchTarget` enums as the design
target; they stay until the replacement lands so nothing regresses.

## 4. Display

- One `DisplayOutput` per Android `Display` the device currently has: the
  built-in screen, the Retroid-style second screen (via Android's
  `DisplayManager`/`Presentation` API — the standard, currently-supported
  mechanism; verify the actual accessory enumerates as a normal secondary
  `Display` before building against it), or an external lapdock monitor over
  USB-C DisplayPort alt mode (also a standard secondary `Display` from
  Android's point of view).
- **"Second screen" is a physical position (upper = output, lower = input
  by default on a Retroid-style device), not whichever `Display` Android
  happens to enumerate second** — `DisplayManager` assigns display IDs by
  connection/registration order, which is not guaranteed to match physical
  upper/lower position, so the two must never be conflated in code.
  droidtop's own upper/lower role assignment needs manual override, not
  just auto-detected enumeration order, plus a persisted per-output (or
  per-app) choice — not trusting auto-detection alone. [Mjolnir](
  https://github.com/blacksheepmvp/mjolnir) (a companion dual-screen
  home-launcher-routing tool) is a concrete reference for the same
  problem.
- Each `DisplayOutput` maps to one headless output inside the primary
  container's compositor (whichever the user's configured — see §2).
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
  accessory): the **upper** physical screen is the default visual output;
  the **lower** physical screen defaults to a trackpad/keyboard *input*
  surface for whatever's showing on the upper one, per §6's
  `AbsoluteTouchContext`/`RelativeTouchContext` split — not automatically a
  second desktop, and not assumed to be "whichever `Display` enumerates
  second" (see the physical-position note above — needs the same manual-
  override-plus-persisted-choice treatment Mjolnir uses, not a fixed
  mapping). Making the lower screen an independent output (its own
  `SecondaryDisplayLauncher` or mirrored desktop) is one of the per-output
  roles above, opt-in like everything else in this section.
  - **Concrete for Desktop mode specifically**: the lower screen's default
    input role is a *persistent* on-screen keyboard (forked from Hacker's
    Keyboard — see §6) plus a trackpad region beneath/alongside it, always
    available rather than popping up only when a text field is focused
    (the normal Android IME behavior would be wrong here — a desktop
    keyboard is expected to just be there). **Toggleable**: a user who'd
    rather use the lower screen as an independent output (mirrored, or
    its own `SecondaryDisplayLauncher`) turns this off, per the per-output
    role model above.
- **Handheld dual-screen roles (directed 2026-08-30, first live addon
  session)**: when the Dual-Screen Add-On (or any second display) is
  present, the HANDHELD SHELL ITSELF moves to it — the addon is the
  upper/main screen — and the built-in screen becomes the
  widgets/ambient-info surface (FocusCompanion/PresencePanel tenants,
  §7e), the inverse of a phone-style "companion on the accessory"
  model. Desktop mode is exempt: it deliberately keeps the lower screen
  as its input surface (keyboard/trackpad, above). Additionally,
  **launch-display targeting is a launcher-wide capability**: every
  launch (console ROM players via `ActivityOptions.setLaunchDisplayId`,
  engine/native/Wine launches alike) targets a configured display,
  defaulting to wherever the shell is. All of it is user-configurable —
  the §4 per-output role/mapping UI is now required, not deferred:
  which display hosts the shell, which hosts widgets, and where games
  launch. Hardware findings from the first session: the addon
  enumerates as a presentation-category EXTERNAL display ("DP Screen",
  1080×1920 native per DRM) but can come up in a 480×640 fallback mode
  until power-cycled — detect and surface that state rather than
  silently running at fallback resolution.
- **On-screen controller (directed 2026-08-30)**: when no physical
  gamepad is detected (`InputDevice` scan for SOURCE_GAMEPAD/JOYSTICK —
  dual-screen phones and foldables running droidtop's surfaces on both
  halves are real targets, not just the Retroid + addon), the companion
  display offers a VIRTUAL controller as one of its roles, feeding the
  same GamepadKeyMap/GamepadAction layer physical pads use. Not built
  from scratch AND not ported: vendor/gamenative's
  `com.winlator.inputcontrols` (a complete, real touch-controls/
  virtual-gamepad implementation) is already vendored and compiled into
  droidtop's build — hook those classes directly in-process (per
  direction), extending what runtime-windows compiles only if a needed
  class isn't in the set yet. User-toggleable; auto-offered only when
  no controller is present.
- **Companion surface is user-populatable (directed 2026-08-30, second
  live addon session)**: the widgets/info screen (CompanionActivity on
  whichever display the shell is not on) is not just droidtop's ambient
  readout — the user populates it: real Android app WIDGETS (an
  `AppWidgetHost`, the same mechanism every launcher uses — music
  controls, calendars, whatever's installed) laid over droidtop's own
  focused-game/info backdrop, plus resizable/floating apps (launched to
  that display via the same launch-display targeting; freeform
  windowing per §2a's native-apps plan). Widget layout persists
  per-display-role. droidtop's info stays the BACKGROUND layer; user
  content composites above it.
- **Handheld Quick Menu (directed 2026-08-31, iiSU-inspired)**: a
  trigger-opened overlay with a Notifications tab and a System tab.
  Paradigm survey behind the design (knowledge-based; no iiSU decompile
  artifacts exist in the container): the Steam Deck QAM (dedicated
  button → right-edge sheet, vertical tabs: notifications / quick
  settings / performance) is the strongest prior art for
  glanceable-while-playing; iiSU's trigger menu is the same family on
  Android handhelds; PS5's control center (bottom pill bar) and the
  Switch HOME-hold sheet are the alternatives considered and passed
  over (bottom bars fight the theme's own helpsystem row; the Switch
  sheet is single-purpose). Chosen: right-edge sheet, HOLD SELECT to
  open (the system key-repeat threshold, ~500ms, detected via
  repeatCount — no timers; short-press Select keeps its meaning; chords
  rejected as undiscoverable; remappable later via the GamepadAction
  layer). **Entirely controller-driven, per direction**: L1/R1 tabs,
  D-pad focus, A open, X dismiss, Y clear-all, B close, with the hint
  row stating exactly that. The System tab renders the settings
  catalog's own System group through the same CatalogNavigator the
  Settings section uses — a view, never a copy. Notifications need the
  notification-access grant (NotificationListenerService in `:app`
  feeding `runtime-common`'s NotificationsStore); until granted the tab
  offers the grant, never a silently empty list. Honest limitation:
  the menu overlays the SHELL only — games are separate activities, and
  a Deck-style in-game overlay is future work tied to this section's
  overlay plans, not claimed here.
- **Display reinit + parked displays (directed 2026-08-30)**: Android
  silently MIRRORS a second display nothing presents on (confirmed live
  on the addon) — droidtop's answer is that some droidtop surface owns
  every display whenever Handheld runs, and a HOME press is the user's
  "fix my screens" gesture: Launcher forwards a warm HOME press back to
  the last-used shell with a display-reinit flag, and MainActivity
  re-runs its role orchestration. A display an app was LAUNCHED onto is
  *parked* (`LaunchDisplay.parkedDisplayId`): reinit never relocates the
  shell onto it or presents the widgets panel over it (a Presentation
  layers above activities), so a running game is never covered; an
  explicit shell entry from the BackButtonMenu reclaims it. Shell
  relocation attempts are cooldown-guarded — the recreated instance can
  read its display as DEFAULT before window attach, and an unguarded
  mismatch check relaunch-looped forever (confirmed live).
- **Recents (decided 2026-08-30): droidtop builds its OWN in-shell
  recents; system quickstep recents is out.** Holding the system
  recents role is impossible without root/system privileges
  (`config_recentsComponentName` is ROM configuration; every launcher
  with working quickstep recents is a system/ROM install), and root is
  desktop-mode-only by standing rule — so replacing the system Recents
  UI would make droidtop device- and Android-version-specific and is
  rejected. Instead: a droidtop recents surface inside the shells,
  unprivileged — droidtop-launched entries first (play history +
  `LaunchDisplay`'s own per-launch display knowledge → screen-aware
  grouping and "pull this game to the other screen" actions the system
  recents could never offer), optionally enriched to all apps via
  `UsageStatsManager` with the user-grantable usage-access permission.
  **Backlog (directed)**: the same in-shell recents should also expose
  every WINDOW running in the Desktop-mode session (the primary
  compositor's window list, via the same wlroots protocols host-bridge
  already speaks — e.g. `wlr-foreign-toplevel-management`) as
  first-class recents entries, so desktop apps can be made fullscreen
  and switched between naturally from the same surface as Android
  tasks. The quickstep sources stay parked in
  `upstream-unused-reference/` for a hypothetical future ROM/system
  build only.
- **General framing**: droidtop's display/shell/settings model takes KDE
  Plasma as its broader reference point, not just for KScreen specifically
  — the goal (§1) is a real general-purpose compute device, and KDE is the
  most complete existing example of "one coherent desktop shell with
  modular, discoverable settings" to learn conventions from as more of
  this gets built out (workspace switching, per-app window rules, etc.),
  not a component to fork code from.

## 4d. The companion screen, designed (research 2026-09-01)

droidtop's companion currently renders a status bar, notifications and
any Android widgets the user added, over a black ground. On a real device
that is mostly empty space. This section is what it should be, and why.

### What the research actually says

**Glanceable-display research** (Matthews/Forlizzi on peripheral displays;
"calm technology"): a glanceable surface must communicate its key message
in one to two seconds, with deliberate visual hierarchy and LOW
information density. It informs without demanding attention from the
primary task. This is the constraint that rules out "put everything on
it" — the failure mode for a second screen is clutter, not emptiness.

**Steam Deck's Quick Access Menu**: five tabs, and a performance overlay
with five graduated LEVELS from off (FPS only) through full GPU/CPU/VRAM/
frametime detail, with an explicit note that higher levels are more
obtrusive. The lesson is progressive density: the user picks how much,
rather than the designer picking for everyone.

**Wii U GamePad**: the durable idea was that the second screen holds what
a game's PAUSE screen would hold — inventory, map, management — so it
COMPLEMENTS rather than duplicates the primary screen. Asymmetric, not
mirrored.

**Retroid dual-screen add-on users**: the dominant real use is DS/3DS
dual-screen emulation, second is media on one screen while playing on the
other. Both matter to droidtop: the companion must get out of the way
entirely when a game owns that panel.

**iiSU 0.1.6.1**, read from its own resources (same hardware class, so
this is the most direct evidence available):

- `"Show Hero on Idle Bottom Screen"` — the idle state is HERO ARTWORK,
  not a placeholder. This is the direct answer to droidtop's empty screen.
- `"Add a universal post-idle delay before hero, title, and backdrop
  artwork commits"` — artwork does not change until browsing settles, so
  scrolling does not thrash the second screen.
- Its own widget set beyond Android widgets: `Clock`, `Calendar`,
  `Image`, `Web` (a URL or HTML file), `Achievements`, `Active Tasks`,
  `Library Stats`, `Playtime Activity`, `Playtime Stats`.
- `"Set Bottom Screen Brightness"` — per-panel brightness.
- Layout modes: `Dual Screen`, `Single Screen mode`, `Bottom Screen
  Only`, `Show Home on Bottom screen`, `Horizontal Grid on Dual Screen`.
- `"Enlarge title artwork on the secondary display while keeping it
  within the detail layout."`
- A notification "bell capsule" that animates arrivals.
- Download state surfaced as `"Downloads waiting for Wi-Fi"`.
- System reach: Wi-Fi, Bluetooth, Do Not Disturb, Low Power Mode,
  Brightness, and separate music/soundbite volumes.

### droidtop's companion, layered

Top to bottom, each layer earning its space:

1. **Status bar** (built): clock, Wi-Fi, VPN, battery, Controls.
2. **Focused entry** (partly built): when the user is browsing, the
   companion shows what the main screen cannot fit — hero art, full
   description, developer/publisher/year/genre/players, rating, playtime,
   and for a PC entry its `PcInfo` (source, install size, and community
   compatibility as REFERENCE, never a verdict — §7g). This is the Wii U
   lesson: the detail you would otherwise open a submenu for.
3. **Idle state** (to build): when nothing is focused, a slow hero/marquee
   rotation drawn from the library plus the clock — calm, ambient, never a
   wordmark. A post-idle delay before artwork commits, so fast scrolling
   does not thrash.
4. **Notifications** (built).
5. **droidtop-native widgets** (to build): library stats, playtime,
   recently played, and live scan/scrape/download progress — the things
   droidtop already knows and currently shows nowhere.
6. **Android widgets** (built).
7. **Controls** (partly built): per-panel brightness, Wi-Fi, Bluetooth,
   DND, volume.

### Rules this design commits to

- **Complement, never mirror.** The companion shows what the primary
  screen is not showing. Duplicating the shell is the failure mode.
- **Density is the user's choice**, Steam Deck style. A default that is
  calm, and an explicit way to show more.
- **Yield completely.** When a game or app owns that panel, the companion
  is gone — not layered over it. This is already why a launched display is
  parked (§4c) and why the Presentation is dismissed on `onStop`.
- **Never a placeholder.** An idle companion shows something real or
  shows the ground. A wordmark on a black rectangle is the bug this
  section exists to close.

## 4e. General-compute parity: status ledger (assessed 2026-09-01)

§1 and §4b already set the goal and the test — *"if the user ever has to
think 'I'll need to pull out my computer for that,' it's a failing."*
This section measures against it rather than restating it.

### What prior art says parity requires

**ChromeOS Crostini** is the closest shipping analogue to droidtop's
architecture (containers, integrated rather than isolated), and its
integration list is effectively the checklist: bidirectional file sharing
surfaced in the host's own file manager; **clipboard forwarding** between
container and host, which its Sommelier Wayland proxy handles alongside
input and window events; audio in/out; OpenGL; USB passthrough (still
only partial even there, after years); printing. Notably Crostini treats
clipboard and file sharing as core, not polish — they are what make two
environments feel like one machine.

**Samsung DeX** is the instructive failure. Its ceiling is not the
hardware, it is that *every app is the same mobile app, stretched*:
reviewers consistently land on stripped-down Office, phone-shaped Slack
and Teams, and photo editors that cannot do the job. This validates
droidtop's core architectural bet — running real desktop software through
containers and Wine, rather than enlarging Android apps, is the only
route to the standing test. It also warns about the second-order problem
DeX has: capping concurrent apps and losing window arrangements makes a
desktop stop feeling like one.

### Ledger

Measured by what droidtop's own code actually touches.

| Capability | Spec | Status |
|---|---|---|
| Container lifecycle (root) | §3 | **built** — `DroidSpacesRuntime` implements create/start/stop/list/exec/destroy |
| Container manager UI | §4b | **built** — `ContainersActivity` over `listContainers` |
| Bind-mounted Android storage | §3 | **built** — `hostStorageToContainerPath`, whole app-storage dir mounted |
| Audio | §2 | **configured, unverified** — droidspaces' own `enable_pulseaudio=1`; never confirmed on device |
| Keyboard/mouse injection | §6 | **built, unproven live** — host-bridge's virtual-pointer/virtual-keyboard injection, and (since §6b) the desktop surface that feeds it. Between the assessment above and §6b the primitive existed but nothing called it: `InputSeat` was constructed only by its own unit test, so the presented desktop was a video feed with no way to click it |
| External display | §4 | **built** |
| **Terminal into a container** | §4b | **NOT BUILT** — zero files. §4b's own words: "a computer the user can't open a shell on isn't a computer" |
| **Clipboard host↔container** | — | **NOT BUILT**, and **not in the spec at all** until now. Crostini treats this as core |
| **USB peripherals** | §4b | **NOT BUILT** — zero files |
| **Printing** | §4b | **NOT BUILT** — zero files |
| **VPN for the whole device** | §4a | **NOT BUILT** — zero files, despite a fully designed section |
| GPU acceleration | §3c | **barely touched** — one file references it |
| Non-root path | §3 | **NOT BUILT** — `ProotRuntime` is 7 `TODO()`s, so an unrooted device has no desktop at all |
| Desktop session end-to-end | §10 | **unproven** — real code, never run against live hardware |

### What actually makes a user reach for a real computer today

In rough order of how often it would bite:

1. **No terminal.** Anything shell-shaped is impossible.
2. **No clipboard bridge.** Text cannot move between Android and the
   container, which is constant friction rather than an occasional need.
   This is the biggest gap the spec did not already name.
3. **No USB.** No flash drives, no serial adapters, no scanners.
4. **No printing.**
5. **No VPN**, despite §4a being fully designed.
6. **Nothing at all without root**, which is a coverage problem rather
   than a capability one, and the largest single body of unwritten work.

### Correction to the record

Six doc comments describing unfinished work outlived the work itself and
were repeatedly read back as gaps (fixed 2026-09-01). The lesson for this
ledger: status claims belong in ONE place that is checked against code,
which is what this section is. Prefer measuring what the code touches
over trusting a comment about intent.

## 5. Windows compatibility — no real virtualization

Confirmed via research, treat as settled: genuine hardware-accelerated x86
virtualization for Windows is not feasible on Snapdragon 865-class hardware
(the Retroid Pocket 5's chip). KVM-backed ARM virtualization (Gunyah)
doesn't ship until Snapdragon 8 Gen 2+; even where it exists it only
accelerates ARM64 guests, not x86 — running Windows would still mean QEMU
software CPU emulation, which is worse than Box64/Wine translation. Google's
AVF/pKVM is Pixel-only in practice and built for paravirtualized Linux
guests, not general-purpose Windows VMs.

**Conclusion: Wine + userspace x86 binary translation (`:runtime-windows`,
forked from [vendor/gamenative](../vendor/gamenative)) is the only Windows
path.** Revisit hardware virtualization only if targeting Snapdragon 8
Gen 2+/Dimensity 9000+ devices specifically, and even then only as a path
to running a *Linux* guest, not Windows. See §5a for which translation
backend and for the native-Linux-build alternative to Wine entirely.

## 5a. CPU-translation backend choice, and preferring a native Linux build over Wine

Two corrections to §5's "Wine + Box64" framing, both from real gamenative
source already in [vendor/gamenative](../vendor/gamenative):

- **Backend choice is the user's, not fixed to Box64** — gamenative
  already ships both `Box64PresetsDialog.kt` and `FEXCorePresetsDialog.kt`
  as selectable CPU-translation backends for the same Wine prefix (see
  `SettingsGroupEmulation.kt`). Once `WineSession.launch()` is actually
  ported from gamenative (§10, still `TODO()`), droidtop inherits this
  choice for free — no new work needed to offer it. FEX also brings real,
  distinct value beyond Wine itself — see §3c.
- **Prefer a native Linux build over Wine+translation when one exists**:
  some Steam titles ship a genuine Linux depot alongside (or instead of)
  Windows, and gamenative's own `DepotInfo` (`vendor/gamenative/app/src/
  main/java/app/gamenative/data/DepotInfo.kt`) already models this —
  `osList: EnumSet<OS>` (`OS.windows`/`OS.linux`/`OS.macos`) and
  `isWindowsCompatible` per depot. droidtop should prefer a Linux depot
  when `osList` contains it, running it as a normal process inside a
  Linux sibling/primary container (§3) with no Wine/translation involved
  at all — strictly better than translation when it's available, not a
  new capability to build so much as a selection-order change: check for
  a Linux depot first, fall back to Wine+Box64/FEX only when there isn't
  one.
  - **Real, undocumented gap, not solved by the above**: gamenative's
    `OSArch` enum only distinguishes 32-bit vs. 64-bit
    (`vendor/gamenative/app/src/main/java/app/gamenative/enums/OSArch.kt`)
    — it has no CPU-family signal (x86 vs. ARM). A Linux depot's *binary*
    could still be x86/x86-64 needing FEX/Box64 translation (the common
    case) or, rarely, genuinely ARM64-native (which some titles do ship,
    per this session's research prompt, though standard Steam depot
    metadata doesn't cleanly flag it) — telling those apart isn't
    something the existing fork provides, and isn't designed here either.
    Detecting a truly native-ARM64 Linux depot (best case: no translation
    layer needed at all) is an open problem, not assumed solved just
    because `osList` says "linux."

### PC launch wiring: the `PcGameRuntime` seam (directed 2026-08-31)

`WINE_PREFIX` and `LINUX_CONTAINER` were both dead `error()` stubs
("isn't wired to a running session yet"), which meant the resolver could
*offer* a detected engine game Wine and then fail the instant the user
picked it. They are now real launches, through a seam:

- `library-core` declares `PcGameRuntime` + `PcGameRuntimeRegistry` —
  the same swappable-seam pattern `LaunchDisplay.chooser` and
  gamenative-tux's own `LinuxContainerBackend` already use. It exists
  because `library-core` cannot depend on the runtime modules, and both
  runtimes need a live container session only the app layer can obtain.
- The implementation lives in **`:runtime-windows`, not `:app`** — that
  is the module compiling the vendored `com.winlator` tree, so
  `ContainerManager` (the real owner of Wine-prefix state) is visible
  only from there; `:app` depends on it with `implementation`, which does
  not re-export those types, so the same code in `:app` would not
  compile. `:app` constructs and registers it.
- `GameExecutableResolver` picks what to actually run. Detection only
  ever proved an engine was *present*; nothing had needed to name the
  launchable file. It skips installers/uninstallers/redistributables and
  returns null rather than choosing between equally plausible candidates,
  so the user gets "pick one explicitly" instead of droidtop silently
  starting a patcher.
- droidtop reuses an existing Wine container rather than creating one per
  game: an engine game should run in the environment the user already
  configured, and spawning multi-hundred-megabyte prefixes per title
  uninvited would be its own bug.
- Per-game choice is exposed as a "Runs with: <backend>" chip in the game
  detail screen, backed by the previously-unwired
  `LaunchStrategyOverridePrefs`.

**Status (corrected 2026-09-01): no longer a blocker in code.**
`DroidtopPcGameRuntime.provision()` now really does create the container,
download the imagefs via `SteamService.fetchFileWithFallback`, install it
through `ImageFsInstaller`, and activate it, and the Steam library offers
it at the point of need ("Set up Windows games") rather than hiding it in
settings. It has still never been *run* on a device, so the paragraph
below describes what was true before that work and what remains unproven
hands-on. Originally: droidtop had no flow to *create*
a Wine container at all — `files/imagefs/home` does not exist on a real
install, so `ContainerManager.containers` is empty and every Wine launch
correctly reports "no Wine container exists yet". The gamenative
machinery to build one (container-pattern download via
`ContainerFilesDownloader`, `ImageFsInstaller` extraction,
`ContainerManager.createContainer`) is all compiled in and unused. Until
that is surfaced, the Wine path is wired but unreachable. This is the
next real step for PC gaming, and it is also what blocks testing whether
Wine can run a game from external storage through droidtop's own stack
rather than through Winlator.

**Implementation status (first slice, real but partial):**

- `ContainerRuntime.exec(container, command, env)` — the actual missing
  primitive both launch paths below needed (there was no "run a process
  inside an already-running container" operation at all before this;
  `create*`/`start`/`stop`/`destroy` are lifecycle, not execution).
  `DroidSpacesRuntime`'s implementation drives droidspaces' own documented
  `--name=<id> run <cmd...>` subcommand (Documentation/Linux-CLI.md);
  per-invocation env vars aren't a `run` flag droidspaces exposes, so
  they're prepended as inline POSIX shell assignments instead (matches
  droidspaces' own CLI-doc examples, e.g. `run sh -c "id && env"`).
  `ProotRuntime.exec()` is still `TODO()`, same as the rest of that class.
- `runtime-common`'s `GameDepotPlatform`/`GameDepotOption`/
  `selectBestDepot()` implement this section's actual decision rule
  (Linux > Windows, never macOS) as small, pure, unit-tested logic —
  droidtop's own copy, not a dependency on gamenative's `DepotInfo`/`OS`
  (`:runtime-windows` builds from ported gamenative code, not a Gradle
  dependency on its module — see that module's build.gradle.kts).
- `WineSession.launch()` now actually calls `ContainerRuntime.exec()` —
  no longer `TODO()` — but only for a bare `wine <executable>` invocation.
  **Still not ported**: Box64/FEXCore wrapper-flag selection, DXVK/VKD3D
  environment setup, ImageFS-tracked component state — all real,
  separate porting work from gamenative, not done in this pass.
- `NativeLinuxGameSession` — the actual "no Wine at all" path this
  section describes, also built on `ContainerRuntime.exec()`. Depends on
  §3c's FEX `binfmt_misc` registration (not implemented yet) to actually
  run an x86/x86-64 Linux binary transparently; a genuinely ARM64-native
  binary would already work through this class today.
- **Not done yet, the real remaining integration work**: wiring
  `selectBestDepot()` into gamenative's actual depot-download/launch
  pipeline — the `isWindowsCompatible`-filtering call sites in
  `vendor/gamenative/.../service/SteamService.kt` (several, always assumes
  Windows today) would need real changes, which is deep surgery into a
  large vendored file not attempted here.

## 5b. One Wine engine, one prefix store, whichever backend is live (assessed 2026-09-02)

Re-derived from the code rather than from any earlier summary, because
two descriptions of this path were in circulation and both were partly
wrong.

### What the code actually does today

- `DroidtopPcGameRuntime.provision()` builds the Wine environment through
  gamenative's own `ContainerManager` + `ImageFs` + `ImageFsInstaller`.
  That machinery needs no root: the prefix and the rootfs live under the
  app's private storage.
- `DroidtopPcGameRuntime.launchWindows()` and `PcGameProvider.launch()`
  both require a `PrimaryContainerSession` before they will run anything,
  and execute `box64 wine <exe>` through `ContainerRuntime.exec` with
  `WINEPREFIX` translated by `hostStorageToContainerPath`. `isAvailable`
  is literally `primarySession() != null`.
- `ContainerRuntimeFactory.select` probes for root and returns
  `DroidSpacesRuntime` when it finds it and `ProotRuntime` otherwise, and
  `ProotRuntime` is seven `TODO()`s including `exec`.

Those three facts compose into the real defect: **Windows games are
root-only today**, not by design but because the only backend whose
`exec` is implemented is the one that needs root. The prefix is
provisioned into a rootfs the launch path never runs in.

### The correction that matters most

The obvious repair — "port `DefaultProotContainerBackend` into
`ProotRuntime`" — does not work as stated, and the entry in §7g saying so
is now wrong:

- `runtime-windows` compiles the vendored tree with
  `MODERN_ANDROID = true`, which is not a preference: Android refuses to
  `exec()` extracted binaries above `targetSdk 28`, so it is the only
  value that can work for droidtop.
- On that path gamenative uses the **bionic** container variant, and
  `BionicProgramLauncherComponent` runs the guest with a plain
  `ProcessHelper.exec` against the ImageFs root. **No proot, and no Linux
  container.** proot is the *glibc* variant's mechanism.
- The arm64 `libproot.so` / `libproot-loader.so` were deleted from the
  vendor tree upstream (commit `dad82a8d`); only an armeabi-v7a pair
  survives under the legacy flavor, and `src/main/cpp/proot`'s CMake
  build is commented out upstream with a note that a cmake-built proot
  fails on `ld-2.31.so`. There is no arm64 proot binary to port to.

So the no-root Windows path does not need proot at all. It needs the
bionic direct-exec model that gamenative already uses everywhere modern
Android is the target.

### The blocker that makes all of it inert right now

`runtime-windows`'s `sourceSets` pull `java`, `res` and `assets` from the
vendor tree and **not `jniLibs`**, and no droidtop module declares a
`jniLibs` source dir anywhere. None of gamenative's native payload
(`libwinlator.so`, `libpatchelf.so`, `libc++_shared.so`, the pulse
libraries) ships in the APK. Confirmed on the device: `files/imagefs`
contains a single `.winlator/.container_migration_version` marker, no
rootfs, and `ContainerManager` has never held a container. Provisioning
has therefore never completed on real hardware, which is why no Windows
game has ever launched through droidtop.

### The shape this settles on

One Wine engine, installed once. One droidtop-owned prefix store, owned
by droidtop and keyed by game, never by whichever container happened to
launch it. Execution is a **runtime** choice, not a structural one:

- the bionic direct-exec path is the universal default, works with no
  root, and is therefore what the handheld uses;
- droidspaces `exec` is an optimization available in desktop mode where
  root is legitimate, never a precondition;
- neither may re-provision anything the other already installed. The test
  is that the same game with the same prefix launches in both modes.

That means `PcGameRuntime`'s implementation must stop treating a live
`PrimaryContainerSession` as the precondition for launching Windows
software at all. `ContainerRuntime` as an interface is adequate to
express "run this command in this environment"; what is not adequate is
the assumption at the call site that the environment IS a droidspaces
primary container.

Packaging the vendored `jniLibs` (both `src/main` and `src/modern`, the
same split upstream applies to its Java sources) is done. Remaining, in
order: give the Wine launch a backend-neutral entry point with no
`PrimaryContainerSession` requirement; then prove provisioning and one
launch on hardware before porting anything further.

## 6. Input

One Wayland seat (`:input-seat`), fed from every physical source: touch,
gamepad-as-pointer, the second-screen trackpad/keyboard, or a lapdock's
physical peripherals. All normalized before reaching `:host-bridge`, so the
compositor only ever sees one logical pointer and keyboard.

- Second-screen trackpad interaction model: borrow Moonlight Android's
  `AbsoluteTouchContext` (primary screen = absolute cursor position) /
  `RelativeTouchContext` (trackpad = relative deltas) split.
- Keyboard forwarding: reference KDE Connect Android's Remote Input plugin.
- **Second-screen persistent keyboard (Desktop mode's default second-screen
  role, §4)**: a fork of [Hacker's Keyboard](
  https://github.com/klausw/hackerskeyboard) (Apache-2.0, confirmed —
  compatible with droidtop's GPL-3.0 combined position the same way
  `:shell-default`'s Apache-2.0 fork already is, see §8), not a new
  keyboard built from scratch — it already has the physical-keyboard-style
  layout (dedicated Ctrl/Alt/Esc/arrow keys, unlike stock Android IMEs) a
  desktop-input surface actually wants. Forked in and adapted the same way
  as `:shell-default`, not kept as a passive `vendor/` reference. Not
  implemented yet.
- **Do not assume Winlator/GameNative's input code is a safe base** —
  Winlator has a known, open, acknowledged gap in native/Bluetooth mouse
  pointer capture (issue #1555). This needs real design and testing effort,
  not inherited code.

## 6b. Desktop surface input (built 2026-09-01)

The seat in §6 was a primitive with no caller. `:shell-desktop`'s
`SurfaceView` handed its surface to `HostBridge.presentOutput` and stopped
there, so the desktop rendered and could not be touched. `DesktopInputRouter`
(`:input-seat`) is what closes that: it is installed on the surface, it holds
one `InputSeat` per live `HostBridge`, and it is the only thing in the app
that drives the seat. Nothing reaches `HostBridgeInput` around it — that is
what keeps §6's "one logical pointer and keyboard" true no matter how many
physical devices are attached.

**Coordinate space.** Touch and mouse position are mapped through
`PointerTransform`, from Android view space into the compositor output's own
pixel space, and handed to `zwlr_virtual_pointer_v1.motion_absolute` together
with the extent they were scaled against. The transform is rebuilt in
`surfaceChanged`, because the surface's size is the only geometry that can be
read honestly there: it is not the panel size (the taskbar takes 48dp of it)
and it is not the output size.

The default fit is `STRETCH`, because that is what the present path actually
does — `presentPrimaryOutput` sets the buffer geometry to the output size and
SurfaceFlinger scales that buffer to fill the view's bounds, per axis, with no
letterboxing anywhere. Under `STRETCH` only the ratio x/x_extent reaches the
compositor, so the fact that Kotlin only knows the *Android panel* size and
not the true compositor output size (which arrives natively, in the screencopy
`buffer` event) cannot produce a scale error. A `LETTERBOX` fit exists and is
tested alongside it, for the moment the present path grows an
aspect-preserving mode; that mode makes the aspect ratio load-bearing, so
taking it requires plumbing the real output size up from native first.

**Keys.** Android keycodes are translated to Linux evdev codes and injected
raw. There is no second layout table: the layout is entirely the XKB keymap
`:host-bridge` already hands the compositor, and the translation table is
checked against that keymap's own `xkb_keycodes` section (XKB keycode = evdev
+ 8). Back, Home, Recents, Power and the volume keys are deliberately not
forwarded — swallowing them would strand the user inside a full-screen
desktop.

**Gamepad.** The right stick drives the pointer and the two stick clicks are
left/right button; the D-pad, face buttons, shoulders and left stick are left
alone. The shell around the surface — taskbar, start menu, settings — is
Compose focus navigation driven by D-pad and A, and claiming those would break
navigation in exactly the case a gamepad pointer is for (no mouse attached).
The right stick and stick clicks are the controls that navigation does not use.

Known gaps, stated rather than guessed at: no long-press-to-right-click on the
touchscreen (a hold threshold is not worth inventing without a device to tune
it on), and the second-screen trackpad surface of §6 is still unbuilt — the
router already has the relative-motion path it will use.

## 6a. Keyboard ownership (directed 2026-09-01)

droidtop ships **Hacker's Keyboard** — `:input-keyboard`, forked from
klausw/hackerskeyboard (`org.pocketworkstation.pckeyboard`). Its main
class is named `LatinIME` because Hacker's Keyboard is itself an AOSP
LatinIME fork that kept the class name; the project is not AOSP's
keyboard, and a report that said otherwise was reading the class rather
than the package.

**It is droidtop's default keyboard, by design.** A device meant to
replace a computer needs a keyboard that computer software can be driven
from: Ctrl, Alt, Esc, Tab, arrow keys and the function row. A terminal
(§4b), a Wine application, or any real desktop program is unusable
without them, and no stock phone keyboard has them. That is why it is
forked in rather than recommended as a download.

Being the active input method has a second real effect, stated plainly
rather than left as a hidden benefit: from Android 10, only a focused app
or the **current input method** may read the clipboard, so droidtop's
host↔container clipboard bridge works properly exactly when its own
keyboard is active.

### The ownership principle, and its limit

Standing direction: droidtop wants to own the device as much as it
usefully can — it is not merely a launcher. The limit is equally
standing: **the user keeps control and is told why.**

Concretely, droidtop cannot silently become the input method even if it
wanted to. Setting `Settings.Secure.DEFAULT_INPUT_METHOD` requires
`WRITE_SECURE_SETTINGS`, which a normal app is never granted, and
handheld/launcher features must never depend on root. So the whole
mechanism is: enumerate what is installed, explain the reason once, and
open Android's own pickers.

- `InputMethodManager.enabledInputMethodList` — what is installed, with
  droidtop's own and the active one marked.
- `InputMethodManager.showInputMethodPicker()` — the system's own
  switcher, no permission needed. **This is how a user swaps keyboards
  from inside droidtop**, and it is the system drawing it, not droidtop
  impersonating it.
- `Settings.ACTION_INPUT_METHOD_SETTINGS` — needed the first time,
  because an installed-but-not-enabled IME does not appear in the picker
  at all.
- An IME may also call `switchInputMethod`/`switchToNextInputMethod` for
  itself, so droidtop's own keyboard can offer "switch keyboard" from a
  key — a real future addition, not built yet.

`Keyboards` (`:runtime-common`) is the single surface for all of this,
and the settings catalog shows the active keyboard, says what it is, and
offers the switch. It never nags and never changes the setting itself.

## 7. Library / launcher-readiness

`:library-core` models every runnable thing — native Android app, Wine
profile, Linux-container app — as an equal `LibraryEntry` from a
`LibraryProvider`, aggregated by a `Library`. Modeled on Playnite (no direct
Android equivalent exists; this is a real gap being filled, not a fork).

This layer carries metadata (artwork, playtime, last-played) and a uniform
launch interface, read by all three shells alike. Which shell is active is
a user choice, not a build-time one — reached via a long-press of the back
key (`dev.droidtop.shell.standard.BackButtonMenu`, wired into both
`:shell-default`'s `Launcher` and `:app`'s `MainActivity`; a plain back
press keeps doing its normal per-shell job in every state), not a separate
app-drawer icon or a floating switcher button:

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
  **Own every control the platform allows; direct-link the rest
  (directed 2026-08-31)**: droidtop consumes as much of the user's UI
  needs as possible — the Android Settings app is something droidtop
  LINKS INTO for the screens the platform refuses to let an app own,
  never something the user has to go spelunking in. Concretely, the
  ownable set on modern Android (all real, all implemented in
  `runtime-common`'s `SystemControls`): volume (AudioManager);
  brightness, adaptive brightness, screen timeout, auto-rotate (all one
  WRITE_SETTINGS special grant); Do Not Disturb (its own
  notification-policy grant). The non-ownable set gets curated direct
  links filtered by `resolveActivity` so an OEM build missing a screen
  never shows a dead row; Wi-Fi toggling specifically left app reach in
  API 29, so surfaces open the system's own internet panel (the sheet
  the quick-settings tile uses) rather than faking a toggle. Every
  special grant is surfaced as an explicit "take me to the grant"
  action until given — never a silent failure.
  **Settings architecture — shared catalogs, per-surface chrome**: the
  settings DATA and LAYOUT live in renderer-agnostic catalogs
  (`dev.droidtop.library.settings` in `:runtime-common` —
  `SettingsCatalog.kt` model + one catalog object per mode, e.g.
  `HandheldSettingsCatalog`): which settings exist, their grouping and
  order, their live values, and their single write path each. Every UI
  surface just chromes a catalog in its own visual context — the unified
  Preference screen renders it via `CatalogPreferenceBuilder`
  (`:shell-default`), and Handheld's own in-shell Settings section
  renders the SAME catalog with pure gamepad input
  (`SettingsCatalogView`, `:shell-gamepad`) so cycling sections with L/R
  never leaves the handheld context (per direction: browsing sections
  must maintain context; explicitly activating a navigation item is the
  one thing that may switch surfaces). Catalog layout convention, every
  mode: the droidtop-wide "global" group first (a surface whose chrome
  already exposes global settings — SettingsActivity's persistent
  action-bar item — skips it by group id), the current mode's own
  settings next, shortcuts to the OTHER modes' settings last, so nobody
  ever switches modes just to reach a setting. Renderers may substitute
  a native fulfillment for an item by its stable id (Handheld performs
  "Rescan library" by bumping its own scan trigger and opens the theme
  browser inline); every catalog default must still be real and correct
  on its own so an id-unaware renderer gets working behavior for
  everything. Coverage is total, per direction:
  EVERYTHING that is a droidtop setting lives in the catalog model —
  flat lists and management surfaces alike. Management screens (console
  systems, per-folder system/player assignment, artwork scraping,
  platform CRUD, ROM folders, scraper credentials) are nested
  `CatalogScreen`s whose data lives in `:app`
  (`dev.droidtop.app.settings.AppSettingsCatalogs`), registered into the
  process-wide `SettingsScreenRegistry` at process start by a
  manifest-declared init provider so lower modules open them by id with
  no dependency edge on `:app`; both renderers navigate them natively
  (the in-shell `CatalogNavigator`'s real nav stack; the Preference
  surface's in-place PreferenceScreen stack in
  `CatalogPreferenceNavigator`). `ConsoleSystemsActivity` is now just a
  host for `CatalogNavigator` — its former hand-rolled one-off UI is
  gone. Desktop/Standard settings are still XML-declared Preference
  screens — migrating them onto catalogs is the follow-up that makes
  their settings renderable inside Desktop's own shell the same way.
  - **Known real gap, confirmed on-device**: the Standard shell as it
    ships from Murine Launcher upstream is functional but plain — first
    real-device testing surfaced this directly, not a guess. Backlog item,
    not started: **research third-party Android launchers** for concrete
    UI/UX improvements to bring to `:shell-default` — real candidates
    worth a structural (not pixel-copying — same discipline as §7's
    gamepad-shell research) pass: Lawnchair (open-source, itself an
    AOSP-Launcher3-family fork like this one — closest architectural
    relative), Nova Launcher (long-established, feature-dense, closed-
    source but well-documented UX conventions), Niagara Launcher
    (minimalist/gesture-first, a genuinely different paradigm worth
    comparing against). Not scoped or started yet.
- **`:shell-desktop` ("Desktop")** — the Android-side half of §2a's split:
  a cross-container task manager and the `SurfaceView` frame-passthrough
  viewport (via `:host-bridge`'s `HostBridge`), *not* the taskbar/app
  launcher — those are container-side (§2a). Current UI chrome (an
  Android-side taskbar + start menu) is a first pass predating §2a's
  design and needs reworking to match it: drop the in-app-launcher
  UI in favor of a real task manager, since the app launcher belongs in
  the primary container instead. The live desktop connection itself is
  blocked on `DesktopSessionService` (still a TODO — see `:app`).
- **`:shell-gamepad` ("Handheld")** — full-screen, D-pad-navigable, reading
  the same `Library`; optional and toggleable, never the assumed default
  experience. **Superseded design decision (2026-08-29): a single real
  paradigm for game browsing specifically, not two competing whole-shell
  designs.** An earlier draft of this section described "multiple
  selectable UI paradigms" (a visuals-first artwork-carousel design
  alongside a separate grid/list one, presented as two whole alternate
  shells) as the real intended shape — that framing is now stale, but
  Daijishō's own real INFLUENCE on Handheld's overall structure is not
  superseded, just narrowed to where it actually applies: the real,
  current shell shape (top-level **Games**/**Apps**/**Settings** tabs,
  **Apps** as a flat kind-sectioned browser) is Daijishō-derived, same
  as it's always been. What's real and current as of this update is
  narrower and more specific: **inside the Games tab**, the actual
  system-browsing and per-game-browsing UI is built entirely around a
  real, generic ES-DE theme engine (§7f) — the active theme itself
  (bundled or downloaded) decides the real browsing shape for a given
  system/gamelist view (a real `<carousel>`/`<grid>`/`<textlist>`, or
  neither, per §7f's own real per-theme resolution), not a droidtop-
  level toggle between two hardcoded app paradigms. A grid/list-style
  experience is still fully available inside Games — it's just a
  property of WHICH real theme is active (Art Book Next's own real
  gamelist view uses `<textlist>`/`<grid>`; DEcaffe's doesn't), not a
  separate droidtop UI mode to build and maintain independently. Reads
  the same `Library` —
  including native Android apps, Wine profiles, and Linux-container apps
  as equally first-class entries, not a bolted-on afterthought behind an
  emulator-frontend-shaped data model — droidtop's whole point is that
  Wine/desktop apps aren't a separate, second-class mode. Current
  implementation (`GamepadShell.kt`) has three top-level sections:
  **Games** (browses engine-first, then per-engine game-second — the same
  System → Game hierarchy ES-DE uses, since droidtop's emulated/
  interpreted `LibraryEntryKind`s are its equivalent of ES-DE's
  "systems"), **Apps** (a flat, kind-sectioned browser for everything
  non-emulated — native/Wine/Linux/remote — kept as its own top-level
  section rather than folded into Games, since treating that content as
  equally first-class is the actual differentiator), and **Settings** —
  as of 2026-08-29, no longer a separate in-house Compose screen:
  selecting Settings (tab click or L/R shoulder cycle) opens the real,
  unified `com.android.launcher3.settings.SettingsActivity` (the same
  Android Preference-based screen every other mode's settings live in —
  see §7's own "no separate standalone settings app" note), deep-linked
  straight to its "Handheld mode" category
  (`SettingsHandheldFragment`/`droidtop_handheld_prefs.xml`, `:shell-default`).
  Default section/Show button hints/Console systems/Game folders/Rescan
  library/Theme/Sync theme index are all real, direct Preference entries
  there now (Theme is a dynamically-populated `ListPreference` — real
  discovered theme names, not a compiled-in list — and reads/writes
  `dev.droidtop.library.theme.ThemeAssets`/`ThemePrefs` directly, which
  moved to `:runtime-common` specifically so `:shell-default` could reach
  them without a circular dependency on `:library-core`). **Browse
  themes** is the one deliberate exception left, still jumping into
  GamepadShell's own Compose `ThemeBrowserScreen` (real per-theme
  screenshot previews genuinely need a different interaction shape than
  a flat preference list) — but directly into it now, not through an
  intermediate list. Real, purpose-modularized settings crosslinking:
  each shell's own settings screen shows ITS OWN settings first (not a
  shared root), with real shortcuts to the other shells' own settings at
  the bottom, rather than one shared, generic root every mode's Settings
  entry point lands on identically.

  **Global settings** (`SettingsGlobalFragment`/`droidtop_global_prefs.xml`)
  is deliberately NOT a row inside any shell's own screen — a plain
  preference row can't be visually distinct from whichever shell's screen
  happens to be showing, and it isn't itself a hub between the three
  shells (linking both ways would just be a redundant loop, since every
  shell's screen already links here). It's reached instead via a real,
  persistent action-bar item on `SettingsActivity` itself
  (`onCreateOptionsMenu`/`settings_activity_menu.xml`), present on every
  settings screen — Standard's root, Desktop, Handheld, and Global
  itself — genuinely above the scrollable list rather than part of it,
  hidden only when already on Global settings (`onPrepareOptionsMenu`).
  Real droidtop-wide content lives there, none of it specific to any one
  shell: which HOME role droidtop holds; a **Modes** category (a real
  Default-mode `ListPreference` with dynamic entries reflecting which
  modes are currently enabled, `ModePrefs.defaultMode`; per-mode
  enable/disable `SwitchPreferenceCompat` toggles for Desktop/Handheld,
  `ModePrefs.isModeEnabled` — a disabled mode's entry is hidden entirely
  from `BackButtonMenu`'s own shell-switcher, not shown greyed out); a
  **Data** category (Rerun onboarding — relaunches `OnboardingActivity`
  with no `EXTRA_START_STEP`, which correctly defaults to
  `OnboardingStep.WELCOME`; Back up/Restore settings — a SAF-based JSON
  export/import of the one shared `"com.android.launcher3.prefs"`
  SharedPreferences file every droidtop setting actually lives in,
  honestly scoped: NOT games, ROMs, downloaded themes, or folder grants,
  which can't safely round-trip through a plain JSON file, folder grants
  in particular needing real re-consent rather than a silent restore);
  and a real Android system Settings shortcut — NOT Standard's own
  launcher preferences, which are Standard's own per-shell settings like
  any other. A
  persistent, always-visible controller-button hint bar
  (what A/B currently do) avoids ever leaving the user guessing — theme-
  driven when the active theme declares a real `<helpsystem>`, a
  droidtop-drawn fallback otherwise.
  - **Design direction, not yet built**: a more flexible, data-driven
    launch-mechanism model — new emulators/interpreters becoming
    configuration (a template describing how to invoke them) rather than
    new Kotlin code per integration — is worth adopting once more than
    one external launcher needs supporting via `JoiPlayGameProvider`; not
    done in this pass.

## 7a. Remote PC streaming — via windowcast, not a droidtop module

**Superseded 2026-08-29.** droidtop does not implement its own remote-
streaming client. `:runtime-remote-stream` (a GameStream/Moonlight-
protocol client vendoring moonlight-common-c + mbedtls) has been removed
entirely — droidtop is always a *client* of
[windowcast](../../windowcast) (a separate, far broader project: many
protocols, per-window streaming, selective/adaptive codec and protocol
switching), not a second, narrower implementation of the same job. A
remote gaming PC's library still belongs in the same unified `LibraryEntry`
model as local Windows/Linux apps (`LibraryEntryKind.REMOTE_STREAM` is
kept as the data-model marker for this), but the actual streaming
implementation is windowcast's, out of this repo's scope — see that
project's own docs, not this section, for protocol/pairing/discovery
detail.

`vendor/moonlight-common-c` and `vendor/mbedtls` were removed along with
`:runtime-remote-stream` (nothing else in droidtop used either).

### PC-side helper (`pc-helper/`)

**Needs reconsideration** now that GameStream/Sunshine-specific streaming
is out of scope here — the sections below describe `pc-helper` as it was
designed *for* the removed Sunshine-specific approach (auto-registering
apps with Sunshine's own REST API). Whether `pc-helper` still has a real
job once windowcast is the actual streaming path (e.g. a
protocol-agnostic "trigger a game install on this PC" helper windowcast
itself calls into) hasn't been re-scoped yet — keeping the prior
description below for reference, not as a still-current plan.

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

## 7b. Onboarding, import, and configuration

The onboarding flow itself is real and built (see below). Import and
launcher-sync are still design only, but two real, concrete mechanisms
already exist to build them on rather than invent from scratch (confirmed
by reading actual code/formats, not assumed):

- **Importing from another Android home-screen launcher** has a real AOSP
  mechanism already sitting in `:shell-default`'s forked source:
  `LauncherProvider`, `provider/RestoreDbTask.java`, and `model/
  DeviceGridState.java` are exactly what stock Android's own device-setup
  "restore your home screen" flow uses to migrate a previous launcher's
  workspace layout, and `RestoreDbTask.setPending(context, isManualRestore
  = true)` confirms a manual-trigger path exists — not exclusively tied to
  the OS's full Backup & Restore pipeline. Open question, not yet
  investigated: exactly what has to exist on-device (a real backed-up
  `launcher.db`, accessible from where) for a *manually*-triggered restore
  to have anything to restore from — this needs real testing against an
  actual previous launcher's data, not assumed to just work.
  (Also relevant: `:app`'s own `android:allowBackup="false"` may need
  reconsidering if any part of this ends up depending on the OS backup
  pipeline specifically, as opposed to the manual path.)
- **Syncing with a gaming-focused launcher's library data** (ES-DE and
  other emulation frontends) — data-level only, both directions: reading
  their catalog into droidtop's own `Library` so it stays aware of what's
  there, and (later) writing back entries droidtop knows about that
  they don't yet. **droidtop does not do emulation itself and does not
  run or launch anything on another app's behalf** — this is purely
  library/metadata sync, the same category as the AOSP launcher-import
  mechanism above, not an execution or backend relationship. Launching
  an ES-DE-sourced entry *from* droidtop means handing off to ES-DE (or
  whatever emulator ES-DE itself would use), not droidtop emulating it.
  Reads from the user's ROMs/frontend-data folder via the Storage Access
  Framework (`ACTION_OPEN_DOCUMENT_TREE`, user grants folder access
  explicitly) — this data lives on shared storage, not another app's
  private sandbox, so it's actually reachable under scoped storage rules,
  confirmed via real research rather than assumed possible. Maps into
  `:library-core`'s `LibraryEntry` model like anything else the library
  aggregates.
  - ES-DE and several compatible tools use `gamelist.xml` + per-system
    JSON platform config — a genuine de-facto-standard in this space, not
    a one-off format; one importer plausibly covers more than just ES-DE
    itself.
  - Other gaming-focused launchers exist with their own platform-ID
    registries that in some cases explicitly interop with ES-DE metadata
    as a built-in feature — further confirmation that ES-DE's format
    really is the common denominator other tools build interop around,
    not just droidtop's own assumption. Any such registry's exact schema
    needs real investigation before an importer for it can be built —
    flagged as an open gap, not assumed settled the way the AOSP/ES-DE
    mechanisms above are.
- **Platform/system taxonomy**: `LibraryEntry`'s retro/emulation entries
  need a canonical platform identifier (`"snes"`, `"psx"`, `"gba"`, ...) to
  group, filter, and theme by in the Handheld shell — and to have anywhere
  to map *into* from each importer above. Rather than inventing droidtop's
  own scheme, adopt **ES-DE's `es_systems.xml` platform-naming
  convention as the canonical standard**: it's already the mechanism
  several compatible launchers are built around, and RetroArch/libretro
  core naming lines up with it closely. Each importer translates its own
  source format into this canonical set (tools already using ES-DE's own
  format need little to no translation; anything with its own registry
  needs an explicit mapping table, not yet built since no such schema is
  confirmed yet — see above) rather than droidtop carrying several
  incompatible per-source taxonomies side by side.
- **Onboarding flow (real, built)** — `app/src/main/kotlin/dev/droidtop/app/OnboardingActivity.kt`.
  Onboards the *device*, not one mode: a user shouldn't have to visit
  Settings right after first run just to finish setting up a second mode
  they also want, since configuring a mode and picking the default are
  independent questions. Real flow:
  `WELCOME → HOME_CHOICE → [STANDARD_SETUP | ALTERNATIVE_SETUP] →
  CONFIGURE_MORE (multi-select: Desktop, Handheld) → [DESKTOP_SETUP] →
  [STORAGE_PERMISSION/GAMES_FOLDERS] → DEFAULT_MODE_CHOICE`.
  - **HOME_CHOICE**: how the Android home screen itself should work —
    droidtop's own Standard launcher, a new **Alternative** mode (see
    below), or neither (droidtop claims no `CATEGORY_HOME` role at all;
    its icon just opens `:app`'s `MainActivity` directly like any other
    app). Every mode-specific setup step below is independently
    skippable and re-enterable later from Settings — see each Settings
    fragment's own `PREF_*` entries
    (`SettingsHandheldFragment.PREF_GAME_FOLDERS`,
    `SettingsDesktopFragment.PREF_ROOT_COMPOSITOR_SETUP`,
    `SettingsMiscFragment.PREF_DROIDTOP_HOME_SCREEN`), each relaunching
    `OnboardingActivity` at one step via
    `EXTRA_START_STEP`, not onboarding-only.
  - **DESKTOP_SETUP** runs a live root-capability check
    (`DroidSpacesRuntime.checkSystemRequirements()`) and lets the user
    pick which catalog entry (distro + compositor) to use, stored via
    `DesktopSetupPrefs` and honored by
    `DesktopSessionService.selectPrimaryImage` — closes what used to be
    "no user-facing compositor-choice setting yet."
  - **DEFAULT_MODE_CHOICE** calls `ModePrefs.setLastMode` — this is what
    `com.android.launcher3.Launcher`'s own real cold-boot redirect
    (`mDroidtopPendingModeRedirect`, already shipping) and
    `AlternativeLauncherActivity`'s equivalent redirect (see below) both
    read to send a user straight to their actual default mode instead of
    always landing on whichever HOME activity is currently active.
- **"Alternative" mode — droidtop holds HOME, forwards to a different
  installed launcher.** Real, verified mechanism (not guessed) — confirmed
  against `farmerbb/Taskbar`'s actual shipping source (`HSLActivity.java`):
  a second `CATEGORY_HOME` activity
  (`shell-default`'s `AlternativeLauncherActivity`), disabled by default,
  mutually exclusive with `com.android.launcher3.Launcher` via
  `HomeRolePrefs.setActiveHomeImplementation` (`PackageManager.setComponentEnabledSetting`
  on each, opposite states — never both enabled). On launch it resolves
  the user's saved target (`HomeRolePrefs.alternativeTarget`, picked from
  `PackageManager.queryIntentActivities` on `ACTION_MAIN`+`CATEGORY_HOME`,
  no extra `<queries>` block needed since `shell-default`'s manifest
  already holds `QUERY_ALL_PACKAGES` for its own app-drawer needs) to an
  explicit `ComponentName` and forwards via `startActivity`. Falls back to
  Settings if the saved target is missing/uninstalled rather than looping
  or crashing. `BackButtonMenu`'s "Android" menu entry resolves to
  whichever of Standard/Alternative is currently active
  (`HomeRolePrefs.activeHomeImplementation`), and is hidden entirely when
  neither is. A separately-considered "Secondary Launcher"/`SECONDARY_HOME`
  AOSP concept (`com.android.launcher3.secondarydisplay.SecondaryDisplayLauncher`)
  turned out to be unrelated — that's Launcher3 itself rendering on an
  *external display*, not forwarding to a different launcher app.
- **`shell-default`'s Standard shell is already a full-featured Murine
  Launcher/Launcher3 fork**, not a bare-bones fallback — real settings
  already exist (`SettingsGeneralFragment`, `SettingsHomeFragment`,
  `SettingsDrawerFragment`, `SettingsIconPackFragment`,
  `SettingsIconsFragment`, `SettingsQsbFragment`,
  `SettingsHiddenAppsFragment`, `SettingsMiscFragment` — backup/restore,
  default-launcher picker). Onboarding's `STANDARD_SETUP` step points here
  rather than re-inventing any of it; if the user can't use droidtop's own
  launcher the way they want, choosing it isn't a real option, so it needs
  to be genuinely full-featured, not an implicit no-setup-needed fallback.

## 7c. Wine prefix / container configuration UI

Not designed in detail or implemented — but real, needed UI surface, not
an afterthought: managing Wine prefixes (Windows version, DXVK/VKD3D
toggles, installed components, per-prefix vs. shared) and Linux sibling
containers (create/clone/delete/stop, which distro, installed packages,
folder/data mounts — §2a already establishes these are user-configurable,
distrobox-style) both need real UI, not just the underlying runtime logic.

Two concrete references to build from rather than design blind:

- **Wine prefix management**: [vendor/gamenative](../vendor/gamenative)
  has its own real, working UI for exactly this — `ContainerConfigDialog.
  kt`/`ContainerConfigState.kt` (Windows version selection, DXVK/VKD3D
  configuration, installed-component tracking) and
  `ContainerStorageManagerDialog.kt` (per-container storage).
  **Decision (2026-08-31): these arrive by COMPILING, not porting** —
  `:runtime-windows` now compiles the entire vendored gamenative tree
  (see §9), so this UI is already built into droidtop's APK; what
  remains is increment 2 of that work (Hilt bootstrap in `:app`,
  manifest components, an entry point from droidtop's own settings), not
  a file-by-file port.
- **Linux container management**: distrobox itself is CLI-only (no
  official GUI), but [BoxBuddy](https://github.com/Dvlv/BoxBuddy) is a
  real, actively-maintained GTK4 GUI for it — confirmed feature set:
  per-container create/clone/delete/stop/upgrade, viewing installed apps,
  opening a terminal into a container. [DistroShelf](
  https://github.com/ranfdev/DistroShelf) is a second distrobox-GUI
  reference worth comparing against, not just BoxBuddy alone. Neither is a
  fork target (both are GTK/Linux-desktop apps, not Android) — interaction
  patterns and feature scope to learn from, the same way other gaming-
  focused launchers are UX references for `:shell-gamepad` rather than
  code to port.

## 7d. VN/RPG-Maker engine games — JoiPlay support + `enginehost`

Superseded, corrected understanding — this section previously assumed an
undocumented JoiPlay launch API existed and hadn't been verified.
Verification here stayed at the manifest/structural level (`aapt dump`,
`dumpsys` — what any Android app's public-facing package inspection
tools show), not a full decompile of JoiPlay's own closed-source
implementation, since that would likely violate its own ToS/EULA and
wasn't cleared with the user first.

**Real, confirmed at the manifest/behavioral level** (not guessed):
- JoiPlay's own "Add Game" import flow is documented (its own site/wiki)
  to work by picking a game archive through its file browser, and it
  extracts into its own app storage — **droidtop never observed or relied
  on any way to add a game to JoiPlay's catalog without a real copy
  landing in JoiPlay's own storage.**
- **Per explicit, repeated, non-negotiable direction: droidtop must never
  copy, move, or otherwise duplicate a user's game files as part of any
  integration** — this rules out driving JoiPlay's own import
  automatically, copy-based or otherwise. Symlinks on Android shared
  storage were tested directly on a real device and don't work either
  (`ln`: "Function not implemented" — a FUSE storage-layer limitation,
  not a permissions issue). Root was considered and explicitly rejected
  as a "solution" — it works on the one dev device, but isn't a real
  answer for other users.
- JoiPlay ships separate plugin APKs per engine (RPG Maker, Ren'Py, Flash,
  etc., confirmed via `pm list packages` on the real test device) — a
  real, standard Android app-plugin pattern (a shared intent-filter
  action + package discovery), the same general shape `enginehost`'s own
  plugin system below uses. No shipped JoiPlay plugin exists for KiriKiri
  anywhere (confirmed: not installed on the real test device, and the
  one public attempt in JoiPlay's own GitHub org has been inactive since
  2022 with no real plugin wiring committed) — a real, confirmed gap
  worth droidtop filling itself.

**Conclusion, and the actual current design**: JoiPlay is a good,
independent, user-facing project worth supporting as-is, not routing
around — droidtop does not try to automate its import. **`enginehost`**
(a separate, standalone repo — `bi0shacker001/enginehost`, deliberately
NOT droidtop-branded, since it's meant to be usable by anything, not just
droidtop) is droidtop's own answer for the cases JoiPlay doesn't cover
programmatically:

- **The whole contract**: fire `ACTION dev.enginehost.LAUNCH` with a
  `path` extra (an absolute folder) and optionally an inline `config`
  JSON extra (used only if the folder has no `enginehost.json` of its
  own — the folder's own file always wins). No catalog, no import step,
  nothing about the folder ever copied or moved.
- **Plugins are separate, manually-installed apps, each its own repo**,
  discovered via `PackageManager` (the same real mechanism JoiPlay's own
  plugin system uses) — identified by `(engine, engineVersion,
  pluginVersion)`. Resolution: exact `engineVersion` match, else nearest;
  an optional per-game `pluginVersion` constraint (comma-separated exact
  versions and/or ranges) lets a game exclude specific plugin builds it's
  known to regress on, independent of engine version — the real
  motivating case being JoiPlay's own RPG Maker plugin reportedly
  regressing specific games in newer builds.
- **Full detail, methodology, and current status**: see
  `/root/coordination/HANDOFF.md`'s own "ES-DE theme engine" and
  "enginehost" sections (kept up to date there, not duplicated here) —
  and `enginehost`'s own README for the real contract spec.
- **Real engine coverage plan, replacing the old Pythia-derived
  RENPY/RPG_MAKER_MV/MZ/VX_ACE-only detection**:
  - **KiriKiri** (`kirikiroid2-joiplay`, a fork of Kirikiroid2Yuri) — the
    one confirmed real gap in JoiPlay's own ecosystem. Plugin shell real
    and discovered correctly by enginehost; engine not wired up yet
    (blocked on finding how to point Kirikiroid2's native init at an
    arbitrary runtime folder — see handoff doc).
  - **RPG Maker XP/VX/VX Ace** via `mkxp-z` (the same real open-source
    engine JoiPlay's own plugin wraps) — covers Monster Girl Quest
    Paradox (confirmed VX Ace). Not started.
  - **Ren'Py** via a fork of the real upstream engine
    (`bi0shacker001/renpy`) — `master` auto-syncs with upstream,
    `plugin/renpy8` branch exists for the real Android patches (not
    written yet).
  - Detection signatures for these (and RPG Maker 2000/2003 via
    EasyRPG's own real engine, WOLF RPG, TyranoBuilder, NScripter) were
    researched and confirmed against real project docs this session —
    see handoff doc for the verified per-engine file signatures.
- **`LibraryEntryKind` stays named per-engine, not per-launcher** (as
  before) — a droidtop `LibraryProvider` detecting a folder can hand off
  either to JoiPlay (foreground-launch only, letting the user add it via
  JoiPlay's own UI if they choose) or to an installed enginehost plugin
  via the contract above, without the entry's own kind needing to know
  which.

## 7e. Second-screen / ambient integrations (Spotify now-playing, Discord presence)

Useful both on the Dual-Screen Add-On (a second physical display, per §4)
and as an idle-screen/status-bar widget on a single-screen device.
droidtop's dual-screen model splits interaction and context the way the
Nintendo 3DS's own dual-screen convention works: navigation/interaction on
the primary screen, ambient context/status on the second — generalized
for droidtop's own scope beyond gaming (§1): a second-screen "info" role
that's just as at home showing Discord/Spotify/system status during
desktop use as it is showing contextual art while browsing Games.

The second screen's "info" role has several real tenants, switching based
on what's happening rather than competing for one fixed layout:

- **`FocusCompanion`** — a live, ambient reflection of whatever's focused
  on the primary screen (e.g. contextual art while browsing a platform or
  game list), registered per-context by whichever shell/screen owns
  primary-screen focus at the time. Not built this session.
- **`PresencePanel`** — a deliberate, always-visible panel a user opens,
  hosting now-playing state and controls for linked media apps and
  (eventually) Discord's friends/voice presence, one card per connected
  app. Not built this session.
- **Routed notifications** — real Android notifications (Discord already
  posts real ones for DMs/mentions; media apps post a real persistent
  now-playing notification) plus per-display-aware heads-up/toast routing,
  on top of whatever `WindowPlacement` (§4) has assigned to each physical
  output. This gets ambient presence "for free" from the platform without
  a custom polling overlay, complementary to the `PresencePanel` rather
  than a replacement for it.

**Media app control — real, local, no credentials handled by droidtop.**
Per explicit direction: droidtop must never hold a streaming service's own
credentials (no OAuth, no developer app registration, no stored tokens),
and control needs to be real — search, playlist/library browsing, and
transport control (play/pause/skip/seek) against whatever's actually
running in the installed app, not just a read-only now-playing display.
An initial design scaffolded a Spotify-specific OAuth/Web-API client for
this; removed once a better real option was confirmed: the standard
Android `android.media.browse.MediaBrowserService` API (`MediaBrowserCompat`
/`MediaControllerCompat`, `androidx.media`) — exactly the mechanism Android
Auto, Wear OS, and Google Assistant use to browse and control a media app
without ever seeing its login. droidtop binds directly to the target app's
own exported service over local IPC; no network calls, no credentials, the
user's session stays entirely inside that app's own process.

Generalized beyond Spotify per direction, since this is a standard Android
API any compliant app can implement, not a Spotify-specific integration.
`library-core/.../presence/MediaAppBrowserClient.kt` is the one client
(package/service-agnostic, parameterized by a `KnownMediaApps.Target`);
`KnownMediaApps` holds the real, **device-verified** targets found this
session (`adb shell dumpsys package <pkg>`, filtered for `android.media.
browse.MediaBrowserService` on this project's own real test device, not
guessed):
- **Spotify** (`com.spotify.music`) — verified.
- **YouTube** — verified against this device's actual installed build,
  which happens to be a ReVanced-patched APK; not yet confirmed whether
  the official `com.google.android.youtube` package uses an identical
  service class name.
- **Jellyfin** (`org.jellyfin.mobile`) — verified.
- Tidal and YouTube Music were asked about too, but neither is installed
  on the test device, so — same standard as everything else in this spec —
  they're deliberately left out of `KnownMediaApps` rather than guessed at;
  add once confirmed against a real install.

Real remaining work: browse/search/playback-control behavior hasn't been
run end-to-end against a real connected session of any of these three
apps yet (the component names are verified real, but what each app's
content tree actually looks like, and whether each really implements
`onPlayFromSearch`, isn't confirmed), plus the `PresencePanel` surface
itself.

**Discord — real, official, self-service: the Discord Social SDK, not a
bot.** Discord publishes an official Social SDK for embedding real social/
voice features (friends, presence, voice, guild/channel access) into a
third-party app, with a native login/consent flow — a materially better
fit than the Gateway-bot-relay workaround this section originally assumed
was necessary before that SDK's existence was confirmed. Setup is
self-service like Spotify's: register a free Discord application at the
Discord Developer Portal, get a client ID, and the SDK's own auth flow
handles user login/consent. Not implemented this session (it's a native/
C++ library with its own download + JNI integration, more setup than
Spotify's plain REST client) — real next step: pull the SDK's own public
integration docs from Discord and scope a `library-core/.../presence/
DiscordPresenceClient` wrapper, same shape as the Spotify client, feeding
the same `PresencePanel`.

## 7e2. Data-driven player/platform database (directed 2026-08-30)

Standalone-emulator launch definitions (the non-RetroArch emulators) are
DATA, not code: `players-database.json` — a bundled seed asset in
library-core plus a separately-updatable copy refreshed, user-driven,
from the droidtop-platforms repository on GitHub
(`bi0shacker001/droidtop-platforms`). `KnownPlayers` loads
filesDir-copy-if-valid, else the seed; `PlayersDatabaseUpdater` fetches
with parse-validation before replacing anything. The previous state —
117 presets as generated Kotlin — required an app release to add an
emulator; now the database grows independently.

**Generation, not hand-maintenance**: the platform-db repo's generator
builds the console entries programmatically from other frontends' own
real, maintained databases — ES-DE mobile's `es_systems.xml` +
`es_find_rules.xml` (MIT; the richest source: per-system standalone
commands with real intent details), Daijishō's public Start-Arguments
wiki (the current seed's origin), and iiSU's database (a third Android
game frontend — source format/license not yet researched). Windows,
Linux, and engine-game entries are droidtop's own to author — no
upstream frontend maintains those.

**JoiPlay is permanently out of the launch loop** (per direction,
re-confirmed after repeated rediscovery): it exposes no consumable
launch surface. Engine games launch via enginehost, Kirikiroid2, Wine,
or a Linux-container build — never JoiPlay, regardless of it being
installed.

## 7e4. Emulator setup helpers (directed 2026-08-31, EmuDeck-style)

Guided per-system setup instead of dead ends, all data-driven like §7e2:
`bios-database.json` in droidtop-platforms is GENERATED from Batocera's
real, maintained BIOS registry (`batocera-systems`, GPL — the same
md5/path data Batocera's own missing-bios checker uses; regenerate with
`generator/from_batocera.py`, never hand-author hashes). droidtop's
`BiosDatabase` (:library-core) mirrors `KnownPlayers`' bundled-seed +
GitHub-refresh + validate-before-replace model, and checks a system's
firmware under `<gamesRoot>/bios` by presence AND md5 (the classic
"right name, wrong dump"). Surfaced as settings-catalog rows in each
folder's screen: a BIOS status screen per system that needs firmware,
and — when NO installed emulator can run a system — "Get an emulator"
actions built from the player database's real packages (market:// with
a web fallback). Follow-ups, not started: per-emulator install sources
beyond Play (GitHub releases in the players DB), and applying
recommended per-emulator settings where an emulator exposes a real
configuration surface.

## 7e2b. Launch resolution FROM the platforms database (directed 2026-08-31)

Extends §7e2 to the whole launch pipeline: droidtop-platforms is the
authority for launch data, four databases, each bundled-seed +
GitHub-refresh + validate-before-replace:

- `players-database.json` — per-system emulator launch presets
  (`KnownPlayers`), as before.
- `platforms-database.json` — platform definitions (id, name,
  extensions, RetroArch core), GENERATED from ES-DE's real
  es_systems.xml (`generator/from_esde_systems.py`; 195 platforms, 153
  with cores). Replaces the formerly compiled-in
  `ES_DE_CONSOLE_SYSTEMS` Kotlin list (deleted) as the seed for
  `ConsoleSystemsRepository`'s Room store — Room stays the runtime
  source of truth because the user can edit platforms.
  `PlatformsDatabase.builtInsOrEmpty()` serves the synchronous label
  lookups (shell group labels, companion), warmed at process start.
- `engines-database.json` — the full engine REGISTRY as of v4
  (directed 2026-08-31: detection, launching, and enginehost vocabulary
  all drive from the JSON bundles so coverage grows as a data update,
  not an app rebuild). Per engine row: `detect` (ordered rules; rules
  OR, conditions AND, first matching row in file order wins; condition
  types dirExists/fileExists/anyFileNameContains/anyFileExtension/
  anyFileNameIn/dirNamePrefixCount/fileHeadRegex, plus `builtin` naming
  a code probe for byte-magic checks JSON can't express — Godot's GDPC
  trailer, Unity's depth-limited player search, Twine's html scan),
  `strategies` (launch priority, as before — availability stays code so
  a bad download can never make an unlaunchable strategy launch), and
  `enginehost` (the family/context/extras/versionSelectorFallback
  vocabulary that used to be a hardwired Kotlin map). `EnginesDatabase`
  parses it via `EngineRegistryParser`; `GameEngineDetector.detect`
  evaluates the rules; a registry row whose id this app doesn't know is
  skipped (future database, older app), an unknown condition type or
  builtin fails its rule rather than matching, and an update whose file
  carries no detection rules at all is rejected as a legacy v3
  database. Users can pin a folder to an engine explicitly
  (`EngineOverridePrefs`, the engine twin of `SystemOverridePrefs` —
  stored by database id, wins over every rule). RPG Maker XP/VX joined
  the engine set with the v4 registry (enginehost contract contexts
  `xp`/`vx`, detected via their real archive/project/Game.ini RGSS
  signatures). The unit tests parse the SHIPPED seed file, so registry
  edits that break detection fail CI before they ship.
- `bios-database.json` — §7e4's firmware registry.

One user action refreshes all four ("Update platform databases" in the
console-systems catalog screen).

### Which system a ROM belongs to (2026-08-31)

Three signals, in this order: the scanned `systemId` stored on the
entry, then a `SystemOverridePrefs` folder override, then the folder
name via `SYSTEM_ID_ALIASES`. The scan itself may correct the folder
name from file content (`SerialScanner`), which is right when a disc
image is misfiled and wrong when the detector is wrong -- so the
detector has to actually be right.

**droidtop's detection is not limited to what the vendored scanner
covers.** `SerialScanner` is forked from Lemuroid, whose system coverage
is its own emulator's, not droidtop's. Where droidtop supports a system
Lemuroid does not, the detector gets extended rather than droidtop
inheriting the gap.

The first real case, found by an all-systems launch sweep: every PS2
disc was being detected as PS1, so PS2 games launched through DuckStation
-- a PS1 emulator that cannot run them -- while an installed PS2 emulator
went unused. The magic-number check keys on the ISO9660 volume descriptor
system identifier, and that string is "PLAYSTATION" on PS1 and PS2 discs
alike; `SystemID` had no PS2 constant at all.

The discriminator is SYSTEM.CNF: PS1 boots through a `BOOT` line, PS2
through `BOOT2`. droidtop finds that file by **reading the image's
ISO9660 filesystem** -- volume descriptor, root directory, file extent --
rather than scanning for the string.

That distinction was earned rather than assumed. Scanning was tried
first and cannot be made to work: across seven real discs SYSTEM.CNF sat
anywhere from byte 552,960 to byte **3,923,748,864**, so no sane window
catches them all; two discs with the file at the identical offset gave
different answers between runs, because fixed-size window reads off an
SD card return short and lose a match that straddles the gap; and one
disc writes `BOOT2=` with no spaces at all. Reading the filesystem is
also cheaper -- two small seeks instead of megabytes of scanning.

`SerialScanner` stays PS1-only by design and carries a note saying so;
PS2 discrimination lives in `PlayStationDiscType`/`Iso9660`, so there is
one discriminator rather than two.

**An unreadable disc is unknown, never PS1.** The magic-number check
reaches "PSX" from the volume identifier alone and defaults to it even
when nothing else was learned. That was safe when only PS1 discs carried
that string; with PS2 sharing it, "magic matched, nothing else known"
means *unknown*. Since a scanned `systemId` outranks the folder name, a
failed read that answered PS1 would override a correct `/Roms/ps2/` --
which is exactly what happened on-device when a scan was interrupted
mid-read. So for a disc image, if SYSTEM.CNF cannot be read droidtop
reports unknown, logs it, and lets the folder name decide. The rule
generalises: content detection may only override the folder when it
actually determined something, never as a default. A serial does not
count as determining it here, since PS2 serials use the same prefixes as
PS1's.

Because the scan result is cached in `rom_entries`, a detection fix ships
with a `RomDatabase` migration clearing that cache and `scan_metadata`;
otherwise it changes nothing for anyone who has already scanned.
`game_metadata` and the collection tables are preserved -- favorites,
completed flags and collection membership are real user data.

## 7e3. Lutris install-script integration (directed 2026-08-30, backlog)

Beyond cover art (§7d's Lutris scraper client), lutris.net's real public
install-script database is a fit for the PC side: per-game scripts that
declare how a game from an arbitrary source (user-provided installers,
GOG/itch builds, engine games) gets set up — files, Wine settings,
required runtime pieces. Consuming those would let droidtop/gamenative-tux
auto-configure games the user supplies themselves instead of only what
GameNative's own community-config backend covers, diversifying away from
a single config source. Standing note, not started: evaluate Lutris first
but not exclusively (its coverage/format may not be the best fit); any
runner-execution mapping goes through the existing strategy resolver
(§7e2) and gamenative-tux's container backends, never a new parallel
launch path.

## 7f. Handheld mode: real, generic ES-DE theme engine

**Status as of 2026-08-29 — this is Handheld's actual, current, singular
paradigm (§2a's earlier "multiple selectable paradigms" framing is
superseded, see that section's own updated note).** droidtop's Handheld
mode renders real, vendored ES-DE (EmulationStation Desktop Edition)
themes — currently `decaffe-es-de` (bundled, CC-BY-NC-SA) and
`art-book-next-es-de` (no longer bundled — it was kept in the APK to
stress-test the engine against a second real theme shape, and was removed
once the theme downloader could fetch it on demand, cutting roughly 220MB
from the install; structurally different — real multi-file `<include>`
chain, per-aspect-ratio XML, real `<textlist>`/`<grid>` gamelist widget,
unlike decaffe's widget-less one) — parsed by a real clean-room port of ES-DE's own theme.xml
parsing rules (`library-core/.../theme/EsDeThemeParser.kt`,
`EsDeTheme.kt`'s `ES_DE_ELEMENT_SCHEMA`) and rendered by
`shell-gamepad/.../theme/EsDeThemeRenderer.kt`.

**Real, working today** (each confirmed against real ES-DE source,
`/root/es-de-reference` in the dev container — see `coordination/
/root/coordination/HANDOFF.md` for full detail/citations (that path is
outside this repository), this section is the durable
summary):

- Real multi-theme discovery/selection (`ThemeAssets.discoverThemes`/
  `resolveActiveTheme`, mirrors `ThemeData::populateThemes` exactly — a
  folder is a valid theme iff it has `capabilities.xml`; the active
  theme is a stored name falling back to the first theme alphabetically,
  never a hardcoded folder name) and a real JGit-based theme downloader
  (`ThemeDownloader`, clones the real `gitlab.com/es-de/themes/
  themes-list.git` index the same way real ES-DE's own
  `GuiThemeDownloader` does) with a real browse/download UI
  (`ThemeBrowserScreen`, including real per-theme screenshot previews).
- Real per-system (`carousel`/`grid`/`textlist` list-widget positioning/
  scale/animation, ported from `CarouselComponent.h`/`GridComponent.h`)
  AND per-game gamelist rendering — ONE real, generic render path
  handles a theme with a real gamelist list widget (Art Book Next) or
  none at all (DEcaffe, which relies on real ES-DE's own always-present
  headless per-game cursor instead) — never a droidtop-level "which
  theme is this" branch.
- Real per-game metadata: `LibraryEntry` models description/developer/
  publisher/genre/releaseDate/rating/players/favorite (explicitly NOT an
  ESRB field — confirmed real ES-DE has none), populated by real
  ScreenScraper and TheGamesDB scraper clients (real ES-DE's own actual
  ROM scrapers — not Lutris/IGDB, which are reserved for PC/engine
  content per §7d) wired into `ConsoleSystemsActivity.kt`'s manual
  per-folder scrape action, single-selected-source only (real ES-DE has
  no automatic multi-source fallback chain).
- Real element rendering: `image`/`text`/`carousel`/`grid`/`textlist`/
  `video`+`animation` (static-fallback only, no real playback yet)/
  `clock`/`datetime`/`rating`/`helpsystem`/`badges` (a real, full
  `FlexboxComponent`-ported layout — grid/direction/alignment/itemMargin/
  lines/itemsPerLine math, not an approximation — rendering 7 of real
  ES-DE's 9 real slots: favorite/completed/kidgame/broken/controller/
  altemulator/manual; `collection`/`folder` stay honestly unrendered,
  blocked on the separate collections gap below)/`systemstatus`
  (wifi/cellular/battery, real live device status — droidtop genuinely IS
  the host; bluetooth deliberately excluded, needs a dangerous runtime
  permission)/`gamelistinfo` (game+favorites count, no filter/folder
  cases). Real input-mapping abstraction (`GamepadAction` +
  `GamepadKeyMap`) feeds both real input handling and the theme's own
  real `<helpsystem>` labels.
- Real per-game metadata editor (`GameMetadataEditor`, droidtop's own
  equivalent of real ES-DE's `GuiMetaDataEd`) — reachable via an "Edit
  metadata" action on the game detail screen, covers the full real
  `MetaData.cpp` field set (completed/kidGame/hidden/broken/
  noGameCount/noMultiScrape/hideMetadata/controller/altEmulator/
  launchScreen/sortName/collectionSortName, plus editing the existing
  scraped description/developer/publisher/genre/players/releaseDate/
  rating fields directly). `GameMetadataEntity`/`RomEntity` schema
  bumped (v3→v4) with a real, handwritten migration preserving existing
  rows — a destructive wipe would have silently discarded real favorite
  toggles. `EsDeControllers` (runtime-common) ports real ES-DE's own
  37-entry controller list unchanged, for the controller-badge/metadata
  picker.
- A real `droidtop-theme-patches` companion repo
  (`github.com/droidtop/droidtop-theme-patches`) — a deliberately
  empty scaffold (real system-id list + real ES-DE metadata field
  template, no filled content — no AI-generated placeholder data) for
  community-contributed per-system metadata covering droidtop's own
  invented engine systems (Ren'Py/RPG Maker variants/KiriKiri), which no
  real ES-DE theme has metadata for since they aren't consoles.

**Real, live-device bugs found and fixed 2026-08-29** (this session,
each confirmed via a real on-device debug log or screenshot before being
fixed, not guessed — see git history for the individual commits):

- `AmStartCommandToIntentConverter` was handing emulators a plain
  `file://` URI, crashing with `FileUriExposedException` on modern
  Android for any of the ~66 real player presets using `{file.uri}` —
  fixed via a real `FileProvider`/`content://` URI.
- `EsDeThemeParser.parseView` replaced (instead of merged) a theme
  element re-declared across multiple `<view>`/`<variant>` blocks —
  wiped decaffe's own carousel `pos`/`size`/`origin` every time its
  later, narrower variant-scoped redeclaration (`staticImage`/
  `imageColor` only) was parsed.
- With no theme ever explicitly selected, `ThemeAssets.resolveActiveTheme`
  fell back to alphabetically-first among bundled themes —
  `art-book-next-es-de` (its own real, intentional full-screen "hero"
  carousel design) was silently rendering instead of decaffe on every
  fresh install. Now prefers decaffe by name when unset.
- The system-list carousel's D-pad left/right never moved focus at all —
  `EsDeCarouselItem`'s absolutely-positioned `.focusable()` items don't
  get Compose's spatial arrow-key traversal for free the way LazyRow
  children would; fixed via explicit `FocusManager.moveFocus`.
- **The big one**: `parseNode`'s variant/colorScheme/fontSize/aspectRatio
  axis matching did plain string equality against a block's RAW,
  un-split `name` attribute — a real, comma-separated multi-name block
  (decaffe's own `<variant name="solidWithoutMeta, solidWithMeta">`,
  which holds the actual metadata sidebar/description panel/game-preview
  content) never matched any single selected value, so that whole real
  render path was silently skipped end to end. This, not a carousel
  rendering bug, was the root cause of the system view looking almost
  entirely blank next to `sys.png`'s own reference screenshot. Confirmed
  fixed live: the metadata sidebar and description text both render now.

**Done (2026-08-30)**: real ES-DE collections — droidtop's own
`CollectionEntity`/`CollectionMemberEntity` (`RomDatabase` v5) for
custom collections, plus real, computed-on-the-fly auto collections
(all games/favorites/last played, `LAST_PLAYED_MAX`=50 confirmed
against `CollectionSystemsManager.cpp`). Both appear as real
`GameGroup.Collection` pseudo-systems leading the Handheld system
carousel, with real per-collection theme overrides (`auto-allgames`/
`auto-favorites`/`auto-lastplayed`/`custom-collections` theme
subfolders, confirmed against the same real source) — falls back to
the theme's root `theme.xml` when a theme doesn't declare that
subfolder, a deliberate droidtop simplification (real ES-DE hides an
incompatible collection from the carousel entirely instead). Real UI:
`CollectionMembershipEditor` (create + toggle per-game membership,
reachable via a "Collections" action on the game detail screen).
Two real, confirmed-live theme-engine bugs found and fixed while
building this: `system.name`/`system.fullName` variables were never
populated at all (only `system.theme` was), and theme-defined
`<variables>` blocks never resolved their own embedded `${...}`
placeholders (Art Book Next's own bundled per-system-metadata fragment
uses exactly this real pattern for `systemDescription`). Badge
`collection` slot (8th of 9 real slots) now wired too — a reverse
"which games are in ANY collection" query
(`RomDao.getGameIdsInAnyCollection`) feeds `LibraryEntry.inCollection`
at library-merge time in `ConsoleRomProvider.withMetadata`. Real, still
deferred: `gamelistinfo`'s folder-entered case specifically (folders
are a different, still-unmodeled concept from collections); the badge
`folder` slot (droidtop's ROM scan is flat, no gamelist-subfolder
concept to detect membership in). **Done (2026-08-30)**: real `video`
element playback via ExoPlayer/media3 (`EsDeThemedVideo`), reading a new
`LibraryEntry.videoUri` resolved at scan time
(`EsDeArtwork.resolveVideo`, real ES-DE `videos` media-type convention),
looped and muted (no per-view visibility signal exists yet to safely
unmute), falling back to the existing static-image path when a game has
no scraped video. **Done (2026-08-31)**: the last two open element
types. `sound` — real ES-DE navigation sounds (the seven real names
from `NavigationSounds::loadThemeNavigationSounds`, Sound.cpp:213-219:
systembrowse/quicksysselect/select/back/scroll/favorite/launch,
declared under the special `all` view per THEMES.md), played through a
SoundPool-backed `EsDeNavigationSounds` singleton bound wherever the
active theme loads, with each trigger wired at the existing real
key-handling site matching its real ES-DE call site (systembrowse:
system-carousel move; scroll: gamelist move, headless or widget;
select: entering a system; back: gamelist drill-up, both the key and
back-dispatcher routes; quicksysselect: Left/Right sibling-system jump;
favorite: actual favorite toggle; launch: the one launch handler). No
bundled fallback sounds, deliberately — real ES-DE falls back per file
to its own .wav resources, which aren't ours to redistribute, so an
undeclared/missing sound plays nothing (the bundled decaffe theme is a
real known no-op on its own terms: its navigationsounds.xml is never
included from theme.xml and declares `./core/sounds/` paths while its
wavs live in `./assets/sounds/`). `animation` — real GIF playback plus
APNG (APNG4Android, the same library/version the vendored gamenative
catalog pins), dispatched by extension exactly like real ES-DE
(SystemView.cpp:648-676: .json→Lottie, .gif→GIF, else refused), with
.png/.apng as droidtop's one deliberate widening of that set; Lottie
(.json) is parsed but rendered as nothing with a logged warning — real
ES-DE treats it as a separate component class (rlottie) and it stays
out of scope. Animation properties speed/direction/interpolation/
colorEnd-gradient/stationary/metadataElement are parsed but not
applied (no decoder-level control for them) — honest gaps documented at
`EsDeThemedAnimation`.
Generalizing the
real gamelist list-widget path (`EsDeListItem`) to badge/rating overlays
the way a real theme's own game GRID entries sometimes show them inline;
droidtop's own "Continue Playing" row (a real bolt-on with no equivalent
in real ES-DE, see its own doc comment in `GamepadShell.kt`) visually
collides with decaffe's real metadata sidebar now that the sidebar
actually renders — needs real per-theme-aware safe-zone placement, not
a hardcoded top-left anchor; not yet designed. Also unverified: the
`${helppos}`/`${helpFontSize}` `font.xml` variables (a *different*
variant axis, `fontSize`, from the one fixed above) may still fail to
resolve — worth re-checking now that the axis-matching fix landed, since
it could turn out to already be fixed as a side effect.

Full real history/reasoning for each of the above (commit-by-commit,
with citations to the exact real ES-DE source lines each decision was
verified against) lives in `/root/coordination/HANDOFF.md`'s own theme-engine
section, not reproduced here — that file is the working log; this
section is the durable, periodically-refreshed summary per this
project's own convention of writing real decisions into SPEC.md itself.

**Refactor decision (2026-08-30)**: a full code audit against real ES-DE
source (`/root/es-de-reference`) and side-by-side device-vs-reference
screenshots found the incremental patch approach had converged on a
architecture that CANNOT reach real parity — not individual bugs, but
three hand-synced layers (a hand-picked property whitelist, a parser
that drops anything not whitelisted, ~15 per-element Compose renderers
each re-implementing geometry/color/font/text independently) where every
fix corrected one wrong constant while the bug *class* survived.
Confirmed critical defects: the schema silently dropped real,
theme-declared properties (`staticImage`/`imageColor`/`maxItemCount`
were missing from carousel entirely — real system logos and item counts
never worked); `text` metadata-key mapping is backwards vs. real
`GamelistView::getMetadataValue` (theme key `"description"` must map to
file key `"desc"`, not itself — real game descriptions have never
rendered, ever); image `<color>` used Compose `ColorFilter.tint`
(replace) instead of real ES-DE's color-shift *modulate* semantics
(`ImageComponent::setColorShift`) — flattens any tinted background art
into a silhouette; `fontPath` was read by the parser but never applied
by any renderer — every themed screen renders in the system default
font regardless of what a theme bundles; real ES-DE collections resolve
their own theme presentation via `${system.theme}` = their own
collection-folder name (`auto-allgames` etc.) — droidtop invented an
unrelated "subfolder theme.xml" mechanism that matches no real bundled
theme, so collections lose their themed presentation entirely.
Decision: a scoped refactor, not further incremental patches. It was
planned as R1–R5 and described as tracked in `coordination/HANDOFF.md`;
neither held. There is no `coordination/` directory in this repository,
and `/root/coordination/HANDOFF.md` contains no R1–R5. The numbering was
abandoned after R2: the later passes are recorded below under dated
descriptive headings instead, so R3–R5 were never delivered under those
names and should not be looked for. The plan below is kept for the shape
of the work, not as a live tracker — R1 (full,
verbatim `ThemeData::sElementMap` schema transcription, landed this
commit, including the real XML-attribute-keyed `customBadgeIcon`/
`customControllerIcon` parsing droidtop's parser never implemented at
all) through R5 (primary-component full fidelity). Parser core (include
resolution, variant axes, multi-name matching, element-merge semantics)
and theme infrastructure (discovery, JGit downloader, prefs) were
confirmed sound in the same audit and are NOT being rewritten — this is
a renderer + schema correction, not a rewrite.

**R2, primary-component renderer parity (2026-09-01)**: R1 left the
parser complete (472 real properties across 16 real element types) and
the renderer at roughly half of it. A fresh count of how many of those
472 property names are referenced anywhere in `:shell-gamepad`'s render
code put it at 242 -- and that count is generous, since it credits a
property whose name merely appears somewhere. The two clusters where the
gap actually made real community themes render WRONG rather than plain
were `carousel` (28/73) and `textlist` (12/38); both are addressed here.

The shape of the fix is the same for both, and it is architectural
rather than a property checklist: the geometry moved out of the Compose
composables into pure, dependency-free Kotlin in `:runtime-common`
(`EsDeCarouselLayout.kt`, `EsDeTextListLayout.kt`) that is a literal port
of real ES-DE's own `CarouselComponent<T>::render()`/`applyTheme()` and
`TextListComponent<T>::render()`/`applyTheme()`, and is unit tested
against values derived by hand from those formulas. The composables now
only draw what the layout says.

For `carousel` the headline is real `type` support. droidtop implemented
exactly ONE of ES-DE's four real types (`horizontal`) and drew
`vertical`/`verticalWheel`/`horizontalWheel` themes -- both wheel types
are common in real community themes -- with horizontal geometry, and a
vertical carousel could not even be navigated (real ES-DE moves the
vertical types on UP/DOWN, not LEFT/RIGHT). All four now render with
their own real geometry, and with them the whole family of properties
that only exists because of it: `itemStacking` (real draw order for
overlapping items), `itemsBeforeCenter`/`itemsAfterCenter` (which is what
sizes a wheel's visible window, not `maxItemCount`), `itemRotation`/
`itemRotationOrigin`/`itemAxisHorizontal`/`itemAxisRotation`,
`itemHorizontalAlignment`/`itemVerticalAlignment`,
`wheelHorizontalAlignment`/`wheelVerticalAlignment`, `horizontalOffset`/
`verticalOffset`, `itemLinearScale`/`itemLinearSpacing`,
`selectedItemOffset`, `itemDiagonalOffset`, `unfocusedItemDimming`,
`imageBrightness`, `itemTransitions`, and `reflections`/
`reflectionsOpacity`/`reflectionsFalloff` (the falloff ported off ES-DE's
own `core.glsl` fragment shader). Two real defaults were wrong and are
fixed: `unfocusedItemOpacity` defaults to 0.5, not 1.0, so a theme
omitting it lost its dimming entirely; and the carousel's gradient
direction was read from a `colorGradientHorizontal` property that exists
nowhere in ES-DE's schema (the real name is `gradientType`), so no theme
could ever influence it. The image color pipeline is now one ColorMatrix
in ES-DE's own shader order (brightness, saturation, color-shift
multiply, dimming), replacing an "unfocused saturation lowers alpha by
15%" approximation.

For `textlist` the model itself was wrong: a `LazyColumn` of
individually-focusable rows, with the highlight drawn as a per-row
background and a hardcoded 48dp horizontal padding that has no basis in
ES-DE at all. Real ES-DE draws a FIXED window of rows and ONE selector
bar at `(cursor - startEntry) * entrySize + selectorVerticalOffset`. With
the real model in place, so is the real schema: `selectorWidth`/
`selectorHeight`/`selectorHorizontalOffset`/`selectorVerticalOffset`/
`selectorImagePath` and the `selectorColor`/`selectorColorEnd`/
`selectorGradientType` gradient; `secondaryColor`/
`selectedSecondaryColor` and `selectedBackgroundColor`/
`selectedSecondaryBackgroundColor`/`selectedBackgroundMargins`/
`selectedBackgroundCornerRadius` (including ES-DE's real fallback CHAIN,
where `selectedColor` falls back to `primaryColor` rather than to a
constant); the real `horizontalMargin` (default zero) and
`horizontalAlignment`; the full four-value `letterCase`; and
`indicators`.

One honest substitution is worth recording because it is visible:
ES-DE's `indicators="symbols"` draws Font Awesome's own U+F005 star from
the `fontawesome-webfont.ttf` ES-DE bundles in its own resources.
droidtop bundles no Font Awesome, and no theme's font carries that
private-use codepoint, so emitting it would draw a missing-glyph box on
every favorite. droidtop emits U+2605 BLACK STAR instead -- same
behavior, a codepoint platform font fallback actually covers. The
`ascii` mode is exact.

Deliberately NOT implemented, rather than approximated: the
`imageColorEnd`/`imageGradientType`/`imageSelectedColorEnd`/
`imageSelectedGradientType` gradient color-shifts (a POSITIONAL gradient
modulated over an image, which a Compose `ColorFilter` cannot express --
it needs a shader); all four `textHorizontalScroll*` properties on every
element that has them; `selectorImageTile`; `collectionIndicators` (real
ES-DE only shows those while a custom collection is being edited IN the
list, a mode droidtop has no equivalent of -- its collection editor is a
separate screen); and `fadeAbovePrimary`. `secondaryColor`'s selection
rule is implemented and its input is modeled (`EsDeListItem.isSecondary`)
but nothing sets it yet: in real ES-DE exactly one thing is secondary, a
FOLDER entry in a gamelist, and droidtop's ROM scan is still flat -- the
same standing gap the `folder` badge slot documents.

The `grid` went the same way in the same pass, and for the same reason:
its model could not express its schema. droidtop drew a
`LazyVerticalGrid` of tiles that were pure invention -- a dark rounded
card with an accent border and an "N items" line, none of which exists in
ES-DE -- and that card occupied exactly the surface a theme's own
`backgroundColor`/`backgroundImage` and `selectorColor`/`selectorImage`
layers are supposed to own. A real grid entry is up to three stacked
layers, ordered by `selectorLayer` (top/middle/bottom), each sized by its
own `*RelativeScale` and rounded by its own `*CornerRadius`, and that is
what renders now, along with `backgroundColorEnd`/
`backgroundGradientType`/`selectorColorEnd`/`selectorGradientType`,
`scaleInwards` (an edge item grows into the grid rather than off it),
`fractionalRows`, `itemTransitions`/`rowTransitions`,
`unfocusedItemDimming`, `imageRelativeScale`/`imageCornerRadius`/
`imageBrightness`, `textRelativeScale` and the full `letterCase` set. Two
more real defaults were wrong here: `itemSpacing` is AUTO-CALCULATED from
`itemScale` when a theme omits it (droidtop used a flat 16dp), and the
grid's own default text color is black on transparent, not white. Its
`itemScale` clamp is also 0.5-2.0, not the carousel's 0.2-3.0. The
tile's item count is gone with the tile and nothing is lost: real ES-DE's
own mechanism for it is a `systemdata` text element the theme places
itself, which droidtop already renders.

All three list widgets now share one architecture, which is ES-DE's own:
the WIDGET owns the cursor and the key handling, and items are render
output with no focus identity of their own. That fixed a real navigation
bug on the way past -- a vertical carousel could not be navigated at all,
because ES-DE moves the vertical types on up/down rather than left/right
-- and it is what let a textlist in a system view finally report its
cursor for per-system theme reloading, which only the carousel did
before.

Still open after this, in rough order of how much a real theme notices:
the `textHorizontalScroll*` family shared by carousel, grid and
textlist, `helpsystem`'s dimmed-state and entry-layout properties
(13/31), and the `rotationOrigin`/`stationary` pair that recurs across
nearly every element type.

**Measured against real themes, then closed by usage (2026-09-02)**: the
"N of 472 properties" figure above is a poor guide to what to do next,
because most of those 472 are set by no theme at all. This pass replaced
it with a measurement. Ten real themes were shallow-cloned from the same
`themes.json` index `ThemeDownloader` itself uses, chosen for variety of
primary view and complexity: DEcaffe (`9adb55a`), Art Book Next
(`d772d07`), Slate (`58041e2`), Modern (`692eb36`), ES-DE-Mini
(`416407f`), Retrofix Revisited (`b43c09d`), TexGriddy (`4db705b`),
Alekfull NX (`645b2bc`), Epic Noir Revisited (`0db85d7`) and Carbon
(`3f3b4cd`). Across all of their `<view>` XML the ten themes together use
282 of the 472 schema properties, and **not one property that the schema
does not know** -- `ES_DE_ELEMENT_SCHEMA` is confirmed complete against
real-world usage, not just against `ThemeData::sElementMap`.

Ranked by how many of the ten themes use each unrendered property, this
pass implemented, all ported from cited real ES-DE source:

- `video`: `pillarboxes` (9 themes) and `pillarboxThreshold` (5), plus
  the bug they exposed -- every themed video was drawn with ExoPlayer's
  `RESIZE_MODE_ZOOM`, which CROPS, while real ES-DE fits the frame and
  fills the remainder with a black frame. The fit and the bar geometry
  are now pure, unit-tested Kotlin in `runtime-common/.../
  EsDeVideoLayout.kt` (ported from `VideoFFmpegComponent::resize()` and
  `::updateBlackFramePosition()`). Also `delay` (9, the static image now
  really does fill the delay), `videoCornerRadius` (which droidtop was
  reading as `imageCornerRadius`, a genuinely different real property
  belonging to the static image), `scrollFadeIn` (5) and `interpolation`
  (5, on the static-image half only).
- `image`: `tile` + `tileSize` (8) -- tiled art was being stretched;
  `interpolation` (7); `scrollFadeIn` (3); `saturation` (2) and
  `brightness` (1), folded into the ONE `esDeImageColorFilter` shader
  pipeline this package already had rather than a second copy.
- The `backgroundColor`/`backgroundHorizontalPadding`/
  `backgroundVerticalPadding`/`backgroundCornerRadius` group on `clock`
  (5), `systemstatus` (5), `helpsystem` (4) and `datetime`. Each padding
  pair is (LEADING, TRAILING) on its own axis, not a width/height pair --
  and `clock`/`systemstatus` previously drew no background box at all.
- `helpsystem`: `entryRelativeScale` (5) and `iconTextSpacing` (3, which
  was a hardcoded 6dp).
- `systemstatus`: `customIcon` (4) -- themes ship a full wifi/cellular/
  battery icon set and droidtop drew unicode glyphs over the top of it.
  Real ES-DE's own defaults are Qt-resource SVGs droidtop cannot
  redistribute, so the glyphs remain the fallback.
- `rating`: `overlay` (4) and `interpolation` (4) -- and with them real
  ES-DE's CONTINUOUS clip geometry, so a 0.7 rating renders three and a
  half stars rather than droidtop's previous rounding to four.
- `carousel`/`grid`: `imageInterpolation` (4).
- `metadataElement` on `text` (5), `image` (3) and `video` -- a real
  binding, since `LibraryEntry.hideMetadata` already exists.

Two honest divergences are recorded in the code rather than hidden.
`interpolation` maps to Compose's `FilterQuality`, which is a single
knob, while real ES-DE's flag is magnify-only -- so an unset
`interpolation` deliberately keeps Compose's filtered default instead of
adopting ES-DE's `nearest` member default, which through the wrong knob
would degrade every downscaled image in every theme. And a `video`
element's `interpolation` reaches only its static image; ExoPlayer's
surface exposes no texture-filter knob.

The measurement also re-prioritised what is left. `imageType` was
confirmed as the single most-used unrendered property in the schema
(video 10/10, image 8, grid 4, carousel 3); it is now implemented, see
below. `text`'s `container*` family is second (8/7/5/3/3/0); it is now implemented too, see below. Deliberately still not implemented, with reasons:
`badges`' `controllerSize`/`controllerPos`/`folderLinkSize`/
`folderLinkPos` family (8/7/5/4) needs overlay icon art droidtop does not
have; `helpsystem`'s whole `*Dimmed` family (4/4/2/2/2/2) fires only
while ES-DE's own menu overlay dims the background, and droidtop renders
no such overlay, so implementing it would mean inventing the state;
`gameselector`'s `selection` (3); `grid`'s `textBackgroundCornerRadius`
(1); and `datetime`'s `displayRelative` (1). (All of those are
implemented as of 2026-09-02 — see "Closing the used set" below; the
badges reason held, the helpsystem one turned out to rest on a false
premise about droidtop's own menus.) **Nothing in this pass was
checked on a real screen** -- the device was off-limits, so verification
is by unit test (`EsDeVideoLayoutTest`, `EsDeTileSizeTest`) and by
reading real ES-DE's source. The video pillarbox/black-frame geometry,
tiled backgrounds, the fractional rating clip and the clock/systemstatus
background boxes all want a real screenshot diff against each theme's own
bundled reference render once the device is available again.

**`imageType` (2026-09-02)**: the most-used unrendered property is now
implemented, for `video`, `image`, `grid` and `carousel`. It was never a
rendering problem. `imageType` selects WHICH scraped media a themed
element shows -- a theme routinely wants the marquee in one element, the
box art in another, a screenshot in a third -- and a `LibraryEntry`
carried one already-resolved `artworkUri`, so there was nothing to
choose between.

What real ES-DE does, read from source rather than inferred:
`GamelistView::setGameImage` (GamelistView.cpp:1255-1330) walks the
THEME's own declared order and takes the first type that has a file on
disk. The order is the theme's, not a fixed preference of ES-DE's:
`marquee,cover` and `cover,marquee` are different requests. When nothing
in the list resolves it calls `setImage("")` -- the element shows its own
`<default>` if it declared one and otherwise shows nothing; it does not
substitute the box art. One value, `image`, names no folder at all: it is
`FileData::getImagePath` (FileData.cpp:360-379), itself a chain of
miximage, then screenshot, then title screen, then cover. `carousel` and
`grid` are a genuinely different case (CarouselComponent.h:1367-1409,
GridComponent.h:988-1029): at most TWO entries, real ES-DE truncating the
rest for performance, no `image` pseudo-type, and `none` meaning "draw
the game's name as text". Every invalid case -- an unsupported value, a
duplicate -- clears the whole list rather than dropping one entry.

The data-model change is `LibraryEntry.mediaLocator`: the games root,
system id and base name, as three strings. Not a resolved per-type media
map -- resolving ten media types for every entry at scan time would cost
a library of hundreds of ROMs ten folders times four extensions times two
candidate media roots of `stat` per game, per scan, to answer a question
most elements never ask. Constructing a locator touches the filesystem
zero times and every scan site already had all three strings in hand, so
**the scan-time cost of this change is nil**. The lookup happens where
the question is asked -- in an element that declared an `imageType`, for
the games currently on screen, memoised by the renderer for as long as
that element is composed. Real ES-DE resolves lazily inside its own
render window for the same reason, and caps the primary elements at two
types because of it. Entries with no ES-DE media layout behind them
(native Android apps, store PC games with remote art) carry no locator
and keep using `artworkUri` exactly as before.

One deliberate divergence, documented in the code at each site: when the
theme's declared types resolve to nothing, droidtop falls back to the
entry's single `artworkUri` before falling back to the element's
`<default>`. Real ES-DE goes straight to `<default>`. droidtop's own
scraper writes covers and little else, so a strict port would blank a
fully scraped library the moment a theme asked for a marquee. `none` is
exempt from that fallback, because there the theme did not fail to find
media -- it asked for text. Also fixed on the way past: the media-type
map was missing `3dbox` -> `3dboxes` and `backcover` -> `backcovers`
(FileData.cpp:381-391), and the extension walk was missing `webp`
(FileData.h:161), so real ES-DE WebP output resolved nothing at all.

**Nothing in this pass was checked on a real screen** -- the device
remained off-limits. Verification is by unit test
(`EsDeImageTypesTest` for the parse and validation rules,
`EsDeArtworkImageTypeTest` for the resolution and fallback order, both
deriving expected values by hand from the C++) and by reading real ES-DE
source. A screenshot diff against a theme that sets `imageType` on its
gamelist carousel or grid, on a library with more than one media type
scraped, is what would confirm the wiring end to end.

**`text`'s `container*` family (2026-09-02)**: the second most-used
unrendered property family is now implemented. Re-measured across the
same ten themes: `container` 8, `containerStartDelay` 7,
`containerScrollSpeed` 5, `containerType` 3, `containerVerticalSnap` 3,
`containerResetDelay` 3, `containerScrollGap` 0. Every use of
`containerType` in the ten is `horizontal`. Before this, a scraped game
description longer than its box was simply cut off, because a Compose
`Text` clips at its constraints -- that implicit truncation is what this
replaces, and there is only one text path now, not two.

The family is two unrelated implementations wearing one name, and real
ES-DE picks between them at GamelistView.cpp:293-305: a `containerType`
of `horizontal` is NOT wrapped in a scrollable container at all, it turns
the `TextComponent` itself into a marquee. `container` defaults to true
for `metadata=description` and false otherwise, needs a horizontal
`size`, and `containerType` is honoured only when the theme wrote
`container` itself.

The VERTICAL container (`ScrollableContainer.cpp`) waits
`containerStartDelay` (default 4.5 s), then moves the text up exactly one
pixel per interval, stops one pixel past the bottom, holds for
`containerResetDelay` (default 7 s), then jumps to the top and fades the
text in over a hard-coded 300 ms during which the start delay is re-armed
-- so the period is delay + travel + reset + 300. The delay is measured
from the last reset, which is the cursor moving to another game
(GamelistView.cpp:914-919), not from when the view appeared. Text that
fits its container never moves at all.

Speed is the part that does not survive a naive port. It is not a
velocity: it is milliseconds per ONE PIXEL, computed as
`clamp(contentWidth / (fontSize * 1.3), 10, 40) * (4.0 /
containerScrollSpeed) / resolutionModifier`, then scaled again by
`rows/8` for containers under eight rows tall. `resolutionModifier` is
`min(screenWidth, screenHeight) / 1080` (Renderer.cpp:188-191, :307-310),
so the interval is secretly resolution-dependent by design; droidtop
passes its real viewport pixels, which is what keeps a theme covering the
same fraction of the screen per second on a handheld as on a desktop.
`containerScrollSpeed` divides into ES-DE's 4.0 constant, so it is a
multiplier on the auto-calculated base and larger is faster.
`containerVerticalSnap` (default true) reduces only the CLIP height to a
whole number of rows, never the element's declared size.

The HORIZONTAL marquee (`TextComponent::update`) is unrelated: it
converts line breaks to spaces, lays the text out on one line, and after
`containerStartDelay` (default 1.5 s here, not 4.5) runs continuously
with no pause at the end, a second copy looping in behind the first once
a `containerScrollGap`-wide hole opens. Its speed is
`Font::getSizeReference() * 0.247 * containerScrollSpeed` pixels per
second, where the size reference is the summed advance of the 26 Latin
capitals at that font size -- so it is already relative to the font, and
needs no resolution term at all. The gap is a fixed distance: the speed
multiplier cancels out of ES-DE's own expression for it.

Nothing was left out of the family. All seven members are implemented,
`containerScrollGap` included even though none of the ten themes sets it.
The timing and geometry are pure Kotlin in `runtime-common/.../
EsDeTextContainer.kt`, free of Compose and Android, in the same shape as
`EsDeVideoLayout.kt`; the drawing is a `Canvas` so the frame clock moves
the text without recomposing, and the frame loop only runs while
something is actually scrolling. One deviation from the C++ is recorded
at the site: an interval that truncates to zero is raised to one, because
real ES-DE's `while (accumulator >= 0)` loop would spin forever on it.

**Nothing in this pass was checked on a real screen** -- the device
remained off-limits. Verification is by unit test
(`EsDeTextContainerTest`, every expected value derived by hand from the
C++ formulas) and by reading real ES-DE's source. What wants a real
screenshot diff once the device is available: whether the apparent
scroll rate of a description panel matches ES-DE's on the same theme at
this device's resolution, the vertical snap and leading-inset clip at the
top and bottom edges, and the marquee's gap on decaffe's own system view.

**Closing the used set (2026-09-02)**: every remaining unrendered
property from the ten-theme measurement above is implemented, all ported
from cited real ES-DE source. The one member of that set deliberately
still left is `helpsystem`'s `originDimmed`, for the reason recorded at
the end of this entry; the count is otherwise closed. (This is stated as
"the measured set minus one" rather than as a fresh N-of-282 figure
because the measurement itself was not re-run in this pass -- the ten
clones it read are not on this machine any more, and quoting a recomputed
number without recomputing it would be worse than not quoting one.)

- `gameselector`'s `selection`, all three real modes
  (`GameSelectorComponent::refreshGames`, GameSelectorComponent.h:51-129,
  plus `FileData::updateLastPlayedList`/`updateMostPlayedList`,
  FileData.cpp:906-940). `lastplayed`/`mostplayed` sort descending and
  skip never-played/never-launched games, which
  `LibraryEntry.lastPlayedEpochMs`/`playCount` already carried. Three
  real bugs went with it: `GameSelector`'s own doc comment claimed ES-DE
  has `similar` and `sameSystem` modes, which exist nowhere in its source
  and were invented; `allowDuplicates` defaulted to true where real ES-DE
  defaults to false, so an unset theme got a mosaic allowed to repeat one
  game; and a `gameCount` larger than the library padded with repeats
  rather than stopping, which is what ES-DE's own retry loop does
  (GameSelectorComponent.h:72-74). `gameCount` is now clamped 1..30 as
  well (:168).
- `datetime`'s `displayRelative`, ported as pure Kotlin in
  `runtime-common/.../EsDeDateTime.kt` from
  `DateTimeComponent::getDisplayString` (DateTimeComponent.cpp:86-135)
  and `Utils::Time::Duration` (TimeUtil.cpp:73-80): the coarsest non-zero
  unit wins, and there is a real 82800-second guard because a stored
  epoch 0 read back through a local timezone is not zero. `lastplayed` is
  bound too (it turns `displayRelative` on implicitly,
  DateTimeComponent.cpp:368-369, and the property overrides that in both
  directions) — the earlier "not modeled yet" note was stale, the field
  existed. ES-DE's real "unknown"/"never" defaults now show where a value
  is absent instead of nothing.
- `carousel`/`grid`'s `imageColorEnd`/`imageGradientType`/
  `imageSelectedColorEnd`/`imageSelectedGradientType`, and `image`'s own
  `colorEnd` when it is declared without a `gradientType`. These were
  left before as needing a shader. They do not: ES-DE puts the start
  color on two of the quad's corners and the end color on the other two
  (`ImageComponent::updateColors`, ImageComponent.cpp:935-947) and
  `core.glsl:147-152` multiplies the interpolated vertex color into the
  sampled texel, which is exactly a linear-gradient fill in
  `BlendMode.Modulate` over the drawn image. The flat shift drops out of
  the `ColorMatrix` when a gradient is active so the color is not applied
  twice. The fallback chain is ES-DE's own and is not per-property
  constants: `imageColor` also sets the end color, the selected pair
  starts as the unselected pair, `imageSelectedColor` in turn sets
  `imageSelectedColorEnd`, and the two gradient axes default to
  horizontal independently.
- `defaultImage` on `carousel`/`grid`, so the primary-element fallback
  chain no longer ends at the pre-resolved artwork
  (`CarouselComponent::onDemandTextureLoad`, CarouselComponent.h:578-579;
  SystemView.cpp:615-618/:868). This is the answer to droidtop's most
  common real gap — a system whose `${system.theme}` logo the theme does
  not ship now draws the theme's declared default rather than falling
  through to text.
- `grid`'s `textBackgroundCornerRadius` (GridComponent.h:394,
  :1395-1398), which rounds the fallback TEXT item's background box, not
  the entry's background layer.
- `video`'s `audio`, `iterationCount` + `onIterationsDone`, and
  `imageMaxSize`. `iterationCount` (VideoComponent.cpp:237-238) was
  ignored entirely — every themed video looped forever regardless — and
  `onIterationsDone` is meaningless without it: at the count real ES-DE
  renders nothing at all, or the static image
  (VideoFFmpegComponent.cpp:200-203). `imageMaxSize` exposed a bigger
  miss: a `video` element has TWO independent size groups, and droidtop
  read only the video's. The static-image group and its inheritance rule
  (`VideoFFmpegComponent::setResize`/`setMaxSize`/`setCroppedSize` forward
  to the static image only when the image group is unset,
  VideoFFmpegComponent.cpp:66-98) are now pure Kotlin in
  `EsDeVideoLayout.kt`. `audio` (VideoComponent.cpp:254-255, real default
  true) is honoured for real rather than kept unconditionally muted; the
  reason it was muted — no "is this view actually visible" signal — is
  fixed rather than worked around, by tying playback to the host
  activity's real lifecycle.
- `badges`' `controllerPos`/`controllerSize`/`folderLinkPos`/
  `folderLinkSize`. The placement is ported from
  `FlexboxComponent::calculateLayout` (FlexboxComponent.cpp:222-231) and
  unit-tested. The ART is theme-supplied only: ES-DE's own defaults are
  the 36 `:/graphics/controllers/*.svg` files and
  `badge_folderlink_overlay.svg`, Qt resources compiled into its binary,
  the same assets droidtop already declines to redistribute for the base
  badges and the systemstatus icons. A theme CAN ship them —
  `customControllerIcon`/`customFolderLinkIcon` are real schema
  properties droidtop already parses — and when it does not, no overlay
  is drawn, which is real ES-DE's own behaviour for an overlay with no
  texture (FlexboxComponent.cpp:222) rather than a droidtop shortcut. No
  glyph stand-in here: the base badge already is a controller glyph, and
  stacking a second one would be invented art, not missing art.
- `helpsystem`'s `*Dimmed` family. The earlier pass left this because
  droidtop rendered no menu overlay to trigger it, and inventing the
  trigger would have been worse than the gap. That premise turned out to
  be wrong: droidtop's in-context options menu is a Compose `Dialog`
  drawn OVER the themed view with a scrim, so the help bar really is on
  screen behind a dimmed background — which is exactly what real ES-DE's
  `Window::isBackgroundDimmed` means ("the GUI stack has more than one
  entry", Window.cpp:513-516). The trigger is now threaded through as
  `EsDeThemedView(backgroundDimmed = …)` and every dimmed variant falls
  back to its undimmed counterpart when unset, ES-DE's own rule
  (HelpComponent.cpp:97-294). `opacityDimmed`'s real clamp is 0.2..1.0,
  not 0..1 — ES-DE will not let a theme hide the help bar behind a menu.

Deliberately still not implemented, with reasons: `helpsystem`'s
`originDimmed`, because plain `origin` is unimplemented on that element
too — the help bar is a wrapping Row whose width is not measured before
placement, so there is nothing to offset an origin against, and doing one
without the other would be worse than neither. `carousel`'s
`textRelativeScale`, the `textHorizontalScroll*` family, `selectorImageTile`,
`collectionIndicators`, `fadeAbovePrimary` and `grid`'s `imageCropPos` are
unchanged from the previous pass's own recorded reasons.

Two pieces of duplication were consolidated on the way past. The six
identical local `float()`/`bool()`/`str()`/`path()`/`pair()`/`color()`
helpers redeclared inside `esDeCarouselConfig`, `esDeGridConfig` and
`esDeTextListConfig` are now one set of extension functions on
`EsDeThemeElement?` next to `valueOrNull` in `EsDeTheme.kt`; and
`EsDeArtwork` rebuilt its two-candidate `downloaded_media` root list
identically at five call sites, which is now one private helper.

**Nothing in this pass was checked on a real screen** — the device
remained off-limits, and nothing was seen on a display at any point.
Verification is by unit test (`EsDeDateTimeTest`, `EsDeBadgeOverlayTest`,
`GameSelectorTest`, plus new cases in `EsDeVideoLayoutTest` and
`EsDeGridLayoutTest`, every expected value derived by hand from the cited
C++) and by reading real ES-DE's source. What wants a real screenshot
diff against each theme's own bundled reference render once the device is
available: the positional image gradients on a theme that sets
`imageColorEnd` (Modulate is the right operation on paper, but whether
Compose's gradient endpoints land on the same corners as ES-DE's quad
vertices is a pixel question), the badge overlay placement on a theme
that ships its own controller icons, the helpsystem dimmed variants with
the options menu open over a themed gamelist, and `defaultImage` on a
system carousel where several systems have no logo art.

**Five-theme on-device review + fixes (2026-08-30, later same day)**:
the theme downloader ran end-to-end for the first time — three real
community themes (Adroit/Catppuccin/ES-DWEE) installed live through
Browse themes, all clones succeeded, each appearing in the Theme
picker with no extra steps. Rendering them surfaced, and this batch
fixes: (1) a theme whose system view declares no carousel/grid/
textlist hard-crashed the app (unattached `FocusRequester` — ES-DWEE,
and the same signature in older device logs); focus requests are now
gated on real attachment AND wrapped as a never-crash boundary — a
theme must never be able to kill droidtop. (2) Art Book Next rendered
near-black because AAPT's DEFAULT ignore-assets pattern strips
`<dir>_*` — its entire `_inc/` tree (fonts/art/metadata/variables)
never shipped in the APK; fixed via `ignoreAssetsPattern` overrides in
:shell-gamepad and :app (the final merge applies the app's pattern).
(3) helpsystem is now a real SINGLETON per view (all declarations
merged in document order, `scope=menu` skipped), matching real
`HelpComponent` — per-element rendering drew ABN's help bar three
times at once. (4) textlist rows are strictly single-line
(maxLines=1 + clip), real `TextListComponent` behavior — wrapped
titles were painting over neighboring rows. (5) theme switches from
Settings now propagate live to a running shell (a change-listener on
the real `ThemePrefs` writer bumps shell-gamepad's Compose signal —
previously every switch needed a process restart) and invalidate the
name-keyed parse cache (also fired after a real re-download of a
same-named theme). (6) bundled-theme cache extraction is invalidated
per APK install (`lastUpdateTime`-keyed marker — versionCode is pinned
at 1 in this project, so it can't be the key); the old bare-existence
marker had devices serving extractions of long-dead asset layouts.
(7) droidtop's "Continue Playing" overlay is REMOVED from themed
screens entirely (it covered decaffe's real sidebar and collided on
every theme tested) — a themed view owns its whole surface, same as
real ES-DE; the row stays on the unthemed fallback, which is
droidtop's own surface. This resolves the earlier "needs per-theme
safe-zone placement" open note. (8) `systemdata` text bindings render
(name/fullname/gamecount family, exact real format strings and the
favorites/recent bare-count special case transcribed from
`SystemView::updateGameCount`).

**"pc" as the theme metacategory for non-console games (2026-08-30)**:
droidtop's engine buckets (Ren'Py, RPG Maker, KiriKiri, …), Linux
container games, and WINE profiles have no ES-DE platform identity of
their own, so no theme ships art for them — previously they passed no
`${system.theme}` at all and rendered near-empty themed views. Decision:
they all theme as the ES-DE `pc` system, the one metacategory every
real theme already covers, instead of droidtop patching per-engine art
into themes. Mechanism is real ES-DE's own: `es_systems.xml`'s
`<theme>` field (`SystemData::mThemeFolder`) already separates a
system's theme folder from its id — mirrored as `GameGroup.
systemThemeFolder` (System → its id; Engine/Linux → `"pc"`; WINE
already carries `systemId = "pc"` directly; Collection → its real
collection folder name, which also lets themes' bundled collection
carousel art like `auto-allgames.png` resolve). Groups keep their own
display names (`fullname` still says "Ren'Py" etc.) — only the art/
metadata lookup folder is shared.

### Credentials transfer (directed 2026-08-31, twice)

Superseded same day: the app-private debug-credentials file below was
built, then retired by direction once the existing whole-prefs settings
backup/restore (Global settings, `BackupHelper` -- it archives the
entire shared prefs file, so every `droidtop_*` credential key rides
along) turned out to already cover the job with one mechanism. The
scraper screen carries a pointer to it; the ScreenScraper fields say
plain Username/Password; the dev ID/password pair is an APPLICATION
credential (real ES-DE embeds its own) with no user-facing field at
all.

### Debug-credentials pathway (built and retired 2026-08-31)

A plain properties file in app-PRIVATE storage
(`filesDir/debug-credentials.properties`, `key=value` lines, namespaced
keys like `screenscraper.ssid`) that credentialed features read as an
OVERRIDE above their stored settings. The point is programmatic control
without exposure: the device's owner places the file (adb push +
run-as, root, a private-storage file manager) and wipes it from
Settings, and no UI field, log line, or automation layer ever carries
the values -- the settings surface shows key NAMES and a count only,
and the visible text fields deliberately render the STORED prefs, never
the override (both the display and the save-one-field-copies-the-rest
paths were real leaks, caught in review before shipping). One generic
mechanism (`DebugCredentials` in library-core) for every current and
future credentialed integration; ScreenScraper and TheGamesDB consume
it today.

## 7g. One library across every source (audit + plan, directed 2026-09-01)

A full audit of droidtop and every vendored repo, against the question
"what would actually be best for users." Two user corrections framed it:
Wine is the fallback for any Windows game *not backed by something else*,
and the per-platform/per-game default-with-priority model (Daijisho's
`playerIdList` + `defaultPlayerId`) is correct — what is missing is not
the default, it is the user's ability to *see and change* it.

### The finding that reframes everything

droidtop compiles **830 gamenative main-source files** and references
**eight symbols** from them:

```
PluviaApp, PluviaApp.bootstrap, PrefManager, SteamAppDao,
LoginResult, SteamEvent, SteamService, QrCodeImage
```

Every one is Steam or bootstrap. The fork already carries complete,
tested services droidtop reaches none of:

| Capability | Where it lives | User-visible value today |
|---|---|---|
| GOG library, auth, manifests, downloads, cloud saves | `service/gog/` (11 files) | none — invisible |
| Epic, same shape | `service/epic/` (12 files) | none — invisible |
| Amazon, same shape | `service/amazon/` (11 files) | none — invisible |
| Loose/DRM-free Windows games in a folder | `utils/CustomGameScanner.kt` | none — invisible |
| Per-game compatibility rating | `GameCompatibilityStatus` | none |
| Automatic per-game workarounds | `gamefixes/` | none |
| Playtime + last-played | `LibraryPlayHistoryDao` | none (playtime reads 0) |
| Mods / Workshop | `mods/`, `workshop/` | none |

`data/LibraryItem.kt` + `GameSource` (STEAM, GOG, EPIC, AMAZON,
CUSTOM_GAME) is already the unified model, and `sync/FrontendSyncManager`
exists specifically to publish installed games to a frontend launcher
like ES-DE. droidtop **is** that frontend, in-process — so it should read
the DAOs directly rather than consume that manager's exported file drops.

So the gap was never "droidtop cannot discover Windows games." It is that
`PcGameProvider.scan()` reads Wine container shortcuts and `SteamAccess`
wraps `SteamService` alone. Everything else is built and unplugged.

Not present upstream, genuinely absent: **itch.io** (the keyword hits are
`switch`/`IconSwitcher`). Origin/Uplay unverified.

### What is best for users, concretely

1. **One library, not five.** Nobody should care whether a game arrived
   from Steam, GOG, Epic, Amazon, a folder of files, or a ROM. Same grid,
   same metadata, same artwork, same "Runs with" control. Source becomes a
   filter, never a separate screen.
2. **Never download what cannot run.** Already true for Steam (the
   provisioning prompt); generalize to every source.
3. **Say whether it will work before the download.**
   `GameCompatibilityStatus` is a rating this device can show up front.
   This is the difference between a launcher users trust and one they
   test by wasting an hour on a 40 GB download.
4. **Apply fixes without teaching users they exist.** `gamefixes/` is a
   registry of per-game workarounds; the correct UX is that it is simply
   applied, and mentioned only when it changes something visible.
5. **Progress follows the user.** Cloud saves exist for all four stores.
   Wiring them means putting the handheld down and resuming on a PC.
6. **Playtime and last-played become real**, which makes "recently
   played", sorting, and the ES-DE gamelist fields honest. Fixes the
   standing playtime-always-0 defect for PC games.
7. **Storage is legible.** `isInstalled`/`sizeBytes` per entry lets a
   handheld user see what is installed and reclaim space, which matters
   far more on an SD card than on a desktop.
8. **Configuration lives where the thing is.** Per-game choices in
   context on the game; per-platform defaults in that platform's
   settings; nothing important reachable only through a settings hunt.

### Launch resolution: keep the default, expose it

The Daijisho model stands, and droidtop half-implements it already. The
defect is surfacing, not policy:

- `LaunchStrategyOverridePrefs` (engine games) exists with no settings
  screen attached; the engines database's `strategies` priority is
  therefore an invisible constant to the user.
- ROMs have `PlayerOverridePrefs` and a picker, but no per-platform
  *default* editor of the kind §7e2 describes.
- `firstOrNull()` in `GameEngineDetector.launch` and
  `ConsoleRomProvider` stays — with exactly one candidate that is not a
  decision, and with several it is a stated default, not a silent one.

Work: a per-platform launch section (ordered candidate list + default,
user-reorderable) covering ROM players, engine strategies, and PC
backends under one control, with the per-game override in context on the
game. Wine is the declared fallback for Windows titles nothing else
backs; a native Linux depot still wins where one exists (§5a).

### Store-installed engine games actually reaching enginehost (fixed 2026-09-02)

The intent was already stated in three places: a Ren'Py game installed
from a store should resolve exactly like one in a games folder,
enginehost included. Two defects stopped the store half of that from
ever happening.

- `PcLibrary.installRoots(context)` — the function that populated the
  store-side roots — had no callers anywhere. Only the synchronous
  `knownInstallRoots()` was wired into `EngineGameProvider`, and its
  store half was therefore permanently empty. Recording the roots inside
  `allGames` instead removes the second entry point rather than adding a
  call to it.
- The roots it recorded were each game's own install *directory*, but
  `GameEngineDetector.scan` reads a root's children as candidate game
  folders, so those pointed one level too deep. The parent is the root.

Steam was unaffected by both, because `SteamService.allInstallPaths`
already returns `steamapps/common`-shaped library roots — which is why a
Steam-installed Ren'Py game does reach enginehost today and a GOG one
did not. `SteamAccess.installRoots()` was a third, uncalled copy of the
Steam half and is gone; `PcLibrary.knownInstallRoots()` is the one
mechanism.

### One entry per game: who owns a store-installed engine game (fixed 2026-09-02)

A store-installed engine game was returned by `EngineGameProvider` *and*
by `PcGameProvider`, so it appeared twice and the two copies routed
differently — the engine entry to enginehost, the `pc` entry straight to
`GameExecutableResolver` and then Wine, with no engine detection
consulted at all.

**The rule: if engine detection recognises a store game's install
directory, the engine entry owns that game and the `pc` entry is
suppressed.** A Ren'Py game runs natively on enginehost; running its
Windows build under Wine plus CPU translation is strictly worse where
both exist, and is the route that does not work on this target today
(§5b). A store game detection does *not* claim is a genuine Windows
title and keeps its `pc` entry and its Wine route — suppression is
per-folder, never general.

The rule is decided from the FOLDER, by `GameEngineDetector.engineOwnsInstall`,
which both providers call. Not from whichever provider returned first:
the two are never even in the same scan (the handheld shell runs Games
and Apps as two independent `scanKinds` calls, and `WINE_PROFILE` is an
Apps kind while every engine kind is a Games kind), so a
`Library`-level dedup pass would never have seen both. Folder-decided
also means the same library deduplicates the same way on every scan
rather than depending on discovery order.

Suppression alone would throw away everything the `pc` entry knew, so
`PcLibrary` hands engine detection its installs as `StoreInstall`s —
install directory plus `PcInfo` plus store art — and the surviving
engine entry absorbs them: source, **store id**, installed state, size,
install path, community compatibility, and the store cover when the
folder has no ES-DE artwork of its own. The store id is what lets
`withScrapedMetadata` also read back a `game_metadata` row scraped
while the game was still a separate `pc` entry. Identity and routing
stay the engine entry's: its id (the folder), its title, its kind, and
a null `systemId` rather than `"pc"`, so it keeps grouping under its
engine's system.

`GameEngineDetector.detectGame` is the single-folder half of `scan`,
extracted so the scan loop, `EngineGameProvider.resolveEntry` and the PC
provider's ownership check all ask the same question of a folder instead
of three slightly different ones; its nested-folder search is
name-ordered rather than in `listFiles()` order so a wrapper folder
resolves identically every scan.

### Dead weight to remove

- **`runtime-remote-stream/`** — already gone from git: zero tracked
  files, absent from `settings.gradle.kts`, and the `moonlight-common-c`
  and `mbedtls` submodules it once used are not in `.gitmodules` either.
  What survived is untracked `.cxx`/`build` residue in working copies,
  deleted 2026-09-01. Streaming is windowcast's exclusively (directed,
  restated 2026-09-01). Note the Windows mirror at `G:\dev\and-pc` still
  carries those stale submodule directories; the WSL clone is
  authoritative and is already clean.
  `LibraryEntryKind.REMOTE_STREAM` **stays** — it is how a
  windowcast-launched entry appears in the same library model as
  everything else, which is the point of that model.
- **`runtime-linux-noroot`** is 7 `TODO()`s. The obvious repair (port
  `DefaultProotContainerBackend` out of the fork) does not apply on this
  target — see §5b: droidtop builds `MODERN_ANDROID`, that path uses the
  bionic variant's direct exec rather than proot, and no arm64 proot
  binary exists in the vendor tree to port to. The class still needs
  filling in for Linux containers generally; it is not what stands
  between droidtop and a Windows game.
- **`vendor/lemuroid`** is a reference checkout, not a build input: its
  detection code was copied into `library-core/.../romdetect/`. Legitimate,
  but it should be documented as reference-only so nobody assumes a
  dependency exists.

### Order of work

1. Source-agnostic PC library: rewrite `SteamAccess` over
   `LibraryItem`/`GameSource` backed by the four DAOs plus
   `CustomGameScanner`; point `PcGameProvider.scan()` at it. One change
   surfaces GOG, Epic, Amazon and loose Windows games at once.
2. Compatibility rating, installed state and size onto library entries.
3. Widen the gamenative migration to all four stores' tables.
4. Per-platform launch settings + in-context per-game override.
5. Playtime/last-played from `LibraryPlayHistoryDao`.
6. Cloud saves across the four stores.
7. Delete the dead streaming module and submodules.
8. Give `runtime-linux-noroot` a real no-root backend (§5b for why
   that is not simply a `DefaultProotContainerBackend` port).

## 8. Licensing

`vendor/gamenative` and `vendor/droidspaces` are GPL-3.0.
`vendor/winlator-upstream` (kept only as a diff reference) is
LGPL-2.1. `vendor/sway`, `vendor/wlroots` (protocol definitions only — not
compiled for Android, see `:host-bridge`), and `vendor/wayland`/`vendor/
wayland-protocols` (same — codegen/headers only) are MIT. `vendor/
go-containerregistry` is Apache-2.0. `shell-default`'s fork source (Murine
Launcher, itself derived from AOSP Launcher3) is also Apache-2.0.
`input-keyboard`'s fork source (Hacker's Keyboard) is also Apache-2.0.
labwc (§2's second compositor preset alongside sway — installed as a package
inside the container image, not vendored/compiled by droidtop itself) is
GPL-2.0; combining it doesn't change the project's overall GPL-3.0
position, license-compatible the same way the other GPL sources already
are. `pc-helper` is a separate program, not
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
runtime-linux-noroot    → proot-based, ported from vendor/gamenative's own
                          DefaultProotContainerBackend (see §3), no root required
input-seat              → unified seat; depends on host-bridge
library-core            → Playnite-style unified library/metadata; depends on runtime-common
display                 → secondary-display behavior for every mode, in one place (the
                          single SECONDARY_HOME activity + mode registry, §4c); depends
                          only on runtime-common
shell-default           → "Standard" shell: forked-in Murine Launcher (real AOSP
                          Launcher3-derived UI, not from-scratch); depends on
                          runtime-common
shell-desktop           → "Desktop" shell's Android-side half (§2a): cross-container
                          task manager + frame passthrough, NOT the taskbar/app
                          launcher (that's container-side); depends on library-core,
                          host-bridge, runtime-common
shell-gamepad           → "Handheld" shell: optional gamepad console UI, multiple
                          selectable paradigms — see §7; depends on library-core.
                          The best-developed module in the repo (~8,000 lines)
input-keyboard          → second-screen persistent keyboard (§4/§6), forked from
                          Hacker's Keyboard; shipping (~17,600 lines) as a real
                          Android IME, surfaced in :app's onboarding; the
                          second-screen/:input-seat integration it was forked in
                          for is still TODO

runtime-remote-stream   → DELETED (2026-09-01). Never in settings.gradle.kts,
                          zero sources. Streaming is windowcast's alone — see §7a.
pc-helper/               → separate Go program, runs on the remote gaming PC, not an
                            Android module — Sunshine REST API client + (limited) Steam
                            install trigger; see §7a
```

### `:runtime-windows` consumes ALL of gamenative (decided 2026-08-31)

Previously the module compiled only the vendored `com.winlator.*` subtree
plus hand-written shims for the `app.gamenative.*` symbols it touched.
Reviewing all 40 local files against the vendor tree showed every one was
either a shim or a stale snapshot of a file the fork had since evolved --
and each shim was a place droidtop re-learned something gamenative
already does (an always-null downloader stub was the direct reason Wine
container creation could never work). Direction: **compile the entire
vendored tree.**

Mechanics worth keeping straight: gamenative's own version catalog is
registered as a second Gradle catalog (`gn`) so dependency versions track
the fork through vendor sync; BuildConfig is AGP-generated with
`MODERN_ANDROID=true` and the W^X bionic preload (droidtop targets SDK
34, where the legacy exec() path has been blocked since 28 -- the old
shim's `false` could never work); Play Integrity's client library is
deliberately not declared, making the fork's ripout structural; PostHog
compiles with an empty key (inert) pending a proper strip in the fork.

Increment 2, still open: Hilt bootstrap in `:app` (gamenative's
`PluviaApp` is a Hilt application; its `@AndroidEntryPoint` activities
need droidtop's Application to carry the Hilt graph), curating the
vendor manifest's components into the module manifest, and real entry
points from droidtop settings into gamenative's container-config UI
(§7c).

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
  droidspaces' real, documented `.config` format. `CraneRootfsPuller`/
  `CraneImageCatalogResolver` are real and wired (an earlier "no
  RootfsPuller implementation" note here was stale). **First real
  on-device executions (2026-08-30, rooted RP5, root AND su-denied
  non-root paths both exercised)**: root check + `droidspaces check`
  pass; both paths then stop at the same first real blocker — the
  bundled `crane` binary's pure-Go DNS resolver is dead on Android (no
  /etc/resolv.conf → falls back to localhost:53; cgo resolver confirmed
  compiled out via GODEBUG). **Fixed (2026-08-30, later same day):**
  build-vendor-deps.sh now builds crane `CGO_ENABLED=1` against the NDK
  clang on EVERY ABI (arm64 included — x86_64 already required it), and
  the workflow's deps-cache key is bumped (v7) so the cached no-cgo
  binary can't keep being served. **VERIFIED on-device**: the rebuilt
  binary, run directly from the installed APK on the RP5, selects the
  cgo resolver (`GODEBUG=netdns=2`: `hostLookupOrder(index.docker.io) =
  cgo`) and `crane ls docker.io/library/alpine` returns real tags.
  Verifying it surfaced a second, independent bug in the same class as
  the theme staleness: `CraneBinary`/`DroidSpacesBinary` extraction was
  gated on bare `dest.exists()`, so the device kept EXECUTING a
  three-day-old no-cgo extraction while the fixed binary sat unread in
  the newly installed APK — both now share one `BundledBinary` helper
  whose marker is keyed on the APK's `lastUpdateTime` (versionCode is
  pinned at 1, so it can't be the key). Also found and since fixed (same
  day, from the second live run — which got further than ever: the
  resolver worked end-to-end inside the real pipeline for the first
  time): (a) first-listed-tag selection picked `alpine:2.6`, a 2015
  image with long-dead package repos — `selectPrimaryImage` now prefers
  the registry's real `latest` tag; (b) `writeInit` failed with
  "Read-only file system" because instances leaked by the 08-27 run
  still held droidspaces mounts over the rootfs — the stale-instance
  reap now also runs at the top of `createContainer`, before anything
  touches the rootfs, not only in `start()`; (c) `pullAndUnpack`
  extracted over whatever the destination already held, silently
  merging images — it now keeps a digest marker (written last), skips
  extraction when the destination already matches, and wipes anything
  else first. Separately: `droidspaces` child processes leaked past app force-stop
  (force-stop skips onDestroy, so no lifecycle hook can reap; fixed by
  a best-effort stale-instance stop at the next `start()` — container
  names are deterministic — plus a real `onDestroy` reap for normal
  teardown), and the pipeline had no logging (every stage and every
  failure now logs under `droidtop.DesktopSession`, with stack traces).

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

## 12. Third-party app integration system

Raised directly by the user, not yet designed or scoped -- recorded here
so it isn't lost, matching this project's own "real decisions land in
SPEC.md" convention, even though this one isn't a fully scoped decision
yet, just a real, worthwhile direction with a real shape already given.

The core idea: let a user hook OTHER real Android apps into droidtop for
data exchange, substitution, or rendering, rather than droidtop only ever
calling its own fixed, hardcoded set of players/tools. Integrations don't
reach into droidtop's internals directly -- droidtop exposes an internal
API surface, and an integration talks to that surface. Concrete real
examples already given:
- A preferred video player (a specific real app) substituted wherever
  droidtop would otherwise launch its own default.
- A preferred browser substituted the same way.
- A content-acquisition integration: search a third-party source and add
  the result into one of droidtop's own configured library folders,
  directly from droidtop's own UI, without leaving it.
- Spotify (real now-playing/control integration -- droidtop already has
  a real, working Spotify presence client per §7e, a real, concrete
  precedent for "talk to one specific real third-party app's real API").
- A hotspot/tethering app, similarly hookable.

**Two real integration types, per the user's own direction:**
- **JSON integrations** -- droidtop drives another already-installed app
  directly through its own real Android Activities/Intents. This covers
  apps whose interface droidtop can already operate itself (launch with
  the right extras, hand off a search query, etc.) -- the integration is
  just a declarative JSON description of which Intent/Activity to call
  and how, no new code running.
- **PLUGIN integrations** -- separate code (either a real APK, or a
  Python module) supplies whatever droidtop doesn't already know how to
  do on its own. This is for cases a bare Intent/Activity call can't
  cover -- richer data exchange, a real API client for a specific
  service, logic droidtop has no built-in equivalent for.

**Built (2026-08-31), JSON half only.** An integration is a `.json` file
declaring which installed app to drive and how:

```json
{ "id": "...", "label": "Get games", "package": "com.example.downloader",
  "capability": "acquire_content",
  "argumentsTemplate": "-a android.intent.action.VIEW -n com.example.downloader/.MainActivity --es system {system.id} --es dest {system.folder}" }
```

- **`argumentsTemplate` is the same `am start` syntax
  `players-database.json` already uses, parsed by the same
  `AmStartCommandToIntentConverter`.** That reuse is deliberate: droidtop
  already had a real, tested mechanism for "describe how to launch
  another app, in data", and an integration is that same problem. No
  second mechanism and no new syntax, and integrations inherit the
  FileProvider `content://` handling and read-permission grants that path
  already had to get right.
- Placeholders droidtop fills in: `{system.id}`, `{system.name}`,
  `{system.folder}` (the real scanned directory), `{query}`.
- `capability` is a closed set (`acquire_content`, `open_with`) because
  the trust shape genuinely differs — handing over one file to a video
  player is not the same as handing over a writable games folder.
- Integrations live in `filesDir/integrations`, are **never bundled,
  downloaded or synced**, and an integration whose package isn't
  installed is hidden rather than offered-and-broken. Which apps someone
  hooks into their own launcher is their business.
- Surfaced per-system: an `acquire_content` integration appears as an
  action inside that system's own settings screen, where the system id
  and its destination folder are both already known.

**The PLUGIN half (APK or Python module) is deliberately not built.**
Nothing in the first real use case needs it, and a sandbox/trust model
for running foreign code is a far larger design than a declarative Intent
description.

**Known limitation, confirmed against a real app:** an integration can
only drive an app as far as that app's own exported surface allows. The
first intended target, a ROM downloader, declares only
`MAIN`/`LAUNCHER` — so droidtop can open it but cannot hand it a system
or a destination, and extras are simply ignored. Making that case work
needs an intent surface added to the *target* app, not more integration
machinery here.

Real open questions a full design pass still needs to answer (not
resolved here): the exact shape of droidtop's internal API surface both
integration types talk to, how a JSON integration's manifest is
structured, how a PLUGIN integration (APK or Python module) is
sandboxed/invoked and what it's allowed to call back into, how droidtop
discovers what's installed and integration-capable, and what a real
permission/trust model looks like for letting a third-party integration
receive data from or act on behalf of droidtop (a "search+add-to-library"
JSON integration is a very different trust shape
than "render this video" or "show now-playing controls").

## 7h. Scraper honesty, and what counts as a game (directed 2026-09-02)

An overnight ScreenScraper pass over the user's real library — 46 ROMs
across 11 systems — returned HTTP 403 for **all 46** requests: zero
successes, zero exceptions, zero files written. The app reported it as
`no match for 46, 0 failed`. Two rules come out of that, and they are
binding on every scraper source, not just ScreenScraper.

**A refusal is not a miss, and the type system says so.**
`ScreenScraperClient.findMetadata` no longer returns a nullable metadata
object. It returns `ScreenScraperLookup`, which is exactly one of
`Found`, `NoMatch` (the server answered HTTP 200 and its response carried
no game — the only outcome that is a statement about the user's library)
or `Refused(httpStatus, reason)` (the server would not serve the request
at all — a statement about the API, about credentials, or about a quota,
and about nothing else). A transport failure stays a thrown exception and
stays counted as `failed`. The scrape summary reports all four buckets
separately and may never fold any of the other three into "no match"; a
pass that was refused everything it asked for leads with that instead of
reporting a count. `formatScrapeSummary` is a pure function so this
arithmetic is unit-tested rather than only observable on hardware.

**The server's own reason is surfaced, not discarded.** ScreenScraper
answers a non-200 with a short human-readable explanation in the response
body. That body is read from `errorStream` under a hard 512-character
bound, has every non-blank credential the request carried redacted out of
it *before* anything else touches it, is stripped of any markup an
intermediary added, and then appears both in logcat (tag
`droidtop.Scraper`) and in the summary the user reads. Credentials
themselves are still never logged — only whether they are present.

**A repeated refusal ends the pass.** Five consecutive refusals stop the
run and report; 46 refusals paced ~11s apart buy no information that the
first five did not.

**Not decided here, deliberately:** the cause of the 2026-09-01 403s.
Credentials were verified present, verified to descramble, and the
personal account was configured. The two candidates — a newly registered
ScreenScraper application pair still awaiting manual approval, and
`softname` needing to match the *registered application name* — are
documented in `ScreenScraperClient.refusalHint` and printed on a 403.
`softname` is **not** changed speculatively: only the person who
registered the application knows what it was registered as.

### One ROM walk, and a DLC folder is not twelve games

A `Rune Factory 5` DLC directory produced twelve separate library
entries, each with its own metadata row and cover, because twelve add-on
files carried the system's ROM extension and the scan was a plain
`walkTopDown()`. The library scan and the scraper each had their own copy
of that walk and could disagree about what a game is; they now share
`RomScanWalk`, which owns the rule:

1. Recursion stays. Reorganising a large system into subfolders must
   never hide files.
2. A directory whose **final name token** is one of `dlc`/`dlcs`,
   `update`/`updates`, `patch`/`patches`, `addon`/`addons`, `bios` or
   `firmware` holds content that attaches to a game rather than being
   one, and is not descended into. Matching the final token is what makes
   this a rule instead of a special case for one title: it covers `DLC`,
   `Rune Factory 5 (DLC)`, `Zelda - Updates` and `_patches` without
   knowing any game's name.
3. The marker list is deliberately short, and `mods`/`hacks`/`romhacks`
   are pointedly **not** on it. A ROM hack is a playable game. A rule that
   hides real games to tidy a list is worse than the bug it fixes.
4. The system folder itself is never excluded by its own name, so this
   can never empty out a whole system.
5. The user's override is ES-DE's own real `noload.txt`
   (`SystemData::populateFolder`): a directory containing that file, and
   everything under it, is skipped. droidtop honours the existing
   mechanism rather than inventing a second one.

Every skipped directory is logged with its reason, so this never loses
files silently.

