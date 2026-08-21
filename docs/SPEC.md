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
  use it, not the only one — someone who uses ES-DE (or Daijishō/iiSU) for
  emulation should still be able to have droidtop's library stay in sync
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
- **PRIMARY entries are the one real exception**: droidtop hasn't
  published any base+compositor image anywhere yet, so those repositories
  are still placeholders with nothing real to resolve tags against — see
  §11's open risks. Everything above about live resolution is fully real
  for the SIBLING entries (plain stock distro images already exist on
  real registries today).

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
  upper/lower position, so the two must never be conflated in code. iiSU
  (a real prior-art reference here, not assumed) doesn't trust
  auto-detection either: it lets the user manually swap which physical
  screen is "top"/"bottom" (a dedicated button for this), and persists a
  chosen screen per app/ROM so the assignment doesn't need to be redone
  every launch. droidtop's own upper/lower role assignment needs the same
  two things — manual override, not just auto-detected enumeration order,
  and a persisted per-output (or per-app) choice — not a fresh design from
  nothing. [Mjolnir](https://github.com/blacksheepmvp/mjolnir) (a
  companion dual-screen home-launcher-routing tool) is another concrete
  reference for the same problem, worth looking at alongside iiSU.
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
  override-plus-persisted-choice treatment iiSU/Mjolnir use, not a fixed
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
  experience. **Multiple selectable UI paradigms, not one merged design**:
  iiSU (visuals-first, Wii U/3DS/XMB-inspired artwork carousels, its own
  multi-display awareness) and Daijishō (its own distinct grid/list
  paradigm, single-display) are different enough interaction models that
  users should be able to pick between them, not get one design blending
  cues from both. Both read the same `Library` — including native Android
  apps, Wine profiles, and Linux-container apps as equally first-class
  entries, which neither iiSU nor Daijishō do natively (both are
  emulator-frontend-focused; droidtop's whole point is that Wine/desktop
  apps aren't a separate, second-class mode). Current implementation
  (`GamepadShell.kt`) is a single plain card carousel — real artwork
  rendering and the paradigm-selection mechanism itself don't exist yet.

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

## 7b. Onboarding, import, and configuration

Not implemented yet — design only, but two real, concrete mechanisms
already exist to build on rather than invent from scratch (confirmed by
reading actual code/formats, not assumed):

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
- **Syncing with a gaming-focused launcher's library data** (Daijishō,
  ES-DE, iiSU) — data-level only, both directions: reading their
  catalog into droidtop's own `Library` so it stays aware of what's
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
  - Daijishō/ES-DE both use `gamelist.xml` + per-system JSON platform
    config — a genuine de-facto-standard in this space, not two
    coincidentally-similar formats; one importer plausibly covers more
    than just those two named tools.
  - iiSU has its own platform-ID registry (`emuladores.json`/
    `emuladores_default.json`) but *also* explicitly interops with ES-DE
    metadata as a built-in feature ("Link ES-DE Metadata" during its own
    setup) — confirms ES-DE's format really is the common denominator
    other tools build interop around, not just droidtop's own assumption.
    iiSU's exact schema needs real investigation before an importer can be
    built (the only repository reachable here is an update-manifest
    mirror, not iiSU's actual app source — this is a gap, not confirmed
    detail, and shouldn't be treated as settled the way the AOSP/ES-DE
    mechanisms above are).
- **Platform/system taxonomy**: `LibraryEntry`'s retro/emulation entries
  need a canonical platform identifier (`"snes"`, `"psx"`, `"gba"`, ...) to
  group, filter, and theme by in the Handheld shell — and to have anywhere
  to map *into* from each importer above. Rather than inventing droidtop's
  own scheme, adopt **ES-DE's `es_systems.xml` platform-naming
  convention as the canonical standard**: it's already the mechanism
  Daijishō is compatible with, RetroArch/libretro core naming lines up
  with closely, and iiSU explicitly interops against. Each importer
  translates its own source format into this canonical set (Daijishō/
  ES-DE need little to no translation since they already use it; iiSU's
  own `emuladores.json` IDs need an explicit mapping table, not yet built
  since the exact schema isn't confirmed — see above) rather than droidtop
  carrying several incompatible per-source taxonomies side by side.
- **Onboarding flow** (not designed in detail yet) needs to cover, roughly
  in order: root detection (determines `:runtime-linux-root` vs.
  `:runtime-linux-noroot` availability, §3), display detection/
  configuration (§4's per-output roles, physical upper/lower position —
  the "which screen is which" question needs resolving here, not
  post-hoc), the two import paths above (offered, not forced), and initial
  shell-paradigm choice (Standard/Desktop/Handheld, and — once the
  Handheld iiSU-vs-Daijishō paradigm split from §7 is real — which
  paradigm). **Configuration itself has no separate settings app** (§7) —
  onboarding is a first-run wizard around the exact same settings surface
  (`com.android.launcher3.settings.SettingsActivity`) a user would reach
  later through the normal back-button menu, not a parallel or
  disposable-after-first-run UI.
- **Opened from a different launcher** (droidtop isn't the user's actual
  home screen — they use something else and just tap droidtop's own app
  icon) **defaults to the Desktop shell**, not Standard — Standard doesn't
  make sense as the thing that opens when droidtop isn't the home screen
  in the first place. Configurable, like every other default in this
  section, not hardcoded.

## 7c. Wine prefix / container configuration UI

Not designed in detail or implemented — but real, needed UI surface, not
an afterthought: managing Wine prefixes (Windows version, DXVK/VKD3D
toggles, installed components, per-prefix vs. shared) and Linux sibling
containers (create/clone/delete/stop, which distro, installed packages,
folder/data mounts — §2a already establishes these are user-configurable,
distrobox-style) both need real UI, not just the underlying runtime logic.

Two concrete references to build from rather than design blind:

- **Wine prefix management**: [vendor/gamenative](../vendor/gamenative)
  (already `:runtime-windows`'s fork source) has its own real, working UI
  for exactly this — Windows version selection, DXVK/VKD3D configuration,
  installed-component tracking. Worth porting/adapting the same way
  `:runtime-windows` itself is forked from gamenative's runtime, not
  designed from nothing.
- **Linux container management**: distrobox itself is CLI-only (no
  official GUI), but [BoxBuddy](https://github.com/Dvlv/BoxBuddy) is a
  real, actively-maintained GTK4 GUI for it — confirmed feature set:
  per-container create/clone/delete/stop/upgrade, viewing installed apps,
  opening a terminal into a container. [DistroShelf](
  https://github.com/ranfdev/DistroShelf) is a second distrobox-GUI
  reference worth comparing against, not just BoxBuddy alone. Neither is a
  fork target (both are GTK/Linux-desktop apps, not Android) — interaction
  patterns and feature scope to learn from, the same way Daijishō/iiSU are
  UX references for `:shell-gamepad` rather than code to port.

## 8. Licensing

`vendor/gamenative`, `vendor/droidspaces`, and `vendor/moonlight-common-c`
are GPL-3.0. `vendor/winlator-upstream` (kept only as a diff reference) is
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
runtime-linux-noroot    → proot-based, new code, no root required
input-seat              → unified seat; depends on host-bridge
runtime-remote-stream    → Moonlight/GameStream client (fork: vendor/moonlight-common-c)
library-core            → Playnite-style unified library/metadata; depends on runtime-common
shell-default           → "Standard" shell: forked-in Murine Launcher (real AOSP
                          Launcher3-derived UI, not from-scratch); depends on
                          library-core, host-bridge
shell-desktop           → "Desktop" shell's Android-side half (§2a): cross-container
                          task manager + frame passthrough, NOT the taskbar/app
                          launcher (that's container-side); depends on library-core,
                          host-bridge, runtime-common
shell-gamepad           → "Handheld" shell: optional gamepad console UI, multiple
                          selectable paradigms (iiSU-style, Daijishō-style — see
                          §7); depends on library-core

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
