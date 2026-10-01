# droidtop — Architecture Specification

This document is the source of truth for the design; module READMEs point
back here rather than restating it. Sections keep their numbers, because code
comments cite them ("SPEC §7g"): a new section takes the next free number in
its group, is placed in numeric order, and gets a line in the contents below.

## Contents

- [1. Product vision](#1-product-vision)
- [2. Core architectural decision: Qubes-style split, not a hand-rolled compositor](#2-core-architectural-decision-qubes-style-split-not-a-hand-rolled-compositor)
- [2a. Desktop shell architecture: launching, task management, native apps](#2a-desktop-shell-architecture-launching-task-management-native-apps)
- [2b. Desktop mode gets the library context, and Android apps as windows (directed 2026-09-01)](#2b-desktop-mode-gets-the-library-context-and-android-apps-as-windows-directed-2026-09-01)
- [2c. Modes and what each contributes (directed 2026-09-11)](#2c-modes-and-what-each-contributes-directed-2026-09-11)
- [3. Containers](#3-containers)
- [3a. Image index — populated live, not a pinned/prepopulated catalog](#3a-image-index--populated-live-not-a-pinnedprepopulated-catalog)
- [3b. Optional: other architectures/OSes via QEMU/libvirt — a value-add, not core](#3b-optional-other-architecturesoses-via-qemulibvirt--a-value-add-not-core)
- [3c. FEX-Emu — x86/x86-64 emulation for Linux software in general, not just Wine](#3c-fex-emu--x86x86-64-emulation-for-linux-software-in-general-not-just-wine)
- [3d. User-facing container/distro management (directed 2026-08-30)](#3d-user-facing-containerdistro-management-directed-2026-08-30)
- [4. Display](#4-display)
- [4a. Networking & VPN (directed 2026-08-30)](#4a-networking--vpn-directed-2026-08-30)
- [4b. PC-parity requirements: printing, USB peripherals, "open with droidtop"](#4b-pc-parity-requirements-printing-usb-peripherals-open-with-droidtop)
- [4c. Multi-display: what iiSU does, and why droidtop fights the platform (2026-09-01)](#4c-multi-display-what-iisu-does-and-why-droidtop-fights-the-platform-2026-09-01)
- [4d. The companion screen, designed (research 2026-09-01)](#4d-the-companion-screen-designed-research-2026-09-01)
- [5. Windows compatibility — no real virtualization](#5-windows-compatibility--no-real-virtualization)
- [5a. CPU-translation backend choice, and preferring a native Linux build over Wine](#5a-cpu-translation-backend-choice-and-preferring-a-native-linux-build-over-wine)
- [5b. One Wine engine, one prefix store, whichever backend is live (assessed 2026-09-02)](#5b-one-wine-engine-one-prefix-store-whichever-backend-is-live-assessed-2026-09-02)
- [6. Input](#6-input)
- [6a. Keyboard ownership (directed 2026-09-01)](#6a-keyboard-ownership-directed-2026-09-01)
- [6b. Desktop surface input (built 2026-09-01)](#6b-desktop-surface-input-built-2026-09-01)
- [6c. Second-screen input (built 2026-09-02)](#6c-second-screen-input-built-2026-09-02)
- [6d. Clipboard bridge, host↔container (built 2026-09-02)](#6d-clipboard-bridge-hostcontainer-built-2026-09-02)
- [7. Library / launcher-readiness](#7-library--launcher-readiness)
- [7a. Remote PC streaming — via windowcast, not a droidtop module](#7a-remote-pc-streaming--via-windowcast-not-a-droidtop-module)
- [7b. Onboarding](#7b-onboarding)
- [7c. Wine prefix / container configuration UI](#7c-wine-prefix--container-configuration-ui)
- [7d. Engine games — enginehost, the contract and the coverage](#7d-engine-games--enginehost-the-contract-and-the-coverage)
- [7e. Second-screen / ambient integrations (Spotify now-playing, Discord presence)](#7e-second-screen--ambient-integrations-spotify-now-playing-discord-presence)
- [7e2. Data-driven player/platform database (directed 2026-08-30)](#7e2-data-driven-playerplatform-database-directed-2026-08-30)
- [7e2b. Launch resolution FROM the platforms database (directed 2026-08-31)](#7e2b-launch-resolution-from-the-platforms-database-directed-2026-08-31)
- [7e3. Lutris install-script integration (directed 2026-08-30, scoped and built 2026-09-25)](#7e3-lutris-install-script-integration-directed-2026-08-30-scoped-and-built-2026-09-25)
- [7e4. Emulator setup helpers (directed 2026-08-31, EmuDeck-style)](#7e4-emulator-setup-helpers-directed-2026-08-31-emudeck-style)
- [7f. Gaming mode: real, generic ES-DE theme engine](#7f-gaming-mode-real-generic-es-de-theme-engine)
- [7g. One library across every source (audit + plan, directed 2026-09-01)](#7g-one-library-across-every-source-audit--plan-directed-2026-09-01)
- [7h. Scraper honesty, and what counts as a game (directed 2026-09-02)](#7h-scraper-honesty-and-what-counts-as-a-game-directed-2026-09-02)
- [7i. The PC surface — droidtop's own actions, on the theme's own layout (REDECIDED 2026-09-26)](#7i-the-pc-surface--droidtops-own-actions-on-the-themes-own-layout-redecided-2026-09-26)
- [7j. Portrait and touch-first chrome (directed 2026-09-10)](#7j-portrait-and-touch-first-chrome-directed-2026-09-10)
- [7k. The design system: one spacing scale, one type scale, one colour source](#7k-the-design-system-one-spacing-scale-one-type-scale-one-colour-source)
- [7m. One game, its versions and its segments (directed 2026-09-16)](#7m-one-game-its-versions-and-its-segments-directed-2026-09-16)
- [8. Licensing](#8-licensing)
- [9. Module map](#9-module-map)
- [10. Build order](#10-build-order)
- [10a. Build environment](#10a-build-environment)
- [10b. Releases and updates (directed 2026-09-02)](#10b-releases-and-updates-directed-2026-09-02)
- [10c. Diagnostics, crash recovery and privacy](#10c-diagnostics-crash-recovery-and-privacy)
- [11. Open risks to verify hands-on, not assume](#11-open-risks-to-verify-hands-on-not-assume)
- [12. Third-party app integration system](#12-third-party-app-integration-system)
- [13. UI v2 — end-user redesign direction (dtv2ui audit, 2026-09-28)](#13-ui-v2--end-user-redesign-direction-dtv2ui-audit-2026-09-28)

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

The in-container launcher doesn't exist as software yet, the injection
channel isn't designed in detail, and the helper process is a concept,
not code.

**Until it does (built 2026-09-24): droidtop's Start menu lists the primary
container's own applications.** `ContainerApplications` (`runtime-common`)
reads the freedesktop desktop entries the container's packages installed
(`/usr/share/applications`, `/usr/local/share/applications`) through
`ContainerRuntime.exec` — the same list any Linux desktop's menu shows, so
anything the user installs appears without droidtop knowing about it — and
parses them per the Desktop Entry Specification (the `[Desktop Entry]`
group only, `Type=Application`, `NoDisplay`/`Hidden` honoured, `Exec`
unquoted per the spec with its field codes dropped, `Terminal=true`
programs run inside the provisioned terminal). Only what a person would
call an app is listed (decided 2026-09-25, rig dq-desk2-01 listed "Foot
Client", "Foot Server" and "Manage Printing"): `OnlyShowIn`/`NotShowIn`
are honoured against the desktop's own names (`sway`, `labwc`,
`wlroots`), an entry whose program is `xdg-open` is a link rather than an
app, and entries sharing an `Icon` with the entry named after that icon
are its variants (a client, a server) and fold into it. Each app shows its
`GenericName` under its name ("Foot", "Terminal"). The list is read every time
the menu opens, above the library's own entries. Launching is an `exec` in
the primary container, so the window appears on the shared desktop. These
are session objects, not library entries: they exist only while the
session runs and launching one needs the live session, whereas the library
is an index persisted per game (§7g). When the container-side launcher
lands it replaces this list rather than joining it.

**A program's lifetime belongs to the session, not a screen.** A launched
program's `exec` lasts as long as its window, and ending that wait ends
the program (under proot the session is killed). The terminal button used
to wait inside the desktop shell's composition, so rotating the device
would have killed every open window. Both the terminal and Start menu
launches now go through `DesktopSessionService.runInPrimary`, which waits
in the service's own scope and reports a failure (the program's last
output) back to the shell.

**Chrome theming (decided 2026-08-30)**: droidtop's own Compose chrome
(Onboarding, Desktop shell panels, Console systems, etc.) follows the
system dark/light setting through one shared Material theme
(`app/.../ui/DroidtopTheme.kt`) — screens take colors from
`MaterialTheme.colorScheme` tokens, never literals (the previous state:
every screen hardcoded its palette, and no light mode existed at all).
Two deliberate exceptions stay always-dark regardless of the system
setting: surfaces living inside the Gaming shell's world (Console
systems opens from Gaming's Settings tab and matches its plain-black
ground) and ambient second-screen companion surfaces; ES-DE-themed
Gaming views take every color from the active ES-DE theme (§7f) and
are outside Material theming entirely.

## 2b. Desktop mode gets the library context, and Android apps as windows (directed 2026-09-01)

Desktop mode currently has a container and a shell and no library. The
whole gamenative/Wine context that Gaming now reaches through
`PcLibrary` — store library managers, installed games, owned-but-not-
installed titles, and the Wine container shortcuts — belongs in Desktop
too, as ordinary desktop entries.

Not a second implementation: `PcLibrary` already returns a
source-agnostic list (§7g) and `ContainerManager.loadShortcuts()` already
enumerates Wine-prefix shortcuts. Desktop's task manager and launcher
surface should read the same `LibraryEntry` stream `:shell-gamepad`
reads, differing only in presentation. Same rule as the secondary display
(§4c): one mechanism, the active mode selects what it looks like.

Two things this needs that Gaming did not, decided precisely on
2026-09-24 because the Start menu lists library entries as text rows and
nothing else of this section exists:

- **Shortcuts as first-class desktop objects.** The Start menu has three
  sections — Linux apps (the container's desktop entries, §2a), Games
  (every `LibraryEntry` of the game kinds, with artwork) and Windows
  (Wine-prefix shortcuts, each with its icon, working directory and
  executable, the same facts a `.desktop` entry carries) — and any entry
  can be pinned to the taskbar from its long-press menu, where it sits
  as an icon until unpinned. The compositor's own desktop surface is the
  container's and gets no droidtop-drawn icons; "placed the way a Linux
  desktop does" means the menu and the bar, which is where a Linux
  desktop places them too.
- **Launching from Desktop.** A Linux program is an `exec` in its
  container and appears as a window through the shared socket (§2). A
  game or a Wine shortcut launches the way it launches everywhere —
  `Library.launch`, then `LaunchDisplay` — targeting the display the
  desktop renders on, and it is a fullscreen Activity over the desktop
  for as long as it runs: a Wine guest draws into gamenative's X server
  view, not into the compositor (§5b), and an emulator is an Android
  Activity (the section below). A Windows program as a window INSIDE the
  container's desktop is Wine-in-the-container, §11's open risk, and no
  launch path assumes it. `WindowPlacement` therefore applies to
  container windows and to compositor outputs, not to Activities.

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

## 2c. Modes and what each contributes (directed 2026-09-11)

droidtop is one app and one process hosting three modes. The user's words:
*"for performance reasons, we need to make sure disabled UI modes are
actually not loaded and run... Each mode contributes something to the
whole."* Two rules follow, and the rest of this section is what they mean
concretely.

**Rule 1 — a disabled mode runs no code.** Not "renders nothing": runs
nothing. No process-start initialiser, no service, no system-bound
component, no background scan, no warm-up thread, no Compose tree. A mode
that is off costs cold-start time and resident memory only for the
package it sits in.

**Rule 2 — each mode contributes to the whole, and none re-implements
another's job.** What the modes share is not copied between them; it sits
underneath all three as the shared core, and each mode is a surface over
it plus the integrations only that surface can offer.

### The shared core (runs in every mode, including none)

| Piece | Where |
| --- | --- |
| The library: providers, scan, dedup, one `LibraryEntry` stream | `LibraryCore` (`:app`), `:library-core` |
| Launch resolution — which player runs this entry, and how | `:library-core` launch strategies, `PcGameRuntimeRegistry` |
| A mode-independent launch entry point | `GameLaunchActivity` (`:app`) |
| Settings catalogs and their registry | `SettingsCatalogInitProvider`, `:runtime-common` |
| Preferences, including the mode switches themselves | `Modes` (`:runtime-common`), one prefs file |
| Self-update and the update-now trigger (§10b) | `AppSelfUpdate`, `UpdateNowReceiver` |
| Crash reporting | `CrashReporting`, `LauncherApplication` |
| Keeping the index honest over time: the slow rebuild pass (§7g) | `Library` (`:library-core`) |

`GameLaunchActivity` is not exported (`android:exported="false"`, 2026-09-28): every internal caller uses an explicit component intent or the library's direct `launchInBackground`, so no external package needs direct access. The index-backed lookup (c35c36f4) makes the surface safe to narrow.

The slow rebuild pass is core, not Gaming's: every surface that shows the
library reads the same index (the Launcher's Games grid, Gaming's rows,
Desktop's objects), and it runs only while one of them is observing the
library (`Library.observed`, §7g), never merely because the process is up.

The core is why "with Gaming off a game still launches" is true rather
than a claim: the library and its resolution never belonged to Gaming, and
since the modes pass they are no longer built inside `MainActivity`, which
only runs in Gaming or Desktop.

### Launcher mode — the Android home screen

The Launcher3 fork (`:shell-default`): home screen, app drawer, widgets,
notification dots, the fork's own gestures. It contributes the *device*
surface: droidtop as the thing the Home key reaches, and the place a game
or a container app appears as an ordinary icon.

Enablement follows the HOME role rather than a switch of its own —
`HomeRolePrefs` enables exactly one of the fork's two HOME activities (or
neither), and `Modes` reads that component state rather than a second
flag.

**Holding the role is Android's decision, and droidtop watches it.**
Enabling the HOME activity is what droidtop can do; which app answers the
Home key is what the person chose in Android's own chooser, and it can
change behind droidtop's back (another launcher installed and picked, a
factory reset of defaults). So Launcher mode reads the real state —
`RoleManager.isRoleHeld(ROLE_HOME)` on API 29+, the resolved default home
activity below — and when droidtop's HOME activity is enabled but not the
device's home, Global settings shows a row saying so ("droidtop is not
your home screen") that opens the system's home chooser, and the
`SECONDARY_HOME` idle surface (§4c), which the platform places only for
the home app, is covered by the companion's Presentation while Gaming
runs. Nothing else changes: Launcher mode stays enabled, because the
person may pick droidtop again from that row.

Built 2026-09-25 (`HomeRolePrefs.isDroidtopHome`, `homeRequestIntent`),
after the Android 14 rig answered "droidtop's own launcher" in setup and
kept Pixel Launcher as Home while Global settings read "On"
(dq-onboard-01): enabling the HOME activity only makes droidtop a
candidate. Onboarding's launcher step asks Android for the role itself
(`RoleManager.createRequestRoleIntent(ROLE_HOME)` on 10+, started for a
result because the request asks who is calling; Android's Default home
app screen below 10), as its hand-off work with the skip beside it; the
Alternative choice asks the same way. Global settings' "Use droidtop as
home screen" is On only when droidtop's launcher is enabled AND is what
Home opens, says which app Home opens, and turning it on opens Android's
Default home app screen. The mode switcher always lists "Android", and
when droidtop's launcher is not Home (or droidtop holds no home at all) it
opens whatever Home opens; it used to vanish then (dq-onboard-02). Below
Android 10 there is no role to request: when no Home app has been chosen
"Always" yet, the step opens Home itself, which is Android's chooser with
droidtop in it; only when another app is already the default does it open
Android's settings (on BlueStacks the Default apps list, one level above
the Home choice). droidtop registers no
boot receiver; the HOME role is what starts it at boot, and nothing
else of droidtop's (the desktop session, the VPN) starts before a person
opens it (§3).

**Games in the Launcher (built 2026-09-24).** Until this change the
Launcher could not show or launch a single library game: droidtop's
package had no launcher activity, and the fork hid everything in its own
package from the drawer. It now works like this:

- With Gaming and Desktop both off, droidtop's one icon in the drawer
  (`LauncherGamesActivity`, `:app`; see "One droidtop icon" below) opens a
  grid of the library's games — the same `LibraryKinds.GAMES` scan the
  Gaming shell's Games section reads, run by the same loop
  (`Library.scanFollowingGamesRoots`, which follows the games roots as they
  change), so with both on there is one scan, and hidden or missing games
  are left out. No themes, no scraped detail views, no Quick Menu: those
  are Gaming's.
- **It is droidtop's own chrome, not a stock screen** (decided 2026-09-25,
  after the rig's user did not recognise the first version as droidtop's).
  It is drawn by the shell (`LauncherGamesScreen`, `:shell-gamepad`) from
  the shell's own pieces: the Games section's card with the one selection
  idiom (§7k), the black ground drawn under the system bars, a screen header
  named "Games" with the count, and a hint row — A Play, Y Pin to home
  screen, Select Game folders, B Back — whose every hint dispatches (§7j).
  Game folders opens in place through the settings navigator, and an empty
  grid offers "Add a games folder" rather than a sentence. It runs in a task
  of its own (`taskAffinity`), so the icon never brings back another
  droidtop screen left in the package's shared task, and B from the grid
  goes home. Game folders opened in the grid carries its own hint row
  (A Select, Y Info, B Back), since the settings navigator draws none.
  Opened from droidtop's own home screen, B reopens that home screen as the
  explicit "Android" one: a plain finish let Android recreate it with a
  fresh Home intent, which forwards to the default mode (dq-shell2-02).
  PC and engine games are listed as Gaming's PC grid lists them, one card
  per game under the game's name (`LibraryGrouping`, §7m).
- A tap or A launches through `GameLaunchActivity.dispatch`, which is
  `Library.launch` (play history and launch-screen memory included).
- Y or a long press pins the game to the home screen as an ordinary icon: a
  launcher shortcut whose intent is `GameLaunchActivity` with the entry's
  id, so a pinned game keeps working with Gaming off. Its icon is the
  entry's local artwork cropped square, or droidtop's icon when there is
  none (a remote cover would mean a network fetch to build an icon).
- The fork's `AppFilter` still hides droidtop's own package except this
  one component, and `NativeAppProvider` leaves droidtop's own package out
  of the Apps list, so droidtop's icon never shows as an "app" in Gaming or
  Desktop.
- A pinned game's shortcut names `LauncherGamesActivity`, the package's one
  launcher activity, as its activity.

**One droidtop icon (decided 2026-09-25).** The package has exactly one
MAIN/LAUNCHER activity, `LauncherGamesActivity`, labelled "droidtop", and
it is the same icon in every launcher, droidtop's own included. What a tap
does depends on what is set up: unfinished onboarding resumes at its step
(§7b); else, with Gaming or Desktop on, it opens `MainActivity` with no
mode named, so the default or last-used shell opens; else it draws the
games grid above. It used to be two icons with droidtop's picture on them:
this one labelled "Games", and `OpenShells`, an alias of `MainActivity`
labelled "droidtop" that was a mode piece and hidden by droidtop's own
launcher. BlueStacks' launcher labels every entry with the application's
name, so a newcomer saw two identical "droidtop" icons and took droidtop
for installed twice, and from droidtop's own home screen there was no icon
into Gaming at all (rig, dq-coordinator-24). The alias and its mode piece
are gone. The icon is not mode-gated: it runs nothing until someone opens
it, and Android requires an enabled launcher activity before it accepts a
pinned shortcut from droidtop at all. The class keeps its old name because
every pinned game names it. It runs in a task of its own (its own task
affinity), so every tap runs it and it decides again; as part of the
app's task, a tap brought back whatever droidtop screen was last in
front, a Gaming shell whose mode had been switched off included
(dq-onboard-01, dq-shell2-01). The games grid is reachable whatever modes are on: the
icon's app shortcut "Games" (a long press) and the home screen's
long-press menu entry "droidtop games" open it (`ACTION_SHOW_GAMES`).
Launcher3's pin sheet (`AddItemActivity`) also runs in a task of its own,
so Cancel returns to the grid rather than into older droidtop screens.
Finishing onboarding with droidtop's own launcher as Home asks for this
icon on the home screen (`HomeRolePrefs.placeDroidtopIcon`), and the
launcher queues it through its own install queue when its home screen
next resumes (`placePendingIcon`); queued from the end of setup, in a
process where the launcher did not exist yet, it never arrived on the
Android 14 rig. Every
game is not put in the drawer as its own icon: the drawer is
`LauncherApps`, which lists installed activities only, and faking entries
into it would mean rewriting the fork's app model.

**Home goes to the default mode (decided 2026-09-25).** A Home press goes
where `ModeGate.homeTarget` says: the default mode the person chose (in
onboarding, or Global settings > Default mode), when it is on; else the
mode they last used; else the Android home screen. The Launcher3 fork and
the Alternative forwarder both ask it, on a cold start and on every Home
press. It used to follow the last-used mode alone, so a person who
answered "Opens into Android" and then opened Gaming once had every later
Home press land back in Gaming (rig, dq-coordinator-24). An explicit
"Android" from the mode switcher (`BackButtonMenu.openHome`, which names
the mode in its intent) shows the home screen and is never forwarded.
Default mode offers Android whenever droidtop holds the home screen, so
onboarding's answer reads back there instead of "Whichever was used last";
"Whichever was used last" keeps the old behaviour for whoever picks it.

Checked again 2026-09-26 after a UX review reported Home landing on the
Standard shell with Default mode = Gaming: on a genuinely cold process start
(`am force-stop` then Home, or a reboot) `Launcher.onCreate`'s redirect is
recorded immediately but only acted on in `onStart` (see that method's own
comment), which does not run until Launcher3's own model load finishes --
observed taking several seconds on a cold, not-yet-JIT-warmed process
(emulator-5560, Android 14). Checking the resumed activity right after the
Home key, rather than waiting those few seconds out, reads as "stuck on
Standard" when it is actually still loading; waiting confirms it does land in
Gaming. No code change from this: recorded here so the same false read isn't
repeated. The switch-mode dialog's own, separately confirmed break is
recorded below.

Revisited 2026-09-26 (launcher2, p1-dt-home-redirect-before-model-load).
The held task's original ask was to decide the target with `ModeGate`/prefs
alone, as early as possible, so Standard never draws when the target is
another mode. That decision already happened as early as `Launcher.onCreate`
could make it -- before `super.onCreate()`, from `Modes.homeTarget` (a
SharedPreferences read, no model, no disk work) -- but `Launcher.onCreate`
could not act on a non-Standard decision without first running the rest of
its own upstream body: `onStart` is where the old redirect fired (not
`onCreate`) specifically because `Launcher.onDestroy` unconditionally
dereferences fields (`mModel`, `mRotationHelper`, `mAppWidgetHolder`,
`mWidgetPickerDataProvider`, `mWorkspace`, `mOverlayManager`) that only
exist once `onCreate`'s full upstream body has run; finishing any earlier
crashed with `LauncherModel.removeCallbacks on a null object reference`.
Making `onDestroy` null-safe across every one of those fields would be a
real rewrite of upstream Launcher3 lifecycle code, not a hook, so that path
stayed closed.

**Built 2026-09-26: `HomeTrampolineActivity`.** `com.android.launcher3.Launcher`
no longer declares `CATEGORY_HOME` at all -- `dev.droidtop.shell.standard.
HomeTrampolineActivity` (`:shell-default`) does, ahead of it, and is now what
`Modes.LAUNCHER_ACTIVITY` names (the component `HomeRolePrefs` and mode
gating's `launcherIsDroidtopHome` check the enabled-state of). A real Home
press now goes: system -> `HomeTrampolineActivity.onCreate` -> one
`Modes.homeTarget` read -> an explicit-component `Intent` to whichever of
`MainActivity` (Gaming/Desktop) or `com.android.launcher3.Launcher`
(Standard) is the target, then `finish()`. When the target isn't Standard,
Launcher3 is never constructed at all -- no `setupViews()`, no
`LauncherModel` bind -- so the multi-second Standard frame is gone for that
case. When the target IS Standard, the trampoline forwards to
`com.android.launcher3.Launcher` explicit-to-explicit, the same pattern
`BackButtonMenu.openHome`'s "Android" already used, so Standard draws
exactly as before. The double-tap "hard display reinit" detection that used
to live in `Launcher.onNewIntent`'s own redirect branch moved to the
trampoline too (a process-lifetime timestamp, since every Home press
recreates the trampoline fresh); `Launcher.java`'s own three copies of this
decision (`onCreate`, `onStart`, `onNewIntent`) are unreachable now that it
no longer holds `CATEGORY_HOME`, and are left in place as a harmless no-op
safety net rather than removed, since ripping working vendored lifecycle
code out is its own risk for no behavioural gain.

**The cold-start splash shows droidtop's own icon, not the platform's
(decided 2026-09-26).** The UX review found droidtop's Android 12+
SplashScreen showing the platform's generic mascot instead of droidtop's
own adaptive icon (laptop + robot, `mipmap/ic_launcher`) on a genuinely
cold `am start` (`p2-ux-droidtop-app-icons.md`). droidtop had never set
`windowSplashScreenAnimatedIcon`/`windowSplashScreenBackground` of its own,
leaving every OS build's automatic derivation from the adaptive icon free
to fall back however it likes; `app/src/main/res/values-v31/themes.xml`
now sets both explicitly on `Theme.DroidTop` so the real icon and its own
background (`#1B2430`, the same slate `ic_launcher_background` uses) render
on every API 31+ device regardless of that derivation. The review's other
fallback-icon findings (Enginehost and several BlueStacks-bundled utility
apps showing the platform mascot in the Standard shell's Apps grid) are not
a droidtop bug: that grid resolves each entry's icon through
`PackageManager`/`LauncherActivityInfo`, which falls back only when the
target app's own manifest has no resolvable icon resource. Enginehost's
manifest (`enginehost/app/src/main/AndroidManifest.xml`) sets no
`android:icon` at all, so this is Enginehost's own fix to make, not
droidtop's; the BlueStacks-bundled apps (Bsxlauncher, Filemanager, Nowgg,
Piggy) are host-emulator utilities with no equivalent on the Retroid
hardware droidtop actually ships on.

**The launcher icon is one replaceable field, not several copies
(restructured 2026-09-27).** Every surface that shows droidtop's own icon -
the manifest's `android:icon`/`android:roundIcon`, the splash theme above,
`shortcuts.xml`, the two home-screen widgets' preview images, and
`LauncherGamesActivity`'s pinned-shortcut fallback - already pointed at the
one alias, `mipmap/ic_launcher`
(`app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`). The desktop-session
notification did not: `DesktopSessionService.buildNotification()` used the
platform's generic `android.R.drawable.ic_menu_manage`. The adaptive icon
now has a third layer, `drawable/ic_launcher_monochrome.xml` (Android 13+
themed icons), and the notification's `setSmallIcon` reuses that same
drawable as its silhouette instead of carrying a second icon asset - one
mechanism, not two. All three layers
(`ic_launcher_background.xml`/`ic_launcher_foreground.xml`/
`ic_launcher_monochrome.xml`) are marked `PLACEHOLDER icon, replace with
real art` in their own file comments (approved as the placeholder by the
owner, 2026-09-27; real art comes later). To replace it: swap the three
drawables for the real design (adaptive-icon safe zone is the centre ~66dp
of a 108x108dp viewport per Android's spec; the monochrome layer must stay
a single flat colour, since both the OS and the notification tint it) and
leave every reference as `mipmap/ic_launcher` - nothing else needs to
change. droidtop's minSdk is 26, so only the `mipmap-anydpi-v26` adaptive
icon exists; there is no legacy per-density mipmap set to keep in sync.
Enginehost's own icon (it currently sets none, per the fallback-icon
finding above) is that app's own fix, not droidtop's.

**Switching modes is named on every surface (decided 2026-09-25).** The
mode switcher (`BackButtonMenu`: Android, the modes that are on, and
"Modes and settings") opens from a long-press of Back anywhere, and by
name from each surface, because the long press alone is invisible to a
newcomer and BlueStacks never delivers it: the Android home screen's
long-press menu ("droidtop modes"), Gaming's Quick Menu, System tab
("Switch mode"), and the Desktop taskbar ("Modes"). A surface with no
Activity of its own to hand opens it through `ModeSwitcherActivity`.
"Modes and settings" opens Global settings, where each mode is switched on
and off (Settings runs in a task of its own and every opening from a
switcher, the home screen menu or the Desktop taskbar starts it fresh, so a
page left open never answers for the page asked for, and clearing it never
takes a running shell with it), and Global settings is the first row of the launcher's settings
list and of Desktop's settings as well as Gaming's; turning a mode off is
always reversible from the UI (it once took a data clear, dq-coordinator-23
F5).

**The switcher's rows are real Views, not a stock dialog list (fixed
2026-09-26).** A UX pass on BlueStacks (Android 9, droidtop 0.1.0-dev-903)
found the switcher unusable both ways: D-pad Down moved focus onto
"Android" and no further Down press ever reached "Gaming" or past it, and
tapping "Gaming" directly restarted `MainActivity` (confirmed in logcat)
but the screen stayed on whatever was showing before -- the stock
`AlertDialog.setItems` list's own internal selection tracking, not
droidtop's own focus/click handling. `BackButtonMenu.show` now builds the
dialog's rows itself: one real, individually focusable `TextView` per row
in a plain vertical `LinearLayout`, each with its own click closure
capturing its mode directly rather than looking an index back up in a
parallel array. A `LinearLayout`'s own focus search is the same mechanism
every other droidtop screen already relies on for pad navigation, and is
far more reliably tested across Android versions than a `ListView`'s
internal one; the first row receives focus explicitly on open rather than
waiting for the first Down press to "acquire" it. The row order is
`ModeGate.switcherModes` (`runtime-common`, unit-tested in `ModesTest`):
Android always, Desktop and Gaming only while enabled -- exactly what
`BackButtonMenu` computed inline before, now a pure function the dialog
and its test share. The dialog also draws its own hint row ("Up/Down
Navigate · A Select · B Cancel") instead of leaving the Quick Menu's
underneath it visible through the dialog, which named controls
("Lower/Raise/Act/Close") that don't apply here.

**That fix was the dialog; a second, separate bug looked like "Gaming
never appears" (found and fixed 2026-09-26).** The rig re-tested the
above fix on BlueStacks (Android 9) and found selecting "Gaming" still
never showed the Gaming carousel, from both the Quick Menu's System tab
and the Android home screen's "droidtop modes" (dq-modefix-01, steps 3-4)
-- reproduced again here. `ModeGate.resolveAppMode` was not the cause: it
already resolves GAMING correctly in this exact scenario (new unit test,
`ModesTest`, "switching to Gaming after Android was last..."), and
`MainActivity`'s `singleTask` + `onNewIntent` handling brings the right
mode back every time. The real cause is `GamepadShell`'s own Quick Menu
(`quickMenuOpen`, a plain Compose `remember`): switching to Android or
Desktop leaves `MainActivity`'s task backgrounded, not destroyed, so a
Quick Menu left open survives the round trip in memory. Picking "Gaming"
from the switcher brings that same instance back to the front with
`quickMenuOpen` still `true`, stacking the old Quick Menu over the
correctly-resolved (but now hidden) Gaming shell -- which is exactly what
a rig walkthrough and the original report both read as "Gaming never
appears" and "Quick Menu still open on top". Fixed by dismissing the
Quick Menu on every real deep-link re-entry
(`LaunchedEffect(deepLinkToken) { quickMenuOpen = false }`, the same
per-entry reset token `GamepadShell` already uses for a rescan/section
deep link), not by touching mode resolution.

### Standard mode on a phone, a tablet and in portrait (owner, 2026-09-28)

Standard supports phone use. The same device can run different UIs as contexts: a phone becomes
a desktop by plugging in and switching UIs, so Standard is a first-class phone launcher and not
only the handheld's plain home screen (Droidtop/tracker#89). What that means in the fork:

- **Grids and sizes.** Launcher3's own two grid profiles ship in `res/xml/device_profiles.xml`:
  `murine_grid` (phone, and multi-display) and `murine_grid_tablet` (6x5, scalable). The device
  category picks between them, and the stock `values-sw600dp`/`sw720dp` and `-land` resources
  size the rest, so portrait and landscape phones and tablets all get a real grid and are not
  one fixed 1920x1080 layout. Rotation of the home screen follows Launcher3's rule: on by
  default from a 600dp smallest width, off (portrait) below it, and a user setting either way.
- **Not built, on purpose.** Split-screen entry from a drag and app pairs
  (`AppPairIcon` is vendored, nothing launches it) are Launcher3 quickstep features. This fork
  compiles only `src_no_quickstep`, and the quickstep module is bound by the system to the
  device's own recents component, so a third-party home cannot host it. The system's own
  split-screen and the freeform window mode still work on the apps themselves
  (`resizeableActivity` is on for the launcher). The taskbar is built without quickstep, as
  droidtop's own component: see "Taskbar on large screens" below.
- **Verified so far:** every rig run in this section is the 1920x1080 landscape handheld or its
  emulator. A tablet-class emulator and the portrait AVD are the outstanding checks.

### Taskbar on large screens (owner, 2026-09-29, Droidtop/tracker#88)

The owner: "Tablets and larger screens are important targets." Standard mode draws a taskbar
along the bottom of every tablet-sized or larger display, and of every external display, with
the launcher's pinned apps, the apps opened since it started, an Apps button that opens the
drawer, Recents, and a Hide button that folds it to a small tab.

**What upstream does, and why droidtop's is its own.** Launcher3's taskbar (`TaskbarActivityContext`
and its controllers) is quickstep code drawn in a system-privileged window and fed by the
system's recents; `DeviceProfile.isTaskbarPresent` is `isTablet && wmProxy.isTaskbarDrawnInProcess()`,
false here because the module is not compiled. What upstream teaches, and what is followed: the
taskbar is a strip of the pinned (hotseat) apps plus the running ones, the drawer button opens the
same all-apps, and it exists only where the screen is large. What is not followed is the window
type and the data source, which a third-party home cannot have.

**The mechanism (`StandardTaskbar`, `dev.droidtop.shell.standard`).**
- *Window.* A `TYPE_ACCESSIBILITY_OVERLAY` window per display, owned by
  `MurineAccessibilityService`, the accessibility service the gesture actions (lock screen, Recents)
  already need. No `SYSTEM_ALERT_WINDOW` and no second service or permission: the person enabling the
  one service is the only grant. The overlay covers the bottom strip of the app in front (an overlay
  cannot reserve space the app lays out around); Hide folds it to a tab for an app whose own controls sit
  there. On external displays the window is created for that display (`createWindowContext` from
  Android 12, `createDisplayContext` before) and apps are launched onto the display of the bar
  that was tapped (`ActivityOptions.setLaunchDisplayId`).
- *Where it shows.* `TaskbarPolicy.shownOnDisplay` (runtime-common, unit-tested): a display of 600dp
  smallest width or more (Android's own phone/tablet line), and any non-default display; a phone's own
  screen gets none. It is shown while another app is in front, not over the home screen (whose hotseat
  is the same pinned row, so there is one source for pinned apps, the launcher model's hotseat, read
  through `enqueueModelUpdateTask`, never a second list) and not over droidtop's own screens. Only when
  droidtop is the Home app, and only while the setting is on (Home settings, "Taskbar on large screens",
  default on; on turning it on the accessibility permission is asked for).
- *Running apps.* An honest limit: Android gives a non-privileged app no list of other apps' tasks. The
  taskbar lists the apps that came to the front since the service connected, from the service's
  window-state events, of which it reads only the package and the activity name of the window (an
  `Activity` that resolves in the package manager, so dialogs, the keyboard and its own overlay do not
  count; never window content, `canRetrieveWindowContent` stays false). The list is per process, capped at
  eight (`TaskbarPolicy.withOpened`), keeps an app's place while the person switches, and an app is taken
  off it by a long press; it is "opened this session", not a live task list. The events are requested only
  while the setting is on.
- *Recents.* The same single path as the gesture slot: `GLOBAL_ACTION_RECENTS` through the service.
- *D-pad.* The window is not focusable, so it never steals keys from the app. The service's key filter
  (`canRequestFilterKeyEvents`, requested only while the setting is on) looks for exactly two keys, the Meta
  key and the pad's Mode button, and consumes only those: a press makes the window focusable and puts focus on
  its first button, the D-pad moves between buttons, A or Enter acts, and B, Back or a second press of the
  trigger hands focus back to the app. No other key is read or consumed. Select/Start were not used as the
  trigger because emulators use them.
- *Disclosure.* The accessibility disclosure text (`pref_accessibility_disclosure_desc`) now says what the
  taskbar reads: the front app's name and the two trigger keys, nothing on screen. Its translations were
  dropped with the old text, which said the service uses only screen locking.

**Why the Desktop shell's taskbar is not reused.** `DesktopShell.Taskbar` is a Compose row over the
Wayland compositor's toplevels (`wlr-foreign-toplevel-management`) inside the Desktop mode surface, with
its Start menu, container windows and tray; its window list is a list of container windows, not Android
apps, and its host is droidtop's own activity, not a window over other apps. Nothing in it fits an
Android-app strip over foreign apps except the look, so the Standard taskbar shares no code with it and
neither is a second mechanism for the other's job: one draws Android apps over Android, the other draws the
container's windows inside the container.

**Not built.** Reserving screen space for the taskbar (apps are not resized around it), freeform launching of
pinned apps into windows, drag from the taskbar into split-screen, per-display foreground tracking (the
service's window events do not say which display an app came up on, so one foreground state drives every
bar), work-profile apps in the open list, and a live task list.
### Accessibility of the custom dialogs (Droidtop/tracker#91)

`BackButtonMenu` (the mode switcher) and `RadioListDialog` (the single-select picker)
replaced stock AlertDialog lists with plain focusable Views so a D-pad reaches every row.
A stock list also told a screen reader what each row is; a focusable `TextView` or
`LinearLayout` does not, so the rewrite had made them read as bare text. One helper,
`shell/standard/DialogAccessibility`, puts that back on the same views and is used by
both: the dialog's title is its accessibility pane title and a heading; a mode row is
announced as a button; a picker row as a radio button with its checked state (and a
disabled row is `isEnabled = false`, not only unfocusable); an icon beside a label that is
already read, and the controller hint row ("A Select, B Cancel"), are hidden from the
screen reader. The D-pad behaviour is untouched, so nothing here can reopen that bug.
Only the code path is changed: TalkBack itself has not been run on either dialog.

### Recents in Standard (Droidtop/tracker#90)

droidtop provides no Overview of its own (no quickstep module, above). The Recents key and
gesture belong to the system: on Android 10 and later SystemUI binds to the device's own
recents component (`config_recentsComponentName`, the OEM launcher's quickstep), whichever
home app is set, and before 10 SystemUI draws it itself, so a third-party home such as this one
gets the system task switcher the same way Nova does. That is expected, not yet rig-verified on
the handheld's firmware. Where a device ships no working recents surface, or a user wants it on
a gesture, the gesture slots (double-tap, swipe-down) offer **Open recent apps**
(`GestureAction.OPEN_RECENTS`): it asks the system for its Recents through the accessibility
service's `GLOBAL_ACTION_RECENTS`, the same single path the lock-screen action already uses
(the disclosure and the Accessibility settings hand-off live in one place, `performGlobal`).

### Wallpaper, and the first-screen widget (Droidtop/tracker#92, #93)

- The long-press menu's **Wallpaper & style** fires the generic `ACTION_SET_WALLPAPER` and
  names no package (`wallpaper_picker_package` is empty in this build), so it can only reach
  what the device really has. The entry is offered only when an activity resolves that intent
  (the manifest holds `QUERY_ALL_PACKAGES`, so the check is reliable), and a tap that races an
  uninstall shows the launcher's own "App isn't installed" toast rather than failing silently.
- The first-screen widget (`SmartspaceMode`) is real, not a stub: **Clock** is a live
  `TextClock` time plus the locale's own date format, and **Google Smartspace** places the
  Google app's own at-a-glance widget and is offered only when that app is installed. Neither
  carries weather or calendar data of droidtop's own, and droidtop fabricates none; a weather
  or calendar card would arrive as a plugin data source, not as placeholder text.

### "Full computer", and where Launcher mode stands against Nova/Apex (survey + decided 2026-09-25)

The owner's direction: droidtop on the console "needs to make the android
device really feel like a full computer, that means being a real
enhancement every way it can", and Launcher mode specifically "needs to
look better, have more features (all the stuff nova and apex added, for
instance...)".

**The survey's finding reframes the work.** `:shell-default` is not a bare
Launcher3 checkout; it is Murine Launcher (github.com/alesimula/Murine-launcher)
forked in whole (`826fb3bb`), and Murine is already a Nova/Apex-class
launcher in its own right. Checked directly against the fork's own
sources rather than assumed from Nova/Apex's feature lists:

| Feature | State | Where |
|---|---|---|
| Dock, folders, drawer, widgets, rotation, grid size | HAVE (stock Launcher3 + Murine) | `com.android.launcher3.Workspace`/`CellLayout`/`Folder`; grid size `SettingsHomeFragment.GRID_SIZE_WIDTH`/`HEIGHT` |
| Icon packs, including **per-app** override | HAVE | `app/murinelauncher/icons/IconPackManager.kt:1067` (`buildIconPackEntries(perAppComponent)`), `SettingsIconPackFragment.kt` |
| Notification badges/dots | HAVE, wired | `NotificationBadgeCounter.kt`, consumed by `BubbleTextView.java` and `FolderIcon.java` |
| Hidden apps / app lock | HAVE | `settings/hiddenapps/{AppLock,HiddenAppsRepository}.kt` |
| Backup/restore | HAVE, wired to Settings | `backup/BackupHelper.kt`, `SettingsMiscFragment.BACKUP_EXPORT`/`BACKUP_IMPORT` |
| Smartspace/clock widget | HAVE | `widget/smartspace/{MurineClockView,SmartspaceMode}.kt` |
| Configurable QSB with web search providers | HAVE | `widget/search/{SearchProvider,MurineSearchBarView}.kt` (8 providers + custom) |
| Gestures: double-tap and swipe-down, each assignable to any of nothing/lock screen/open notifications/open app drawer/open recent apps (**built 2026-09-26**, was two fixed on/off gestures) | HAVE, exposed in Settings | `GestureAction` enum + `perform(Launcher)` (`com.android.launcher3.touch`), `LauncherPrefs.GESTURE_DOUBLE_TAP_ACTION`/`GESTURE_SWIPE_DOWN_ACTION`, picked from `SettingsHomeFragment`'s `RadioGroupPreference` rows (`DOUBLE_TAP_ACTION`, `SWIPE_DOWN_ACTION`), applied in `WorkspaceTouchListener.java`/`NotificationSwipeController.kt`; an existing install's old two-boolean prefs are carried over once by `GestureActionMigration` |
| Drawer search over apps, droidtop's own library and plugin "Get more" results | **built (Droidtop/tracker#12)** | the drawer's search field opens `LauncherSearchActivity` on the shared `LibrarySearchDialog`; see "Launcher search" below |
| A home-screen widget of droidtop's own (a "full computer" feature neither Nova nor Apex can offer, since they have no game library) | **built this change** | `ContinuePlayingWidgetProvider.kt` (see below) |
| Global settings, Desktop settings rendered in the shell's own row component, pad-navigable | HAVE (fixed 2026-09-24/25, UI pass H4) | `DroidtopWideSettings.kt`, `SettingsGlobalFragment.kt`'s `CatalogPreferenceNavigator` |
| Icon-pack/drawer/hidden-apps settings pages left as stock Android preference UI | HAVE, and correct: H4's own fix text scopes the shell's row component to Global/Desktop only, and explicitly keeps these stock | `docs/audit-2026-09-24/ui-assessment.md` H4 |
| Plugin contributions in the launcher: status tiles (**built this change**), search providers and app actions (still not buildable -- see below) | PARTIAL | `PluginStatusWidgetProvider.kt`; see below |
| Recent/frequently-used apps row in the app drawer | **built this change** | `RecentAppsStore`, `AlphabeticalAppsList.addRecentAppItems`; see below |

**A real plugin-fed launcher surface: the status-tile home-screen widget
(built this change).** §12a's `PluginCapability.STATUS_TILE` and
`PluginStore.runnableFor` already documented themselves as "what an
actual call site (a status tile row, a metadata pass) should iterate",
but the only existing call site was a manual test button in the Plugins
settings screen (`AppSettingsCatalogs.pluginsScreen`, "Call ...'s status
tile"), not a real launcher surface. `PluginStatusWidgetProvider` is that
call site: a droidtop-drawn home-screen widget (not a row inside Murine's
own workspace grid, per §7k), same shape as the already-shipped
`ContinuePlayingWidgetProvider` -- placeable from the Standard launcher's
stock widget picker, refreshed on its own schedule (the system's periodic
tick, or immediately after a plugin's approval/enabled state changes in
Settings) rather than a background loop a plugin owns itself, matching
`STATUS_TILE`'s own contract. Each refresh opens one short-lived
`PluginCrashPolicy` connection per candidate plugin and tears it down
right after; a plugin that times out or fails is dropped for that refresh
rather than shown as broken.

Two real, load-bearing bugs surfaced and were fixed while wiring this up,
neither of them specific to the new widget:

1. **droidtop's own home-screen widgets never appeared in its own widget
   picker at all.** `WidgetsModel.WidgetValidityCheckForPicker` runs every
   non-custom widget item through `AppFilter.shouldShowApp`, and
   `AppFilter` hides every component in droidtop's own package except
   `LauncherGamesActivity` (the "One droidtop icon" rule, aimed at the app
   drawer/all-apps list) -- a rule that was never meant to cover a
   home-screen widget (an explicit, opt-in placement surface) but applied
   there too since the exemption was one hardcoded class name.
   `ContinuePlayingWidgetProvider`'s own acceptance check ("place the
   widget from the stock widget picker") could not have actually passed
   before this fix; confirmed live on emulator-5560 that neither widget
   appeared (droidtop's own `#custom-widget-scheme` smartspace clock still
   did, since that path skips `AppFilter` entirely) until
   `AppFilter.HIDE_SELF_EXEMPT_CLASSES` was extended to include both
   widget providers.
2. **A `native_bundle` plugin self-disables on its very first real call on
   Android 10+.** Android refuses `DexClassLoader` on a file that is still
   writable by the app ("Writable dex file ... is not allowed"); this
   plugin worked fine through approval (a load, not an `invoke()`) and
   then disabled itself the moment the new widget's first refresh actually
   called it. `PluginBundleInstaller` wrote each payload file and never
   marked it read-only afterwards, so this was never going to work on any
   Android 10+ device -- it only looked like it worked because the
   existing `dq-plugins-01` rig check ran entirely on BlueStacks (Android
   9), which does not enforce this restriction. Fixed with one line
   (`target.setReadOnly()` right after each payload file is written) --
   `PluginContext`'s own doc comment already called the payload
   "read-only" as if this were already true, and now it actually is.

Rig-verified on emulator-5560 end to end, the user way: installed and
approved the real `plugin-sample-statustile` bundle through Settings'
own file picker, placed "Plugin status" from the stock widget picker,
and it showed "Sample tile: loaded 1 time(s), called OK" -- a real
`invoke()` round trip through the isolated `:pluginhost` process,
rendered on the home screen.

Scoped to status tiles only this pass, not search providers or app
actions: `PluginCapability` has no search-provider capability defined at
all (adding one here would be inventing API surface, not building
against the real one -- a decision for whoever owns §12a next), and app
actions (`PluginCapability.APP_STATUS`) has no real sample plugin yet to
verify against. Both left open.

**A real "Recent" row in the app drawer (built this change).**
Confirmed genuinely absent first (grepped shell-default for predicted/
frequent -- no `PredictedAppIcon`, no `UsageStatsManager`, nothing; Nova/
Apex both have this, stock Launcher3 does not by default). Built on
droidtop's own launch history rather than `UsageStatsManager`: that needs
the special "Usage access" grant (a Settings toggle, not a runtime
permission dialog) a fresh install would not have, and this pass has no UI
to request it. `RecentAppsStore` records a launch directly at the one
place both workspace and drawer icon taps already funnel through
(`ItemClickHandler.startAppShortcutOrInfoActivity`) -- the same
"droidtop tracks its own history" shape `RoomPlayHistoryStore` already
uses for games, just SharedPreferences-simple since this is component
names. `AlphabeticalAppsList.addRecentAppItems` prepends a "Recent"
header + up to 5 matched `AppInfo` rows + a divider before the
alphabetical list (same shape as the work-profile items already prepended
there), matched against apps already in `mApps` so a hidden, filtered, or
since-uninstalled component is silently excluded.

A first version only recorded launches but never re-derived the row on
reopen -- `AlphabeticalAppsList`'s cached adapter items only rebuild from
a real `LauncherModel` change (install/uninstall), which closing and
reopening the drawer does not trigger, so a launch recorded after the
drawer's last real data refresh never appeared. Rig-caught on
emulator-5560: launched Chrome and Clock from a fresh drawer, reopened it,
saw no row at all. Fixed by having `Launcher.onStateSetStart` call
`ActivityAllAppsContainerView.refreshRecentApps()` (which just calls the
already-public `updateAdapterItems()`, which already dispatches its own
`DiffUtil` update) every time the drawer opens (`ALL_APPS` state).
Rig-confirmed after the fix: a fresh (onboarded) install shows no row at
all; launching Chrome then Clock and reopening the drawer shows
"Clock, Chrome" (most-recent-leftmost); launching Calendar next moves it
to the front ("Calendar, Clock, Chrome").

**Launcher search (rebuilt 2026-09-29, Droidtop/tracker#12, owner: "go ahead").**
The drawer's search field is no longer a filter of the drawer. It is a door:
a tap or Enter on it, or a printable key typed on a hardware keyboard while the
drawer is up, opens `dev.droidtop.app.LauncherSearchActivity`, a translucent
activity that draws the shared `LibrarySearchDialog` (`shell-gamepad`
`query/LibraryQueryUi.kt`, the dialog the PC library and the console lists
open, see 12a "Search surfaces wired") through `LauncherSearchScreen`. Its
local results are the installed apps that match, then the library's games
(`matchesSearchText`, the one text rule of every list, over the library's
already-scanned in-RAM list: no per-game disk lookups while typing), then the
dialog's own "Get more" group from the source plugins and, while the field is
empty, droidtop's Recommendations. A tap on an app opens it, a tap on a game
plays it through `GameLaunchActivity.dispatch`, a tap on a "Get more" result
starts the download job as everywhere else.

`:shell-default` cannot depend on `:app` or `:library-core`, so the seam is an
action, `LauncherSearch.ACTION_SEARCH` (`dev.droidtop.shell.standard.
LauncherSearch`), that the launcher fires and `:app` answers, and the
installed apps are answered by the launcher itself
(`LauncherSearch.findApps`: the launcher model's app list, the drawer's own
word-matching rule, a quiet private space left out) and handed to the screen as
plain rows. This replaced the library-only path, and what it replaced is
deleted: `LibrarySearch`/`LibrarySearchEntry` in `:runtime-common`,
`LibrarySearchBridge` in `:app`, the `VIEW_TYPE_LIBRARY_GAME` row of
`BaseAllAppsAdapter` with its layout and placeholder drawable,
`DefaultAppSearchAlgorithm` and `AllAppsSearchBarController` (the in-place
filter). Known differences from the stock drawer search: the private-space
"unlock" row that a search for its name used to offer is gone with the in-place
list, and "Get more" from here has no download destination (the list has no
system, as in the PC library), so a pick says where to download it from instead
of starting a job; a per-title system guess is the follow-up if the owner wants
downloads started from the drawer.

**RadioGroupBottomSheet renders no options on this rig -- four real fixes
landed, root cause still open (2026-09-26).** Verifying the gesture-action
picker on emulator-5560 (Android 14, 1920x1080 landscape -- the Retroid
Pocket 5's own resolution and orientation) found `RadioGroupBottomSheet`
showing only its title row, no radio options visible or reachable by
touch. Confirmed not a gesture-actions regression: it reproduces
identically on the pre-existing, unmodified smartspace-mode picker
(`SmartspaceMode`, same `RadioGroupPreference` mechanism), so every
`RadioGroupPreference` row in Settings is affected. Four distinct, real
layout bugs were found and fixed by inspecting `dumpsys accessibility`
output live against each hypothesis in turn: the preference list's
RecyclerView inherited `match_parent` height from its stock AndroidX
layout inside a `wrap_content` container; the child fragment holding it
was added with a deferred `commit()` rather than `commitNow()`, so the
container's first layout pass ran before that fragment's view existed;
the sheet's own collapsed/peek state clipped content even once the above
two were fixed; and `PreferenceFragmentCompat`'s own root view (not just
its RecyclerView) was also `match_parent`. Each fix was verified to have
actually compiled into the tested APK (decompiled and grepped for the
added method names -- catching a false negative earlier in this pass
where a stale reused download directory served an old build and looked
identical to genuine failures) and rig-tested fresh after each one; the
sheet confirmed reaching `STATE_EXPANDED` (`drag_handle`'s own
content-desc read "Expanded. Drag handle") but `prefs_container` still
measured `Rect(0,0-1280,0)` -- zero height -- with all four fixes in
place simultaneously. The remaining cause is narrower than layout at this
point: most likely `RadioPreferenceFragment.onCreatePreferences` (or
`SelectorWithWidgetPreference`'s own view binding) isn't producing rows
at runtime despite looking correct in source, which needs Android
Studio's Layout Inspector or a debugger attached to a live session to
isolate -- past what adb screenshots, `uiautomator dump` and logcat can
distinguish. The four fixes stay landed (each is independently correct
regardless of the remaining symptom); the open remainder is filed as its
own follow-up rather than guessed at further.

**Replaced with a real dialog instead of debugging further (fixed
2026-09-26).** Rather than keep chasing the zero-height symptom above with
no way to attach a debugger from a cloud session, `RadioGroupPreference`
now shows its own `RadioListDialog` (real, individually focusable rows in
a plain `LinearLayout`, a hint row, current selection shown via the same
`droidtop_list_selector` accent-ring state the mode switcher uses) instead
of `RadioGroupBottomSheet` -- the same fix commit e978478e already applied
to the mode switcher when a stock widget proved unreliable for D-pad
focus. This covers every caller that goes through `RadioGroupPreference`:
gesture actions, smartspace/"At a glance" mode, icon packs, per-app icon
pack override list, and the QSB search-provider picker.
`RadioGroupBottomSheet` itself is not deleted -- `FilterableIconPackSheet`
and `IconPickerBottomSheet` (`AppInfoPreferenceFragment`'s per-app icon
picker, a filterable icon grid with a live "show all" toggle, a genuinely
different job) still build on it directly and were left alone; if the
same zero-height bug turns out to affect that sheet too it is a separate,
not-yet-confirmed follow-up.

**Rig-verified (emulator-5560, 1920x1080 landscape).** `RadioListDialog`
renders correctly for every caller: `SmartspaceMode`'s "First screen
widget" picker (3 rows, icons, current-selection ring), the icon-shape
picker (`iconPosition="end"`, 6+ entries, scrolls), and both gesture
pickers -- by D-pad (focus moves row to row, A selects, dismisses) and by
touch. Selecting a value updates the outer preference row's summary and
persists immediately.

**A second, unrelated bug surfaced during that same verification and is
fixed alongside it:** `murine_prefs_home.xml`'s two gesture rows had
`android:key="pref_double_tap_action"`/`"pref_swipe_down_action"`, but
`SettingsHomeFragment.initPreference`'s own `DOUBLE_TAP_ACTION`/
`SWIPE_DOWN_ACTION` constants (and the `LauncherPrefs` items the runtime
gesture handlers actually read) are
`"pref_gesture_double_tap_action"`/`"pref_gesture_swipe_down_action"`.
The `when (preference.key)` branch never matched, so `bindGestureAction`
never ran: both rows opened with zero entries and zero configured
text/summary providers, independent of the dialog/layout work above. This
predates this pass (introduced when gesture actions were first built,
2026-09-26 morning) and was mistaken at the time for another instance of
the sheet's layout bug since the visible symptom (title only, no rows)
looked identical. Fixed by renaming the XML keys to match; confirmed live
that both gesture rows now show all four `GestureAction` entries, that a
selection's summary updates ("Opens the full list of apps"), and that it
persists to the exact `SharedPreferences` key
(`pref_gesture_double_tap_action`) the gesture handlers read.

**The target feature set, decided:** Launcher mode keeps inheriting Nova/Apex-class
functionality from Murine wholesale rather than droidtop reimplementing any
of the rows marked HAVE above — the vendored-tree rule (hook or extend, never
rewrite) applies here as much as anywhere. droidtop's own work is the rows
marked LACK, plus the "full computer" rows that are droidtop's alone because
they need the shared library: a games-aware search algorithm, and
launcher-native surfaces (widgets now, plugin-fed tiles once §12a lands) that
no general-purpose launcher can offer since it has no library to draw from.

**How plugin contributions reach the launcher (seam, not built here).** Once
§12a's plugin API exists, a plugin-contributed status tile, search provider,
app action or widget reaches Launcher mode through the same catalog pattern
`DroidtopWideSettings`/`SettingsScreenRegistry` already uses for Global
settings: a registry `:app` (or a new small module both `:shell-default` and
the Gaming shell can see) populates from installed plugins, and each surface
(the QSB's search results, a home-screen widget slot, a long-press app
action) reads that registry rather than knowing about plugins directly. This
keeps Launcher mode buildable now and the plugin surface pluggable in later
without a second registration mechanism. The plugin surfaces for every
mode, Launcher mode included (status tiles and home widgets, search
providers, long-press app actions, drawer groups), are specified in
`docs/plugin-api.md` §3 C (§12a). The plugin status widget
(`PluginStatusWidgetProvider`) is built; the rest are on that document's
roadmap.

**Handheld constraint, restated for this work specifically:** every row above
must work by controller AND touch, pointer and focus as one selection (§7j),
same as the rest of droidtop's chrome — Murine's stock pages already satisfy
this by inheriting Launcher3's own d-pad/keyboard navigation, and
`ContinuePlayingWidgetProvider`'s rows are ordinary focusable/clickable
widget views, reachable the same way any home-screen widget is. Root stays
what it has always been for the launcher: never used, never required: none
of Launcher mode's rows above touch `runtime-linux-root`.

**Built this change: the "Continue playing" home-screen widget.** A tap-to-launch
list of the library's most recently played games (up to four), placed on
the home screen like any other app's widget through Launcher3's own stock
widget picker (no droidtop-specific picker UI needed). It reads the same
in-RAM index every other surface reads (`Library.backgroundScanState`,
never a folder walk from the widget itself — the perf rule in §7g), and a
tap dispatches through the same `GameLaunchActivity.intentFor` path a
pinned game icon already uses (launch-screen memory and play history
included, no second launch mechanism). `GameLaunchActivity.dispatch` pushes
an immediate widget refresh on `LaunchResult.Launched` so the widget shows
the just-played game without waiting for the next 30-minute system tick.

### Gaming mode — the gaming-focused shell (renamed from Handheld)



The mode was never about the form factor; it is the gaming shell, and it
is offered on devices that are not handhelds. It contributes:

- the ES-DE theme engine and its themed surfaces (§7f),
- the gamepad shell and its in-context menus, the Quick Menu (§7f),
- the PC surface — every PC and engine game as one list (§7i),
- enginehost/emulator integration and the per-game runner choice (§7e2b),
- scraping and metadata (the standing gap, §7),
- the companion/input surface on a secondary screen (§4c, §4d).

With Gaming off, games still launch — from the launcher, or from any
entry point onto the shared library — through the same resolution. What
is lost is the integration around them: the themed browsing surface, the
Quick Menu overlay, the companion screen, scraped metadata.

### Desktop mode — the PC in a box

Containers and the primary container session (`DesktopSessionService`),
the Wine/Linux desktop and its window placement, the host bridge, the
clipboard bridge, container management UI, and window streaming through
windowcast (§7a). It contributes everything that makes a Linux or Windows
program a first-class window rather than a game launch.

### Integration points (named, so nothing is re-implemented)

- **One library, three surfaces.** Launcher shows games as a Games grid
  and as pinned home-screen icons,
  Gaming as themed rows, Desktop as desktop objects (§2b). One scan.
- **One launch resolution.** Every surface launches through the same
  strategy selection; only placement differs (fullscreen on a display vs
  windowed on the shared desktop).
- **One settings catalog.** The same catalog renders as Android
  preferences (`:shell-default`) and as the in-shell gamepad settings
  (`:shell-gamepad`).
- **One secondary-screen mechanism.** `SecondaryDisplayContent` holds one
  registration per mode; the active mode selects what the second screen
  draws (§4c).
- **Quick Menu reaches Desktop.** Gaming's Quick Menu offers Desktop's
  containers rather than carrying a container implementation of its own.
- **The Windows backbone has two owners.** The vendored gamenative
  bootstrap serves Gaming's PC surface and Desktop's containers, and the
  shared PC launch path; it is one idempotent entry point
  (`WindowsBackbone.ensureStarted`, `:runtime-windows`), never a second
  init path.

### How the rule is enforced

`ModePiece` (`:runtime-common`) lists every piece of droidtop that belongs
to a mode rather than to the core, with the mode(s) that own it.
`ModeGate.piecesToStart(enabled)` turns the enabled set into the set of
pieces that may run, and `ModeStartup` (`:app`) is the single place that
starts exactly those and stops the rest. It runs from
`DroidtopApplication.onCreate` and again on every mode switch, so a mode
turned off mid-session stops contributing immediately rather than at the
next process start.

The library's own background work follows the same rule even though the
library is core: the slow index pass (§7g) runs only while some surface
observes the library (a Gaming shell on screen, the Launcher's Games grid
open, a companion drawing library entries) and stops when the last
observer goes, rather than for the life of the process. A process whose
every mode is off, or whose only live mode is Launcher with the Games grid
closed, walks no games root.

Components the SYSTEM starts on its own — a bound
`NotificationListenerService`, an accessibility service, a broadcast
receiver the platform fires — cannot be gated by any Activity of ours, so
they are enabled and disabled as components
(`PackageManager.setComponentEnabledSetting`). The honest price: a
system-bound service loses its grant when its component is disabled, so
re-enabling the mode means granting notification or accessibility access
again.

A piece a mode STARTED is stopped by the same switch: `ModeStartup.apply`
stops every running piece whose owning modes are all off, the vendored
`SteamService` included (started by the Windows backbone for Gaming's PC
surface and Desktop's containers, it must not outlive both), so "runs no
code" holds mid-session and not only at the next process start.

Deliberately not component-gated, with reasons: a device-admin receiver
(disabling an active admin is not droidtop's call behind the user's
back), exported Activities the HOME role already gates, and the
launcher's ContentProviders, whose `onCreate` is a bare `return true`.

**A games folder is walked when it is added.** `GamesRoots.walkIfChanged`
is the one step from "the folders changed" to "the library has their
games", and it has two callers: every surface that lists games runs it for
as long as it is open (`Library.scanFollowingGamesRoots`), and the shared
core runs it the moment the folder preference changes, for the life of the
process (`GamesRoots.follow`, installed from `DroidtopApplication`), so a
folder added in onboarding or Settings is walked whichever mode is on and
whichever surface is open. That walk is the person's own act, not the slow
pass, which still runs only while something observes the library. It used
to wait for the Gaming shell, so after "Open Android" the launcher's games
said "No games yet" (rig, dq-coordinator-24). A root set counts as walked
only when a walk of it FINISHES (`Library.scanInBackground(onFinished)`):
the mark used to be written when the walk started, so a walk that died
with the process left the new folder marked walked and unread
(dq-onboard-01). So the core follower also walks at process start when the
last process did not finish, and a second caller joins a walk of the same
set instead of restarting it. scan.log says when the folders change, when
such a walk starts and when it ends. The core follower holds its
preference listener strongly for the life of the process: SharedPreferences
keeps listeners in a weak map, and the first version (a flow collected in a
scope nothing referenced) was garbage-collected with its listener, so a
folder added in setup went unwalked until the next process start, twice on
the rig (dq-onboard-01, -02).

**Gaming and Desktop are on once setup has turned them on** (decided
2026-09-25). Until onboarding finishes, `Modes.reload` counts neither as
on, whatever their stored switches say, so nothing of theirs runs before
anything was chosen: on a fresh install the Windows backbone booted in
`Application.onCreate` and crashed droidtop's first launch on Android 14
(a DataStore race in the vendored preferences, fixed there too). Finishing
onboarding reloads the modes, which starts what they own.

**A mode switched off leaves the screen** (decided 2026-09-25).
`Modes.enabledFlow` is watched by `MainActivity`: when the shell on screen
belongs to a mode that was just switched off, it changes to the other
app-hosted mode if that one is on, and otherwise finishes for the Android
home screen. Switching Gaming off used to leave the running Gaming shell
fully usable until a force-stop (dq-onboard-01).

**A crash ends the process; there is no recovery screen** (decided
2026-09-25). The Murine fork's Recovery library ("Recover / Restart")
rebuilt the activity stack after a crash into a blank white home screen
that Home could not leave, and locked the screen to portrait
(dq-onboard-01). It is gone; the crash is written down locally and Android's
own crash handling follows (§10c: crash notes, and safe mode after a loop).

The Gaming and Desktop switches are set in two places and read in one:
onboarding writes them from "Anything else to set up" when it finishes
(§7b), and Settings > Global settings > Modes changes them later; both go
through `Modes.setEnabled`, which re-runs `ModeStartup`.

A mode's Activities and Compose trees need no gate of their own:
`MainActivity` renders one enabled shell or nothing (it used to fall
through to Desktop for an undecided mode), and the launcher's Activities
follow the HOME role.

### Renaming Handheld to Gaming

Every user-facing string, heading and identifier that carried the old word
now says Gaming; "handheld" survives only where it means a device shape,
and in vendored trees whose own vocabulary it is (the AOSP launcher's
device profiles, ES-DE theme metadata). Stored preferences move with the
identifiers: `ModeRenameMigration` rewrites the renamed keys and the two
keys that store a mode id, once, reading each old key exactly once and
writing its marker in the same commit as the migrated entries.

## 3. Containers

One `ContainerRuntime` interface (`runtime-common`), two interchangeable
backends selected automatically by root availability:

| | Rooted (`:runtime-linux-root`) | No root (`:runtime-linux-noroot`) |
|---|---|---|
| Fork base | [vendor/droidspaces](../vendor/droidspaces) (GPL-3.0) | [vendor/proot](../vendor/proot) — Termux's PRoot (GPL-2.0), unmodified, built for arm64-v8a and x86_64 |
| Isolation | Real kernel namespaces + cgroups | ptrace-based (proot), no true isolation |
| Requires | KernelSU / APatch / Magisk (Daemon Mode) | nothing |
| Pattern | DroidSpaces' own LXC-like model | proot-distro's model (fake root, link2symlink), with droidtop's shared-socket layout |

### The no-root backend: `ProotRuntime` (built 2026-09-24)

The earlier plan here was to port gamenative-tux's
`com.winlator.linux.DefaultProotContainerBackend` and its bundled proot
binaries. Checked against the vendor tree, neither can run a stock distro
on droidtop's targets:

- **The binaries do not exist for droidtop's ABIs.** The only proot pair in
  the fork is `app/src/legacy/jniLibs/armeabi-v7a/`; the modern flavor
  ships none (the arm64 pair was deleted upstream, §5b), and droidtop
  ships arm64-v8a and x86_64 only.
- **The source cannot build them.** `app/src/main/cpp/proot/src/arch.h`
  `#error`s on every architecture but ARM (no x86_64 at all, which the
  BlueStacks rig is), and its CMake build is commented out upstream.
- **The Java backend cannot drive a distro.** `DefaultProotContainerBackend`
  passes no fake-root or link2symlink option and pins `--cwd` to Winlator's
  `/home/xuser`: a stock image's package manager refuses to run without
  root, and dpkg's hard links fail on Android.

So the no-root backend runs **[vendor/proot](../vendor/proot)**, Termux's
PRoot (the build Termux and proot-distro run whole distributions on),
pinned to the release termux-packages ships. The vendored tree is not
modified; droidtop's additions are patches in
`build-scripts/proot-patches/`, each stating its reason, applied to the
build's own copy. The first: Android's x86_64 app seccomp policy refuses
the `fork` and `vfork` syscalls (bionic forks with `clone`; arm64 has no
such syscalls), musl forks with the raw syscall, and proot answered the
resulting SIGSYS with ENOSYS, so an Alpine guest's shell died at its first
fork on the API 34 emulator ("can't fork: Function not implemented",
dq-desktop-05/06). The patch restarts each as the `clone` it is defined to
be. Reproduced and verified off-device under a seccomp filter that traps
the two syscalls the way Android does: unpatched, the same error;
patched, provisioning, sway, exec and screencopy all ran.
`build-scripts/build-vendor-deps.sh` builds it with its own GNUmakefile and
the NDK, linking the single-file talloc vendored beside gamenative's proot,
into `runtime-linux-noroot/src/main/jniLibs/<abi>/`: `libproot.so`,
`libproot-loader.so` and `libproot-loader32.so` (the loader kept separate,
`PROOT_UNBUNDLE_LOADER`, rather than embedded and extracted at run time).
These are the file names and environment variables (`PROOT_LOADER`,
`PROOT_LOADER_32`, `PROOT_TMP_DIR`) gamenative's own backend already uses, so
that class now finds a working proot too; `ProotRuntime` does not route
through it for the reasons above, and gamenative's
`LinuxContainerBackendRegistry` stays the seam if Wine ever needs to run
inside a container rather than on the ImageFs (§5b says it does not).

**Everything the app executes as itself lives in `nativeLibraryDir`.**
Android refuses an app `exec()` of a file it extracted into its own data
directory once targetSdk is above 28 (droidtop targets 34, §5b). proot and
its loaders are packaged as `lib*.so` for that reason, and so is `crane`
(`runtime-common`'s `libcrane.so`), which used to be an APK asset
extracted into `filesDir` and only worked where root ran it. Only
droidspaces, which always runs through `su`, remains an extracted asset.

**How a container is made (images kept as OCI, 2026-09-24).** Both
backends pull through one `CraneRootfsPuller` (`runtime-common`) into one
`OciImageStore`, and the image is flattened by one `OciFlattener`; they
differ only in the `RootfsUnpacker` that writes the tree.

- *The store is an OCI image layout* (`files/oci`: `index.json`,
  `blobs/sha256/`), which `crane pull --format=oci --platform <this ABI>`
  appends to. Blobs are content-addressed, so a layer shared by two images
  is downloaded and stored once, and the image config (Env, User,
  Entrypoint) is kept; nothing reads it yet. It replaced a `.tar` per image
  from `crane export`, which duplicated every shared layer and threw the
  config away; the old `files/image-cache` is deleted on first use. Each
  `index.json` entry is annotated with the digest it was pulled by (for a
  multi-platform image the index's, while the stored manifest is the
  platform's) and the reference, for the cache UI. `--platform` is always
  passed: without it crane stores every architecture of a multi-platform
  image. Eviction removes least recently used images to the policy's cap
  (2 GB by default) and deletes every blob no remaining image references,
  including an interrupted pull's leftovers; all layout changes run under
  one process-wide lock, since a collection beside a pull would delete the
  layers it just wrote.
- *The flattener* reads the layers top first, so the first time a path is
  seen is its final version, and applies whiteouts (`.wh.<name>`, and
  `.wh..wh..opq` for an opaque directory) to the layers below theirs. What
  it emits is clean by construction: no name with `..` (`TarPaths`, the one
  rule), nothing beneath a symlink or other non-directory (a path with
  anything emitted beneath it stays a directory, so a lower layer's symlink
  of that name is dropped), each path once into an empty destination, hard
  links last and only to a regular file of their own layer that survived,
  numeric ids and permission bits only (no user or group names, which
  toybox would look up in Android's own user table; no pax headers; no
  extended attributes, so file capabilities are not carried). Each layer is
  hashed as it is read and a mismatch fails the unpack and deletes the blob.
  Off-device, alpine, debian:bookworm-slim and python:3.12-slim flattened
  through it and extracted by toybox 0.8.14 as root matched `crane export`
  extracted by GNU tar path for path (mode, uid, link count, symlink
  target, content).
- *droidspaces* streams the flattened image into `su -c tar -xf - -C
  <rootfs>` (`RootTarUnpacker`, through `ProcessRunner`'s standard input),
  keeping the image's real ownership and setuid bits. Root never reads the
  image itself, so which `tar` `su` finds and how it treats hostile names no
  longer matters: it is only ever given the flattener's stream. This closed
  finding 7 of `docs/security/2026-09-24-droidtop-intents-updater.md`; the
  old root `tar -x` of crane's export let a hard link out of the rootfs
  chmod and chown a file outside it.
- *proot* writes the flattener's entries in-process (`RootfsTarExtractor`,
  no tar stream in between): the tree is owned by the app, the image's
  ownership is dropped because `--root-id` presents everything as root's to
  the guest, permission bits are kept plus owner read/write, hard links
  become hard links where Android allows the app one and copies otherwise,
  device nodes and FIFOs are skipped (the guest's `/dev` is the host's). It
  checks again against the filesystem before each write: every parent
  component is checked with lstat and an entry beneath a symlink is
  skipped, because an image's absolute links (Debian's `/var/run -> /run`)
  point into Android's own filesystem on the host. Removal (`TreeDelete`)
  walks without following links and refuses any path outside the backend's
  containers directory — the defect class of the 2026-09-02 storage wipe
  (§5b).

**How a process runs.** Every guest process is one proot session:
`--kill-on-exit --root-id --link2symlink --sysvipc --ashmem-memfd`, the
rootfs, `/dev` `/proc` `/sys` bound through, and droidtop's own binds
(below), then `/usr/bin/env -i` with a clean Linux environment so nothing
of Android's leaks in. `--ashmem-memfd` matters on Android 9: its app
seccomp policy predates bionic's memfd_create (API 30), and wlroots,
libwayland and every Wayland client allocate shared memory with memfd;
proot probes and only substitutes ashmem where memfd is refused. `exec`
waits for its session (a GUI program returns when its window closes, as
`ContainerTerminal` expects) and captures its output. A sibling has no
init under proot: starting one is a no-op and each `exec` is a session of
its own, so the interface says so (`ContainerRuntime.siblingsNeedStart`
is false here) and the container manager offers no Start for one.

**The desktop that is not running offers to start (2026-09-25).** Desktop
mode's viewport, when the session was stopped, says so and has one button,
"Start the desktop"; a failed start has "Try again" (rig dq-desk2-02: the
only route was Containers, and the taskbar's Start is the Start menu).

**Stopping is a stop (decided 2026-09-25, rig dq-coordinator-23 F9).**
proot ignores SIGTERM (`src/tracee/event.c` sets every terminating signal
but SIGQUIT and the fault signals to SIG_IGN) and sets no
`PTRACE_O_EXITKILL`, so the old stop (`destroy()`, then `destroyForcibly()`)
did nothing and then killed proot alone, detaching its tracees: sway,
swaybar and foot kept running after Containers > Stop and after leaving
Desktop, and the next start booted a second sway beside the first. Every
proot droidtop starts, and every guest process under it, now carries two
environment entries, `DROIDTOP_CONTAINER=<id>` and
`DROIDTOP_SESSION=<uuid>` (proot through its own environment, the guest
through the `env -i` list, inherited by everything it starts).
`ProotProcesses` ends a session or a container by SIGKILLing every
process of the app's uid whose `/proc/<pid>/environ` holds the entry,
and every descendant of those (a program that cleared its environment
is still a child of one that did not), repeating until none is left; a
tracee is killable while ptrace-stopped, and proot exits with its last
tracee. `stop` is that for the whole container and is not cancellable
half way; a cancelled `exec` is that for its session; `start` of the
PRIMARY begins with it, so a compositor left by a dead app process can
never run beside the new one. A container is "running" while any
process of it is alive, which is what the container manager shows.

**One layout, both backends** (`ContainerLayout`, `runtime-common`): the
host socket directory at `/run/droidtop-sockets` (`XDG_RUNTIME_DIR`), the
app's storage at `/run/droidtop-app-storage`, `WAYLAND_DISPLAY` set to the
compositor's own socket name for every client, and the PRIMARY's boot
script. The socket is found in the socket directory, never assumed: sway
deliberately skips `wayland-0` (`sway/server.c` starts at `wayland-1`), and
every compositor names its own. The script provisions
once (a marker in `/var/lib`; the install is tested explicitly, because
`set -e` ignores a failure inside an `&&` chain) and then `exec`s the
compositor. droidspaces writes it to `/sbin/init`; proot runs it as the
primary's one long-lived session, and `start()` returns when the
compositor's socket accepts a connection (it fails with the script's last
output if the script exits first, or prints nothing for fifteen minutes).
The plan booted is the catalog entry's current one (`start` takes it), and
the marker records every plan whose install completed, so a package added
to a plan reaches a container made before it (a reused container
provisioned with its creation-time command and had no font, dq-desktop-07),
and a plan already installed, such as Printing switched off and on again,
is not installed again. The script says "first boot" only when no plan has
completed yet, and "the desktop setup changed" otherwise; it used to keep
only the last plan and announce a first boot on every such switch
(dq-desk2-02).
The proot backend also binds a generated `/etc/resolv.conf` (the active
Android network's own DNS servers, read at every start; a stock image has
none and Android has no `/etc/resolv.conf` to inherit) and `/etc/hosts`.
Diagnostics go to logcat (`droidtop.proot`) and
`<external files>/logs/desktop-container.log`, readable on an unrooted
device without `run-as`.

**The compositor environment** is wlroots' own and holds for sway and labwc
alike: `WLR_BACKENDS=headless`, `WLR_HEADLESS_OUTPUTS=1` (the headless
backend starts with no output otherwise; sway's own FALLBACK output is
never advertised), `WLR_RENDERER=pixman` (no DRM render node exists in a
container on Android, and screencopy's shm path is what `:host-bridge`
reads). No seat daemon: wlroots' `backend/backend.c` creates a session only
for its drm and libinput backends, so `seatd` was dropped from
provisioning. The provisioning plan (`CompositorProvisioning.plan`) names
both the packages and the compositor command, so the compositor started
is the one installed (labwc used to be installed and `sway` started).
Debian's plan installs a `policy-rc.d` that refuses service starts first —
Debian's own mechanism for package installs in a chroot or container with
no init; dbus's and polkitd's maintainer scripts otherwise try to start
daemons and fail the install.

**The primary container is the user's desktop and persists.** The desktop
session reuses the existing PRIMARY while it was made from the image the
user still has chosen, and only pulls and recreates when that choice
changed. Resolving the catalog's `latest` on every session start (the
earlier behaviour) meant a registry publishing a new `latest` silently
replaced the whole container, and everything installed in it, on the
next start.

**The session has a lifetime of its own, and it is not the process's
(decided 2026-09-24).** `DesktopSessionService` is a foreground service
with the system's notification (a real icon, the container's name and a
Stop action; on API 33+ the notification permission is asked when the
session is first started, §7b, and a refusal only hides the notification;
built 2026-09-25 as `DesktopNotificationPermission`: droidtop's own dialog
gives the reason, Android's prompt follows only "Allow", the question is
asked once, and the session starts without waiting for the answer).
Under `DroidSpacesRuntime` the container outlives droidtop's process, so
a process that starts with a PRIMARY still running re-attaches to it
(`start` finds the compositor's socket accepting connections and
returns without provisioning) rather than reporting "not started" over a
live desktop; under `ProotRuntime` the session is the process's child
and dies with it, and the service says so and offers Start. A display
plugged in or removed during a session is a `DisplayManager` event the
service subscribes to: a new display becomes an output candidate at
once (§4 Displays), a removed one takes its output with it and the
compositor's remaining output keeps the windows. Low memory is not the
session's problem — the compositor and its clients are the container's —
so `onTrimMemory` drops droidtop's own caches and nothing else. Stopping
never blocks the main thread: `onDestroy` hands the container stop to a
process-lifetime worker and returns. Nothing starts at boot: the
desktop session, like the VPN it may serve (§4a), starts when a person
opens Desktop mode or turns on "start with droidtop" on the container's
page (§3d), never from a boot receiver.

**The session is Desktop mode's, and it ends with it (decided
2026-09-25).** It ends when the shell leaves Desktop for Gaming
(`MainActivity` switching mode; pressing Home into another launcher does
not end it), when Desktop mode is switched off (`ModePiece.DESKTOP_SESSION`,
through `ModeStartup`), when the PRIMARY is stopped in Containers, and
from the notification's Stop. Ending it stops the PRIMARY with everything
on the desktop, including a primary still booting (the service tracks
the container it is booting, not only a connected one). A stop that
lands while the session is still coming up is a stop, not a failed
start: every phase of the connecting coroutine treats its own
cancellation as the session ending — the image download and container
creation as much as the boot wait, and the post-boot identity probe —
so the person who pressed Stop sees the stopped screen, never "failed
to start" over a "Job was cancelled" that only meant the stop worked.
The boot wait alone has had this since e1de60f9 (rig dq-desk2-01 step 4
saw the message); the image download, the one phase long enough to
press Stop in, still reported it until now. The service is
not sticky: a process Android killed does not come back as a desktop
nobody opened. The PRIMARY's running state in the container manager IS
the session: Start opens Desktop (which starts the session with the
current plan, Printing included) and Stop ends the session; a raw
`ContainerRuntime.start` from the manager used to boot the recorded plan
with no host bridge attached, a second desktop nobody could see.

**Every external process droidtop runs is bounded.** crane, proot,
droidspaces and `su` run through one `ProcessRunner` that drains stdout
and stderr concurrently (a full stderr pipe must never deadlock a read of
stdout) and takes a timeout from its caller: a catalog listing or digest
resolution is refused after a minute, a root probe after ten seconds, a
pull or unpack is unbounded but reports progress and can be cancelled.
Whether root is available is probed once per process and remembered;
opening the container manager or the Desktop setup step never runs
`su id` again, so a prompting root manager asks once.

**Storage is checked before it is spent.** A pull refuses to start when
the volume holding the containers directory has less free space than
three times the image's compressed size (the layers, the unpacked tree
and headroom for the first `apt`/`apk` run), with a sentence naming the
numbers; the image cache has a cap, 2 GB by default, and evicts oldest
first; and the container manager shows each container's size so the
person can see what can be reclaimed. The cache is a settings group
of its own on the Desktop settings catalog — on/off, the cap, the space
in use, Clear — rather than a policy object nothing exposes.

**Onboarding's Desktop step** asks the selected backend to prove itself
(`ContainerRuntime.checkSystemRequirements`: droidspaces' own `check`, or a
trivial program run under proot against the device's root), instead of
treating an unrooted device as unable to run Desktop mode.

**The installed ABI must be the kernel's own.** An app's native libraries
are one ABI's, picked by the package manager at install time, and proot is
an executable. Android-x86 derivatives with ARM translation pick arm64-v8a
when an APK's arm64 set is the fuller one, and droidtop's is (gamenative's
prebuilt natives exist for arm64 only): on the BlueStacks rig
(`abilist x86_64,x86,arm64-v8a,...`) builds 571 and 572 installed as
arm64-v8a, and exec'ing the ARM proot on the x86_64 kernel fell through to
`/system/bin/sh` reading the ELF as a script ("libproot.so[1]: syntax
error"). Translation covers libraries loaded into the app, never programs
it runs. The picker's rule, read from Android-x86's
`core/jni/abipicker/ABIPicker.cpp` (BlueStacks logs its
"selected abi ... for ..." line): take the x86_64 set only if it holds
every arm64 library name, or the APK has no arm64 set at all; an x86_64
set holding an ELF of another machine type counts as invalid. Forcing the
ABI at install (`pm install --abi x86_64`) crashed the rig's package
installer instead (AIOOBE in `NativeLibraryHelper.copyNativeBinariesWithOverride`,
dq-desktop-03). So the fat APK's x86_64 set must hold every library the
arm64 set does (§10b, "Fat APKs only"). The proot check names this case
outright (installed ABI versus `Build.SUPPORTED_64_BIT_ABIS[0]`). A real arm64 device (the Retroid) is unaffected.

**Not every Android lets an app trace its children.** The BlueStacks rig
refuses `ptrace(PTRACE_TRACEME)` to app processes (dq-desktop-04: "proot
error: ptrace(TRACEME): Operation not permitted"), while the identical
x86_64 proot runs as the shell user there (dq-desktop-05: SELinux disabled,
no Yama, the app untraced and without seccomp). That is BlueStacks' kernel,
and proot has no way around it; the proot check reports it and Desktop mode
is unavailable there. Container work is verified on the stock-Android
emulator (API 34), which, like the user's Android 13 console, also applies
the exec restrictions on app-private files.

**What the hardware said (emulator, build 581, dq-desktop-08).** On the
stock Android 14 x86_64 emulator, unrooted, with the published universal
APK: onboarding's check said Desktop mode can run through proot; the
session pulled `alpine:latest` with crane, extracted it in-process,
provisioned sway, xwayland, a font and foot inside proot, and came up in
under a minute on a provisioned container; `exec` answered (`x86_64 |
Alpine Linux v3.24 | 0`); host-bridge connected to sway's `wayland-1` and
had the output resized to the view (1920x984, "output size applied"); the
viewport showed sway's desktop with its bar and clock; taps moved sway's
cursor to where they landed; the Start menu's "Linux apps" listed Foot,
Foot Client and Foot Server, and Foot opened a terminal window on the
desktop; text typed into it ran (`echo droidtop-typed` printed its output).
Getting there took three fixes found only on stock Android: fork/vfork
refused by the x86_64 app seccomp policy (proot patch), the compositor's
socket name, and the keymap memfd (§6b).

**dq-desktop-08's four milestones (for regression tracking).** The above
run is summarised as four concrete checks that any device must pass for
Desktop mode to be considered working:

1. **Proot system check passes** — `ContainerRuntime.checkSystemRequirements()`
   runs a trivial `proot --kill-on-exit --rootfs=/ /system/bin/sh -c 'echo $CHECK_TOKEN'`
   and it exits 0 with the token in stdout. This proves ptrace is allowed and
   the packaged proot/loaders can start a program.

2. **Primary container boots and `exec` answers** — the session pulls the
   chosen image (Alpine in the emulator run), unpacks it, provisions the
   compositor (sway) and base packages (xwayland, a font, foot), starts the
   primary container, and a probe `exec` (`uname -m; . /etc/os-release && echo "$PRETTY_NAME"; id -u`)
   returns 0 with the expected architecture, distro name, and uid 0.

3. **Host-bridge connects and input works** — `HostBridge` connects to the
   compositor's Wayland socket (found dynamically, not hardcoded to
   `wayland-0`), the output is resized to the Android view, the viewport
   renders sway's desktop with its bar and clock, and taps on the Android
   surface move the compositor's cursor to the correct coordinates.

4. **Start menu launches a Linux app with typed input** — the Start menu's
   "Linux apps" section lists the container's desktop entries (Foot, Foot
   Client, Foot Server on Alpine), tapping Foot opens a terminal window on
   the compositor's desktop, and text typed into it executes (verified by
   `echo droidtop-typed` printing its output).

**Retroid Pocket 5 (Android 13, arm64-v8a) — verification needed.** The
emulator run was x86_64 on API 34; the Retroid is the project's target
hardware and has never run Desktop mode. The following differences may
surface blockers:

- **ptrace permission** — BlueStacks (Android 9 x86_64) refuses
  `ptrace(PTRACE_TRACEME)` to app processes (dq-desktop-04). The Retroid's
  vendor kernel may or may not allow it. `ProotRuntime.checkSystemRequirements`
  will report the exact error if blocked (e.g. "proot error: ptrace(TRACEME):
  Operation not permitted").

- **seccomp policy on arm64** — the fork/vfork→clone patch (build-scripts/proot-patches/0001)
  addresses x86_64 only (Android's arm64 app seccomp policy does not trap
  fork/vfork syscalls). No arm64-specific seccomp issue is known, but the
  Retroid's vendor policy may differ from the stock emulator.

- **ABI match** — droidtop ships fat APKs with arm64-v8a proot binaries.
  `ProotRuntime.installedAbiMismatch()` checks that the installed ABI equals
  `Build.SUPPORTED_64_BIT_ABIS[0]`; a mismatch (e.g. if Android installs the
  x86_64 slice on an arm64 device) is reported explicitly.

- **memfd_create** — Android 13 (API 33) has memfd_create; proot's
  `--ashmem-memfd` option is a no-op where memfd works. The emulator run
  needed the keymap memfd fix (§6b); the same fix applies on arm64.

- **Wayland compositor on arm64** — sway and wlroots are architecture-
  agnostic; the Alpine arm64 packages exist. No code change is expected.

**Needs a rig check** (to be run by the coordinator on the Retroid Pocket 5):
1. Open droidtop, complete onboarding, enable Desktop mode in Global settings.
2. Open Desktop mode — the viewport should show "Start the desktop"; press it.
3. Watch logcat (`droidtop.proot` and `droidtop.DesktopSession`) for the four
   milestones above. Record which pass and which fail, with exact error text.
4. If milestone 1 fails, capture the full `checkSystemRequirements` stderr —
   that is the "exact cause" to document (as was done for BlueStacks' ptrace
   refusal at SPEC §3 line 1060-1064).
5. If milestones 1-2 pass but 3 fails, capture host-bridge connection logs
   (socket path, connection result, output resize).
6. If milestones 1-3 pass but 4 fails, capture Start menu UI and Foot launch
   logs (desktop entry parsing, exec result, input injection).

Not verified yet (tracked separately): the Debian plan, labwc, sibling
containers through this backend, clipboard, rotation, and the second screen.

**What the pipeline did off-device (2026-09-24).** Termux's proot built for
x86_64 Linux, a stock Alpine rootfs owned by an unprivileged user, the exact
proot options and boot script this backend uses (less `--ashmem-memfd`,
which exists only on Android): `apk add` provisioned sway, sway came up
headless, a second session's `exec` answered, the container's desktop
entries listed foot, and `grim` (a wlr-screencopy client) connecting from
outside proot through the bound socket directory captured sway's desktop
with swaybar and a foot window at a root prompt. It also found two defects
fixed in the same change: the hardcoded `wayland-0` (sway made `wayland-1`)
and no font on Alpine.

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
image works. Pulled images are kept on-device by digest in `OciImageStore`
(above), **meant as an explicit user-facing setting** (on/off, size cap,
clear-cache action) rather than an invisible always-on cache, since it
trades storage for avoiding re-downloads when containers are recreated.

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
  a spec violation.) Within a chosen repository droidtop takes the
  repository's CURRENT tag (`ImageTags.current`, decided 2026-09-25):
  the registry's own `latest` when it publishes one, otherwise the
  highest plain version number (`12`, `3.20`, compared part by part),
  otherwise none, and the person is told to enter a reference under
  Custom. The same rule serves the PRIMARY (Desktop setup) and a
  Recommended sibling (`ImageCatalogResolver.resolveCurrent`). Never the
  first tag listed: registries list tags in ascending order, so the
  container manager's first-listed pick created `alpine:2.6` (2014, a
  schema-1 manifest crane cannot read) and `debian:10` (rig
  dq-coordinator-23 F13). A tag picker in the creation flow remains open.

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
  step (it would ship in `nativeLibraryDir` like crane and proot, §3 —
  an extracted asset cannot be executed above targetSdk 28), no UI
  surface.

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
- **A graphical file manager.** PCManFM (`CompositorProvisioning.FILE_MANAGER_PACKAGE`), an
  ordinary distro package installed with the terminal on the primary container's first boot
  and re-provisioned on an existing one (the plan changed), never an image of ours. Its
  `.desktop` entry puts it in the Start menu; it browses the shared-storage folder (§4b), so
  Android files can be opened, copied and moved with a pointer rather than `cd` and `ls`.
  On the proot backend `/usr/local/bin/bwrap` is a droidtop-written stand-in (bound in per
  start beside resolv.conf, `ProotRuntime.BWRAP_SHIM`) that answers "no permissions to create a
  new namespace": GTK loads its icons and images through glycin, which runs its decoders under
  bubblewrap, and bubblewrap dies under proot (it cannot read `/proc/sys/kernel/overflowuid`),
  so PCManFM aborted on its first icon (Droidtop/tracker#96). glycin probes bwrap once and, on
  that answer, decodes unsandboxed. glycin 2.1, the Alpine 3.24 package, has no
  `GLYCIN_DISABLE_SANDBOX` (2.2 added it), so the stand-in is the one mechanism that works on
  both. proot is not a security boundary here; the droidspaces backend is unchanged. The plan
  also installs an icon theme (`CompositorProvisioning.ICON_THEME_PACKAGE`, Adwaita): a stock
  image ships none, and without one GTK draws only its built-in fallbacks, so every file,
  folder and Places entry in PCManFM was a blank page (Droidtop/tracker#146).
- **A real terminal into any container** — a computer the user can't
  open a shell on isn't a computer. **Decided and built 2026-09-02, the
  other way round from this section's original sketch**: droidtop does
  NOT host a Compose terminal view of its own, and neither Termux's
  terminal-view nor Jackpal's Android-Terminal-Emulator is forked in.
  The terminal is a real terminal application (`foot`) running *inside*
  the container, provisioned alongside the compositor and launched
  through the `exec` this section already named, appearing as an ordinary
  window on the shared desktop. See `runtime-common`'s
  `ContainerTerminal` for the argument in full; in short:

  - `ContainerRuntime.exec` is run-to-completion and returns captured
    output. An Android-side terminal view needs a pty and a live stream,
    so it would begin by widening `ContainerRuntime` with a streaming
    primitive every backend then owes — including the one that is still
    `TODO()`. The in-container terminal needs nothing new from that
    interface at all.
  - Above the pty it would still need a VT parser and renderer. A real
    terminal already exists in every distro's package repository.
  - §6a's whole justification for forking a keyboard in is that a
    terminal needs Ctrl/Alt/Esc/Tab/arrows/function keys. Those already
    reach the container through `:input-seat` → `:host-bridge`'s virtual
    keyboard; an Android-side view would use none of that path.
  - It is what distrobox — and BoxBuddy/DistroShelf over it, §7c — do.

  The cost, stated rather than hidden: a terminal that lives in the
  compositor is unreachable when the compositor is not running, which is
  when a shell would help most for debugging. No second non-interactive
  path is built to cover that, deliberately; a container that will not
  boot is this section's container-manager problem, not the terminal's.
  It opens in any container (built 2026-09-25). Siblings share the
  Wayland socket, so a terminal in one is a window on the same desktop;
  the container manager's Terminal installs `foot` and a font on first
  use through whichever package manager the container has (apk, apt-get,
  dnf, zypper, pacman, xbps-install; found in the container, so a Custom
  image gets one too), starts a sibling first where the backend needs
  that, runs the terminal in the desktop session and brings Desktop
  forward to show it. Without a running desktop the row offers "Start the
  desktop for a terminal" instead, since there is nowhere to show one.
- The desktop session's PRIMARY container is listed like everything else
  but guarded (can't be deleted while it's the active desktop).
- **What the screen is (built 2026-09-25).** The container manager is the
  catalog screen `containers` (`ContainersCatalog`, registered with the
  other settings screens): Desktop settings opens it in place, whichever
  surface draws them, and `ContainersActivity` hosts it for the Desktop
  taskbar with the shell's hint row (A Select, B Back, Y Info), in
  droidtop's dark look, pad and touch alike. It replaced a hand-built
  Material list with no focus, no hint row, an error line at the top of
  the screen and a rename field under the keyboard (dq-desk2-02). The root
  holds "Create a container" and one row per container: its name, "The
  desktop's own" for the primary, `image:tag` with the digest's first
  twelve characters, and its state (Starting and Running for the primary
  from the session, Running for a sibling with programs in it, Stopped
  for one that must be started, Ready for a proot sibling, which needs no
  start). A container's page: the primary action (Start the desktop, which
  opens Desktop; Stop the desktop, which ends the session; Start or Stop
  for a sibling), Terminal, Name (a refused rename says why on the Name
  row itself), Printing (primary), VPN (carry the device's traffic, the
  apps it carries, Android's always-on settings), USB devices, Delete.
  "Create a container" lists the Recommended repositories and "Any OCI
  image"; a repository's page has **Version, a tag picker** over the live
  tag list (`ImageTags.ordered`: the current tag first, marked
  "(current)", then version numbers newest first, then the other tags, at
  most 200, the rest reachable as a Custom reference), Name (defaulting as
  below) and Create, which reports its progress on its own row. Text is
  edited in the navigator's dialog, where the keyboard's Done key saves.
  **Restart, Recreate from the image, storage used, Sockets and Mounts
  (built 2026-09-27).** `ContainerRuntime` gained `restart` (stop then
  start; a default every backend shares), `recreateFromImage` (destroy
  and re-create from the SAME recorded image reference, name and role,
  the PRIMARY's provisioning plan carried over -- each backend implements
  it itself, since where the plan lives differs), `diskUsageBytes` (a
  real recursive walk of the rootfs tree, `ContainerDiskUsage`, shared by
  both backends as a default method), `sockets`/`setSockets`
  (`ContainerSockets(waylandShared, audioShared)`) and
  `extraMounts`/`setExtraMounts` (`ExtraMount`, a host folder bound at its
  own path under `ContainerLayout.EXTRA_MOUNTS_DIR`, picked with the
  system folder picker like §4b's shared-storage flow). Sockets and
  Mounts follow the same "one row unlocks a real backend feature" shape
  as Devices, except both backends can now bridge host audio in (fixed
  2026-09-28, Droidtop/tracker#95; `audioSharingUnavailableReason` is
  null on both -- neither has a message-only Audio row any more).
  droidspaces wires the toggle straight to droidspaces' own
  `enable_pulseaudio` config field, already used as-is
  (`DroidSpacesContainerConfig`): its own host-side PulseAudio daemon,
  bridged to Android's audio HAL, bind-mounted into any container that
  asks for it. proot has no kernel namespace and no HAL access at all --
  the same wall a rootless Wine/box64 guest hits -- so rather than a
  second audio server it reuses the ONE PulseAudio build already in the
  tree for that other rootless case: gamenative's Windows runtime
  (`runtime-windows/WineXSession`, docs/SPEC.md 10b) ships a PulseAudio
  server built against bionic with an AAudio sink instead of a real
  ALSA/HAL backend (`libpulseaudio.so` per ABI, `build-scripts/
  build-vendor-deps.sh`'s "PulseAudio 13.0" section, its x86_64 half).
  `runtime-linux-noroot`'s `HostAudioServer` runs one instance of that
  same binary for the whole desktop session -- one long-lived thing the
  PRIMARY owns, like the compositor and cupsd, not one per launch the way
  gamenative's own `PulseAudioComponent` runs it for Wine -- listening on
  `ContainerLayout.AUDIO_SOCKET` under the shared socket directory every
  container already binds, so a program in any container reaches it
  through `PULSE_SERVER` (`ContainerLayout.clientEnvironment`) exactly as
  it would reach a real Linux desktop's PulseAudio; `sockets`/
  `setSockets` persist a real per-container `audioShared` toggle for it,
  same as Wayland's. droidspaces' containers keep setting `PULSE_SERVER`
  through their own bridge instead (`clientEnvironment`'s callers there
  pass `audioShared = false`), since that variable is already droidspaces'
  own to set. Wayland is real on both backends: off withholds
  `WAYLAND_DISPLAY` from that container's processes (the shared socket
  directory itself stays bound, since CUPS and the VPN socket also live
  there and are a different question). Extra Mounts bind through each
  backend's own existing primitive (proot's `--bind`, droidspaces'
  `bind_mounts`) exactly like the shared-storage/USB-device binds already
  there. The droidspaces backend previously recorded no image reference
  at all (`ContainerInfo.image`/`digest` were always null for it) --
  fixed alongside this work, since `recreateFromImage` needed one and the
  container page's own image line was silently blank for every
  droidspaces container. Rig-checked on emulator-5560 (proot backend);
  the droidspaces backend is unverified against a live container, same
  standing caveat as the rest of that class -- no rooted device available
  here. The proot audio bridge itself is unverified on a live device too
  (needs a rig check, see the commit that landed it): the AAudio sink and
  the extracted-modules path are new here, not proven on-device the way
  the rest of gamenative's own PulseAudio use is.
  **Microphone (Droidtop/tracker#80, 2026-09-28).** The device microphone
  is a source on that same server, not a second mechanism: with the
  Sockets group's "Microphone" switch on (off by default; one switch for
  the whole desktop, since every container that shares audio reaches the
  same server; proot backend only, droidspaces' own bridge is its to
  extend) and RECORD_AUDIO granted, `HostAudioServer` also loads
  PulseAudio's stock `module-pipe-source` on a FIFO and makes
  `droidtop_mic` the default source, and `MicrophonePump` fills the FIFO
  from `AudioRecord` (48 kHz mono s16le). A write that would block is
  dropped, because PulseAudio stops reading a pipe source while nothing
  records and a queued backlog would play into the next recording. The
  permission is asked once, when the switch is turned on, by
  `MicrophonePermissionActivity`, after the row's own text gave the reason
  (7b's rule); a refusal leaves the switch off. It applies from the
  desktop's next start. Android only lets an app record while it is
  visible (or runs a microphone foreground service, which the desktop
  service is not), so it works while droidtop is on screen, which is where
  Desktop mode is; a recording started with droidtop in the background
  gets silence. The x86_64 modules asset now includes `module-pipe-source`
  (the CI dependency cache key had to move for a release to carry it: a
  cache hit skips the script, so a module added to the script alone ships
  the old asset); the arm64 asset is upstream gamenative's prebuilt set, and where it lacks
  that module the desktop log says "microphone not bridged" and audio out
  is unaffected. plugin-api D9's refusal of `audio.record` to plugins is a
  different question and stands.
  Still not built: Start with droidtop (autostart with the session).
  **Backup (Droidtop/tracker#81, 2026-09-28).** A container's own data
  (what was installed and made inside it: programs, config, a Wine prefix,
  documents) had no way out. The container page has a Backup group with two
  by-hand actions, "Back up its data" and "Restore its data from a backup",
  through the system file picker like the settings backup (7f), not an
  automatic sync: a container can be gigabytes. `ContainerRuntime.exportData`
  / `importData` stream the rootfs as a tar archive (`ContainerArchive`, via
  Android's own toybox `tar`, so symlinks and modes survive and there is no
  tar writer in the app; ownership is not carried, every file is the app's).
  proot leaves a permissionless placeholder at every bind target the image lacks
  (`/etc/resolv.conf`, `/run/droidtop-sockets`, `/run/droidtop-app-storage`, the shared-storage
  and extra-mount paths) and toybox tar stops on each unreadable entry, so the export first
  gives the owner read (and search) back where it is missing, walking the rootfs without
  following symlinks (`ContainerArchive.prepareForTar`); the placeholders are archived as the
  empty entries they are. A socket a program left in the rootfs is dead state in a stopped
  container and tar cannot store it ("unknown file type '140000'"), so it is left out. A
  failed export deletes the file the picker made, so no half archive looks like a backup.
  The container must be stopped (the rows say so while it runs). A restore
  unpacks beside the container, refuses an archive with no `/etc` and `/usr`,
  and only then swaps it in, so a bad or truncated archive leaves the
  container as it was. It replaces the container's files whole; the
  container's own settings (name, sockets, mounts, provisioning plan) live
  outside the rootfs and stay. Proot backend only: the droidspaces rootfs is
  an image mounted as root, which shows "not available here". Scheduled or
  incremental backups are later, once the manual path is proven.
  **Names (decided 2026-09-25).** A container is called by a name the
  person chooses, never by its id (`droidtop-sibling-8993dfbd` told two
  terminals nothing, dq-desk2-01): `ContainerNames`, one file per backend
  beside its containers, read by both, `ContainerInfo.displayName`
  everywhere a container is named (cards, busy lines, "Open with", a
  terminal window's title, `foot --title=<name>`). A new sibling defaults
  to its image's repository name, capitalised and numbered when taken
  ("Debian", "Debian 2"); the primary is "Desktop"; a rename is refused
  when empty, over 40 characters or another container's name.
  **One delete rule.** Any running container is stopped before it can be
  deleted ("Running: stop it to delete"); the primary used to be guarded
  and a running sibling not.
- **The surface, precisely (decided 2026-09-24).** The container manager
  is one catalog screen (`containers`, registered by `:app`) rendered by
  the same navigator as every other settings screen, in every mode that
  can reach Desktop settings. Its list has one row per container: name,
  role, `image:tag` with the digest's first twelve characters, state
  (running / stopped / needs root / unavailable on this device) and
  size on disk. Above the list, "Create a container" opens the one
  choice component over the live catalog (§3a: Recommended, sortable by
  desktop environment and arm64 availability) with a Custom row for a
  raw reference. A container's own page holds, as rows of the one row
  anatomy: Rename; Image (the reference and digest, and "Recreate from
  the image" as a two-step action); Storage used; Start, Stop and
  Restart as the primary action, whichever applies; Start with droidtop
  (autostart with the session); Sockets (Wayland and audio switches,
  on by default); Mounts (shared storage on or off, and a list of extra
  host folder to container path binds, each added with the system
  picker); Devices (§4b); VPN (§4a); Printing (§4b, primary only);
  Terminal; and Delete, two-step, with the PRIMARY's row disabled while
  it is the live desktop and saying so. Everything a row writes lives in
  one `ContainerConfig` JSON file beside the container's rootfs in the
  backend's containers directory, read by `ContainerRuntime.start`, so
  the two backends share the model; the interface gains `rename` and
  `inspect` (digest, bytes on disk) and nothing else. A sibling's
  Terminal provisions `foot` into that sibling on first use (the
  provisioning plan without the compositor), which is what makes "a
  terminal into any container" literally true.
- **The stop is off the main thread, and where `runBlocking` remains
  (audited 2026-09-29).** Leaving Desktop must never park the UI thread
  on the container stop (audit 2026-09-24, C4):
  `DesktopSessionService.onDestroy` hands the stop to a process-lifetime
  worker and returns, and the next session's `connect()` joins that
  worker before it touches the container (`DesktopSessionService.kt:117`,
  `:136`); the worker runs §3's "Stopping is a stop" mechanism, which
  both backends implement as suspend work (`ProotRuntime.stop`,
  `DroidSpacesRuntime.stop`), and neither backend module contains a
  `runBlocking` at all. The `runBlocking`s that do remain in the app:
  - **On the main thread, in this section's own surface (the known
    gap).** The container page's Sockets switches (Wayland, Audio), the
    Devices toggles and the Mounts "Add a folder" pick wrap their
    `ContainerRuntime` config write in `runBlocking(Dispatchers.IO)`
    (`ContainersCatalog.kt:394`, `:411`, `:465`, `:736`) — but both
    renderers call a `ToggleItem.onToggle` and a `FolderPickItem.onPicked`
    synchronously on the main thread (`CatalogPreferenceBuilder.kt:111`,
    `:387`; `SettingsCatalogView.kt:238`, `:593`), and `runBlocking` parks
    its caller whatever dispatcher the body runs on, so each of those
    rows holds the UI thread for a container-config write. That is the
    remaining violation of the settings catalog's own C4 rule ("an
    action that touches a database or disk is an `AsyncActionItem` ...
    Never `runBlocking` in a catalog callback", §7c); the fix is to make
    those rows async like the rule says, and it is not built yet.
  - **Off the main thread, by structure.** The Backup rows' tar
    export/restore (`ContainersCatalog.kt:302`, `:317`) run inside a
    `DocumentPickItem.onPicked`, which both renderers already run under
    `withContext(Dispatchers.IO)` (`CatalogPreferenceBuilder.kt:128`;
    `SettingsCatalogView.kt:228`), so the `runBlocking` parks an IO
    thread for the archive, not the UI. The plugin broker's surface is
    synchronous by contract, so its `runBlocking`s park the calling
    binder thread while a grant sheet is answered or a provider call runs
    (`PluginBrokers.kt:61`, `:158`). The Wine prefix preparation blocks
    the dedicated `droidtop-wine-start` thread (`WineXSession.kt:106`,
    started from `WineGameActivity.kt`'s single-thread executor).
    launcher3's own `PreferenceSearchIndexablesProvider` keeps its
    upstream `runBlocking` because a ContentProvider query must answer
    synchronously (`shell-default`, upstream code, not ours to rewrite).

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
  just auto-detected enumeration order, plus a persisted choice — not
  trusting auto-detection alone. [Mjolnir](
  https://github.com/blacksheepmvp/mjolnir) (a companion dual-screen
  home-launcher-routing tool) is a concrete reference for the same
  problem.
  **One persisted answer, relative (decided 2026-09-24):** which panel is
  the main output is a single choice, `MainScreen` in `:runtime-common`
  — "the second screen when one is connected" or "the built-in screen" —
  written by the Main screen settings row and by Swap screens, and read
  by Gaming and Desktop alike. It is never keyed by display id: Android
  hands the add-on a new id when it re-enumerates, so an id-keyed store
  (the earlier `DualScreenCoordinator`, which ran alongside a separate
  shell-target preference with the opposite default) forgot the user's
  swap after a replug and let the first Swap press do nothing. Changing
  it, or the game launch target, re-runs orchestration immediately.
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
  just listing them by number. This configuration lives in the settings
  catalog (§7), rendered by every mode's settings surface; droidtop does
  not have or want a separate standalone settings app.
  **How it fits the one main-screen answer (decided 2026-09-24):**
  `MainScreen` says which panel is the main output and is the only
  relative choice; the KScreen-shaped part is the **Displays** screen of
  the catalog (`displays`, under Screens), one row per display Android
  currently has, named by the platform's own name for it ("DP Screen",
  a lapdock's EDID name) with its size beside it, and identified for
  persistence by that name and size, never by display id (§4c). The main
  display's row says Main and links to the Main screen row. Every other
  display's row is a choice of role: **Input surface** (§6c), **Companion**
  (§4d), **Virtual controller** (§4), **Extended desktop output** (Desktop
  only: a second headless output in the compositor, sized to the display;
  real multi-output support in `:host-bridge` — today single-output-only,
  see §2a's taskbar paragraph — is needed before this role or a taskbar
  "move to" action can exist), or **Mirror** (Android's
  own mirroring, chosen rather than fallen into). The per-mode
  second-screen role rows are these same rows filtered to the mode. A
  display in a fallback mode the add-on is known to come up in (480x640
  until power-cycled) shows that state on its row with "replug to fix"
  rather than being used at that size silently.
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
  - **Concrete for Desktop mode specifically** (BUILT — see §6c): the
    lower screen's default input role is a *persistent* on-screen keyboard
    (the forked Hacker's Keyboard's own `LatinKeyboardView` — see §6) plus
    a trackpad region beneath it, always available rather than popping up
    only when a text field is focused. That last part is not achievable as
    an IME window — Android places those itself, §6c has the detail — so
    it is an ordinary droidtop window on that display, and droidtop's IME
    is told to stop drawing over the primary one while it is up.
    **Toggleable**: "Second screen in Desktop mode" and "Second screen in
    Gaming mode" in settings choose between this input surface and the
    companion/widgets surface, per mode, per the per-output role model
    above. Gaming defaults to the companion, Desktop to input.
- **Gaming dual-screen roles (directed 2026-08-30, first live addon
  session)**: when the Dual-Screen Add-On (or any second display) is
  present, the GAMING SHELL ITSELF moves to it — the addon is the
  upper/main screen — and the built-in screen becomes the
  widgets/ambient-info surface (FocusCompanion/PresencePanel tenants,
  §7e), the inverse of a phone-style "companion on the accessory"
  model. Desktop mode relocates the same way as of 2026-09-02 (its
  output renders on the addon/external panel, the built-in panel keeps
  the input surface role — §4c, external screen priority); it stays
  exempt only from per-launch GAME display targeting, since its windows
  are the compositor's job. Additionally,
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
  silently running at fallback resolution. Detection compares the
  current size with the panel's largest `Display.getSupportedModes()`
  entry and flags only a VGA-class mode (short side under 720 px) below
  it (`DisplayModes.isFallback`), so a 1080p lapdock that also lists
  4K is not called broken. It is surfaced on the companion's status bar
  and on the Reinitialize displays row, each saying to power-cycle the
  panel; droidtop does not try to force a mode change itself.
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
  no controller is present. Precisely: the virtual controller is a
  third **role of the second screen** beside the companion and the
  input surface (`SecondScreenInput`'s role set gains `VIRTUAL_PAD`),
  drawn with gamenative's `inputcontrols` profile renderer on the panel
  the shell is not on, and its presses are dispatched as real gamepad
  key events into droidtop's own window (the same `dispatchKeyEvent`
  route the trackpad's focus steps take, §6c) so the shell, the Quick
  Menu and every hint row see a pad and nothing knows the difference.
  It is offered — a one-time notice on the companion with "Use the
  screen as a controller" — when `ControllerPrefs.attachedControllers`
  is empty and a second display is present, and chosen from the same
  per-mode second-screen setting as the other two roles. On a
  single-screen device there is no virtual pad over droidtop's own
  chrome, by §7j: the chrome is touch-first, so the pad would only cover
  the affordances that already answer; in a Wine game the overlay is
  gamenative's own (§5b), and in an emulator it is the emulator's.
- **Companion surface is user-populatable (directed 2026-08-30, second
  live addon session)**: the widgets/info screen (CompanionActivity on
  whichever display the shell is not on) is not just droidtop's ambient
  readout — the user populates it: real Android app WIDGETS (an
  `AppWidgetHost`, the same mechanism every launcher uses — music
  controls, calendars, whatever's installed) laid over droidtop's own
  focused-game/info backdrop, plus resizable/floating apps (launched to
  that display via the same launch-display targeting; freeform
  windowing per §2a's native-apps plan). The widget set persists once
  and every companion host shows the same set (`CompanionWidgets`, one
  `AppWidgetHost`): the companion is one surface wherever it lands, so
  there is one layout, not one per display role. droidtop's info stays
  the BACKGROUND layer; user content composites above it.
- **Gaming Quick Menu (directed 2026-08-31, iiSU-inspired)**: a
  trigger-opened overlay with a Notifications tab and a System tab.
  Paradigm survey behind the design (knowledge-based; no iiSU decompile
  artifacts exist in the container): the Steam Deck QAM (dedicated
  button → right-edge sheet, vertical tabs: notifications / quick
  settings / performance) is the strongest prior art for
  glanceable-while-playing; iiSU's trigger menu is the same family on
  Android handhelds; PS5's control center (bottom pill bar) and the
  Switch HOME-hold sheet are the alternatives considered and passed
  over (bottom bars fight the theme's own helpsystem row; the Switch
  sheet is single-purpose). Chosen: right-edge sheet IN LANDSCAPE and
  a bottom sheet in portrait (2026-09-11, §7j: the premise is that the
  shell stays visible behind it, and a full-height right-edge sheet on
  a tall screen is the whole screen; the bottom sheet also puts the
  tabs in thumb reach. The tabs are tappable and the sheet has a
  visible Close, neither of which it had). **R2 opens it** (directed:
  the dedicated quick-device-management button, named on screen by the
  R2 pill beside the tab bar; a fresh R2 press inside the menu closes
  it), and HOLD SELECT is the fallback for pads whose triggers are
  analog-only and never emit an R2 key event (the system's own
  key-repeat, a second KeyDown at ~500 ms, no timer of droidtop's;
  short-press Select keeps its meaning; chords rejected as
  undiscoverable). The menu's hint row is docked at the bottom of the
  sheet on every tab, carries `L1/R1 Tab` beside the tab's own actions,
  and its Close is the row's `B Close` pill rather than a second text
  button; on the Notifications tab `A Open`, `X Dismiss` and `Y Clear all`
  are drawn only while there is a notification to act on (§7j: a hint
  row promises only what dispatches). **Fully controller-driven, per direction**: L1/R1 tabs,
  D-pad focus, A act, X dismiss, Y clear-all, B close, with the hint
  row stating exactly that --- and, since 2026-09-11, fully reachable
  by touch as well (§7j): the hint row IS the touch control surface,
  every hint dispatching the real button press it names. The System tab is Android's QUICK-SETTINGS
  shape, not a settings list (directed 2026-09-10, against droidtop's
  older habit of a single centred narrow column): a status header
  (clock, battery and level, network state, the connected controller's
  name), brightness and media volume as sliders, then a grid of large
  tiles — two columns, three when the sheet is wide enough. A toggle is
  a lit or dim tile, a choice shows its current value and cycles on A
  (a long option list opens the catalog's own picker screen), an action
  is a tile, a nested screen opens in the sheet, and the display-role
  rows (shell display, game launch display, swap screens) are tiles
  too. The sheet stays a right-edge, full-height sheet, widened to what
  the grid needs instead of a fixed 420 dp column. What it renders is
  unchanged: the settings catalog's own System group plus those display
  rows, through the same catalog items and the same write paths the
  Settings section uses — a view, never a copy, so a System setting
  added to the catalog appears here as a tile with no edit to the menu,
  and an item the tile view has no glyph for still gets a real tile.
  Tile glyphs are drawn in the shell (droidtop ships no icon
  dependency). Notifications need the
  notification-access grant (NotificationListenerService in `:app`
  feeding `runtime-common`'s NotificationsStore); until granted the tab
  offers the grant, never a silently empty list. Honest limitation:
  the menu overlays the SHELL only — games are separate activities, and
  a Deck-style in-game overlay is future work tied to this section's
  overlay plans, not claimed here.
- **Game tab (decided 2026-09-28, Droidtop/tracker#82)**: before this,
  R2 drew the exact same Notifications/System pair whether or not a
  game was running — no "you are in a game" surface at all, against
  every console this mode is modeled on (this section's own Steam Deck
  QAM survey concluded exactly that and was never built into a third
  tab). A **Game** tab now exists whenever the shell is showing but the
  most recent launch is still parked rather than explicitly reclaimed
  (`LaunchDisplay.parkedDisplayId`/`runningGame`: non-null exactly when
  a Home press brought the shell back over a game still running in the
  background, per the previous bullet's own field — an explicit shell
  entry clears both together, `LaunchDisplay.clearRunning`). When
  present it is the tab that opens first, not something shoulder-cycled
  to: the point of a distinct in-game menu is that it greets you. Two
  rows today, in the same `MenuRow` tile shape Settings and every other
  menu in this shell already uses: **Resume** relaunches the entry
  through the shell's one real launch path (`GamepadShell.onLaunch` —
  console ROM, PC and engine games alike, the same mechanism already
  decided for the planned Recents tab's own "launch it again" row, not
  a second resume mechanism), and **Quit to Library** calls
  `Library.quit`, which dispatches to `LibraryProvider.quit` for the
  entry's kind. `quit` returns a `QuitResult`, not a boolean: the row's
  subtitle is `quitOutcome.message`, which starts as the pre-quit
  promise "Ends <game>" and is replaced by the outcome when a quit runs
  (see the next bullet). Row list, not a bespoke layout: the same shape
  a plugin's `ui.quick_tile@1` (docs/plugin-api.md C2, tracker#73) will
  append to once that extension point's host exists, so it extends this
  tab instead of needing a second in-game menu built to compete with
  it.
- **Quit to Library says what it did (decided 2026-09-29, Droidtop/tracker#82)**: on the owner's
  console (Android 13) the first version of this tab did not end the game — after Quit the
  emulator's process and its Recents task both stayed alive, yet droidtop had already dropped its
  running-game state, so the menu read "the game ended" when it hadn't. The rule now:
  `LibraryProvider.quit` returns `QuitResult` (`Ended`, `NotEnded`, `Unresolvable`) instead of
  `Boolean`; the shell clears `LaunchDisplay`'s running-game state **only** on `Ended`, keeps the
  sheet open otherwise, and shows the outcome in the row's subtitle (the pre-quit promise "Ends
  <game>" until a quit runs). What a non-privileged app can actually do to another app's game:
  `killBackgroundProcesses` (only while its processes are cached; not on Android 14+ for other
  apps, 7i) and `ActivityManager.getAppTasks`, which lists only tasks whose root activity is
  droidtop's own, so it never finds a third-party emulator's task. `ConsoleRomProvider.quit` tries
  both and reports `Ended` only when it removed a task; for an emulator it can neither end nor
  confirm it says so and names Recents as the way out. Nothing here claims an end it did not see.
  PC/engine games and native apps return `NotEnded` by default.
- **Playtime label**: droidtop records launches (last played, count) but not session length, so a
  launched game's `playtime` theme binding reads "Played", never "Never played" (console pass,
  2026-09-28). The launch itself republishes the lists the shell is already showing, like an F95
  link or a folded replacement does: the 2026-09-29 rig pass caught the detail still reading
  "Never played" over the running game because the recorded play waited for the next rescan to
  reach a published list.
- **First tap on a pad button**: `padSelectable` reads its current `onPress` instead of keying
  the tap detector on it, because a recomposition that swapped the lambda cancelled a tap whose
  DOWN had landed (Droidtop/tracker#42, the secondary buttons of onboarding and the tutorial).
- **Display reinit + parked displays (directed 2026-08-30)**: Android
  silently MIRRORS a second display nothing presents on (confirmed live
  on the addon) — droidtop's answer is that some droidtop surface owns
  every display whenever Gaming runs, and a HOME press is the user's
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
  recents; system quickstep recents is out.** Its shape, so it can be
  built: a **Recents** tab of the Quick Menu beside Notifications and
  System (the menu is the one overlay that opens over every shell
  screen, and "what was I playing" is a glance, not a section), listing
  droidtop's own launches newest first from play history, each row
  carrying the entry's artwork, name, when it was played and, on a
  dual-screen device, WHICH panel it was launched onto from
  `LaunchDisplay`'s per-launch record; `A` launches it again through
  `Library.launch` (launch-screen memory included), `X` offers "on the
  other screen" (the relative vocabulary of §4c, writing the per-game
  launch screen), and `Y` opens its detail. Below droidtop's own rows,
  once usage access is granted (§7b permissions), the same list continues
  with every other app used today from `UsageStatsManager`, launched as
  an app; until granted, one row offers the grant and the list is
  droidtop's launches only, never empty when there are any. Desktop
  windows (the compositor's toplevels through
  `wlr-foreign-toplevel-management`) join the same list as rows that
  activate or fullscreen the window when Desktop mode is on; that is the
  only Desktop-specific half and it waits on nothing else. Holding the system
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
  tasks. Quickstep is not carried in the fork; a hypothetical future
  ROM/system build would take it from upstream Launcher3.
- **General framing**: droidtop's display/shell/settings model takes KDE
  Plasma as its broader reference point, not just for KScreen specifically
  — the goal (§1) is a real general-purpose compute device, and KDE is the
  most complete existing example of "one coherent desktop shell with
  modular, discoverable settings" to learn conventions from as more of
  this gets built out (workspace switching, per-app window rules, etc.),
  not a component to fork code from.

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
- **The shape, decided (2026-09-24), and as built.** `DroidtopVpnService`
  (`:app`, `vpn/`) is a Desktop-mode piece (`ModePiece.DESKTOP_VPN`: the
  component is offered to the system only with Desktop on, and stopped
  when Desktop goes off), because a VPN a container serves needs the
  container. It is a plain `VpnService` bound by the system, so the
  system's own VPN indicator and notification are what the person sees;
  it is not a foreground service of droidtop's. Its tun fd is fed to
  vendor/hev-socks5-tunnel, a userspace IP stack built by
  `build-vendor-deps.sh` into `libhev-socks5-tunnel.so` in the APK (a JNI
  library, `vpn/TunnelNative`, rather than an executable: it takes the fd
  directly, and Android does not pass fds across an exec). Every device
  connection becomes a SOCKS5 connection. **The container's VPN is
  whatever the person installed in it**; what droidtop asks of it is one
  thing every VPN client can provide, a SOCKS5 proxy bound on a Unix
  socket in the shared socket directory
  (`/run/droidtop-sockets/vpn.sock`, `ContainerLayout.VPN_SOCKET`; the
  host side is `ContainerRuntime.hostSocketDir()`): WireGuard through
  `wireproxy`, OpenVPN through its own client plus a local `microsocks`,
  or any commercial client's proxy mode.
- **The relay.** hev speaks SOCKS5 over TCP only, so `vpn/SocksUnixRelay`
  listens on a loopback port and copies each connection to `vpn.sock`,
  byte for byte. A loopback port is open to every app; from API 29 the
  relay asks Android who owns each connection
  (`ConnectivityManager.getConnectionOwnerUid`, allowed to the VPN app)
  and refuses anything but droidtop's own stack. Below 29 there is no way
  to tell, and the port is open while the VPN is.
- **DNS and UDP.** DNS is answered by hev's mapped DNS on the device
  (`vpn/TunnelConfig`): a query gets an address from a private range, and
  a connection to it reaches the proxy by name, so resolution needs only
  TCP CONNECT, which every SOCKS5 server has. Other UDP needs the proxy's
  UDP ASSOCIATE and works where the proxy offers it (wireproxy and
  microsocks are TCP-only).
- **Root.** Nothing on the device side changes with root. With real
  namespaces (`DroidSpacesRuntime`) the container may additionally own a
  real tun and route its own traffic, a per-container setting and never
  required. Unverified on a rooted device: a droidspaces container's
  processes run as root rather than as droidtop's uid, so its VPN
  client's own traffic is not covered by droidtop's exclusion below, and
  root-created sockets in the shared directory may not be connectable by
  the app.
- **Configuration is per container, in the container manager (§3d)**: a
  "VPN" row on each container's entry names the socket the container is
  expected to serve, a switch makes that container the device's VPN
  (another container's VPN is replaced, not stacked; Android's own
  consent is asked the first time), and the row states the live state:
  connected, or nothing serving the socket (the container or its VPN
  client is not running), or why it could not start. droidtop does not
  import `.conf` or `.ovpn` files: the VPN client is configured inside the
  container with that client's own tools, in the terminal (§3d), because a
  config format is the client's and a second importer per client would be
  exactly the duplication the socket contract avoids.
- **Kill switch and per-app routing are the platform's.** While the VPN is
  on, every route points into the tunnel, so when the endpoint disappears
  traffic is held back rather than leaving on the bare network; the
  service stays up until the person turns it off (a silent fall-through
  is the failure mode). This is the routes, not
  `VpnService.Builder.setBlocking`, which only sets the fd's blocking mode.
  "Route only these apps" is the builder's own allowed-apps list, edited
  on the same row, with droidtop itself never among them (and, with no
  list, the one disallowed app), so a proot container's VPN client, which
  is a droidtop process, never routes its own traffic into the tunnel it
  serves. Android's own "always-on VPN" and "block connections without
  VPN" settings are linked from the row, not reimplemented; an always-on
  start uses the container recorded in `VpnPrefs`.

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
- **Audio output from containers** (Droidtop/tracker#95). As basic an
  expectation as printing, and unlike USB it affects every container app
  with sound on the default noroot install, not a niche device case: a
  Linux GUI app, terminal bell, or Wine game in a container had no path
  to the device's speakers at all under `ProotRuntime`. Root path:
  droidspaces already had a real one, its own host-side PulseAudio
  daemon bridged to Android's audio HAL. Noroot path: reused, not
  reinvented — the same AAudio-backed PulseAudio build gamenative's
  Windows runtime already carries for the identical rootless problem
  (§3d, `HostAudioServer`), one instance for the whole desktop session.
  See §3d's Sockets paragraph for the real mechanism.
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

**Decided (2026-09-24), so each has one shape:**

- **Printing.** CUPS is an option in the primary container's provisioning
  plan (`CompositorProvisioning.plan(..., printing)`, the "Printing" switch
  on the primary's entry in the container manager, §3d, kept in
  `DesktopSetupPrefs`). Switching it changes the plan, so the next desktop
  start installs and configures CUPS, and the boot script starts `cupsd`
  (the plan's `daemons`) before the compositor. **No daemon ever holds up
  the desktop (decided 2026-09-25, rig dq-desk2-01):** a daemon is given
  as a foreground command (`cupsd -f`), and the script starts it as a
  background job with its output in `/var/log/droidtop/<name>.log`, never
  waiting for it; a watcher reports after 15 s whether it is still running
  ("cupsd is running", or "cupsd stopped: <its last lines>") in the
  desktop log. The script used to run `cupsd` in line and trust it to
  detach, and under proot cupsd's parent never returned: sway never
  started and the desktop hung on "provisioning finished". While the
  desktop runs, the Printing row says whether CUPS is up (its socket
  exists) and offers "Add a printer" only then. cupsd also listens on
  `cups.sock` in the shared socket directory (`ContainerLayout.CUPS_SOCKET`)
  and every container's processes get `CUPS_SERVER` pointing at it
  (`ContainerLayout.clientEnvironment`), so a program in any container
  prints through the primary's CUPS like a program on any Linux desktop,
  through the same shared directory as the compositor rather than a bind
  of its own. Printers are added in CUPS's own web interface, which moves
  to `127.0.0.1:6310` (Android refuses an app a port below 1024) and is
  opened in Android's browser from the same row: a proot container shares
  the device's network, and no container is provisioned with a browser.
  Where CUPS asks for a login the container cannot give, `lpadmin` in the
  terminal adds the printer. Container printers are **not** exposed back
  to Android as a `PrintService`: Android apps already print through the
  platform's framework and IPP Everywhere, and a second print path with
  its own driver model is duplication for no case the standing test names.
- **USB peripherals.** A "Devices" row on each container's entry in the
  container manager (§3d, the USB devices group of a container's page) lists the USB devices
  Android enumerates now (`UsbManager.deviceList`, which needs no
  permission; each device's name is its node path) and lets each be bound
  into that container at the same path, from its next start. Under
  `DroidSpacesRuntime` that is a real bind of the node through the
  container's config (`<name>.devices` beside it; droidspaces skips a node
  that is gone rather than failing the start). Under `ProotRuntime` the row
  states, with the reason (`ContainerRuntime.deviceSharingUnavailableReason`),
  that a device cannot be shared without root and offers nothing to tick.
  Storage is the one exception with a no-root story: a USB drive mounted
  by Android is a storage volume, and volumes are reachable through the
  shared-storage bind below.
- **Shared storage is bound into every container.** `ContainerLayout`
  binds the device's shared storage (every mounted volume droidtop can
  read, at `/run/droidtop-shared-storage/<volume>`) into the primary and
  every sibling, read-write, the way distrobox shares the home directory.
  Downloads, documents and game folders are then the same files inside and
  outside the desktop, which is what "PC in a box" means for a file, and
  it is what the file associations below hand a path to.
- **"Open with droidtop"** is one Activity in `:app`, `OpenWithActivity`,
  declared for `VIEW` and `SEND` on the MIME types and path patterns of
  `.exe`, `.msi`, `.AppImage`, `.deb` and `.rpm`, and enabled as a component
  only while Desktop mode is on (`ModePiece.DESKTOP_OPEN_WITH`, §2c). It
  resolves the content URI to a real path on a volume droidtop can read (a
  `file://` path, the system picker's external-storage documents, the
  Downloads provider's `raw:` ids, or a provider's `_data` column; a file
  only a provider serves is not one, and the Activity says the file has to
  be saved to the Download folder first). It then shows the chooser, which
  is the one choice component (§7b, `ui/SelectableRow`, shared with
  onboarding): for `.exe`/`.msi`, droidtop's provisioned Wine environment
  plus every per-game prefix (§7i, `WinePrefixes`), launched through the
  same `WineEngine` path a library game uses (an `.msi` as Wine's own
  `start /unix <path>`, which opens it with the prefix's registered
  installer); for `.deb`/`.rpm`, each container whose package manager
  (`apt-get`, `dnf`, `zypper`, found by running a probe in it) takes the
  file; for `.AppImage`, each container, run in place with
  `APPIMAGE_EXTRACT_AND_RUN=1` because no container has FUSE. Container
  choices need the desktop session, since a program runs as long as the
  session and not as long as the chooser (`DesktopSessionService.runInPrimary`);
  with the desktop stopped the chooser says so and offers to start it. An
  install is an `exec` of the manager with its own non-interactive flag,
  and its outcome (installed, or the exit code and the last lines of
  output) is shown on the chooser and, on failure, on the desktop's
  launch-failure banner: the terminal exists only in the primary (§3d), so
  running installs in it would have been a second mechanism for siblings.
  The last choice per extension is remembered and offered first, with
  "always" as an explicit row in the chooser, iiSU-style (§4c), never a
  silent default; with "always" on, the file opens straight away and the
  chooser stays up with the way back. Nothing is copied: the file runs, or
  installs, from where it is.

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

Instead the Gaming shell uses `Presentation` for the companion plus
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
all; and being a real Activity it takes input focus, which droidtop's
Presentation is made not to (a Presentation is focusable unless told
otherwise: see "Second display: a Presentation takes no focus", below). The handoff is the shell's own
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
behaviour and the Gaming shell has its own. These must not become two
implementations of one job. A single module owns:

- the one `SECONDARY_HOME` activity in the merged manifest — Launcher3's
  `SecondaryDisplayLauncher` and any Gaming equivalent collapse into
  it, since two activities both claiming that category is precisely the
  duplication to remove;
- what that activity renders, chosen by the active mode: Standard gets
  the launcher's secondary-display UI, Gaming gets the companion
  surface, Desktop gets its input surface (§4);
- the panel role assignment and swap (`MainScreen`, `DisplayArrangement`);
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

**BUILT (2026-09-02), as the launch-screen memory model.** The three
lessons above are now code, layered over (not replacing) the
`MainScreenChoice`/`GameLaunchTarget` choices, which remain the global
fallback:

- `LaunchScreenMemory` (library-core) stores relative `LaunchScreen`
  choices (`BUILT_IN`/`SECOND` — roles, never display ids) at two
  levels: per game and per system. Resolution priority, pure and
  unit-tested (`LaunchScreenResolution`): **per-game > per-system >
  ask > global target.** A remembered SECOND with no second display
  attached degrades to the default display — a preference never fails
  a launch.
- The chooser dialog carries iiSU's own row vocabulary: a plain
  "launch here" pair for this one launch, then an **"Always"** pair
  that remembers for this game. Asking is still the first-launch
  default; a remembered answer is the steady state, so the question is
  asked once per game, not every time.
- Clearing is first-class ("Delete preferred Screen"): the game's
  metadata editor has a Launch screen row (Ask / Built-in / Add-on,
  writes immediately — a display choice is a launcher preference, not
  gamelist metadata), and the gamelist options menu has the per-system
  equivalent ("Tune individual platforms").
- Game identity reaches `LaunchDisplay` through
  `LaunchDisplay.launchContext`, published by `Library.launch` around
  the provider call — the ONE launch path — rather than threading the
  entry through every provider signature. Launches with no game
  identity (opening Kirikiroid2's own UI) simply have no memory and no
  "Always" rows.

### Mirroring, root-caused and fixed (2026-09-02)

The "launching apps mirrors them" report is Android's own fallback: a
secondary display whose window stack is EMPTY mirrors the default
display (already confirmed live above, §4 "Display reinit"). droidtop
had two ways to leave the addon empty and relied on a third party to
fill it:

- the platform only places the `SECONDARY_HOME` idle surface while
  droidtop holds the HOME role AND the display is one Android
  decorates — neither is guaranteed on the addon; and
- the live companion `Presentation` dies with the shell's `onStop`,
  which is exactly what a game launch causes.

Fix: **droidtop covers vacated displays itself.**
`LaunchDisplay.coverVacatedDisplays` runs BEFORE every launch dispatch
and explicitly starts `:display`'s `SecondaryDisplayActivity` (with
`setLaunchDisplayId`) on every secondary display the launch would
otherwise leave empty — not the launch's own target, not the display
the shell renders on, not a parked display; the qualifying set is pure
and unit-tested (`DualScreenOrchestration.displaysNeedingIdleCover`).
Ordering matters: the cover starts first so the game's window lands
last and keeps input focus. `MainActivity.onStop` does the same
best-effort cover for the display its dying Presentation vacates (the
user pressed HOME / switched apps case).

### Relocation can be refused; droidtop now concedes (2026-09-02)

Moving the shell to the addon is a `startActivity` the platform may
refuse (some presentation-category displays reject activity launches).
The cooldown only stopped the retry LOOP — it never concluded
anything, so a refusing addon left the shell built-in and the addon
EMPTY, i.e. mirroring, indefinitely. Now: after
`MAX_RELOCATION_ATTEMPTS` (2) whole cooldown windows without the shell
verifiably on the addon — or immediately on a synchronous
`SecurityException` — orchestration falls back to shell-on-built-in
with the live companion covering the addon. Either way the addon shows
a droidtop surface, never a mirror.

### External screen priority, per mode (2026-09-02)

What "the addon is the better screen" concretely means in each mode:

- **Gaming**: the shell itself moves to the addon (the existing
  SECOND_WHEN_PRESENT default), the built-in panel gets the companion.
- **Desktop**: same relocation, same default — the desktop renders on
  the addon/external and the built-in panel becomes the input surface
  (trackpad + keyboard, §6c) via the same role preference. This
  replaces the earlier "Desktop is exempt" stance: exempt from GAME
  launch targeting it remains (windows are the compositor's job), but
  not from wanting the bigger/better panel as its output — a lapdock
  monitor is the canonical case.
- **Standard**: unchanged — Launcher3's own secondary-display handling.
- The launch chooser lists the ADD-ON row first in both arrangements,
  so the default-highlighted choice is the better screen
  (`DualScreenOrchestration.chooserCandidates`, unit-tested).

### A buried game, root-caused on the live console (2026-09-25)

Live-console review (owner's own Retroid Pocket 5, dual-screen: built-in
+ an external "DP Screen" over the add-on port): launching a game with
two displays attached and the launch-target preference at its default
(`GameLaunchTarget.ASK`) could land the game on the addon display with
real activity focus (`dumpsys activity activities` showed it as
`topResumedActivity`) while the live companion `Presentation` stayed
drawn on top of it, holding the real input focus
(`dumpsys window displays` -> `mCurrentFocus`) and hiding the game
entirely. The game sat there, reachable by nothing but a manual Back
press, until the user noticed. Root cause: `LaunchDisplay.startOn`
launched the "default display" decision (`displayId == null`) with NO
`ActivityOptions` at all, so Android resolved the launch against
whichever display was ambiently current rather than the built-in panel
-- and `coverVacatedDisplays` had just placed `SecondaryDisplayActivity`
on the addon a line earlier in the same call, so the game rode that same
ambient placement onto the addon. Meanwhile `parkedDisplayId` recorded
the *requested* `null`, never the addon's real id, so the role
orchestration never learned the addon was taken and kept showing the
companion there. **Fixed**: `startOn` now always resolves an explicit
display (`displayId ?: Display.DEFAULT_DISPLAY`) and always passes
`ActivityOptions.setLaunchDisplayId` -- the launch, and `parkedDisplayId`,
now always land where droidtop actually asked, never wherever Android's
ambient default happens to be.

### Second display: a Presentation takes no focus, the IME follows visibility (2026-09-30)

Owner, 2026-09-30: "most things on the second display aren't touchable"
(Droidtop/tracker#155) and "apps we launch on the second display can't get
keyboard" (#156). Diagnosed from the code and Android's input model; the
console was not reachable from this session, so the touch half is NOT
proven and the keyboard half is a code-level cause, both needing the rig
check below.

What is true on Android, and what this section corrects in the text above:

- A `Presentation` is an ordinary FOCUSABLE `Dialog` window (type
  `TYPE_PRESENTATION`) layered above every activity on its display. The
  earlier statement that it "never takes input focus" was wrong. A
  hardware key or gamepad press goes to the focused window of the
  top-focused display, and touching a focusable window on another display
  makes that display the top-focused one. So a companion Presentation left
  focusable (a) took key focus away from an app launched onto the same
  display (the app got no keyboard) and (b) flipped the whole system's
  focus to the other screen on every tap on the companion, taking the
  gamepad away from the shell. **Decision: the companion Presentation is
  `FLAG_NOT_FOCUSABLE | FLAG_ALT_FOCUSABLE_IM`.** It is a touch-only surface
  (no text field; the keyboard surface is a pure touch `View`), touch is
  unaffected by the flags, and a Presentation never competes for key focus
  again. This is what makes the Presentation-plus-SECONDARY_HOME split above
  actually hold: the live surface adds no second focus holder.
- An IME for an editor on a non-default presentation display is shown on the
  default display (display IME policy `FALLBACK_DISPLAY`; changing it needs a
  signature permission), so droidtop's IME being selected and allowed to draw
  is what gives such an app a soft keyboard at all.
  `SecondScreenKeyboard.attached` suppresses the IME's own view while the
  second-screen keyboard surface is up. It counted ATTACHED surfaces, and a
  stopped Activity's views stay attached, so the idle `SECONDARY_HOME` cover
  left underneath an app launched onto that display kept the IME suppressed
  for the app's own text fields. **Decision: the count follows window
  visibility** (`onWindowVisibilityChanged`, GONE once the Activity stops), so
  only a keyboard surface that is actually on screen suppresses it.
- The Standard second screen's quick-launch row started apps with no launch
  display, which resolves against whichever display is ambiently current (the
  ambiguity `LaunchDisplay.startOn` already documents). It now pins the display
  the tap was on.
- Probe for the touch report: the Presentation and `SecondaryDisplayActivity`
  log one `droidtop.SecondScreen` debug line per touch-down with the display
  id. If a tap on the second screen produces no line, the touch never reached
  droidtop's window (the panel's touch device is not associated with that
  display, which no app can change without a signature permission); if it
  produces a line and the control does not react, the fault is in the surface.

**Needs a rig check** (real dual-screen console; nothing here could be run
from the build environment): see the commit message for the steps.

### G6 status: store consolidated, relocation logic still in `:app` (2026-09-25)

The "one persisted answer" decision above (`MainScreen`, 2026-09-24)
already finished the store half of the gap-audit's G6 item: there is one
role-model store, not two -- `DisplayRolePrefs.kt` only holds the
orthogonal per-launch `GameLaunchTarget` preference, and `MainScreen.kt`
carries one-time migration code off the old per-display-id
`dual_screen_assignment` file, confirming that store is retired, not
live. What is still open is the other half of G6 and of "one module owns
secondary-display behaviour... `:app` hosts it, the shells contribute
only their own content" above: roughly 350 lines of live
relocation/companion/idle-cover orchestration
(`MainActivity.observeSecondScreen`, `onStop`'s idle-cover-on-stop,
`onNewIntent`'s reinit branch, the `DisplayRelocation` companion object)
still live in `app/.../MainActivity.kt` rather than in `:display`
alongside `SecondaryDisplayActivity`. Moving it touches this Activity's
own lifecycle callbacks, `lifecycleScope`, `ForegroundShell` and
`CompanionState`, and was not attempted in the same session as the
buried-game fix above -- recorded here as the remaining scope, not done.

### "Reinitialize displays", made findable (2026-09-25)

Confirmed live: an addon display can go empty, and so mirror the built-in panel, through
a door `LaunchDisplay.coverVacatedDisplays` does not watch -- an app running on it exits
on its own (the user backs out of it directly, not through any droidtop-owned flow),
while droidtop's own shell never loses foreground on ITS OWN display, so nothing re-runs
the role orchestration. The existing HARD reinit
(`BackButtonMenu.EXTRA_DISPLAY_REINIT_FORCE`, already wired since the 2026-09-02 mirror
fix) recovers from this once it runs -- verified live, screencap before/after -- but it
was reachable only by double-tapping Home, a gesture the UI never tells anyone exists.
**Fixed the reachability, not the plumbing**: "Reinitialize displays" is now a row in
`BackButtonMenu`'s mode switcher, the one dialog already reachable from every mode (a
long-press of Back, the Gaming Quick Menu's System tab, the Desktop taskbar, Android's
own home long-press menu, and droidtop's games screen -- see that object's own class
doc). It sends the same intent extra the double-tap already sends, with no `EXTRA_MODE`,
so it never changes which mode is showing, only forces the display-role reinit.
Self-detection (droidtop noticing the addon has gone empty and surfacing this action, or
running it, without the user having to notice a mirror first) is NOT built -- recorded
as open scope, not attempted alongside this reachability fix. A dedicated hardware
shortcut (chord) was considered and dropped for this pass: everywhere this menu already
opens from is reachable without inventing new key-combo plumbing to audit against every
bundled emulator's own hotkeys first.

### Self-detection, built (2026-09-25): a pill, and a periodic self-heal

The self-detection recorded as open scope above is now built, alongside the companion
redesign in section 4d.

`DualScreenOrchestration.secondScreenNeedsReinit` (unit-tested) is the pure decision:
given the addon's display id, the parked-display id, whether the shell itself is the
addon's content, and which display id (if any) each of droidtop's own two addon surfaces
-- the live `SecondScreenPresentation` and `:display`'s `SecondaryDisplayActivity` (now
tracking its own `resumedDisplayId`, the same pattern `CompanionActivity.visible` already
used for the built-in screen) -- is actually on, it answers whether the addon looks
broken: present, not parked, and covered by neither surface. This is the same "is
anything of ours actually on the addon" question `displaysNeedingIdleCover` (the original
mirroring fix) already asks before a launch, asked continuously rather than only
pre-launch, which is what closes the gap that fix left open: an app on the addon exiting
on its own, with droidtop's own shell never losing foreground on its own display, so
nothing re-ran orchestration.

`MainActivity` publishes the result to `CompanionState.dualScreenBroken` -- a
process-wide flow, the same pattern as `focusedEntry`/`libraryEntries` -- at the end of
every orchestration pass. Two things feed a pass: the existing display-topology/role
triggers, and (new) a plain timer while the Activity is started
(`secondScreenHealthCheckJob`, every 4s), because there is no event this process can
listen for without a privileged task-stack API a sideloaded launcher is not guaranteed to
hold -- `ActivityManager.registerTaskStackListener`'s `ITaskStackListener` is a
`@SystemApi` surface historically gated to privileged/signature callers, and this could
not be verified against a real dual-screen device from this environment, so it was not
risked. The timer means self-healing usually finishes within a few seconds of the addon
going empty, through the SAME orchestration pass every other display change already
runs -- one mechanism, not a second recovery path. The pass this triggers does no disk,
scan or per-game work: it reads already-observed display state and one SharedPreferences
read on `Dispatchers.IO`.

`ReinitializeDisplaysPill` (app module) reads `CompanionState.dualScreenBroken` and draws
itself, over whichever shell is showing (Gaming or Desktop -- both put the addon in play
by default per this section's "External screen priority"), only while the state reads
broken; a tap calls the same `reinitializeDisplays()` the mode switcher's row and the
double-tap-Home gesture already call. It is the backstop the design brief asked for, not
the fix: the periodic self-heal above is the fix, and in the common case the pill either
never appears or clears itself within one health-check tick. Drawn by `MainActivity`
itself, over both shells' own content, rather than inside either shell's chrome (the
Gaming hint row, the Desktop taskbar): the broken state is a MainActivity-level fact true
in both, and neither shell needs a second copy of when to show it. Standard mode is not
covered (Launcher3's own secondary-display handling, not this orchestration, owns that
display there) -- the existing menu row and gestures remain its route to the same
recovery.

**Needs a rig check** (no dual-screen hardware reachable from this environment): attach
the "DP Screen" add-on, launch a game onto it from Gaming mode with two displays
attached, exit the game the user way (its own in-game menu, not Back-to-desktop), and
confirm the addon recovers within a few seconds on its own with no pill ever appearing.
Then force the recurrence if possible (or wait for it to reappear per the owner's earlier
report) and confirm the pill DOES appear within one tick, and that tapping it clears the
mirror the same way the existing double-tap-Home / mode-switcher row already does
(`shots/recheck_d5.png` from the 2026-09-25 review is the known-good reference image).
Also confirm the pill does not appear/flicker during an ordinary game launch or during
the shell's own relocation to the addon, which both change display state through the
same orchestration pass.

### An N64 launch, root-caused: `onStop` churned an unrelated display (rig, 2026-09-27)

Reproduced live on the Retroid Pocket 5 (p1-dt-n64-black-screen-hang): launching an N64
ROM (RetroArch AArch64, mupen64plus_next) from Gaming with "Games launch on: Same display
as the shell" (built-in) and a second display attached left the built-in screen solid
black for 45s+ -- RetroArch's own logcat goes silent right after "Auto-start game" and
never renders a frame, eventually hitting Android's ANR dialog -- while the addon
simultaneously fell back from droidtop's own companion to the bare system
`SecondaryDisplayLauncher`.

`MainActivity.onStop()` was the cause, not RetroArch or mupen64plus_next: on every stop,
regardless of WHICH display the newly-foregrounded thing was actually on, it
unconditionally dismissed `secondScreenPresentation` (the live companion, on the ADDON
display) and reasserted `SecondaryDisplayActivity` there -- even when the launch that
triggered the stop had nothing to do with that display at all, exactly this repro's case
(the game landed on the built-in display, same as the shell; the addon's content never
changed). Two real, connected effects followed: the reassertion racing and losing gave
the addon nothing until Android's own fallback picked it (the companion-fallback half of
this bug and of p1-dt-companion-text-overlap's own notification finding), and the extra
cross-display `dismiss()` + `startActivity` pair fired on the main thread at the exact
moment RetroArch was trying to claim its own EGL surface on the OTHER display --
contention plausible enough, and exactly timed enough against "goes silent right after
Auto-start game", to be the more likely explanation for the hang itself than a
RetroArch/mupen64plus_next-side defect for this specific core and ROM.

Fixed by only touching the companion in `onStop` when the launch actually parked onto
ITS display (`LaunchDisplay.parkedDisplayId == secondScreenPresentation.display.displayId`)
-- the one case where something genuinely now sits underneath the Presentation's window.
A `Presentation` is a `WindowManager` window on its own `Display`, not something tied to
the owning Activity's own foreground state, so leaving it alone when the addon itself was
never touched is correct, not merely "less churn": the companion was always meant to keep
showing "widgets and game info" while a game plays on the OTHER screen (this section's own
design), and tearing it down on every unrelated `onStop` never matched that.

**Needs a rig check**: repeat the exact repro (Gaming, built-in shell, N64 ROM, "Games
launch on: Same display as the shell", a second display attached) and confirm the
built-in screen shows a rendered frame within a few seconds rather than 45s+ of black,
and that the addon keeps showing droidtop's own companion throughout rather than falling
to `SecondaryDisplayLauncher`. Also confirm the companion still tears down correctly when
a game DOES launch onto the addon itself (Games launch on: Second screen, or Ask -> Second
screen) -- that is the one case this fix still acts on.

### The second screen is per-mode, not one surface for all three (directed 2026-09-27)

The owner, after this session's other dual-screen fixes: "it seems we use the same dual
screen mode for standard and gaming. The second screen needs to apply more to the mode."
True of the code as it stood: SecondScreenPresentation (the LIVE surface, shown whenever
MainActivity itself drives the second screen) never branched on mode at all and always drew
the game companion, and Standard's own registration
(SecondaryDisplayRegistrations.registerLauncherHandoff) handed off to Launcher3's bare
SecondaryDisplayLauncher -- a near-empty system stub, not a droidtop surface, and the exact
thing rp5test's screenshots kept catching (this section's own "companion-fallback" finding
above). droidtop has three modes and now three second-screen designs:

- **Gaming**: the game companion, unchanged (CompanionSurface, section 4d) -- the focused
  game's art and metadata, play time, "Runs with", updates, and the idle rotation/status
  strip while browsing.
- **Standard**: StandardSecondScreenSurface (:app) -- a launcher-style surface, built
  from pieces droidtop already has rather than a new system: CompanionSystemBar and
  CompanionNotifications (the SAME droidtop-styled clock/battery/network/controls and
  notification rows Gaming's companion draws -- no second implementation), a "Quick launch"
  row backed by RecentAppsStore (:shell-default's own real, already-recorded
  recent/frequently-used app history -- not a fabricated list), and already-added Android
  widgets through the SAME CompanionWidgets host and CompanionWidgetPrefs set the
  companion's own widget picker manages (one widget set across the whole companion
  experience; Standard has no add/remove UI of its own, the same constraint every
  non-owning companion host already documents). No media-session "now playing": docs/SPEC.md
  section 7e is scoped but not built, and there is nothing real to show without fabricating
  it; a plugin status tile is an ordinary Android widget (PluginStatusWidgetProvider),
  already covered by the widget area.
- **Desktop**: unchanged -- keyboard and trackpad (SecondScreenInputSurface), or the
  companion when the user picks that role instead (SecondScreenInputPrefs).

**One registry, one mechanism (fixed the same pass).** SecondaryDisplayContent (:display)
already held a per-mode composable registry that only SecondaryDisplayActivity (the idle,
platform-placed SECONDARY_HOME surface) read from; SecondScreenPresentation (the live,
shell-owned surface) duplicated its own role-branch and hardcoded CompanionSurface
regardless of what SecondaryDisplayContent.currentMode said. SecondScreenPresentation now
reads the SAME registry (SecondaryDisplayContent.contentFor(mode)) the idle surface does,
so the two can never disagree about what a mode's second screen is again. The dead
handoff-to-an-Activity mechanism (SecondaryDisplayContent.registerHandoff, added only for
Standard's old bare-launcher-3 hand-off) is removed with it: Standard registers real
composable content the same way Gaming and Desktop do, and ModePiece.LAUNCHER_SECOND_SCREEN
(the mode-piece that used to gate that hand-off) is gone -- Standard's second screen is not
gated on a mode piece at all -- the platform only ever places SecondaryDisplayActivity
(which reads this registry) while droidtop holds Home, so an unregistered Standard content
would only matter in a state nothing can show it in anyway.

### The Alternative forwarder keeps the second screen (directed 2026-09-27, corrected)

Second owner requirement, same pass, relayed wrong the first time and corrected: droidtop
does not need its second screen to work while some OTHER app holds Home outright -- the real
ask was the existing **Alternative** home implementation (`HomeRolePrefs.HomeImplementation.
ALTERNATIVE`, `AlternativeLauncherActivity`, farmerbb/Taskbar's real `HSLActivity` pattern):
droidtop still holds the HOME role, one of its own two HOME activities (this one, not
`HomeTrampolineActivity`) is what is enabled, and it immediately forwards Home presses to a
different, person-chosen launcher instead of rendering anything itself. Since droidtop is
still the platform's current Home app in this configuration, the platform's own
`SECONDARY_HOME` placement already puts `SecondaryDisplayActivity` on the secondary display
exactly as it does for plain Standard -- nothing here needed a new attachment mechanism,
and the `SecondScreenAttachService`/`SecondScreenOwnership` foreground-service machinery this
section briefly carried (checked against AOSP's background-activity-launch policy, still a
real thing to know if droidtop ever needs a TRUE not-Home case) was removed again per one
mechanism per job: it solved a problem that, once the requirement was corrected, droidtop
does not actually have.

**The real gap**: `SecondaryDisplayContent.currentMode` (what both `SecondaryDisplayActivity`
and `SecondScreenPresentation` read) resolves from `Modes.lastMode`, and nothing wrote
`Mode.LAUNCHER` there when Home actually landed on Standard or forwarded through Alternative
-- only `BackButtonMenu`'s explicit "Android" switcher row did. Pressing the real Home key
(`HomeTrampolineActivity.forwardToStandard`) or letting Alternative forward
(`AlternativeLauncherActivity`) left `Modes.lastMode` however it last was from Gaming or
Desktop, so the second screen kept showing that stale mode's content instead of Standard's
own -- the same root shape as "The second screen is per-mode" above, reaching one step
further back into how the mode is even recorded. Both activities now call
`Modes.setLastMode(this, Mode.LAUNCHER)` before forwarding, matching what every other real
entry into a mode already does (`MainActivity.resolveMode`, `BackButtonMenu.launchAppMode`).

**Two more pieces, needed for that fix to actually show up on screen**: `SecondaryDisplayActivity`
is `singleTop`, so re-asserting it (MainActivity's `onStop` starting it again to reclaim the
display) delivered `onNewIntent`, not a fresh `onCreate`/`onResume` -- and nothing overrode
`onNewIntent`, so `render()` never re-ran when the Activity was already resumed underneath a
live `Presentation` (which is the common case: covering another window on the same `Display`
does not pause the Activity beneath it). It now does. And `MainActivity.onStop` (this
session's earlier N64-hang fix, "An N64 launch, root-caused" above) only tore its own
companion `Presentation` down when a launch had parked onto that SAME display -- correct for
"a game launched elsewhere," but it left a stale Gaming/Desktop companion Presentation
sitting on top of Standard's freshly-registered content when the real reason for leaving was
a Home press. `onStop` now also tears it down when `Modes.lastMode` no longer matches the
mode this MainActivity instance was showing (`modeDeparted`) -- set by the trampoline/
forwarder/switcher BEFORE this Activity's own `onStop` runs, in Android's normal activity-
transition order, so comparing the two numbers apart needs no extra signalling. A plain game
launch never touches `Modes.lastMode`, so the N64 fix is unaffected by this addition.

**Verified live** (console, 2026-09-27, after the nudge fix below): set the home
implementation to Alternative through droidtop's own Settings (Global settings > Use droidtop
as home screen > A launcher you already have > RetroidLauncher, the console's own stock
launcher), pressed Home — forwarded cleanly, no flash, no loop
(`com.retroidpocket.gamelauncher/.activities.RpGameLauncher` resumed on the built-in display).
The mode switcher's "Android" row opens that same launcher. Confirmed the second screen
attaches (see "The Alternative forwarder needed a nudge" below for the one further fix this
needed) and switched Home back to Standard afterward, confirmed on both screens.

### The Alternative forwarder needed a nudge to keep the second screen (rig, 2026-09-27)

Verifying "The Alternative forwarder keeps the second screen" above on the console (droidtop
holding Home via `AlternativeLauncherActivity`, forwarding to the console's own stock
launcher) found the theory incomplete: after droidtop self-updated (a fresh process, no
droidtop Activity resumed anywhere at all) the secondary display stayed on Android's own
mirror-of-the-default-display fallback rather than picking up droidtop's newly-registered
Standard content, even though droidtop still held Home. The platform's automatic
SECONDARY_HOME placement does not proactively re-evaluate an already-established mirror on
its own -- it needs a nudge, the exact same platform behaviour
`LaunchDisplay.coverVacatedDisplays` was already built to work around for game launches (that
function's own doc comment: "The platform's own SECONDARY_HOME placement can't be relied on
for this ... so droidtop places its own"). Nothing was left running anywhere to catch this a
few seconds later either: the periodic self-heal (section 4c, "Self-detection, built") only
runs while `MainActivity` itself is started, and nothing app-hosted is running at all while
the person is on Standard or an Alternative-forwarded launcher.

Fixed by reusing the SAME pattern at the one remaining place it was missing:
`reassertSecondaryDisplays` (`:shell-default`) explicitly starts
`dev.droidtop.display.SecondaryDisplayActivity` on every attached secondary display
(`DisplayManager.displays`, filtered to non-default `FLAG_PRESENTATION` displays), called
from both `HomeTrampolineActivity.forwardToStandard` and `AlternativeLauncherActivity`'s
forward path, right alongside the `Modes.setLastMode(LAUNCHER)` call each already makes. A
string component name (`Intent().setClassName(...)`), not a class reference: `:shell-default`
does not depend on `:display`, the same reason `HomeTrampolineActivity` already names
`dev.droidtop.app.MainActivity` as a string rather than importing it.

**Verified live**: droidtop self-updated (969 -> the fixed build) with "Install debug
builds" Off while itself running a debug-signed install -- confirmed the updater fix in the
same pass (section 10b) -- then, with Alternative set to the console's own stock launcher
(`com.retroidpocket.gamelauncher`), a Home press forwarded cleanly with no flash and no loop,
and the mode switcher's "Android" row opened that same launcher.

### The task model, and one droidtop in Recents (owner correction, 2026-09-27)

The same verification pass also found droidtop's own Recents entries reading as multiple app
instances -- one process (confirmed via `dumpsys`), but several visible tasks:
`dev.droidtop.app/.MainActivity` relocated onto the secondary display (an unrelated,
pre-existing "Main screen: Second display when present" placement, not caused by this pass),
`dev.droidtop.app/com.android.launcher3.Launcher` left over from earlier Standard navigation,
and (while it was open) the Settings task. Checked each against its manifest declaration:

- `HomeTrampolineActivity` and `AlternativeLauncherActivity` (the two real `CATEGORY_HOME`
  holders) already declare `excludeFromRecents="true"` -- correct, and unchanged.
- `com.android.launcher3.Launcher` (Standard's actual home screen UI, reached only by
  forwarding from the activity above, never directly) did NOT -- its own real Home screen
  showing up as a separately-switchable Recents card is not how a real Home screen behaves on
  stock Android either. Now declares `excludeFromRecents="true"` too, alongside its existing
  `taskAffinity=""` (kept -- a launcher's own task must not merge with an ordinary app task;
  unrelated to the Recents-card question).
- `com.android.launcher3.settings.SettingsActivity` (Settings' own task, `taskAffinity=
  "dev.droidtop.app.settings"`, deliberate since dq-onboard-02 -- a shared task let "Home
  settings" clear a running Gaming/Desktop shell out from under it with `CLEAR_TASK`) already
  declares `autoRemoveFromRecents="true"`: it drops out of Recents the moment it is left, so
  it only ever appears as its own card while genuinely still open, the same as most Settings
  apps' own Recents behaviour. Left as-is; the owner's own caveat ("if deliberate, it still
  shouldn't read as a second instance") is already satisfied by the auto-remove, not by full
  exclusion, since full exclusion would make a person unable to switch back into Settings via
  Recents while it is legitimately still open.
- `MainActivity` itself carries no special Recents handling and needs none: it is the ONE
  real app-hosted task (Gaming/Desktop), exactly what a person expects to find droidtop under
  in Recents.

**Partially verified live** (console, 2026-09-27): `dumpsys activity recents` after the
nudge fix above still showed `com.android.launcher3.Launcher` (task #1743) alongside the real
app-hosted task -- but that task predates this session's manifest change (created before this
build was installed), and `excludeFromRecents` is read at task-creation time, not enforced
retroactively on an existing one; it was never re-verified from a genuinely cold task history.
**Needs a rig check**: from a clean state (reboot, or after clearing old droidtop tasks), use
Standard and Alternative normally, then open Recents the user way (the Recents gesture/
button, not `dumpsys`) and confirm exactly one droidtop card appears (or none, when nothing
app-hosted is running), never `com.android.launcher3.Launcher` as a separate switchable entry.

### The relocation/companion orchestration moved into `:display` (2026-09-27)

The one-role-store consolidation (`MainScreen`, commit `c91e1948`, "Keep one answer to
which screen is the main one") already replaced the two disagreeing display-role stores with a
single persisted choice back on 2026-09-24; nothing since had reintroduced a second one. What
was still outstanding was where the *code* that acts on that choice lived: the roughly 350
lines of relocation/companion decision logic (display topology tracking, the relocation
cooldown, the live `SecondScreenPresentation`/idle-cover handoff, the companion re-assert) sat
in `MainActivity` (`:app`) rather than `:display`, where this section says droidtop's
secondary-display decisions belong.

Moved to `SecondScreenOrchestrator` (`:display`), along with `DisplayRolePrefs` (the
per-launch game-display-target reader it is the one caller of) and `SecondScreenPresentation`
(already self-contained -- it only ever read `SecondaryDisplayContent`, also in this module).
`MainActivity` now implements a small `SecondScreenHost` interface: the handful of things
that must be the foreground Activity (its own current display id, relaunching itself) or that
only `:app` owns (`CompanionActivity`, `dev.droidtop.library.LaunchDisplay` -- `:display`
still has no dependency on `:library-core`). Every decision -- topology change detection, the
relocation give-up policy, which surface covers which display -- is now orchestrator code, not
Activity code. The relocation cooldown counters stay process-wide (a companion object on the
orchestrator class, same as `MainActivity`'s own former `DisplayRelocation` companion
object), since a relocation recreates the Activity (and with it a fresh
`SecondScreenOrchestrator` instance) mid-guard-window.

Behavior is unchanged -- this is a move, not a redesign: explicit launch-display targeting,
the Standard second screen (via `reassertSecondaryDisplays`, untouched), the Alternative
forwarder, and `reassertSecondaryDisplays` itself all keep working exactly as before.

**Rig-checked (emulator-5560, 2026-09-27)**, fake second display via
`settings put global overlay_display_devices 1280x800/213`: with `MainScreen` at its default
(`SECOND_WHEN_PRESENT`), a Gaming-mode launch relocated `MainActivity` onto the overlay display
and started `CompanionActivity` on the built-in one; a Desktop-mode launch did the same
(`DesktopSessionService` itself then failed for an unrelated, pre-existing reason -- no desktop
image configured on the test install -- which does not touch the orchestrator). No crash, no
`SecurityException`/relocation-refused log line, in either mode. Standard mode was NOT
re-verified through this path: `MainActivity` is not how Standard mode actually runs (the real
Standard home is `com.android.launcher3.Launcher`/`HomeTrampolineActivity`, whose second screen
goes through the untouched `reassertSecondaryDisplays`, not `SecondScreenOrchestrator`), so
launching `MainActivity` directly with a `standard` mode extra -- which this Activity itself
only ever draws as a blank screen for -- exercises no code this change moved. A live game
launch (the `coverVacatedDisplays` idle-cover path) was not exercised live this pass; it is
unchanged code moved verbatim and worth a follow-up device-queue item rather than blocking on it
here.

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
3. **Idle state** (built): when nothing is focused, a slow hero/marquee
   rotation drawn from the library plus the clock — calm, ambient, never a
   wordmark (`CompanionIdle`). A post-idle delay before artwork commits, so
   fast scrolling does not thrash: every companion host reads the focused
   entry through `settledFocusedEntry`, which draws a new focus only once
   it has held for 350 ms and a cleared focus at once.
4. **Notifications** (built).
5. **droidtop-native widgets**: the widget picker offers droidtop's own
   tiles beside the installed Android widgets, each a `CompanionWidget`
   drawn by droidtop and laid out and persisted like an Android widget:
   library stats (games per system, the numbers the theme's `systemdata`
   knows), playtime and most played (from play history, once playtime
   is measured, §7g), recently played (the rail, as a placeable tile),
   and live progress for the scan, a scrape and downloads (the same
   `ScanProgress`, scrape summary and download queue the settings rows
   read, so a walk started from onboarding is visible on the second
   screen while it runs). Tiles never run their own scan or query; they
   observe the stores the shell already holds.
6. **Android widgets**.
7. **Controls**: per-panel brightness (the companion's own display's
   brightness, `WindowManager.LayoutParams.screenBrightness` on that
   window, which needs no grant; and the system's, through the same
   `SystemControls` tile the Quick Menu uses), Wi-Fi, Bluetooth, DND and
   media volume, drawn as the Quick Menu's tiles in a row the person can
   show or hide. The companion never carries a control the Quick Menu
   does not: it is the same catalog items rendered on the other panel.

### Rules this design commits to

- **Complement, never mirror.** The companion shows what the primary
  screen is not showing. Duplicating the shell is the failure mode.
- **Density is the user's choice**, Steam Deck style. A default that is
  calm, and an explicit way to show more.
- **Yield completely.** When a game or app owns that panel, the companion
  is gone — not layered over it. This is already why a launched display is
  parked (§4c) and why the Presentation is dismissed on `onStop` when a
  launch parks onto the SAME display the companion is on (§4c, "An N64
  launch, root-caused") — never on an unrelated display change, which
  the companion has no reason to react to at all.
- **Never a placeholder.** An idle companion shows something real or
  shows the ground. A wordmark on a black rectangle is the bug this
  section exists to close.

### Continue-playing rail (built 2026-09-02)

The companion's one interactive element: a tap-to-launch row of the
most recently played games (`CompanionRecents`, on every companion
host). The lower panel is a touchscreen the user's thumbs already rest
near, and "tap the game I was playing yesterday" is the most common
launcher action — so it is one tap deep there, not only behind gamepad
navigation on the other screen. Data is the same
`CompanionState.libraryEntries` feed the idle rotation uses (the
companion never runs its own scan); a tap goes through
`CompanionState.onLaunchEntry`, installed by MainActivity and backed by
the ordinary `Library.launch` path — launch-screen memory, play
history and error handling included, never a second launch mechanism.
A failed launch is said under the rail in the shell's own wording
(`CompanionState.launchError`), since the shell's error line is on the
other screen.
While no shell is alive to launch through, a tap does nothing rather
than half-launching outside that path.

### Focused-game detail, filled in with real data (built 2026-09-25)

The owner's own framing (dq-dualscreen review, 2026-09-25): the companion was "a bunch
of images and some white text" with no per-game detail at all -- `info_d0.png`/
`info_d5.png` from that review show the addon not even changing when the main screen's
own game-info view opened. `CompanionContent`'s focused-entry panel now carries, all from
data the library already has (never re-scraped, never fabricated):

- a real artwork thumbnail beside the text column (`entry.artworkUri`, falling back to
  `heroUri`) -- previously text-only, the concrete gap the review's captures showed;
- title, system/engine, developer/publisher/year/genre, series, rating, description --
  already built before this pass;
- play time (already built) and last played, now added, formatted with Android's own
  `DateUtils.getRelativeTimeSpanString` ("3 days ago") rather than a raw epoch or a
  hand-rolled duration;
- an available-update line, reusing the existing F95/update tracking
  (`LibraryEntry.availableUpdate`, docs/SPEC.md §7g) rather than building a second
  "is this current" check;
- which player will actually run it, for console ROMs: `ConsoleRomProvider.resolvePlayer`
  -- the SAME resolution `Library.launch` itself uses (the game's own `altEmulator`, then
  the system's override, then the first installed candidate) -- read here, not
  duplicated, so the line can never claim a player launch would not actually use. PC
  entries already carry their own source line (`PcInfo`); engine games are named by the
  system line, since their engine already IS what droidtop calls the "system" for them.

**Explicitly not built, because droidtop has no real data source for them today, and the
owner's own rule against fabricated content rules out inventing one for this pass:**

- **Save states.** No droidtop code discovers or reads emulator save-state files today --
  each bundled emulator's own save/state directory convention is undocumented in this
  codebase and would need to be confirmed per emulator (accuracy-over-deference: it needs
  the same source-citation standard as the players database itself, not a guess), and the
  save-location policy already directs droidtop to make SYSTEM locations mean somewhere
  else without changing save logic -- a save-state reader is real, separate scope, not a
  companion-screen afternoon.

  **Decision (Droidtop/tracker#76, 2026-09-28): discovery is core, data-driven, read-only,
  and blocked on two facts that must be established per emulator before any row exists.**
  It is one table in the players-database style (`vendor/droidtop-platforms`): per player
  package, where its states live and how a slot file is named, each row citing the
  emulator's own source or documentation, and a row is added only once that is confirmed
  on a real device. First, the location and naming come from the emulator, never from
  memory. Second, reachability: on Android 11+ another app's `Android/data` is not
  readable without a grant, so a row is usable only where the states sit in shared storage
  or a folder the person has granted (the same "make system locations mean somewhere
  else" policy the save-location rule already uses); a state store droidtop cannot read
  is reported as "not visible to droidtop", never as "no states". This supersedes the
  "links to where the runner keeps them" line of §7g only in that a game's detail may
  LIST slots and timestamps read-only; loading, switching, backing up or syncing a state
  stays the runner's. Not built yet: no row has been confirmed, so no reader ships (an
  empty mechanism would be dead code). The first step is a rig session per emulator
  (RetroArch, DuckStation, PPSSPP, Dolphin) that records the path and file naming from a
  real save, which becomes the cited row.
- **Achievements.** DuckStation's own in-game menu has an Achievements row, which is
  RetroAchievements support living entirely inside that emulator's own process; droidtop
  has no RetroAchievements client of its own and no channel to read that emulator's
  in-process achievement state from outside it. Nothing to surface without inventing data.
- **Per-core controls/hotkeys.** Each bundled emulator owns and lets the user reconfigure
  its own key bindings; droidtop has no reader for any of their configuration formats.
  Showing droidtop's own shell bindings here (A/B/Select/etc.) would be showing the WRONG
  thing -- those are shell navigation, not what the running emulator answers to.

All three are real, scoped gaps for a follow-up pass, not oversights folded into this
one -- each needs its own real data source before it can honestly appear here at all.

### Layout overlap and a duplicate rail entry, fixed (rig, 2026-09-27)

Reproduced live (p1-dt-companion-text-overlap): with a game's detail open on the built-in
screen, the addon's description paragraph was drawn directly across the "Continue
playing" thumbnail row, illegible where the two crossed, and the same game ("Glover
(USA)") appeared twice in that rail at once.

The overlap was a real layout bug, not a data one: `CompanionSurface` stacks
`CompanionContent` (the focused-entry text, full-screen, vertically centred -- "the
BACKGROUND layer" by direction) UNDER a foreground `Column` (status bar, notifications,
the rail) in a plain `Box`, and neither composable knew the other's size -- "background"
never meant "drawn under other text". `CompanionSurface` now measures the foreground
block's real height (`Modifier.onSizeChanged`, since notifications and the rail can vary
it) and `CompanionContent` takes that as a `topInset`, so its own title/description Row
never draws above where the foreground block ends. The foreground block also gained a
real scrim (`MaterialTheme.colorScheme.background` at partial alpha, existing theme
tokens, no new art) behind the status bar/notifications/rail, since the same rig review
read live Android notifications rendered there as unstyled system clutter -- they already
draw through droidtop's own themed row (`CompanionNotifications`: `Text` + a "Dismiss"
button, never the system's own notification view), what was missing was a surface of
their own to sit on.

The duplicate rail entry was `CompanionRecents` trusting every id in
`CompanionState.libraryEntries` to be unique per game, which is not guaranteed while a
rescan is settling an entry's id (the exact class of problem
`PlayHistoryDatabase.moveTo` exists to reconcile once it has). The rail -- sorted by
recency and shown to the user directly -- now dedupes defensively rather than assuming
the feed already has: first by `id` (a literal duplicate), then by
`title + systemId` (two ids, one game), keeping the more-recently-played of a pair since
the list is already recency-sorted at that point.

**Needs a rig check**: open a game's detail view on the built-in screen with a second
display attached and confirm the addon's description text no longer crosses the
"Continue playing" row at any point, that notifications/status/rail read as one droidtop
surface rather than loose text over the art, and that no game appears twice in the rail
across a normal browsing session.

### Browsing/idle status strip: what is real today, what still is not

`CompanionSystemBar` (built) already carries clock, network (with signal level and
"no internet" for the captive-portal case), VPN, battery, and an expandable Controls row
(volume, brightness where granted, DND, Wi-Fi/Bluetooth panels) -- the real status strip
this section's design called for, not Android's notification shade bleeding through.
Two items from the same design remain genuinely unbuilt, for the same reason as the
per-game gaps above -- no real data source exists yet, not a placeholder standing in for
one:

- **Now playing** (Spotify/Discord ambient presence, §7e) -- that section is scoped, not
  built; there is no now-playing store this screen could read.
- **Running jobs and downloads** -- no second mechanism; the visible download/install queue uses `PluginJobsCenter` / the Jobs screen (`plugin_jobs`) directly (`GamingSettingsCatalog` exposes it as "Downloads and installs", `Droidtop/tracker#85`). No `ScanProgress`/download-queue store is invented -- the existing registry (`entries(): StateFlow<List<Entry>>`) is the one surface every caller reads, controller-first (`CatalogNavigator`'s own `LazyColumn` focus and touch dispatch), with end-user wording ("Downloading…" / "Done" / "Failed", progress % and status line, cancel best-effort). No main-thread file/database work: all reads go through the flow, writes stay in the plugin runtime.

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
which compiles [vendor/gamenative](../vendor/gamenative) in) is the only Windows
path.** Revisit hardware virtualization only if targeting Snapdragon 8
Gen 2+/Dimensity 9000+ devices specifically, and even then only as a path
to running a *Linux* guest, not Windows. See §5a for which translation
backend and for the native-Linux-build alternative to Wine entirely.

## 5a. CPU-translation backend choice, and preferring a native Linux build over Wine

Two corrections to §5's "Wine + Box64" framing, both from gamenative
source in [vendor/gamenative](../vendor/gamenative):

- **Backend choice is the user's, per prefix, not fixed to Box64.** The
  backend is the prefix's own `emulator` field, and the vendored
  `BionicProgramLauncherComponent` is what honours it: an **arm64ec** Wine
  build loads `wowbox64.dll` or `libwow64fex.dll` as its `HODLL` according
  to that field, while an **x86_64** Wine build always runs as
  `box64 <guest>`. droidtop's launch (`WineXSession`) hands the launcher
  the prefix's own Box64 version and preset and FEXCore preset, and the
  per-game Wine configuration screen (gamenative's `ContainerConfigDialog`,
  opened through `PcContainerConfigActivity`) is where a person changes
  them. droidtop provisions `proton-9.0-x86_64` (the one build that
  installs without a hand-installed `.wcp`, see `DroidtopPcGameRuntime.
  WINE_VERSION`), so the **default is Box64**; FEX applies once a prefix
  is switched to an arm64ec build with FEXCore as its emulator. FEX's
  separate value for Linux software is §3c.
- **Prefer a native Linux build over Wine+translation when one exists
  and can run.** Some games ship a genuine Linux build alongside (or
  instead of) Windows. Running it as a normal process inside a Linux
  container (§3) with no Wine involved is strictly better when it is
  available, so it is a selection-order rule, applied **at launch** by the
  one runner model (`RunnerAvailability`, §7i): for a PC game no engine
  claims, the native Linux row ranks above the Wine row whenever both are
  in the same state, and the store/folder launch
  (`PcGameProvider.launchStoreGame`) runs whatever that model resolves --
  the same answer the game's "Runs with" row shows, and the user's
  per-game choice beats it. Engine games take their order from the
  engines database (§7e2) instead. The Linux row is ready only when
  Desktop mode's primary container is live -- droidspaces with root,
  proot without (§3) -- and, for an `.x86_64`/`.x86` launcher, when FEX
  is registered through `binfmt_misc` (§3c, not built). Anywhere that
  does not hold, Wine wins and the Linux row says why.
  - **Downloads stay on Windows depots (decided 2026-09-24).** The fork
    can fetch a Linux depot instead (`SteamService.
    resolveDownloadableDepots(preferLinux)`, gated on gamenative's
    `PrefManager.preferLinuxDepots`, default off and not surfaced by
    droidtop). droidtop leaves it off: a Linux-only install cannot run
    wherever Desktop mode's container is not up, which on a handheld is
    most of the time, while a Windows install runs everywhere through
    Wine. Native-first is applied among the builds actually on disk,
    where the device's facts are known; a depot rule would decide it
    before them. (An earlier droidtop-side depot picker,
    `selectBestDepot`, was never called and has been removed.)
  - **Open: which CPU a Linux build targets.** Steam's depot metadata
    (`OSArch`) says 32- or 64-bit, never x86 or ARM, so an ARM64-native
    Linux build cannot be recognised before download. On disk the ELF
    header's machine field would answer it; droidtop reads the launcher's
    extension instead (`.x86_64`/`.x86` means x86), so an ARM64 build
    shipped under another name is not yet told apart from an x86 one.

### PC launch wiring: the `PcGameRuntime` seam (directed 2026-08-31)

`WINE_PREFIX` and `LINUX_CONTAINER` were both dead `error()` stubs
("isn't wired to a running session yet"), which meant the resolver could
*offer* a detected engine game Wine and then fail the instant the user
picked it. They are now real launches, through a seam:

- `library-core` declares `PcGameRuntime` + `PcGameRuntimeRegistry` —
  the same swappable-seam pattern `LaunchDisplay.chooser` and
  gamenative-tux's own `LinuxContainerBackend` already use. It exists
  because `library-core` cannot depend on the runtime modules, and the
  native-Linux half needs Desktop mode's container session, which only
  the app layer can obtain. The Wine half needs no session (§5b).
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
  detail screen, backed by `LaunchStrategyOverridePrefs`. Engine games
  and store/folder games launch with what that row resolves, not with a
  second rule (§5a).

The pieces behind the seam:

- **Provisioning** is `DroidtopPcGameRuntime.provision()`: it fetches
  the Wine build and the bionic ImageFs through gamenative's own
  instance-free downloader, installs them with `ImageFsInstaller`,
  creates and activates one droidtop container with the games roots
  mapped as drives, and is offered where it is needed ("Set up Windows
  games" on a Wine row) rather than hidden in settings. Its order and its
  on-device history are §5b.
- **Windows launches** go through the sealed `WineEngine` seam
  (`BionicWineEngine`, §5b), never through `ContainerRuntime`.
- **Native Linux launches** go through `NativeLinuxGameSession`, which
  runs the launcher with `ContainerRuntime.exec` in Desktop mode's primary
  container. `DroidSpacesRuntime.exec` drives droidspaces' own
  `--name=<id> run <cmd...>` subcommand (Documentation/Linux-CLI.md),
  with per-invocation env vars prepended as inline POSIX assignments
  because `run` has no env flag; `ProotRuntime.exec` runs the command as
  its own proot session in the container's rootfs (§3). An x86 Linux
  binary additionally needs §3c's FEX registration, which is not built.

## 5b. One Wine engine, one prefix store, whichever backend is live (assessed 2026-09-02)

Re-derived from the code rather than from any earlier summary, because
two descriptions of this path were in circulation and both were partly
wrong.

### What the code did when this was assessed (2026-09-02)

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
  `ProotRuntime` was seven `TODO()`s including `exec`.

Those three facts composed into the real defect: **Windows games were
root-only**, not by design but because the only backend whose `exec`
was implemented was the one that needs root. The prefix was provisioned
into a rootfs the launch path never ran in. The shape below removed
`ContainerRuntime` from the Wine path altogether, so `ProotRuntime`
since being built (§3) changes nothing for Windows games.

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
  (droidtop's own no-root Linux containers use vendor/proot, Termux's
  build, instead; §3.)

So the no-root Windows path does not need proot at all. It needs the
bionic direct-exec model that gamenative already uses everywhere modern
Android is the target.

### The blocker that made all of it inert (fixed, see below)

`runtime-windows`'s `sourceSets` pulled `java`, `res` and `assets` from
the vendor tree and **not `jniLibs`**, and no droidtop module declared a
`jniLibs` source dir anywhere. None of gamenative's native payload
(`libwinlator.so`, `libpatchelf.so`, `libc++_shared.so`, the pulse
libraries) shipped in the APK. Confirmed on the device: `files/imagefs`
contained a single `.winlator/.container_migration_version` marker, no
rootfs, and `ContainerManager` had never held a container. Provisioning
had therefore never completed on real hardware.

### The shape this settles on

One Wine engine, installed once. One droidtop-owned prefix store, owned
by droidtop and keyed by game, never by whichever container happened to
launch it. Execution is a **runtime** choice, not a structural one:

- the bionic direct-exec path is the universal default, works with no
  root, and is what BOTH modes use to run Wine;
- **Wine never runs with root -- a hard security boundary, not a
  preference** (stated 2026-09-02, superseding the earlier "droidspaces
  `exec` as a desktop optimization" shape recorded here). Wine's whole
  job is executing arbitrary third-party Windows binaries; a malicious
  game in a root-capable context needs no exploit, only to be run. So
  the Wine launch path must not pass through
  `ContainerRuntimeFactory.select` at all -- on a rooted device that
  selection silently yields the root-backed droidspaces runtime. The
  constraint is structural: `WineEngine` is a *sealed* interface that
  cannot be handed a `ContainerRuntime`, so "run Wine as root" is not
  expressible outside `:runtime-windows`, and inside it any
  implementation touching `RootProcess`/`ContainerRuntime` is a
  boundary violation by definition. Provisioning is separate from
  execution: laying out the ImageFs is droidtop's own work on its own
  directories and needs no root either (`:runtime-windows` has no root
  use at all: the GameNative data import that once read another app's
  database as root has been removed).
  `RootfsDelete` (root, `:runtime-linux-root`) is droidspaces container
  lifecycle only; `:runtime-windows` does not depend on that module, so
  the Wine path cannot reach a root-capable delete, and a Wine prefix
  can never be handed to it;
- root elsewhere in desktop mode (droidspaces containers for the Linux
  desktop itself) is unchanged -- the ban is on the Wine *guest*;
- neither mode may re-provision anything the other already installed.
  The test is that the same game with the same prefix launches in both
  modes.

That means `PcGameRuntime`'s implementation must stop treating a live
`PrimaryContainerSession` as the precondition for launching Windows
software at all. `ContainerRuntime` as an interface is adequate to
express "run this command in this environment"; what is not adequate is
the assumption at the call site that the environment IS a droidspaces
primary container.

### What the hardware said (2026-09-02)

The seam exists: `WineEngine`, with `BionicWineEngine` behind it, and no
launch site asks for a `PrimaryContainerSession` any more.
`WineSession`/`WineLaunchEnvironment` are gone -- they existed only to
run Wine through `ContainerRuntime.exec`, and the hand-transcribed guest
environment is superseded by the vendored launcher component's own.

Packaging was not one omission but three, each fatal on its own and each
found by the next one failing on the device:

- the vendored `jniLibs` (fixed previously);
- the modern flavor's **assets**. `MODERN_ANDROID` makes every guest
  process `LD_PRELOAD` `libredirect-bionic-wx.so`, which
  `ImageFsInstaller` copies out of `src/modern/assets` -- a source dir
  nothing referenced;
- `useLegacyPackaging` for `jniLibs`. The runtime hands native library
  *paths* to processes it starts (`libevshim.so` out of
  `ApplicationInfo.nativeLibraryDir`), and without extraction at install
  time that directory is empty. Confirmed on the device: 35 libraries in
  the APK, `lib/arm64` empty.

Provisioning also could not have worked as written, for two reasons
beyond the missing natives:

- it created the container from `Container.DEFAULT_VARIANT`, which reads
  `DefaultVersion.VARIANT` -- `glibc` at class-load time. droidtop now
  names bionic and a bionic wine version explicitly;
- **order**. Creating a container copies Wine's own DLLs into the new
  prefix, so Wine has to be installed first; and gamenative's proton
  launch dependency downloads through `SteamService.downloadFile`, which
  dereferences a service droidtop never starts. droidtop fetches the
  archive with the instance-free downloader and lets the dependency do
  the rest.

**Milestone 1 is met.** On build 388 the ImageFs rootfs installs for the
first time: 175MB base system downloaded and extracted to a 947MB rootfs
with `bin`/`etc`/`lib`/`usr`/`opt`, `opt/proton-9.0-x86_64` symlinked
into a 358MB shared Proton store. Before this it had never once
completed.

**Milestone 2 is not.** The run ended in a destructive bug of droidtop's
own making, recorded here so it is never repeated: a half-finished
container directory was cleaned up with Kotlin's `deleteRecursively`,
which follows symlinked directories. A Wine prefix contains
`dosdevices/z: -> /`. The walk left the prefix, and emptied what it
could reach on internal storage before it was stopped. gamenative's
`FileUtils.delete` refuses to descend into a symlink -- that is why it
exists, and it is what the cleanup uses now. **Never point a
walk-and-delete at a Wine prefix.**

### Destructive-operation audit (2026-09-02, after the incident)

Every recursive removal on the branch, checked for the same defect
class -- a delete that can leave the tree it was pointed at:

- **Wine-prefix cleanup** (`DroidtopPcGameRuntime.provision`): now goes
  through droidtop's own `SafeDelete.deleteWithin(imageFs.rootDir, ...)`
  rather than trusting a helper to refuse -- it proves the target is
  inside the ImageFs (parent canonicalized first, so a symlinked
  ancestor can't redirect it) and walks with `Files.walkFileTree`
  without `FOLLOW_LINKS`, which cannot enter a symlink. A refusal
  aborts provisioning, deleting nothing. Regression-tested against the
  incident's exact shape (`SafeDeleteTest`).
- **`DroidSpacesRuntime.destroy` / `CraneRootfsPuller` stale-rootfs
  wipe**: both previously hazardous. `destroy` used Kotlin's
  `deleteRecursively` on a Linux rootfs -- a tree full of symlinks,
  root-owned so the app-side walk couldn't even work, while the walk
  itself could still follow a rootfs symlink into shared storage. The
  puller used a bare root `rm -rf`, which never follows symlinks but
  DOES descend into a live mount -- and droidspaces bind-mounts the
  app-storage dir into every rootfs, with leaked instances confirmed
  to keep those mounts alive. Both now go through `RootfsDelete`:
  root `rm -rf`, refused while `/proc/mounts` shows anything mounted
  under the canonicalized path.
- **Archive extraction** (`TarCompressorUtils.extract`, gamenative
  fork `4ac3f2a8`): entry names are network input and were joined to
  the destination unchecked. Extraction now refuses any entry whose
  canonicalized parent is outside the destination (covers `../`
  traversal and writes routed through a symlink an earlier entry
  planted) and unlinks a symlink sitting where a regular file is about
  to be written. Archives keep their legitimate internal symlinks.
- **Safe by construction, left as they are**: `FileImageCache.clear` (flat `.tar`s in cache; since replaced by `OciImageStore.clear`, whose layout holds only crane-written plain files), `ThemeAssets`
  (APK-asset extraction -- assets cannot be symlinks),
  `BackupHelper` (entry names whitelisted to exact known filenames,
  staging dirs hold only droidtop-written flat files), and test-only
  temp dirs.

### Presentation and input: gamenative's renderer, on droidtop's launch display

A Wine guest does not draw Wayland surfaces, so it cannot be presented
the way desktop mode presents a container: it draws into an X server.
gamenative already has the whole Android side of that -- `XServerView`
(Vulkan/SurfaceFlinger) and `XServerViewGL` (the VirGL passthrough
path), the `VortekRendererComponent`/`VirGLRendererComponent` guests
talk to, `WinHandler` for XInput, `TouchpadView` and the X keyboard for
pointer and keys -- and the native libraries behind them
(`libvulkan_renderer.so`, `libvortekrenderer.so`, `libwinlator.so`) have
always been packaged by `:runtime-windows`. droidtop consumes that path
rather than writing a renderer; the alternative is a second renderer for
a window system that already has one.

Where it lives is droidtop's decision, and it is an **Activity**
(`WineGameActivity`). Gaming launches are placed on the configured
launch-target display through `LaunchDisplay`, which means
`ActivityOptions.setLaunchDisplayId` -- so the picture has to be
something Android can place on a display, which a surface inside the
shell's own window is not. That also keeps the Wine path identical in
shape to every other launch droidtop makes: build an intent, hand it to
`LaunchDisplay`, and let the started thing own its own lifetime.

The launch entry is unchanged. `PcGameRuntime`/`WineEngine` are still
the seam, so library entries, the prefix store and the strategy resolver
know nothing about any of this; the engine's `launch` now returns when
the game has been handed off rather than when it exits, because the
running game's lifetime belongs to the Activity presenting it.

The environment the guest runs in is one object with a lifetime
(`WineXSession`): the X server socket, the audio server, the GPU
renderer component and the guest launcher, all gamenative's own
components, chosen from the prefix's own fields. Audio follows
gamenative exactly -- PulseAudio by default, ALSA where the prefix says
so, each with the environment variable pointing at the socket its own
component binds. Two components are deliberately not started:
`SteamClientComponent` (droidtop is not a Steam client on this path) and
`WineRequestComponent` (it hands guest URL requests to an Epic OAuth
activity droidtop keeps out of its merged manifest).

A launch also **prepares the prefix before the guest starts in it**, and
that is not a detail: the prefix's drive letters are symlinks something
has to create, the D3D wrapper's DLLs and the Vulkan driver the Vortek
renderer loads are files something has to put where the guest can find
them, and which backend Wine itself hands audio to is a registry value
something has to write. A launch into a prefix none of that has happened
to reaches a game that cannot see its own folder, cannot create a device
and cannot make a sound. Every step is gamenative's own function, in
gamenative's own order (`WinePrefixPreparation`); they are `internal`
rather than private in the fork for exactly this reason, so droidtop's
launch and gamenative's run the same code instead of two copies of it.
Steam- and store-specific steps are deliberately left out.

The Android side reads the **prefix's own fields** wherever gamenative
does: which surface presents it and in which Vulkan present mode, whether
the pointer is visible and what a touch means, and which Windows input
API a pad reaches the game through (`WinHandler`'s preferred input API
and DirectInput mapping, plus the SDL hints a guest built on SDL reads).
A pad's B button reaches the game before it can end it: several
controllers report B as `KEYCODE_BACK`, so the controller bridge and the X
keyboard see the event first and only an unclaimed back press ends the
session.

Desktop mode is **out of scope here**. There a Windows program should
appear as a window among others inside the container's sway compositor,
which is a different presentation problem with a different answer;
nothing in this path assumes it and nothing in it should be stretched to
cover it.

Remaining: one Windows launch on hardware that is seen and played, and
an in-game menu over a running Windows game -- gamenative's own is a
Compose radial/quick menu wired through its game screen's state, so it is
a port rather than a move, and droidtop has no host hotkey of its own on
this path yet.

## 6. Input

One Wayland seat (`:input-seat`), fed from every physical source: touch,
gamepad-as-pointer, the second-screen trackpad/keyboard, or a lapdock's
physical peripherals. All normalized before reaching `:host-bridge`, so the
compositor only ever sees one logical pointer and keyboard.

- Second-screen trackpad interaction model (BUILT — §6c): the
  `AbsoluteTouchContext` (primary screen = absolute cursor position) /
  `RelativeTouchContext` (trackpad = relative deltas) split, which is
  Moonlight Android's, is now the split between `DesktopInputRouter`'s
  own touch path and `TrackpadGestureEngine`.
- Keyboard forwarding: reference KDE Connect Android's Remote Input plugin.
- **Second-screen persistent keyboard (Desktop mode's default second-screen
  role, §4)**: a fork of [Hacker's Keyboard](
  https://github.com/klausw/hackerskeyboard) (Apache-2.0, confirmed —
  compatible with droidtop's GPL-3.0 combined position the same way
  `:shell-default`'s Apache-2.0 fork already is, see §8), not a new
  keyboard built from scratch — it already has the physical-keyboard-style
  layout (dedicated Ctrl/Alt/Esc/arrow keys, unlike stock Android IMEs) a
  desktop-input surface actually wants. Forked in and adapted the same way
  as `:shell-default`, not kept as a passive `vendor/` reference. BUILT as
  a second-screen surface — see §6c, including what Android does and does
  not permit for a keyboard on a secondary display.
- **Do not assume Winlator/GameNative's input code is a safe base** —
  Winlator has a known, open, acknowledged gap in native/Bluetooth mouse
  pointer capture (issue #1555). This needs real design and testing effort,
  not inherited code.
- **A physical keyboard and mouse drive the shells too (decided
  2026-09-24).** Outside Desktop mode there is no seat to feed, and the
  platform already delivers a lapdock's or a Bluetooth keyboard's and
  mouse's events to droidtop's own window. A mouse is treated as a finger
  on droidtop's chrome: a click is a tap on the affordance under it,
  hover moves focus so pointer and focus stay one selection (the design
  language's rule), and the wheel scrolls a list or steps a themed
  widget one entry. A keyboard is treated as a pad through one table in
  `GamepadKeyMap`: the arrow keys are the D-pad, Enter is A, Escape is B,
  Tab and Shift+Tab are L1/R1, Space is X, Backspace is Y, the menu key
  is Select and F10 is R2 — so every hint row's promise holds for a
  keyboard user, and no screen carries a second key table. Text fields
  take typed characters ahead of that table while they have focus. In
  Desktop mode the same devices reach the container through the seat
  (§6b), and the shell's own chrome around the viewport still answers
  as above.
- **The keyboard is core, not Desktop's.** `:input-keyboard`'s IME serves
  every mode: it is what a text filter, a scraper login or the clipboard
  bridge (§6d) uses in Gaming and Launcher as much as a terminal in
  Desktop. It is therefore not a `ModePiece` and is never disabled with a
  mode (§2c); only the second-screen keyboard SURFACE (§6c) is Desktop's.

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

The output is sized to the view. In `surfaceChanged` the viewport asks the
compositor, through `HostBridge.setOutputSize`, to give its headless output
exactly the surface's size (a custom mode over
`wlr-output-management-unstable-v1`; a headless output accepts any), so a
captured frame lands 1:1 on the view. That is compositor-neutral: any
wlroots compositor implements the protocol, where `swaymsg` would have tied
the viewport to sway.

The fit stays `STRETCH`, because that is still what the present path does
until the new mode is applied (and permanently, for a compositor without
output management) — `presentPrimaryOutput` sets the buffer geometry to the
output size and SurfaceFlinger scales that buffer to fill the view's
bounds, per axis, with no letterboxing anywhere. Under `STRETCH` only the
ratio x/x_extent reaches the compositor, so a mismatch between the size
Kotlin assumes and the true output size cannot produce a scale error. A
`LETTERBOX` fit exists and is tested alongside it, for the moment the
present path grows an aspect-preserving mode; that mode makes the aspect
ratio load-bearing, so taking it requires plumbing the real output size up
from native first.

**The frame path's threading (rebuilt 2026-09-24).** `:host-bridge`'s native
client has one dispatch thread, and it owns everything the compositor's
events touch: the capture loop and the output configuration. It waits on
the display fd and an eventfd together (libwayland's
`prepare_read`/`read_events` protocol) and flushes before every wait;
starting and stopping a capture and requesting a size are tasks posted to
it, and input requests flush as they are made. The loop it replaced
blocked in `wl_display_dispatch()`, which flushes only when an event
arrives: a capture request made from the UI thread against an idle
compositor was never sent, `disconnect()` joined a thread that could wait
forever, and stopping a capture from the UI thread raced the frame
callback still drawing with it. Capture uses `copy_with_damage` (screencopy
v2+), so the compositor holds each frame until something on the output
changed and an idle desktop costs nothing; a buffer is reallocated when the
frame's geometry changes, not just its byte count.

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
it on). The second-screen trackpad surface of §6 is built on top of this
router's relative-motion path — see §6c.

### Desktop keeps the screen awake (Droidtop/tracker#97)

A desktop session is driven through the seat, which Android's screen-off timer does not count
as activity, so the primary panel could blank in the middle of typing on the second-screen
keyboard. `MainActivity` sets `FLAG_KEEP_SCREEN_ON` on its window exactly while the mode is
Desktop and a session is connected, and clears it otherwise. A window flag rather than a
wake lock: it needs no permission, holds only while that window is showing, and Android
releases it if the app dies or the window goes away, so nothing can leak a hold. It does not
stop the user turning the screen off with the power key.

## 6c. Second-screen input (built 2026-09-02)

Until now the second screen was output only, which §4 and §6 both name as
the point of the Dual-Screen Add-On and neither had. It is now an input
surface: droidtop's own keyboard above a trackpad, selectable per mode
against the companion/widgets surface it competes with.

### What Android permits for a keyboard on a secondary display

Established before designing, because the obvious approach does not work.
An IME's window is placed by the PLATFORM. Android chooses the display
from `WindowManager#getDisplayImePolicy()` for the display the focused app
is on, and the only outcomes are the focused app's display, the default
display, or nowhere; changing that policy needs a system permission, and
the platform additionally refuses to show an IME on displays it does not
own. "Persistent" is also not an IME concept — the soft input window is
shown when an editor asks and hidden when none does.

So a persistent second-screen keyboard **cannot be an IME window**, and
building one as though it could would ship something that silently does
nothing.

What it is instead: an ordinary droidtop window on the second screen
containing a real `LatinKeyboardView` — the fork's own key grid, themes
and `kbd_full` layout, with the function row, Ctrl, Alt, Esc and the arrow
cluster that §6a is about. `LatinIME.onEvaluateInputViewShown` returns
false while that surface is up, which is the platform's own mechanism for
"there is a real keyboard elsewhere" and is what stops droidtop covering
the primary screen with a second one.

Keys are delivered on a HARDWARE-keyboard model, one model for both
destinations: a key is pressed and released, and the far side decides what
that produces.

- **Into a container** (Desktop mode): each key becomes an Android keycode
  handed to `DesktopInputRouter`, which already turns those into evdev
  keys. The compositor's XKB keymap applies the layout and its own
  auto-repeat, exactly as for a lapdock's physical keyboard. No IME
  involved, no editor focus needed.
- **Into an Android app** (Gaming and Standard): each key becomes an
  `InputConnection.sendKeyEvent`, whose contract is precisely "as though a
  hardware key was pressed". This needs droidtop's IME to be the selected
  input method (that is what supplies the connection) and an editor to
  have focus (that is what it points at). Both are the platform's
  conditions, not droidtop's, and the surface says which one is missing
  rather than dropping keystrokes.

Nothing calls into `LatinIME`'s internals: with the on-primary input view
suppressed those internals have no view to work against.

What the hardware-key model gives up, stated rather than hidden: no
autocorrect, no suggestion strip, no dead-key composition, and the key
labels on this surface do not relabel for Shift. For a keyboard whose
purpose is driving a terminal, Wine and a desktop (§6a) those are the
right things to lose; a user who wants them turns this surface off and
uses the ordinary on-primary keyboard.

The translation is Hacker's Keyboard's own encoding read back, not a new
table: the fork already encodes every non-printable key as the NEGATED
Android keycode (`KEYCODE_ESCAPE` is -111 against Android's 111,
`KEYCODE_FKEY_F1` is -131 against `KEYCODE_F1`), and printable characters
go through Android's own `KeyCharacterMap` rather than a hand-written
list. From there `EvdevKeys` and the compositor's keymap finish the chain.
Three links, no branch duplicating another.

### The trackpad, and what it means in each mode

Relative, not absolute: the screens are different sizes and the user is
not pointing at the second screen. `TrackpadGestureEngine` speaks
millimetres, so every threshold is a property of finger travel rather than
of a panel's pixel density, and `MmScale` discards the implausible `xdpi`
values panels really do report.

The gesture model is libinput's, deliberately not droidtop's own: one
finger moves, one-finger tap is left click, two-finger tap is right click,
three-finger tap is middle click, two fingers scroll, and tap-then-touch
again drags with the button held. Drag lock, bottom-edge software buttons
and edge scrolling are all omitted for the reasons libinput omits or
supersedes them. A trackpad that behaves like every other trackpad needs
no learning.

Acceleration is libinput's adaptive profile reduced to its two knees: a
flat slow plateau so a slow finger can land on a small target, a flat fast
plateau so a flick crosses the screen, a straight ramp between. Gain is
derived from the DESTINATION output's width — 160 mm of finger travel
crosses it once at factor 1.0 — so the same hand movement crosses a
1080-wide container output and a 2560-wide lapdock alike.

**Where the pointer goes is not the same question in each mode**, and the
two answers are different features rather than one feature configured
twice:

- **Desktop**: a real pointer in the primary container, through the same
  single `InputSeat` the desktop surface's own touch and keyboard use.
  `InputSeats` now hands out that one seat per live `HostBridge` instead
  of `DesktopShell` constructing its own, because two surfaces now drive
  it and two seats over one bridge would break the one-normalized-seat
  invariant §6 exists for.
- **Gaming / Standard**: there is no pointer, and droidtop cannot make
  one — moving a system cursor over another app's window needs
  `INJECT_EVENTS`, a signature permission, and the Gaming shell is
  Compose focus navigation driven by a D-pad, so a drawn arrow would have
  nothing to click. The trackpad drives what the shell actually
  understands: `DirectionalStepper` quantises travel into focus steps
  (with the dominant axis locked, so a mostly-horizontal swipe cannot
  jump a row), a tap is confirm and a two-finger tap is back. Those reach
  the shell as ordinary `Activity.dispatchKeyEvent` calls into droidtop's
  own window, which needs no permission, and they are read through the
  existing `GamepadKeyMap` vocabulary rather than a second mapping.

### What is verified, and what is not

Unit-tested, in `:input-seat` and `:input-keyboard`: the gesture state
machine (including the cases that are invisible on a screenshot and fatal
in use — a button left stuck by a second finger landing mid-drag, a tap
that fires twice, a cursor teleporting when a finger is added), the
acceleration curve, the millimetre scale's fallbacks, the step quantiser's
axis lock, and the key translation including modifier latching and
auto-repeat suppression.

NOT verified, because it needs the hardware: that the addon panel reports
a usable `xdpi`; that the keyboard lays out correctly at 45% of a
1080x1920 panel; that suppressing the on-primary input view behaves as
documented on this device; that `InputConnection.sendKeyEvent` reaches the
editor the user expects; and every latency and feel judgement, which is
the whole reason the acceleration curve is written as constants that can
be read and changed rather than tuned by hand.

## 6d. Clipboard bridge, host↔container (built 2026-09-02)

Text copied in Android pastes in the container, and text copied in the
container pastes in Android. Clipboard forwarding is friction on ordinary
work rather than an occasional need, which is also why Crostini treats it
as core rather than polish.

**The Wayland seam is `ext-data-control-v1`**, bound by `:host-bridge`'s
native client next to the screencopy and virtual-input protocols it
already speaks.

- `wl_data_device` — the ordinary clipboard protocol — is not merely
  inconvenient here, it is *unavailable*: it only delivers a selection to
  the client holding keyboard focus on one of its own surfaces, and
  host-bridge has no surface at all. It is a screencopy + virtual-input
  client by design (§2), so it can never hold focus.
- `ext-data-control-v1` exists for exactly this: a privileged client that
  observes and sets a seat's selection without focus — the
  clipboard-manager role.
- It is the stabilised successor to `wlr-data-control-unstable-v1`.
  `vendor/sway` creates **both** globals unconditionally
  (`sway/server.c`), so the ext one is always present in the compositor
  droidtop itself provisions and no runtime fallback to the deprecated
  zwlr one is carried. One protocol, one code path.

**Neither direction polls.** Android reports changes through
`OnPrimaryClipChangedListener`; the compositor reports them through the
protocol's own `selection` event. The single non-event read is one read
on regaining window focus, which exists because of the restriction below,
not as a disguised timer.

**What happens when Android refuses a read.** From Android 10, only the
focused app or the owner of the current input method may read the
clipboard — everyone else gets null, silently, and from Android 12 a
successful read shows the user a toast. droidtop satisfies the second
clause by shipping its own IME (§6a); that was already stated there as a
real benefit of the fork, and this is the thing that consumes it. A
refused read is skipped and logged, explicitly **not** treated as "the
clipboard was cleared" and pushed to the container as an empty selection.
The container→Android direction has no such gate: `setPrimaryClip` is not
focus-restricted. The bridge is owned by `MainActivity` rather than by
`DesktopSessionService` for the same reason — window focus is an Activity
fact and a Service has none to report.

**The keyboard caveat is shown, not hidden (Droidtop/tracker#99).** While another keyboard
than droidtop's own is active, a copy made in an Android app is read only when droidtop's
window next has focus (the catch-up read above), so a paste can lag a copy. The bridge
publishes that state (`ClipboardBridge.androidReadsLive`, checked when it starts and on every
focus change, which is when a keyboard switch is seen), and the Desktop taskbar shows a quiet
"Clipboard: on focus" entry only in that state. It carries `ClipboardAccess.WHY_BLOCKED` and
opens the system keyboard switcher (§6a: the system draws it, droidtop changes nothing). It is
the one explanation surface, and it never blocks.

**Scope, and what is deliberately not bridged.** Text only. A null
selection from the container is not mirrored: wiping the user's phone
clipboard because a container application exited is destructive, and
nothing about "the container has no selection" implies they wanted it.
The middle-click primary selection is not bridged either — Android has no
counterpart to bridge it to. Both directions cap at 1 MiB and drop rather
than truncate, so nothing silently lies about what was copied.

**Echo suppression is one object, not two flags.** Pushing to the
container makes the compositor announce "a new selection"; setting
Android's clipboard fires droidtop's own change listener. Both directions
run through the same `ClipboardSync`, and because they share its one
piece of state, each direction's echo is recognised as already-synced
text and dropped. That is the whole loop-prevention mechanism and it
lives in exactly one place, under unit test.

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
  source tree compile), not assumed upfront. The fork does not carry that
  source; upstream Murine Launcher and AOSP Launcher3 have it.
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
  `GamingSettingsCatalog`): which settings exist, their grouping and
  order, their live values, and their single write path each. Every UI
  surface just chromes a catalog in its own visual context — the unified
  Preference screen renders it via `CatalogPreferenceBuilder`
  (`:shell-default`), and Gaming's own in-shell Settings section
  renders the SAME catalog with pure gamepad input
  (`SettingsCatalogView`, `:shell-gamepad`) so cycling sections with L/R
  never leaves the Gaming context (per direction: browsing sections
  must maintain context; explicitly activating a navigation item is the
  one thing that may switch surfaces). Catalog layout convention, every
  mode: the droidtop-wide "global" group first (a surface whose chrome
  already exposes global settings — SettingsActivity's persistent
  action-bar item — skips it by group id), the current mode's own
  settings next, shortcuts to the OTHER modes' settings last, so nobody
  ever switches modes just to reach a setting. Renderers may substitute
  a native fulfillment for an item by its stable id (Gaming performs
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
  gone. No catalog item blocks the main thread (audit 2026-09-24, C4):
  an action that touches a database or disk is an `AsyncActionItem`
  (run off the main thread, with the same optional `confirmTitle` as
  `ActionItem`), and `TextInputItem.onChange` is a main-safe suspend
  call; every renderer re-reads the screen only after either returns,
  so a list shows the write at once. Never `runBlocking` in a catalog
  callback. A screen that edits one row re-reads that row on every
  build and, once it is gone, shows only a row saying so: its fields
  would write the deleted row back (platform edit, 2026-09-24). Its
  field writes are Room `@Update`, never an insert-or-replace, so an
  edit committed after a delete changes nothing. Global settings and Desktop mode's settings are
  catalogs too (`DroidtopWideSettings`, screens `global_settings`/`desktop_settings`), fixed onto
  the shared model in the same UI pass (H4) that moved them off launcher3 preference XML — the
  paragraph that used to say this was still XML is stale; `SettingsGlobalFragment`/
  `SettingsDesktopFragment` and Gaming's in-shell Settings both chrome the same catalog now. What
  is still stock launcher3 preferences, deliberately, is only Standard mode's OWN launcher pages
  (icons, drawer, home screen, rotation) — the fork's settings for the fork's own surface, not a
  droidtop-wide setting, so migrating them onto the catalog model is not the goal.
  - **Settings polish pass (settingsui, 2026-09-25), what was already real versus what this
    found stale.** Owner direction was "significant improvements to polish, layout, and all of
    that" across every settings surface. Auditing against the catalog architecture above and
    the 2026-09-24 UI assessment (H1 to H8) found the shell/row-component consolidation this
    would otherwise have built had already landed the same day, in commits this pass rebased
    onto rather than duplicated: the shared catalog model and its two renderers (H4); a nested
    sub-screen restoring the list where it was instead of dropping the user at the top (H3,
    `a4b0e295`); Browse themes claiming its own hint row instead of stacking under the shell's
    (`eabd684d`); the mode switcher drawing a real focus ring on its rows (`c31af69b`). Build
    861, the one `dq-onboard-02`'s rig report is against, was cut before all three landed
    (06:55 vs 09:09-09:13), so that report's "stale sub-page"/"stacked hint row"/"no focus ring"
    findings are already fixed on `main` and not evidence of a live gap; only a fresh rig item
    against a post-09:13 build confirms it (`dq-settingsui-01`). What this pass found and fixed
    instead: the paragraph above claiming Global/Desktop settings were still XML — stale, left
    over from before H4 landed and corrected in this change.
  - **Still open, not built in this pass**: search across settings (the design target of typing
    a few letters and jumping to any row in any catalog, across every screen the registry
    knows) — the catalog model's per-screen, lazily-built `groups` lambdas make a flat searchable
    index a real feature (walk every registered `CatalogScreen`, force-build its groups, index
    row titles/subtitles), not a small addition, and was not attempted here rather than shipped
    partial. A `CatalogScreen`/row family is exactly the seam agent `scrape`'s SteamGridDB
    credential row and agent `plugins`' plugin-contributed integration rows both already use —
    no new mechanism needed for either. (Built two days later, on the settings home only, by
    `SettingsSearchIndex` — see 7k's "Search across settings" above; the touch surface's own
    entry point followed in the H4 shared-row-language pass, 2026-09-26.)
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
  the `SurfaceView` frame-passthrough viewport (via `:host-bridge`'s
  `HostBridge`), and around it a taskbar, a Start menu and a tray
  (`DesktopShell`). **The taskbar IS the cross-container task manager**
  (decided 2026-09-24, built 2026-09-28 — Droidtop/tracker#94):
  `:host-bridge`'s native Wayland client binds
  `wlr-foreign-toplevel-management-unstable-v1` beside screencopy and the
  virtual-input protocols (`wayland_client.cpp`'s `ToplevelState`/
  `kToplevelManagerListener`), and the taskbar (`TaskbarWindowList` in
  `DesktopShell.kt`) lists every toplevel the compositor reports — title,
  app id, activated/minimized/maximized/fullscreen state — tap activates
  the row (and un-minimizes it first if needed), a second tap on the
  already-activated row minimizes it, and its long-press menu offers
  Restore/Minimize and Close (`zwlr_foreign_toplevel_handle_v1`'s
  activate/set_minimized/unset_minimized/close requests). The minimize
  affordances are compositor-conditional (2026-09-29, Droidtop/tracker#145):
  sway ignores set_minimized/unset_minimized outright — under it the
  second-tap minimize and the menu's Minimize did nothing (rig,
  fix2-t5.png in verify-2026-09-29) — while labwc implements the request
  (its src/foreign-toplevel/wlr-foreign.c handle_request_minimize calls
  view_minimize), and the protocol has no capability query to ask with,
  so the session publishes the compositor it started
  (`DesktopSessionState.Connected.compositorCommand`, straight from
  CompositorProvisioning's plan) and under sway the taskbar drops the
  second-tap minimize and hides the menu's Restore/Minimize row rather
  than offer an action the compositor discards. The manager's
  listener is attached in the registry callback at bind time: the compositor
  answers a bind with one `toplevel` event per window already open, and a
  listener added after connect()'s round trips lost those, so a desktop
  re-entered with apps running showed no rows (Droidtop/tracker#94). The window list is the taskbar's one weighted slot, so the fixed buttons, tray and clock take what they need first: on a 1080p tablet-class display the stock Material buttons' padding left the slot no width at all and no row could show, so the taskbar buttons use narrow padding (`TaskbarButton`) and the clock never wraps. **Not built**:
  which container a toplevel came from (there is only ever one primary
  container today, so nothing distinguishes this yet), moving a toplevel to
  another output (the protocol itself has no such request — only
  activate/set_minimized/unset_minimized/close/set_fullscreen/
  set_rectangle — and host-bridge's own capture/output handling is still
  single-output-only besides, see `WaylandGlobals`'s comment in
  `wayland_client.cpp`; real "move to output" needs real multi-output
  support in host-bridge first), and folding Android tasks droidtop itself
  launched onto the desktop's display (a game, a Wine activity) into the
  same bar via `LaunchDisplay`'s record — all three are backlog, not
  shipped, corrected here after an earlier draft of this section described
  them as already decided/built. The Start menu lists the primary
  container's installed applications and the
  library's entries (§2a, §2b) until the container-side launcher exists,
  and is replaced by it, not joined. The live desktop connection is
  `DesktopSessionService` (`:app`), over either backend (§3).
- **`:shell-gamepad` ("Gaming")** — full-screen, D-pad-navigable, reading
  the same `Library`; optional and toggleable, never the assumed default
  experience. **Superseded design decision (2026-08-29): a single real
  paradigm for game browsing specifically, not two competing whole-shell
  designs.** An earlier draft of this section described "multiple
  selectable UI paradigms" (a visuals-first artwork-carousel design
  alongside a separate grid/list one, presented as two whole alternate
  shells) as the real intended shape — that framing is now stale, but
  Daijishō's own real INFLUENCE on Gaming's overall structure is not
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
  the Gaming settings catalog rendered inside the shell
  (`SettingsCatalogView`), with the pad's focus, the hint row and B back,
  as the settings architecture above describes. It is not the Standard
  shell's `SettingsActivity`: that surface chromes the same catalogs for
  touch (`SettingsGamingFragment` in `:shell-default`), so a setting
  changed in either place is the same setting. **Browse themes** opens
  the Compose `ThemeBrowserScreen` from inside that view, because
  per-theme screenshot previews need a different interaction shape than a
  list of rows. Each mode's settings show that mode's own settings first,
  with shortcuts to the other modes' settings at the bottom.

  **H4 is not satisfied by the catalog migration alone (decided 2026-09-26,
  owner delegated).** "H4 fixed" means BOTH renderers use the same row
  language: the same icons, the same grouping, a visible focus ring, the hint
  row, and search -- not merely reading from the shared `DroidtopWideSettings`
  catalog. A screen that pulls its rows from the catalog but still renders
  them as a stock Material `Preference` list, with no focus ring and no hint
  row, is still broken under H4 even though the data model is already unified.
  `p1-dt-h4-rig-verify` checks this with fresh rig screenshots of Global
  settings, Desktop mode's settings and the Standard settings surface.

  **Global settings and Desktop mode's settings are catalogs**
  (`DroidtopWideSettings` in `:app`, registered as `global_settings` and
  `desktop_settings`), like every other droidtop setting. The Gaming shell
  opens them as its own nested settings pages, with focus, the hint row
  and B back to Settings; `SettingsGlobalFragment` and
  `SettingsDesktopFragment` only chrome the same catalogs for the
  Standard settings surface, where Global stays the persistent action-bar
  item on every screen. They were launcher3 preference XML until the UI
  pass of 2026-09-24 (H4): from the Gaming shell they opened a stock
  Android list that the pad could not drive. Global holds which HOME role
  droidtop has, **Modes** (the default mode, offering only enabled modes,
  and the Desktop/Gaming enable switches; a disabled mode is absent from
  `BackButtonMenu`'s shell switcher, not greyed out) and **Data** (Rerun
  onboarding from `OnboardingStep.WELCOME`; Back up/Restore settings, a
  JSON export/import of the one shared `"com.android.launcher3.prefs"`
  file through the system file picker, `DocumentPickItem`, and NOT games,
  ROMs, downloaded themes or folder grants, which need consent again
  rather than a silent restore). **What a backup holds (decided
  2026-09-24):** the settings file, the game records under
  `files/library/` (§7g), and the person's own stores exported as JSON —
  play history and sessions, favourites, collections and memberships,
  scraped metadata and metadata edits — in one archive, so restoring on
  a new device brings back everything a person did in droidtop and
  nothing that needs a grant; the row is named "Back up droidtop" for
  that reason, and Restore says what it will and will not bring back
  before it runs. The index is rebuilt from the restored records (Data
  › Rebuild the library index), never copied. Android's own Settings are reached from
  Settings > Android settings, so Global has no second shortcut to them.
  Standard mode's launcher pages (icons, drawer, home screen) stay stock
  launcher3 preferences. A
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

What droidtop owns is the integration only: `REMOTE_STREAM` entries sit
with the apps (`LibraryKinds.APPS`) and render like any other entry. No
code produces one yet; they come from windowcast's own client, and
launching one hands off to it, the way a console game hands off to its
emulator. No droidtop module encodes, decodes or transports a stream.

### No PC-side helper in droidtop

droidtop carries no program for the gaming PC. The Go scaffold that once
sat in `pc-helper/` (a Sunshine `POST /api/apps` client and a Steam install
trigger) was built for the removed Sunshine-specific streaming path, was
never built or run, and was deleted: anything that runs on the remote PC
belongs to windowcast. The one finding worth keeping for whoever builds it
there: a remote Steam install has no zero-touch first-time path.
`steam://install/<appid>` needs Steam running and logged in on that PC and
shows its own UI; SteamCmd runs unattended only after a one-time
interactive Steam Guard login on that machine, and its default layout is
not the client's `steamapps/common/`. Product copy must not promise more.

## 7b. Onboarding

**The bar: ready to use at the first home screen (directed 2026-09-24).** Onboarding covers,
clearly and in order, everything a person needs to be up and running the moment they reach the
home page of the default view they chose: games found, controls understood, the way to launch,
switch modes and reach the Quick Menu known. Anything that leaves a newcomer thinking "I have to
look in the settings" or "I don't know how to do this" is a defect to fix, not a documentation
gap. There is no first-run tutorial (removed, owner decision 2026-09-30, Droidtop/tracker#150):
what a newcomer needs is carried by the screens themselves, the hint row on every screen names the
live buttons and is also the touch route to them, and the rig's new-user passes, which try to
break onboarding on purpose, are where a missing hint is found and fixed.

**Themes are chosen during setup (directed 2026-09-24).** Onboarding includes the theme
downloader (the same Browse themes screen Settings opens, one mechanism), so a person can pick and
download another ES-DE theme before they first see the Gaming shell, not only the bundled default.
Built 2026-09-25: the Appearance step's "Download more themes" draws `ThemeBrowserScreen` in place of the
step, and the theme list is read again when it returns. The downloader's git library is JGit 5.13,
the last line built for Java 8: JGit 6 and 7 call Java 11+ methods (`InputStream.readNBytes(int)`)
that Android has only from API 33, and both "Download more themes" and Settings > Browse themes crashed
Android 9 with NoSuchMethodError (dq-onboard-01). A library method the running Android lacks is a
failed download, never a crash (`ThemeDownloader` catches `LinkageError`). The browser has its own
hint row.

**The theme index is fetched as one raw HTTPS file, not cloned (decided 2026-09-29).** The index
(`gitlab.com/es-de/themes/themes-list.git`) used to be a full JGit clone: JGit 5.13 has no
`CloneCommand.setDepth` (a JGit 6.4 addition), a shallow clone is what real ES-DE asks libgit2
for, and the full-history clone measured about four minutes on the rig. Bumping JGit to get
`setDepth` is not safe: 6.4.0, the first line with it (confirmed by reading the real jar's
`CloneCommand`), still cannot run on minSdk 26 -- its own clone/checkout path (`DirCacheCheckout`)
calls `InputStream.transferTo` and its `StringUtils`/`CommitConfig` call `String.strip`/
`stripTrailing`, all Android API 33 additions; the latest `desugar_jdk_libs` (2.1.5) bridges
`transferTo` but ships no shim for the String methods, and the build carries no core-library
desugaring anyway -- and 7.x is the line that crashed dq-onboard-01 above (its
`InputStream.readNBytes`). So the pin stays and the index changed
route instead: `ThemeDownloader.syncThemesList` downloads `themes.json` alone, from GitLab's
raw-file endpoint of the same repo (master, its own default branch; 153 KB measured), validates
it as JSON before it replaces the old list, reports UP_TO_DATE when the bytes did not change, and
feeds real byte counts into the same `withStallWatchdog` progress/stall wiring the clones use --
one progress and stall mechanism for both routes. The git clone path stays for the per-theme
downloads and the theme patches, where git semantics (fast-forward-only, divergence reporting) are
actually needed; nothing clones the index anymore, so no second index mechanism exists. Two
follow-ons fall out of the same route: the downloaded file's own mtime is now what "a week old"
reads (the clone's `FETCH_HEAD` used to), and the browser's screenshot previews stream from the
index repo's raw endpoint on demand through Coil (`coil-network-okhttp`, the network fetcher
coil3 does not bundle; disk-cached after first view) instead of arriving with the index -- a
one-time cleanup drops an old install's index-clone `.git` history and `screenshots/` tree after
its first successful raw fetch.

**Onboarding survives becoming Home (decided 2026-09-25).** droidtop is a Home candidate from the
moment it is installed, and a person may make it the Home app before or during setup: in Android's
chooser, in Settings > Default apps, or through the Home screen step itself. So every droidtop entry
point asks `OnboardingGate.resumeIfUnfinished` first: the Launcher3 fork (cold start and every Home
press), the Alternative forwarder, droidtop's icon and `MainActivity`. While setup is unfinished each
of them hands over to the running onboarding (one instance: `CLEAR_TOP` with `SINGLE_TOP`) instead of
drawing itself; the rig found the home screen drawn over an unfinished setup with nothing saying so,
and only Recents (labelled "Games") or the icon (which restarted at step 1) as ways back
(dq-coordinator-24). A first run is written down as it goes (`OnboardingProgress`, the run's answers
as JSON; folders, grants and the home role are real state already), so a new process resumes at the
same step with the same answers; one force-stop used to keep only the folder list. "Leave setup" is a
real answer: for the rest of that process the Home key shows the home screen, and the next process,
or a tap on droidtop's icon, resumes. The gate no longer runs from `Application.onCreate`, which also
starts for a broadcast, a bound service or a pinned game. Recents names the task "droidtop setup".

### What onboarding is for

droidtop onboards the **device**, not one mode. Configuring a mode and choosing the
default are independent questions, so a person who wants two modes must not have to
finish in Settings what first run started. Every step is independently skippable and
independently re-enterable later from the Settings row that owns it
(`OnboardingActivity.EXTRA_START_STEP`); re-entering runs that one step and returns.

The flow onboards in this order: how the Android home screen behaves, what else to set
up, the permissions and folders those choices need, the input and appearance they will be
used through, and finally which of the things actually configured droidtop opens into.

### The frame every step renders into

Onboarding is one scaffold, not a set of unrelated screens. The scaffold owns:

- **Progress**, always visible, counting parts that never change (decided 2026-09-25): "Part 3 of
  6: What to set up", over six segments (Welcome, Home screen, What to set up, Your games, Controls
  and look, Finish). The steps inside a part come and go with the answers; the parts do not, and a
  part a run has nothing to ask in is passed over and still counted. Counting steps against the plan
  was accurate and still read as broken, because the total moved as answers came in ("1 of 7", then
  "2 of 8", "4 of 10", and "of 7" again after a restart; dq-coordinator-24). The plan is still the
  pipeline.
- **Pad-first** (decided 2026-09-25). The pad's selection starts on the step's forward action, or on
  its first answer when the step asks something (asked again for a moment, since some answers are
  read off the main thread, then the forward action). Every button and answer takes the pad's A
  and draws the shell's accent ring when it has focus. Every control is `padSelectable` (built
  2026-09-25, after dq-onboard-01): ONE focus target that holds focus in touch mode too, a key
  handler for A, Enter and DPAD_CENTER, a tap, and button semantics. Compose's `clickable` answers
  Enter and DPAD_CENTER but never BUTTON_A, and in touch mode its focus target refuses focus, so
  the initial selection never landed, the first pad press only brought the ring back, the
  first A hit "Skip", and a tapped hint pill dispatched its key into a window with
  nothing focused; the window owns the pad (`ownPadButtons`), so B is Back; and
  a hint row (A Select, B Back) is the touch route to both. On the rig, A did nothing on Welcome, no
  focus showed anywhere, and D-pad Down went up to Back (dq-coordinator-24).
  **Fixed:** `ownPadButtons` now only consumes B; A reaches the focused element via
  `padSelectable`, so hint-bar taps work like real pad presses.
- **Back**, always available, stepping back through the path actually taken. System Back
  is the same control. Leaving onboarding is a deliberate act with a confirmation — never
  one Back press, which today drops to the system home.
- A **title**, a **body capped to a readable measure** (roughly 72 characters, whatever the
  window is), a **content slot**, and a **fixed action area**.
- The action area carries the step's own advance at full weight. A step's Next is never a
  text link sitting below a louder button that does something smaller.
- **One way forward.** A step never offers two actions that both move on. The forward action
  is one action, and its label is the whole affordance: before the step has been answered it
  IS the skip and says so ("Skip this step"), once it is answered it is "Next", and a step
  entered on its own from its Settings row has nothing after it, so it is "Done"
  (`onboardingForwardLabel`). A disabled "Next" beside a working "Skip for now", or a text
  "Skip" beside a filled "Next", are the same fault: two ways forward, with nothing saying
  which one moves on without answering (rig, build 547, Controller and Desktop setup).
  A step's second action is therefore never a second way forward. It is the step's own WORK,
  which stays on the step — and a step whose work is a hand-off to Android's own screens
  (Storage, Keyboard) keeps that hand-off as its primary, with the one forward action beside
  it as the skip until the hand-off has actually taken, at which point the forward action
  becomes the primary "Next" and the hand-off is done with.
- **A step that cannot be skipped shows no forward action until it is answered**, and its own
  answer rows are the way on (`onboardingForwardLabelWhenAnswerRequired`). The forward action
  is always actionable or it is not drawn: a greyed "Next" is an action that says "go on"
  while refusing to, and on a step with no other action it leaves nothing on the screen that
  can be pressed at all (rig, build 548, step 2 of 7, "Your Android home screen"). Which
  steps those are is decided by whether skipping has a MEANING for that question: the
  home-screen step has a row that already means "not now" ("Neither, for now"), so a skip
  beside it would be droidtop answering for the person in a second way; the "which launcher"
  step has no default at all, because droidtop never picks somebody's launcher. Every other
  step is skippable and says so.
- Content is top-aligned and the action area is docked at the bottom in portrait, bottom-right
  in landscape. Nothing is vertically centred in a tall window.
- One gutter, the shell's own (`ShellWindow.edgePadding`), one spacing scale, one type scale,
  one colour source — section 7j. Touch targets meet `minTouchTarget` in every orientation,
  not only when the window is touch-first.
- Onboarding is dark, like the shell it hands over to; it does not follow the system
  light/dark setting into a white first run.

### The one choice component

Every question with mutually exclusive answers — the home-screen choice, the launcher list,
the distro/compositor list, the theme list, the default mode — is the same selectable row:
full width, at least 56dp, a leading icon where the thing has one (an app's own icon, a
theme's thumbnail), a title, one line of supporting text, and a real selected state. Equal
options are equally weighted; droidtop never renders three equal answers as two filled
buttons and a link. The component is the shell's existing menu row anatomy
(section 7j), not a third one invented here.

### The steps

- **Welcome.** Says what droidtop can turn this device into and that every choice is
  changeable later. Carries droidtop's own mark. It SAYS it: the three surfaces are prose,
  not controls. Nothing on this step is selectable, because nothing here is a choice — what
  to set up is asked two steps later, by the one choice component, and setting the default
  mode is asked at the end. Drawn as filled accent chips, those three words were a selector
  that ignored every tap and could not be reached by the D-pad, because there was nothing
  behind them to reach (rig, build 547). The rule this states generally: a shape that says
  "pick one" appears only where one can be picked.
- **Home screen.** How the home screen behaves when Home is pressed: droidtop's own Standard
  launcher, Alternative (droidtop holds the HOME role and forwards to a launcher the person
  already has), or neither, in which case droidtop claims no `CATEGORY_HOME` role.
  droidtop's one icon (§2c, "One droidtop icon") is in whichever launcher is the home screen,
  droidtop's own included, and opens the default or last-used mode like any other app.
  - *Standard* points at the Standard shell's own settings rather than re-inventing them,
    and returns to onboarding afterwards. Its work is making droidtop the Home app, which only
    Android can do (§2c, "Holding the role"): "Make droidtop the Home app" hands over to
    Android's own request, the forward action is the skip beside it until Android says droidtop
    is Home, and the summary lists Home as not done while it is not.
  - *Alternative* lists the installed home activities with their icons and their application
    labels — never a class name, never a label that names nothing.
- **Anything else to set up.** Desktop and Gaming, each with a line saying what setting it
  up involves; the step says what leaving one unticked does. Leaving both unticked is a valid
  answer and says so. The ticks ARE the mode switches (§2c): when onboarding finishes, a
  ticked mode is on and an unticked one is off and runs nothing
  (`appModesOnAfterOnboarding`). They are written only at the end, so leaving part-way
  changes no mode. Two refinements: Desktop ticked on a device whose capability check failed
  stays off, since it could only open onto its own failure; and the mode onboarding opens
  into is on, which matters only when nothing at all was set up and that mode is Gaming. A
  first run starts with nothing ticked; a rerun starts from the modes that are on, so walking
  through it again changes nothing that is not changed on the way.
- **Desktop setup.** States the root situation first, as a statement a person can act on: what
  was found, what it means, and what to do about it — not a backend error string. When the
  mode cannot run on this device, droidtop says so and does not present a choice underneath
  that the person cannot use. When it can, the distro-and-compositor list is the one choice
  component, each entry named and described rather than shown as an id. droidtop never
  pre-selects an image the person did not choose; Skip is the honest "no choice yet".
- **Storage.** Skipped outright when the permission is already held. Otherwise the rationale
  comes **before** the prompt and says what droidtop reads (the game folders you name) and
  what it does not, per API level:
  - API 26–29: the platform's own order — already granted, then
    `shouldShowRequestPermissionRationale`, then the rationale, then the request. On denial,
    name the feature that is now unavailable, continue, and do not ask again.
  - API 30+: all-files access is granted on Android's own Settings screen. Say that droidtop
    cannot grant it, say what to turn on, hand over, and re-check the real state on return
    rather than trusting a result code.
  - The skip label describes the consequence, not a motive the person may not hold.
  - The wording (first human tester, 2026-09-29, Droidtop/tracker#150): the page points at the next
    step ("droidtop will only read the folders you choose in the next step") and says what the
    button does (open Android's settings, turn it on, come back). It never claims droidtop CANNOT
    read other data, since all-files access is exactly the ability to; it says what droidtop reads.
- **Game folders.** The single name for this concept, everywhere in droidtop. Two routes, both
  first-class: the system picker, and a typed path for what the picker cannot reach (an
  emulator's host share, a mount a rooted device adds, a USB drive), validated for real before
  it is stored. Readable folders the picker cannot offer are listed under "Found on this device"
  (directories under `/storage` other than the emulated internal storage, and under
  `/mnt/windows`, where emulators mount a host share), each with Add; the typed path's example
  names no folder, since a newcomer had to already know the share's path (dq-coordinator-24).
  The system picker's button ("Add a folder", filled) sits with the typed-path field, not in the
  action area beside Next, so the two ways to add are one place; the path box gives up focus (and
  the keyboard) before the picker opens, and the folder list is read again on every resume, so a
  pick shows at once. An invalid-path error shows only after a path was tried. Adding a folder starts the library's walk of it at once (§2c). Each added folder is a row
  showing the path, what the scan found under it, and a way to remove it. The step reports the result of the scan; a folder that yields nothing is
  a fact the person learns here, not after onboarding.
- **No games yet.** When nothing is found, droidtop offers concrete repairs rather than an
  empty library, following ES-DE: choose a different folder, generate the conventional
  `roms/<system>` plus `bios` layout under ES-DE's own system ids and report what was created,
  or continue with an empty library. Generating is safe to re-run and never overwrites an
  existing folder or assignment.
- **Controller.** Reports the attached pad by name, takes one press to confirm the mapping,
  and offers the A/B swap as an explicit question rather than a setting to discover. Skippable,
  and says so. Built 2026-09-17; three things decided in building it:
  - Detection is the shell's own (`ControllerPrefs.attachedControllers`), and the Quick Menu's
    status header asks the same function. Onboarding never gets a detector of its own.
  - The press check names the button by POSITION ("the bottom face button"), because that is
    what Android's key codes actually mean and the whole point of the question below it is
    that droidtop does not know what is printed on the pad.
  - The swap is REAL, not a note for later: `ControllerPrefs.swapConfirmCancel` is applied in
    one place, `GamepadKeyMap.applySwap`, to the MEANING of a press. `actionFor` swaps what A
    and B mean; `keyCodeFor` and `labelFor` answer in physical terms (which button to press,
    which letter to draw in a hint) so a touch affordance and a help row both name the button
    that now confirms. `BACK` is untouched: the hardware back key is not a face button, and a
    person who swapped their face buttons did not ask for it to start confirming. The value is
    loaded once at shell start and on every write, because `actionFor` is on every screen's key
    path and has no `Context`.
  - The step is not conditional on Gaming: the pad is how the shell itself is driven. The
    Settings row that owns it is Input > Controller, which re-enters this same step.
- **Appearance.** Built 2026-09-17. Themes as the one choice component with a real rendered
  preview, named by display name — the theme's own `<themeName>`, never a directory id, and never a theme's own
  untranslated capability label (a `capabilities.xml` declares one `<label>` per language, so
  the label is resolved for the running language with `en_US` as the floor, as ES-DE does). On a
  portrait screen the portrait-capable theme is preselected and the reason is stated as a
  property of the themes, not as a swap to accept; choosing a landscape-only theme anyway
  restates what that will look like. The resolved default is written down the first time it
  resolves, so rotating the device never moves the theme under the person.

  What "a real rendered preview" means, decided in building it: the preview is the theme's own
  `system` view, parsed by the one theme parser and drawn by the one renderer the Gaming shell
  itself uses (`ThemeSystemPreview` -> `EsDeThemedView`), laid out into a 16:9 thumbnail. Not a
  screenshot, not an image shipped beside the theme, not a colour swatch someone chose. It
  renders with NO list items: a preview shows what the THEME draws -- its background, its
  colours, its own static art -- and a carousel of systems this device has not scanned yet
  would be invented content shown to a person as if it were their library. The one theme
  loader gained `ThemeAssets.loadTheme(theme)` for this, with `loadActiveTheme` delegating to
  it, so a preview and the shell parse the same way and share the same cache; a second,
  simplified parse for previews would be a preview of something the shell never draws.

  This step replaces the earlier `PORTRAIT_THEME` step, which appeared only when droidtop had
  already swapped the theme and offered exactly two answers, one of them hardcoded to DEcaffe.
  A person setting droidtop up chooses their theme; they are not handed a swap to ratify.

  Each row says whether the theme is "Included with droidtop." or "Downloaded."; "Download more
  themes" opens the browser with the same Back button every other page has. The recommended
  theme's background download is not announced on this step (a notice nobody can act on); only a
  failure is, once.
- **Keyboard.** Asked only of a run setting up Desktop (decided 2026-09-25): its reason is
  terminals and Windows programs, and a Gaming-only run was asked about software it had just said
  it did not want. Optional. droidtop cannot set the system input method itself, so it states why
  a desktop keyboard is needed, hands over to Android's own screens, and reflects what came
  back. Declining is a real answer, not a nag. A primary action always produces visible
  feedback, including when the platform screen it opens does not exist on this API level.
- **Default mode.** Offers only modes whose setup actually produced something usable — the
  outcome, not the tick-box: Desktop qualifies when an image was chosen and the capability
  check passed. When exactly one mode qualifies this is a confirmation, not a question with
  one answer. When none does, it is a confirmation that droidtop opens into Gaming, which
  explains what to add.
- **Default mode is also where Home goes** (§2c, "Home goes to the default mode"), and the step
  says so.
- **What next.** Onboarding ends with a summary: what was set up, what was skipped (skipped
  steps included: Controller, Keyboard), and where in Settings each skipped thing lives, and
  whether it is now off; then what a newcomer will want a minute later and is asked where it is
  used (scraping, the Windows games download, notification access), named with where it is; then
  one action into the chosen mode. Finishing with droidtop's own launcher as Home puts droidtop's
  icon on its home screen. It does not end by returning to the system home. "Android" opens the home
  screen droidtop holds, through the same `BackButtonMenu.openHome` the mode switcher uses;
  it is not a `MainActivity` shell.

### No first-run tutorial (decided 2026-09-30)

Also decided 2026-09-30 (owner): there is NO screenshots or getting-started page to replace it,
because droidtop has no consistent look to photograph: every person's Gaming UI is drawn by
whichever ES-DE theme they chose. Do not propose one again. For the same reason onboarding text
never describes what the UI looks like; guidance names buttons, places in Settings and what
happens, and stays true under any theme.

The first-run tutorial (`TutorialActivity`, built 2026-09-25) and Global settings' "Show the
tutorial" row are gone: nothing opens over the first frame of the chosen mode, and no setting
reopens one. The owner's call, Droidtop/tracker#150. What it taught (the controls, that the hint
row is also the touch route, switching modes, the Quick Menu, where help is) is not replaced by
new UI here. Where a newcomer still cannot find one of those, that is a defect in the screen that
owns it, found by the rig's new-user pass and fixed there.

### Copy

A summary states what it knows, and says so when it does not know yet:
"You're set up" reported `Gaming: set up, with no games found yet.` while
the folder it had just been given was still being walked (rig, build 539).
A count in flight says it is counting, and says how far it has got; only a
finished count with nothing in it says nothing was found.

Sentence case. One dash convention. One name per concept — "game folders" is never also "ROM
folders". No developer notation in a user-facing string (no `<folder>/<system>/<romFile>`, no
package or class names, no backend error text). Button labels are the verb of what happens.
A sentence that describes a consequence belongs where the consequence is chosen.

### Permissions are asked at the feature, once, with the reason first

Onboarding asks for exactly one grant, storage, because the library cannot
exist without it. Every other grant Android makes special is asked where
the feature that needs it is first used, never at start and never in a
list on the Welcome step, with the reason stated before the system prompt
and a row that stays until it is given (§7: "take me to the grant"). A
declined grant disables the one feature, says which, and is not asked
again until the person opens that feature. The complete set, and where
each is asked:

| Grant | Feature that needs it | Where it is asked |
| --- | --- | --- |
| All files access (API 30+) / read storage (API 26-29) | reading game folders | onboarding Storage step; Game folders |
| Notification access | the Quick Menu's Notifications tab, the companion's notifications | the tab itself, when opened |
| Usage access | recents beyond droidtop's own launches (§4) | the recents surface's "show all apps" row |
| Install unknown apps | self-update (§10b) | the first in-app update, from the update row |
| Modify system settings | brightness, adaptive brightness, screen timeout, auto-rotate tiles | the tile, on first use |
| Notification policy | the Do Not Disturb tile | the tile, on first use |
| Post notifications (API 33+) | the desktop session's foreground notification, download progress | starting the desktop session; the first download |
| Draw over other apps | nothing; droidtop draws no overlay over another app | never asked |

A grant that Android revokes behind droidtop's back (a system-bound
service disabled with its mode, §2c; an unused-app reset) is detected the
next time the feature is opened, and the same row asks again. Grants are
never requested from a Service or at boot.

### Import and library sync (design; separate from the flow above)

Two real mechanisms exist to build import on rather than invent from scratch:

- **Importing another Android launcher's home screen** uses the AOSP mechanism already in
  `:shell-default`'s forked source — `LauncherProvider`, `provider/RestoreDbTask.java`,
  `model/DeviceGridState.java`, the same path stock Android's own device-setup restore uses,
  with `RestoreDbTask.setPending(context, isManualRestore = true)` as the manual trigger. Open
  question: what must exist on-device for a manually-triggered restore to have anything to
  restore from. (`:app`'s `android:allowBackup="false"` needs revisiting only if this ends up
  depending on the OS backup pipeline rather than the manual path.)
- **Syncing a gaming frontend's library data** is data-level only, both directions: reading
  their catalog into droidtop's `Library`, and later writing back entries they lack. droidtop
  does not emulate and does not run anything on another app's behalf; launching an
  ES-DE-sourced entry means handing off to ES-DE. Reads go through the Storage Access Framework
  (`ACTION_OPEN_DOCUMENT_TREE`), which reaches shared storage under scoped-storage rules, and
  map into `:library-core`'s `LibraryEntry` like any other source. `gamelist.xml` plus
  per-system JSON is the de-facto standard here, so one importer plausibly covers more than
  ES-DE itself; any other frontend's platform-id registry needs its schema investigated before
  an importer for it is built.
- **Platform taxonomy.** Retro entries need a canonical platform identifier to group, filter
  and theme by. droidtop adopts **ES-DE's `es_systems.xml` naming** as that standard rather
  than inventing one: several compatible launchers are already built around it and
  RetroArch/libretro core naming lines up closely. Each importer translates its own format into
  that set; droidtop does not carry several incompatible per-source taxonomies side by side.

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
  (see §9), so this UI is already built into droidtop's APK.
  `DroidtopApplication` carries the Hilt graph, the store and
  container-configuration activities are `:app` hosts
  (`PcContainerConfigActivity` and the store hosts, §7i), and the
  entry points are two: a game's own "Prefix and graphics" row on the PC
  surface, and the Windows games row of Desktop settings, which opens
  the same activity for droidtop's provisioned environment so a prefix
  can be configured without going through a game.
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

## 7d. Engine games — enginehost, the contract and the coverage

An engine game is a folder holding a game written for an interpreter
(Ren'Py, RPG Maker, KiriKiri, Godot, ...) rather than a ROM for an
emulator or a Windows binary. droidtop classifies the folder (§7e2b),
lists it once (§7g, §7m) and ROUTES it; it never runs an engine itself.
**Enginehost** (`Droidtop/enginehost`, deliberately not droidtop-branded
because anything may drive it) is the app that runs the game, through a
per-engine plugin. JoiPlay is permanently out of the launch loop (§7e2):
it exposes nothing a launcher can call. And nothing about a game folder is
ever copied, moved or imported by either app; both read it in place.

### The contract droidtop uses

Enginehost's surface is Intents and one content provider, and the rule
both apps hold to is that **any flow in Enginehost's UI has a programmatic
equivalent, and vice versa** (its README). What droidtop calls:

- `dev.enginehost.LAUNCH` with `path` (the game folder), an optional
  `config` (an `enginehost.json`-shaped string that fills only what the
  folder's own file omits; the file always wins) and `autoinstallPlugin`
  (when true, a missing plugin is offered from the catalog instead of a
  bare failure). droidtop fills `config` from the game record's launch
  facts — engine, context, version, executable, runtime requirements —
  so a folder with no `enginehost.json` still launches; Enginehost writes
  the file itself when detection is complete and opens its config editor
  only for a folder that leaves a question open.
- `dev.enginehost.CONFIGURE` with the same `path`: the config editor,
  for "Engine settings" on the game's detail (§7i).
- `dev.enginehost.CONFIGURE_SETTINGS` and `CONFIGURE_SAVES`: the host's
  settings, for the rows droidtop does not model twice.
- `content://dev.enginehost.capabilities/installed`: one row per
  capability of every installed bundle — bundle id, the engine THAT
  CAPABILITY serves (a web bundle serves `html`, `rpgmaker` and
  `flash_air` at once, and each row names its own), context, plugin
  version, runtime version, the exact versions, series and ranges it
  supports, whether it accepts any engine version, its runtime
  components, origin, and its trust state (approved, pending, denied).
  droidtop reads it to annotate a game's enginehost runner as Ready,
  Needs setup or Not for this game (§7i), reading ranges as objects and
  "accepts any version" as covering every version; the list is advisory,
  and droidtop never refuses a launch Enginehost would resolve.
- `dev.enginehost.UPDATE_NOW` (§10b), the forced update pass over adb.
- **The outcome comes back.** A launch Enginehost cannot start —
  the folder is gone, the executable is missing, no plugin covers the
  version, the plugin is not approved, the save root is unwritable —
  is reported to the caller as well as on Enginehost's own launch
  screen: a broadcast `dev.enginehost.LAUNCH_RESULT`, sent to the
  calling package only, carrying `path`, an `outcome` (`started`,
  `failed`, `detour`) and the same one-sentence `reason` the screen
  shows. droidtop shows that sentence in its own launch failure path,
  so a person who launched from the shell learns what happened where
  they pressed A, and a detour (the catalog, the trust screen, the
  editor) is Enginehost's screen in front, by design.
- Per-engine controls and saves are Enginehost screens droidtop links
  to, so they are exported: `dev.enginehost.CONTROLLER` with `engine`
  and `engineContext` opens that scope's mapping, and
  `dev.enginehost.SAVES` with `path` opens the game's save location.
  droidtop's detail rows say "opens enginehost's controls for this
  engine" and mean it.

### Plugins are signed bundles, not apps

A plugin is a signed `*.enginehost.tar.xz` engine bundle (an earlier
version of this section said "separate apps discovered via
PackageManager"; that was the design before bundles and is wrong).
Enginehost verifies the manifest's P-256 signature against the key pinned
for the bundle's origin, extracts into its private storage, and runs the
approved entrypoint in its own `:runtime` process under its own UID;
approval is bound to the exact archive digest and signer and is the hard
gate (enginehost's `docs/engine-bundle-format.md` and
`docs/plugin-catalog.md` are the normative documents). Resolution is
exact: a capability serves its bundled runtime version plus only the
exact versions, series (`8.2` covers `8.2.*`, never `8.3`) and ranges it
declares, preferring the exact runtime, then the narrowest span, then the
newest build the game's own `pluginVersion` allowlist permits. There is
no "nearest version" fallback, and droidtop's `versionSelectorFallback`
exists for the one engine family (KiriKiri) whose games carry no version
at all. Every bundle ships arm64-v8a AND x86_64 (enginehost's standing
rule), enforced by the bundle builder and refused by the host at install
when a native bundle lacks the device's ABI. Updates within one bundle id
replace in place; a different id coexists; approval never carries over.

### One registry, both apps

`engines-database.json` from droidtop-platforms is the single
classification authority for droidtop's scan and Enginehost's launch
(§7e2b, v5). Each app seeds from its own submodule pin and refreshes from
the same index; the two pins are moved by the same weekly job so the
seeds never drift by more than a week, and both apps' unit tests parse
the shipped seed. Rows an app's id map does not know are skipped by that
app: `rpgmaker-mvmz` and `flash-swf` are Enginehost-only by design, and
a family the host does not name (display name, controller scope, default
origin and key) is not a family the host runs, whatever the row says.
Enrichment (RGSS version from `Game.ini`, `vc_version.py`, the GDPC
trailer) runs after classification and never changes it. The two
interpreters agree on rules OR / conditions AND / file order, and their
builtins agree on what they accept (`.htm` and `.html` alike, the same
Godot pack test, the same depth cap), because a folder that scans as one
engine and launches as another is the defect v5 exists to prevent.

### Coverage: what runs where

Every engine row in the registry routes to at least one runner (§7i).
The table is the design; a row's `strategies` list carries it as data.

| Engine family | Runner | Plugin line(s) |
| --- | --- | --- |
| Ren'Py 7.3 to 7.8, 8.0 to 8.5 | enginehost | `enginehost-renpy-plugin`, one `plugin/<minor>` per line. Ren'Py 6.99 to 7.2 games are served by the 7.3 line, whose capability declares that range: Ren'Py's Python 2 runtime runs the earlier scripts, and a separate line per dead minor is upkeep for nothing. |
| Godot 4.0 to 4.7 | enginehost | `enginehost-godot-plugin`, one line per minor (GDScript tokens are refused across minors). Godot 3.x games exist in real libraries, so a 3.6 line is in scope; .NET exports are not (no Mono runtime on Android arm64 worth carrying). |
| RPG Maker 2000/2003 | enginehost | `enginehost-rpgmaker-easyrpg-plugin` (EasyRPG Player) |
| RPG Maker XP/VX/VX Ace | enginehost | `enginehost-rpgmaker-mkxp-z-plugin` (mkxp-z; Ruby 1.9.2 and 3.1.3 as runtime components) |
| RPG Maker MV/MZ | enginehost | `enginehost-rpgmaker-mv-mz-plugin` (a WebView shell; browser storage mapped to the save folder) |
| KiriKiri 2 / KAG3 | enginehost, Kirikiroid2 | `enginehost-kirikiri-plugin` (Kirikiroid2 lineage). Kirikiroid2 as an installed app is offered only as "opens the app, not the game" (§7i). KiriKiri Z is out until a port exists. |
| Buriko / Ethornell (AUGUST) | enginehost | `enginehost-buriko-plugin` (OpenBGI) |
| CatSystem2 | enginehost | `enginehost-catsystem2-plugin` (droidtop's own scene player) |
| CMVS (PS2/PS3 scripts) | enginehost | `enginehost-cmvs-plugin` (droidtop's own engine) |
| NScripter / ONScripter | enginehost | `enginehost-nscripter-plugin` (OnscripterYuri); a default origin with a certified key, a named family and an `ons_*` controller scope in the host, like every other family. |
| HTML games, Twine 2.x, TyranoScript, Construct 2/3, Visual Novel Maker, NW.js/Electron packages | enginehost | `enginehost-html-plugin`: one WebView runtime, one capability per format. The registry rows for TyranoScript, Construct, Visual Novel Maker and `nwjs-electron` route to it (enginehost family `html` with a context each) rather than to Wine. |
| Flash and AIR, plain SWF | enginehost | `enginehost-flash-air-plugin` (Ruffle); `flash-swf` is Enginehost's row, a lone `.swf` stays a players-database file. |
| Unity, Unreal, WOLF RPG, Artemis/Live2D, GameMaker, Siglus, LiveMaker, RAGS and the other Windows-only engines in the registry | Wine (§5b), a native Linux build in a container where one exists (§5a) | none: no portable interpreter exists, and Enginehost's rule is that a plugin embeds a real implementation of its engine, never a Wine hand-off |
| DOS, ScummVM engines, J2ME, and every other emulated platform | an emulator from the players database (§7e2) | not engine games |

"Complete" for coverage means: on an unrooted arm64 handheld every
engine game in a real library has a runner whose row reads Ready or
Needs setup with a named action, and a game whose only route needs
something this device cannot do says so with the reason (§7i). It does
not mean every plugin line has a stable release: a line is published to
`testing` on device evidence and to `stable` at 1.0 (§10b), and until
then the catalog's channel picker says which lines hold what.

### What droidtop requires of Enginehost, and Enginehost's own rules

Enginehost is a complete app on its own and its own repository owns its
decisions; this list is the part of them droidtop depends on, stated once
so neither app assumes the other:

- **Preflight before the engine.** A launch checks the all-files grant,
  that the folder and the executable exist and that the save folder can
  be created, and turns each failure into a sentence, before any engine
  code runs; a bad path is never a native crash reported afterwards.
- **The one-game rule survives process death**, so relaunching the game
  droidtop shows as running brings it back rather than ending it; and a
  finished game ends its `:runtime` process, so the next launch never
  waits on, or loads into, a live one.
- **The runtime activity declares every configuration change**
  (keyboard, navigation, ui mode, density, screen layout, smallest
  screen size, orientation, screen size), because a pad attaching or a
  dark-mode toggle must not recreate the Activity inside a live engine.
- **The update pass runs on the schedule the person set** (§10b), from
  a scheduled job and not only when the home screen is opened, so an
  install that is only ever driven by droidtop still checks; an
  auto-installed bundle never replaces one a running game is using, and
  a working game is never demoted to a trust prompt without a notice on
  the home screen saying an update is waiting for approval.
- **Saves**: Enginehost changes no engine's save logic; it makes SYSTEM
  locations (user data dirs, app-private dirs, browser storage) mean a
  folder the person chose, and engines that save beside the game keep
  doing so (its AGENTS.md rule). droidtop's "Saves" row opens that
  location and models nothing of its own.
- **Controller**: the host's map speaks each engine's own vocabulary
  (`rgss_*`, `mvmz_*`, `cs2_*`, `cmvs_*`, `ons_*`, the common set for the
  rest) and every plugin that reads the map reads its engine's ids; a
  bypass engine (one whose runtime maps a pad itself: Ren'Py, Godot,
  EasyRPG) reads no map and the controller screen says so beside it.
- **The engine sandbox direction** (decided 2026-09-24 in Enginehost):
  engine code must not have internet access or arbitrary file access;
  the host does the reads a launch needs. Today the `:runtime` process
  inherits both under the app's UID; nothing widens that, and
  `dev.enginehost.LAUNCH` stays open to any app by design.
- **Its surface is the shared design language** (`docs/DESIGN-LANGUAGE.md`,
  §7j, §7k): fully pad-driven with a docked hint row on every screen, one
  focus token, B back by every route, a dark palette resolved from the
  same role names droidtop uses so the two apps read as one system, no
  system bar over a game, two-step confirmation on every destructive
  control, display names rather than bundle ids and URLs in primary text,
  the channel chosen in one place, and a home screen that IS the library
  (every game added, scanned or launched, most recent first).
- **What is published is a release build**, with the same signing-key
  continuity and `release-info.json` shape as droidtop (§10b); the debug
  installer activities live in the debug source set only. Enginehost still
  publishes every push to its own rolling `latest` release -- droidtop
  moved to one permanent release per build, tagged by version
  (Droidtop/tracker#119, §10b "Build history"); enginehost has not been
  changed to match.

## 7e. Second-screen / ambient integrations (Spotify now-playing, Discord presence)

Useful both on the Dual-Screen Add-On (a second physical display, §4) and
as an idle-screen widget on a single-screen device. droidtop's dual-screen
model splits interaction and context the way the Nintendo 3DS does:
navigation on the primary screen, ambient context on the second, and not
only for games: the same "info" role shows media and presence during
desktop use.

What holds that role is the companion (§4d, `CompanionActivity` and the
`SECONDARY_HOME` host in `:display`), and its tenants are §4d's layers:

- **The focused-entry reflection** (the role this section first called
  `FocusCompanion`) is `CompanionState.focusedEntry`, written by the shell
  that owns primary-screen focus and read, settled, by every companion
  host; with nothing focused the companion shows its idle rotation.
- **Routed notifications** are the platform's own, shown by the
  companion's notifications layer: Discord already posts DMs and mentions,
  and a media app posts its persistent now-playing notification, so
  ambient presence comes from the platform with no polling overlay.
- **Now-playing today** is the media app's own Android widget, placed on
  the companion like any other (`CompanionWidgets`, one shared host).
- **`PresencePanel`**, a deliberate panel with one card per linked media
  app (and later Discord), is not built.

**Media app control: local, no credentials held by droidtop.** droidtop
never holds a streaming service's credentials (no OAuth, no developer app
registration, no stored tokens), and control must be real: search,
library browsing and transport, against whatever is running in the
installed app. The mechanism is Android's `MediaBrowserService` API
(`MediaBrowserCompat`/`MediaControllerCompat`, `androidx.media`), which
Android Auto, Wear OS and Assistant use to browse and control a media app
without seeing its login; droidtop binds to the app's exported service over
local IPC. An earlier Spotify-specific OAuth client was removed for this.

The client written for it (`library-core/.../presence/MediaAppBrowserClient`)
was constructed by nothing and was deleted with its `androidx.media`
dependency (audit 2026-09-24); it is written again together with the
`PresencePanel` that uses it. Two facts carry over. The targets verified
on the test device (`adb shell dumpsys package <pkg>`, filtered for
`android.media.browse.MediaBrowserService`) are Spotify
(`com.spotify.music`), Jellyfin (`org.jellyfin.mobile`) and the device's
YouTube, which is a ReVanced build, so the official
`com.google.android.youtube` component name is unconfirmed; Tidal and
YouTube Music were not installed and are not listed until confirmed. And
nothing has been run end to end: whether each app accepts droidtop as a
browser client (an app may refuse callers it does not know), what its
content tree looks like, and whether it implements `onPlayFromSearch` are
open until the panel is tried on the rig.

**Discord: the official Discord Social SDK, not a bot.** Discord publishes
a Social SDK for embedding friends, presence and voice in a third-party
app, with Discord's own login and consent flow; setup is a free
application on the Discord Developer Portal and its client id. It is a
native library with its own download and JNI integration and is not
integrated; when it is, it is a `DiscordPresenceClient` in the same place
as the media client, feeding the same `PresencePanel`.

## 7e2. Data-driven player/platform database (directed 2026-08-30)

Standalone-emulator launch definitions (the non-RetroArch emulators) are
DATA, not code: `players-database.json` — refreshed from the
droidtop-platforms repository on GitHub. `KnownPlayers` loads
filesDir-copy-if-valid, else the bundled seed; every refresh
parse-validates before replacing anything. The previous state —
117 presets as generated Kotlin — required an app release to add an
emulator; now the database grows independently.

**The bundled databases are a SNAPSHOT of droidtop-platforms, not a copy
of it** (directed 2026-09-10: "the bundled engine database shouldn't
MATCH the platform repo, it should literally be a snapshot of it, with a
built-in updater"). Hand-copied seed assets had drifted days apart from
the repository they claimed to mirror, with nothing in the app saying
how old they were. Instead: droidtop-platforms is a submodule at
`vendor/droidtop-platforms` pinned to one commit, a Gradle task
(`platformDatabaseSeed`) copies that commit's databases into generated
assets at build time, and the commit is written beside them and shown on
the settings row. Nothing about the seed is checked in — a build ships
one identifiable state of the platform repo, and the tests parse the
same generated files the APK ships. Enginehost takes its
`engines-database.json` seed the same way from its own pin, and shows
its snapshot on the version row.

**The repository is a TREE with an index** (directed 2026-09-10: "the
platform repo should have a BUNCH of jsons. One per engine, different
revision ones, hardware ones, etc"). droidtop-platforms holds one file
per thing — `engines/<id>.json`, `platforms/<id>.json`,
`players/<id>.json`, `bios/<systemId>.json`, `hardware/<device>.json`,
`controllers/<vendor>-<product>.json` — plus `index.json`: schema
version, and every file with its sha256 and its collection. Each
collection directory carries `_order.json`, because for engines the file
order IS detection precedence and that is an editorial decision, not a
directory listing. The repo's generator validates the tree and composes
the monolithic documents into `legacy/`, and for now also at the
repository root, where released builds already fetch them; those root
copies are deprecated and go when no supported build fetches them.

**Refresh** fetches `index.json`, compares each entry's sha256 against
the per-file cache under `filesDir/platform-db/`, downloads only what
changed, composes the monolithic documents the parsers read, and hands
each to its database's own validate-then-atomically-replace
(`PlatformDatabaseIndex` → `EnginesDatabase.install` and friends). A
one-engine fix costs a few hundred bytes; a check with nothing new costs
one request; every composed document is validated by its database's own
parser before any is written, so one that does not validate changes
nothing in any of the four. A
source that publishes no index falls back to the four whole files, which
is what a fork or an older commit serves. This runs on the update
schedule (§ Software updates) as well as from the manual "Update
platform databases" button — the same call, so the two cannot diverge —
and in enginehost inside `PluginUpdateCheck`'s existing pass.

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

**What a launch template may do (decided 2026-09-24, security review).**
A template is data that droidtop turns into an Intent it sends with its
own identity, and templates arrive from the network (the players
database, whose base URL is also overridable) as well as from the
person. So `AmStartCommandToIntentConverter` enforces the limits itself,
whatever the template's source, rather than trusting any one source:

- Only the template's own text is expanded. A substituted value (a ROM
  path, a folder, `{query}`) and injected file content are copied
  verbatim and never scanned for placeholders or directives again.
- `{file.inject:REL}` reads only a file inside the game's own directory
  (after resolving `..` and links); the launched file itself qualifies.
- URI grant bits in `-f` are dropped: droidtop alone decides grants, and
  it grants read access to `{file.uri}` and nothing else.
- `-d` may name droidtop's FileProvider only as the exact `{file.uri}`
  it issued, and `{file.uri}` is never issued for a file in droidtop's
  own app data.
- `-n`/`-p` may not target droidtop itself: a template launches another
  app, never droidtop's unexported screens.

Findings and reasoning: `docs/security/2026-09-24-droidtop-intents-updater.md`.

## 7e2b. Launch resolution FROM the platforms database (directed 2026-08-31)

Extends §7e2 to the whole launch pipeline: droidtop-platforms is the
authority for launch data. Four documents are composed out of its tree
(§7e2), each build-time-snapshot seed + index-driven refresh +
validate-before-replace:

- `players-database.json` — per-system emulator launch presets
  (`KnownPlayers`), as before.
- `platforms-database.json` — platform definitions (id, name,
  extensions, RetroArch core), GENERATED from ES-DE's real
  es_systems.xml (`generator/from_esde_systems.py`; 195 platforms, 153
  with cores). Replaces the formerly compiled-in
  `ES_DE_CONSOLE_SYSTEMS` Kotlin list (deleted) as the seed for
  `ConsoleSystemsRepository`'s Room store — Room stays the runtime
  source of truth because the user can edit platforms. A platform a
  refresh adds reaches Room once: the built-in ids already offered are
  remembered, so a new platform appears while a built-in the user
  deleted stays deleted and an edited row is never overwritten (a
  refresh's changes to an existing platform reach Room only through
  "restore defaults").
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
  trailer, Unity's depth-limited player search, the HTML row's
  root-page probe),
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
  stored by database id, wins over every rule), from the Engine row on a
  PC game's own detail screen; a pin also outranks the engine already
  written in the game's record, so a launch re-detects rather than run as
  the engine just corrected, and the library's label follows on its next
  scan. RPG Maker XP/VX joined
  the engine set with the v4 registry (enginehost contract contexts
  `xp`/`vx`, detected via their real archive/project/Game.ini RGSS
  signatures). The unit tests parse the SHIPPED seed file, so registry
  edits that break detection fail CI before they ship.

  **v5 — one detection authority for droidtop AND enginehost (decided
  2026-09-02).** droidtop and enginehost briefly carried two independent
  engine detectors, extended separately, that could classify the same
  folder differently (library says one thing, launch does another). The
  resolution: `engines-database.json` is the single classification
  authority for both apps. enginehost seeds from its own pin of the same
  repository and refreshes from the same index, evaluating the
  same rows with the same semantics (rules OR, conditions AND, file
  order is the sole precedence), so scan-time and launch-time
  classification agree by construction and a detection fix ships once,
  as data, to both. The alternative — droidtop asking enginehost at scan
  time — was rejected: droidtop classifies hundreds of folders per scan
  and must work with enginehost absent, and detection also drives
  non-enginehost routes (Wine/Linux/Kirikiroid2, the §7g store-ownership
  rule, Unreal/Unity). A compiled shared module across the two repos was
  rejected too (two release cadences, separate CI): the DATA is the
  shared module; each app keeps a thin interpreter of the documented
  format, and both test suites parse the same shipped seed file. Three
  consequences: (1) rows an app's id map doesn't know are skipped by
  that app — `rpgmaker-mvmz` and `flash-swf` are enginehost-only by
  design (plain `.swf` stays droidtop's players-database path);
  (2) enginehost's richer per-engine parsing (RGSS version out of
  `Game.ini`, `RPGMAKER_VERSION`, Ren'Py's `vc_version.py`, GDPC/SWF
  headers) is ENRICHMENT, run after classification and keyed on the
  classified family — it prefills version/execFile/evidence and can
  never change which engine a folder is; (3) where one engine id spans
  multiple rows (`cmvs`/`cmvs-ps3`/`cmvs-ps2`), the FIRST row is the
  canonical launch row (`EnginesDatabase.defFor`), so the generic
  context-null row is listed first and the context-refining rows after
  it. New in v5, every marker taken from one the two detectors had
  already verified, none invented: `anyFileExtensionDeep` (depth-capped
  subtree extension search, for the compiled-Ren'Py fallback),
  `startup.tjs`, corroborated `RPG_RT.ldb`+(`exe`|`lmt`),
  `project.godot`, `.cst`, `.ps3`/`.ps2`, and `data01000.arc`.

  **Where the game ROOT is, versus which engine it is (2026-09-02).**
  The database answers "which engine", in file order, for one folder.
  It does not answer "which folder is the game root", and v5's first
  subtree rule (`anyFileExtensionDeep`, the compiled-Ren'Py fallback)
  made the difference visible: it matched a wrapper folder whose real
  markers sat one level down, so `GameEngineDetector.detectGame`
  returned the wrapper as the game root and never looked inside it --
  breaking the version-folder shape 7g relies on, and with it the
  determinism guarantee on the nested search. Both changes were green
  on their own branches and only met on `main`. `detectGame` therefore
  searches in three tiers: evidence AT the folder that the folder is a
  game root; then that same precise question of each subfolder, in name
  order; then, only if nothing more precise exists anywhere, the
  subtree rules against the folder itself. A rule is a subtree rule if
  any of its conditions can be satisfied from a subdirectory the rule
  never names (`anyFileExtensionDeep` with a non-zero depth, the
  `unity` probe's 3-deep search). Classification is unchanged: `detect`
  still evaluates every row in file order, so file order remains the
  sole precedence rule shared with enginehost, and a subtree match is
  never downgraded to no match -- only to a later tier.
- `bios-database.json` — §7e4's firmware registry.

One call refreshes all four (`PlatformDatabases.refresh`), and every
"Update platform databases" runs it: the console-systems settings row,
the Gaming gamelist menu's item, each BIOS screen's row, and the update
schedule. None of them refreshes a single database on its own.

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

That preservation is not a claim but a walked path: `RomDatabaseMigrationTest`
(`:library-core`) builds a database exactly as a real v3 install had it, puts a
favourite-and-completed row in `game_metadata`, and runs every real migration
object from `MIGRATION_3_4` through `MIGRATION_10_11` forward over it, ending
with Room's own post-migration schema validation against the current entities.
The row must arrive with its values and the six `MIGRATION_9_10` columns
(series, links, field_sources, hero_path, logo_path, icon_path) must arrive
present, nullable, and null. A version bump extends that walk in the same
change or CI goes red: since `RomDatabase` falls back to a destructive wipe
when a bump has no migration path, this test is the only thing standing
between a missed bump and a user's library edits.

## 7e3. Lutris install-script integration (directed 2026-08-30, scoped and built 2026-09-25)

Beyond cover art (§7h's Lutris scraper source), lutris.net's real public
install-script database is a fit for the PC side: per-game scripts that
declare how a game from an arbitrary source (user-provided installers,
GOG/itch builds, engine games) gets set up — files, Wine settings,
required runtime pieces. The standing risk this backlog note flagged —
consuming a script the way Lutris does means running an unsigned
installer from whoever last edited its page — is why the importer is
built as a DATA TRANSLATION step and never a live interpreter; its full
threat model, written before any of this code, is
`docs/security/2026-09-25-lutris-importer.md`. Format reference:
`github.com/lutris/lutris`, `docs/installers.rst`.

**What is imported.** `LutrisImport.translate` (`:library-core`,
`library/lutris/LutrisImport.kt`) reads one Lutris installer's `script`
JSON into two closed shapes and nothing else: a `WineGameSettings`
(executable, arguments, working directory — all relative paths inside
the game's own folder, re-validated against it canonically on every
launch by `WindowsLaunchResolver`, never only at import time) and a
`WinePrefixChanges` (DXVK, esync, a fixed set of gamenative Windows
components reached only through a closed `winetricks`-verb table, DLL
overrides, and an environment-variable allowlist). Every directive that
would execute, fetch, write a file, touch the registry or ask the
installer a question (`execute`, `task: wineexec`, `write_file`,
`write_config`, `write_json`, `extract`/`move`/`merge`/`copy`,
`insert-disc`, `input_menu`, `gogdl_setup`, `set_regedit*`) is refused
and listed to the person as **not imported, needs manual setup**, in
plain words, never run, emulated or partially run. Only `runner: wine`
scripts are offered; every other runner is shown, named, and cannot be
picked, because §7i's own runner resolution is the authority on how a
game runs. Runner-execution mapping goes through the existing launch
path (`WindowsLaunchResolver`, `PcGameRuntime.launchWindows`) and
gamenative's own container save path (`ContainerUtils`), never a new
parallel launch path — the same rule this section always had, now with
code behind it.

**Entry point (§7i, beside the per-game overrides).** "Import a Lutris
install script" is a row in the PC detail's "Runs on Windows" runner
section (`PcGameDetail.kt`, `runnerGroup`), reached the user way from the
game whose settings it would change — never a global screen, because an
import is always for one game. `LutrisImportScreen` (`:shell-gamepad`)
walks: search lutris.net for the game's own name, pick one of its
installers (Wine ones pickable, others shown with their runner and
disabled), then a preview that is the whole point. The preview lists,
before anything is written: what changes for this game, what changes on
the prefix (named, and stated as shared with every other Windows game
without a prefix of its own when it is), what the script asked for that
droidtop already does its own way, and everything not imported with why.
Apply writes both; "This game only" writes just the game's own settings
and leaves the prefix alone. Nothing is written on open, on search, or in
the background. A game an import touched shows its own "Program: …" row
in the same section, naming the import as its source and clearing back
to detection on selection — the per-game override this section already
promises, now with a second way to set it besides picking a file by
hand.

**Where the settings live.** `WineGameSettings` (`:library-core`,
per-game, keyed like `LaunchStrategyOverridePrefs`) is read by both PC
launch paths — `PcGameProvider` and `GameEngineDetector`'s
`EngineGameProvider` — through the one `WindowsLaunchResolver.resolve`,
so an imported game and a detected one share the same launch code past
that point. `PcGameRuntime.applyPrefixChanges` writes prefix-wide changes
through gamenative's own `ContainerUtils.toContainerData`/
`applyToContainer` round trip, the same path its own configuration
dialog saves through, so an import and a hand edit land in the prefix
identically.

**Confirmed against the real API.** `LutrisInstallerClient` reads
`GET https://lutris.net/api/installers/<slug>`, keyless like the
existing `LutrisScraperClient` search it reuses to find the slug from
the game's own name. The response is read to 2 MiB and refused past it;
at most 500 installer steps and 200 files are read per script, each
string capped at 4 KiB (threat model, decision 8) — a script over a
limit is refused whole, never half-read.

Needs a rig check: dq-lutrisimp-01.

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

## 7f. Gaming mode: real, generic ES-DE theme engine

**Status as of 2026-08-29 — this is Gaming's actual, current, singular
paradigm (§2a's earlier "multiple selectable paradigms" framing is
superseded, see that section's own updated note).** droidtop's Gaming
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
  (`ThemeDownloader`: each theme repo cloned the same way real ES-DE's
  own `GuiThemeDownloader` does; the `themes-list` index itself fetched
  as one raw HTTPS file, §7b's raw-file decision) with a real
  browse/download UI (`ThemeBrowserScreen`, including real per-theme
  screenshot previews, streamed from the index repo on demand).
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
  ROM scrapers) and the keyless libretro database — not Lutris/IGDB/the
  Steam store, which are reserved for PC/engine content per §7h — wired into `ConsoleSystemsActivity.kt`'s manual
  per-folder scrape action, single-selected-source only (real ES-DE has
  no automatic multi-source fallback chain).
- Real element rendering: `image`/`text`/`carousel`/`grid`/`textlist`/
  `video`/`animation` (both played, see below)/
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

**Favourites are a library fact, not a console-ROM fact.** A console
ROM's favourite is ES-DE metadata (`GameMetadataEntity`, the field ES-DE's
`gamelist.xml` carries; droidtop reads gamelists and never writes one —
the record and the metadata store are the truth, and a gamelist is
written only by the explicit export of §7b's library sync, so a person's
own ES-DE gamelists are never edited behind their back). Every other kind — an engine game, a PC game, an
app — has no gamelist, so its favourite lives in the library's own
`FavoritesStore` (a `favorites` table beside play history, keyed by entry
id) and is merged into the scanned entries the way play history is.
`Library.toggleFavorite` picks the store by the entry's provider; the
gamelist's `X FAVORITE`, the favourites collection and the theme's
favourite badge read one `LibraryEntry.favorite` either way.

**Done (2026-08-30)**: real ES-DE collections — droidtop's own
`CollectionEntity`/`CollectionMemberEntity` (`RomDatabase` v5) for
custom collections, plus real, computed-on-the-fly auto collections
(all games/favorites/last played, `LAST_PLAYED_MAX`=50 confirmed
against `CollectionSystemsManager.cpp`). Both appear as real
`GameGroup.Collection` pseudo-systems leading the Gaming system
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
.png/.apng as droidtop's one deliberate widening of that set. Lottie
(.json) plays too, as of 2026-09-24: ES-DE's `LottieAnimComponent`
renders through rlottie, droidtop through the Lottie library the
launcher shell already ships (`EsDeLottieAnimation`). What is ES-DE's is
the sizing, which comes from the file's own viewport rather than the
image rules, including `scaleFactor` (rasterise smaller, draw scaled
up), and the clock: frames advance at the file's frame rate divided by
`speed`, through the same frame bookkeeping the GIF path ports, since
LottieAnimComponent.cpp:410-518 is the GIF component's line for line;
both are pure Kotlin in `EsDeAnimationPlayback.kt`, unit tested.
ES-DE's per-file frame cache is not carried over. `speed`/`direction`/
`interpolation`/`colorEnd`/`gradientType` are applied on both paths;
`stationary`/`metadataElement` are not, as recorded at
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

**Launch audio hand-off (2026-09-30, tracker#160).** Droidtop's own audio
(themed preview video, SoundPool navigation samples) must never be cut
mid-buffer while a launched app opens its output; that is audible as a
burst of static on every launch. The one mechanism is `ShellAudio`:
before the launch intent is dispatched the themed videos are faded to
zero over 120 ms and paused; the video's lifecycle observer mutes and
pauses on ON_PAUSE (not ON_STOP) and resumes on ON_RESUME; the host
activity's onPause silences and stops any sounding navigation sample.
Preview video is `USAGE_MEDIA` with ExoPlayer audio-focus handling, so
focus is requested on play and abandoned on pause/release in the order
the launched app expects.

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
the `textHorizontalScroll*` family on grid and textlist (the carousel
has it), `helpsystem`'s entry-layout properties, and `rotationOrigin`,
which recurs across nearly every element type.

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

The chain ends where ES-DE's ends. When the theme's declared types
resolve to nothing, the element shows its own `<default>` and otherwise
nothing at all; it does NOT fall back to the entry's single `artworkUri`.
droidtop did fall back that way for a while, on the grounds that its own
scraper writes covers and little else and a strict port would blank a
scraped library the moment a theme asked for a marquee -- but a scraper
gap is not a renderer rule, and the place to fix it is the scraper. The
divergence is removed on all four element types that carry such a chain
(`carousel`, `grid`, `image`, `video`), and with it goes the other half
of the same ES-DE function that had been missing entirely: an `imageType`
a GAMELIST primary element never declared is not "no image type", it is
`marquee` (CarouselComponent.h:485-486, GridComponent.h:452-453). `none`
still breaks the walk before the default, because there the theme did not
fail to find media -- it asked for text. Also fixed on the way past: the media-type
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

**Miximages (2026-09-24)**: the scraper composes the `miximage` media
type itself when "Generate miximages" is on, in
`library-core/.../scraper/MiximageGenerator.kt`, a port of ES-DE's own
`MiximageGenerator.cpp` at ES-DE's default settings (1280x960, medium
box, medium physical media). The box is the game's 3D box when
`3dboxes/` has one and its cover otherwise (MiximageGenerator.cpp:68-84);
droidtop's scrapers write covers, so a 3D box only comes from a media
folder ES-DE already populated. A box wider than 1.14:1 is turned a
quarter turn clockwise first (:614-618), behind the same default-on
setting ES-DE has (`MiximageRotateHorizontalBoxes`, Settings.cpp:139),
shown as "Rotate horizontal boxes in miximages" under the scraper's
content options. ES-DE's other miximage settings (resolution, file
format, fit modes, sizes, which parts to include, overwrite) are fixed
at their defaults, not offered. Resampling, the drop shadow and letterbox
trimming use Android's own operations, recorded at the class.

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

### Default theme: Art Book Next, downloaded during setup (decided 2026-09-25)

**Decided (owner, via coordinator, 2026-09-25): "Art book next works well."** This replaces the
2026-09-25 survey's own "PROPOSAL pending owner decision" framing below with the shipped
mechanism. DEcaffe is NOT removed from the APK — it stays bundled as the offline fallback
(NOTICE.md) — and the bundled *asset* default (`ThemeAssets.DEFAULT_THEME_NAME`) is unchanged, so
every path that doesn't go through onboarding (a fresh install with onboarding skipped, a device
provisioned without setup) still lands on DEcaffe exactly as before. What changed is onboarding's
own Appearance step:

1. The step starts downloading Art Book Next (`art-book-next-es-de`) through the real theme
   downloader (`ThemeDownloader`, `runtime-common/.../theme/ThemeDownloader.kt` — the same
   mechanism "Browse themes" uses, not a second one) the moment it is shown
   (`OnboardingThemeDownload.ensureStarted`, `app/.../OnboardingActivity.kt`), on its own
   `CoroutineScope` that outlives the step's own composition — leaving the step (Next, Back) does
   not cancel the download.
2. Progress is shown inline in the step (a status line above the theme list: downloading / failed)
   and does **not** block "Next" — the person can finish setup while it is still running.
3. If the download succeeds — at any point up to and including after the person has moved past the
   Appearance step — and no explicit theme choice has been stored yet (`ThemePrefs.get() == null`),
   Art Book Next is written as the active theme (`ThemePrefs.set`), through the exact same call the
   Appearance step's own row-tap uses. An explicit tap on any row, before or after the download
   finishes, always wins — this is a pre-selected default, not a forced one.
4. If setup finishes (`finishOnboarding`) while the download is still in flight, it is cancelled and
   its partial clone removed (`OnboardingThemeDownload.cancelIfIncomplete`) rather than left to
   finish and swap the theme out from under someone who is already looking at Gaming mode —
   `ThemeAssets.defaultThemeFor`'s own doc comment already states this rule for the portrait
   default ("the theme moving under the user is the one thing this rule must not do"); the same
   rule applies here. A cancelled/failed/offline download leaves `ThemePrefs` unset, so
   `ThemeAssets.resolveActiveTheme`'s existing fallback (DEcaffe, or Slate on a portrait screen)
   applies exactly as it did before this section — no second fallback mechanism was added.
5. "Browse themes" (Settings, and onboarding's own "Download more themes") offers Art Book Next exactly
   as it offers every other theme in the index, whether or not onboarding's own download reached
   it — a person who skipped or lost the setup-time download is never blocked from getting it
   later the same way as any other theme.

Licence credit lives in `NOTICE.md`'s new "Downloaded default theme" section, next to the two
already-bundled themes' own entries (CC-BY-NC-SA 4.0, by anthonycaccese) — droidtop's one existing
place themes are credited; nothing about the credit mechanism itself needed inventing.

**Why Art Book Next, from the 2026-09-25 survey** (ES-DE's own downloader index,
`gitlab.com/es-de/themes/themes-list.git`, `themes.json`, 66 entries checked; each candidate's own
repo for licence/maintenance/size; droidtop's renderer code for support evidence):

- **Licence — clear.** `README.md`'s own License section: "Creative Commons CC-BY-NC-SA -
  https://creativecommons.org/licenses/by-nc-sa/2.0/" (fetched 2026-09-25) — the same licence
  class as both currently-bundled themes (DEcaffe, Slate; NOTICE.md). No LICENSE file in the
  repo, only the README clause, same as most of this author's themes.
- **droidtop renderer support — proven, not assumed.** Art Book Next was one of the two themes
  bundled in the APK earlier in the theme engine's build specifically to stress-test the renderer
  against a second real theme shape (this section's own opening paragraph, above), and one of the
  ten real themes shallow-cloned to MEASURE which of ES-DE's 472 schema properties real themes
  actually use (the "measured against real themes" pass, above) — the carousel/grid/textlist
  parity work in this section's R2 pass and the property-closing passes that followed were tuned
  against it directly, more than any other non-bundled theme.
- **Handheld fit — the widest aspect-ratio coverage of any surveyed theme.** `themes.json`:
  `16:9, 16:10, 3:2, 4:3, 5:3_vertical, 5:4, 8:7, 19.5:9, 20:9, 21:9, 32:9, 1:1` — the RP5's exact
  16:9 is native, and it is one of the very few surveyed themes with a declared vertical variant
  at all (`5:3_vertical`). 20 variants, 30 colour schemes, 4 font sizes.
- **Coverage.** Generic per-`${system.theme}` art with no per-franchise character folders — covers
  every console `system.theme` plus the `pc` metacategory droidtop routes engine/PC/Linux/WINE
  games through (this section, "pc as the theme metacategory").
- **Maintenance.** Pushed 2026-02-07 (most recent of the shortlist survey).
- **Looks.** A clean "coffee table book" metadata-forward layout with a real box art/screenshot/
  marquee sidebar.
- **APK/storage cost.** ~205 MB (GitHub repo size 209,605 KB) — no longer an APK-size question
  since it is downloaded, not bundled; it is a setup-time download size and app-private storage
  cost instead, on a network the onboarding flow already assumes for other setup steps.

Runners-up from the same survey — **Alekfull NX (Revisited)** (cheapest, ~85 MB, CC-BY-NC-SA,
narrower widget coverage), **Canvas** (richest variant set, but a genuine licence conflict: its
repo's own `LICENSE` file reads CC0-1.0 while its README pastes the same CC-BY-NC-SA clause as
this author's other themes, plus franchise-character wallpaper folders — not resolved, not used),
**Colorful (Revisited)** and **Iconic** (same Canvas-author licence conflict, heaviest franchise-art
exposure surveyed) — are recorded for reference; none is wired into onboarding.

**Rig checks.** `dq-deftheme-01` (queued, `/root/coordination/device/QUEUE.md`) screenshots Art
Book Next/Canvas/Alekfull NX installed through Browse themes against the DEcaffe baseline (system
carousel, game list, detail, no-art system) — the look-and-feel evidence for the table above, not
yet run as of this writing. `dq-deftheme-02` (queued the same place) is the activation check: a
fresh onboarding on BlueStacks the user way, confirming the download starts on the Appearance
step, does not block finishing setup, and Art Book Next is actually active in Gaming mode
afterward — plus the offline/skipped path staying on DEcaffe with its note. Neither has run yet;
nothing above is confirmed by an on-device screenshot.

**Renderer gaps this survey exposes as follow-up work** (filed to
`/root/coordination/INBOX.md`, not yet built): Canvas's `[Grid]`/`[Carousel]`-prefixed variant
names suggest a theme-level "which primary widget family" switch beyond the per-view
carousel/grid/textlist choice droidtop already parses — moot unless Canvas is revisited, since it
is not the theme shipping. Art Book Next's `5:3_vertical` and other non-16:9-family aspect ratios
are the first surveyed theme to exercise `8:7`/`20:9`/`32:9`/`1:1` against droidtop's
`EsDeAspectRatio` port in any real, non-synthetic theme, now that it actually ships — worth a
screenshot diff against those specific ratios beyond the automatic-selection logic this section
already verified.

Superseded same day: the app-private debug-credentials file below was
built, then retired by direction once the existing whole-prefs settings
backup/restore (Global settings, `DroidtopWideSettings` -- it archives the
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

### Aspect ratio, and what a portrait screen gets (2026-09-11)

The aspect-ratio axis follows ES-DE exactly
(`EsDeAspectRatio`, ported from `ThemeData.cpp`), including the vertical
variants a theme may ship for a screen held upright:

1. A theme's capability list is its declared `<aspectRatio>` entries,
   validated against ES-DE's own supported set, de-duplicated, re-emitted
   in that set's order, with `"automatic"` PREPENDED whenever at least one
   ratio survived (`ThemeData.cpp:1232-1252`, `:1766-1775`).
2. The selected ratio is the user's `ThemeAspectRatio` setting when the
   theme declares it, otherwise the list's `front()` --- which is
   therefore always `"automatic"`, since no real theme writes that value
   itself (`ThemeData.cpp:739-746`).
   A theme that declares NOTHING gets no list, selects nothing, and has
   no `<aspectRatio>` block applied at all --- ES-DE enters the selection
   at all only for a non-empty list (`ThemeData.cpp:738`), leaves
   `sSelectedAspectRatio` empty (`ThemeData.h:301`) and returns on that
   immediately when parsing (`:2036-2037`). It is not the same as
   selecting `16:9`, which would apply a block the theme never declared
   --- a state ES-DE reports as a theme error (`:2057-2061`). A theme
   that declares `"automatic"` itself gets it twice, because the prepend
   is unconditional and the re-emit loop starts there; droidtop
   reproduces that rather than tidying it, since selection is identical
   either way and the only surface it reaches is the aspect-ratio
   setting's own option list.
3. `"automatic"` resolves to the declared ratio numerically closest to
   the live screen's width/height, seeded with `16:9` and its own
   difference so a theme whose every ratio is further away still yields
   `16:9` (`ThemeData.cpp:748-771`). Screen ratio is width/height in BOTH
   orientations, exactly as ES-DE computes it (`Renderer.cpp:305`); a
   portrait screen reports a value below 1, which is why the `_vertical`
   table entries are height/width. Nothing is flipped and nothing is
   orientation-special.

The fallback for a theme with **no** vertical variant falls straight out
of (3) rather than being a separate path: on 1080x1920 (0.5625) DEcaffe
compares 16:9 (1.2152 away), 16:10 (1.0375), 4:3 (0.7708), 19.5:9
(1.6042) and 21:9 (1.8078), and renders its **4:3** layout --- a real
landscape layout drawn stretched over a tall screen, since theme
coordinates are normalised to the screen. It does not letterbox and it
does not rotate.

**Type scales with the SHORT axis on a screen held upright.** Every
`fontSize`/`fontSizeDimmed` fraction is a fraction of
`getIsVerticalOrientation() ? getScreenWidth() : getScreenHeight()`
(`Font.cpp:216-219`, the orientation test at `Renderer.cpp:188-191`) --
the one place in the schema where an axis is chosen by orientation rather
than used per-axis, and the only way a portrait layout keeps its
proportions as its long axis grows. droidtop scaled by height in both
orientations, which on a 1080x1920 screen made every themed font about
half again too large: Slate's portrait gamelist lost the ends of its
metadata labels and its titles no longer fit their list (rig, build 546).
`esDeFontScreenSize` is that rule, applied once for every text-bearing
element. Text that still overflows is ABBREVIATED, never cut: ES-DE
removes glyphs until the ellipsis glyph fits (`Font.cpp:1074-1078`), and
a textlist row is built single-line with the list's own width as its
maximum (`TextListComponent.h:209-213`). The reference for all of this is
the official Linux ES-DE rendering the same vendored theme at the same
screen size, `reference/es-de-render/run.sh` (that harness needs a theme
no newer than the AppImage it drives: a `<language>` the binary does not
know makes `ThemeData::parseLanguages` throw for every system, and ES-DE
then draws its own unthemed fallback while still logging the theme as
loaded).

That is a property of the theme, not a bug in the engine: no renderer can
invent the portrait artwork and element positions an author never wrote.
So the **default** theme on a portrait display is one that ships them.
Slate (ES-DE's own default, `16:9_vertical` and `4:3_vertical`, bundled
alongside DEcaffe, CC-BY-NC-SA, see NOTICE.md) is that theme;
DEcaffe remains the landscape default. The rule applies only when the
user has chosen nothing, an explicit choice always wins (including
choosing DEcaffe on a phone), and onboarding says what happened and
writes the result down as a real choice so a later rotation cannot move
the theme under the user (`ThemeAssets.defaultThemeFor`).

**View transitions (2026-09-11)**: moving between the system view and a
gamelist is animated by the theme, not by droidtop. The animation for
each of ES-DE's six transition kinds comes from a `<transitions>` profile
in the theme's own capabilities.xml, selected by
`ThemeData::setThemeTransitions` (ThemeData.cpp:1042-1120), which droidtop
now ports in full: everything starts at instant; with the setting on
`automatic` the profile is the one the selected variant named if it named
one and otherwise the theme's FIRST declared profile; a named profile
always beats `builtin-slide`/`builtin-fade`, which apply only when no
profile carries that name and the theme has not listed it under
`suppressTransitionProfiles`. The three animations are ES-DE's own and
their timings are not what the names suggest: a fade is a 120 ms fade out,
200 ms of black with the view already swapped underneath, and a 120 ms
fade back in (ViewController.cpp:951-992) -- not a cross-fade; a slide is a
400 ms ease-out cubic camera move (MoveCameraAnimation.h:25-33) that
travels downward into a gamelist, because ES-DE's system-select view sits
one screen height below the gamelists (ViewController.cpp:1229).

Twelve of the fifteen themes collected for the parity work declare a
profile, and DEcaffe's own first-declared profile fades both into and out
of a gamelist, so this was visible under the bundled theme on every
system entry. The PER-ELEMENT half — `stationary`,
`renderDuringTransitions` and `fadeAbovePrimary`, which let an individual
element sit still, keep drawing, or wear a fade while the transition runs
— is implemented for the view's elements (`EsDeTransitionBehaviour` in
the renderer); the list widgets' own items honour `stationary` and
`renderDuringTransitions` but not yet `fadeAbovePrimary`.

### Back goes back, and lands where you left (rig, build 542)

B from a PC game's detail put the user on the system carousel, at the top,
having lost the grid's position; getting back to the game they had been
looking at took B, B, Right, A.

The cause was that the shell had no answer to "where am I". The section
was one piece of state, the drilled-into group was a `remember` inside the
games screen, and the open detail was a third; the detail was drawn as a
sibling branch of the games screen, so opening it DESTROYED that screen
and everything it remembered, and closing it rebuilt the screen from
nothing.

**One stack answers it** (`ShellBackStack`, `shell-gamepad`). The Gaming
shell is a section, a group inside Games, that group's own options screen
and one game's detail -- and the stack holds all of them, plus the entry
the user was on in each one. A screen ASKS where it is and what to focus;
it does not own the answer and so cannot lose it.

- B closes the detail, else closes the group's own options screen, else
  leaves the group, else does nothing (the top of the shell is a home
  screen).
- **A screen opened from a level is a level**, not state the screen under
  it holds. The PC surface's "Stores and folders" was a `remember` inside
  the PC surface -- which stops being composed the moment that screen is
  drawn instead of it -- and the group's own drill-up sits ABOVE it in the
  tree, so the drill-up answered for it: `KEYCODE_BACK` reaches the view
  tree as an ordinary key event before it reaches the back dispatcher, so
  B out of Stores and folders left the group outright and landed on the
  carousel with the system reset (rig, build 548). Both back routes -- the
  dispatcher and the B/BACK key -- go through `nav.back()`, which leaves
  one level at a time.
- Returning to a group lands on the entry that was focused there: the PC
  grid scrolls to that card and focuses it, and a themed gamelist opens on
  that game. A group this session has not been in opens at the top, which
  is ES-DE's own "selection resets per gamelist".
- Opening another version or part of a game from its own detail (SPEC 7m)
  is a move SIDEWAYS, not a level: B from it still means "back to the grid
  I came from".
- Switching sections is a move at the top level and leaves no group or
  detail open.

The three levels are fixed rather than an arbitrary push-down stack,
because a push-down stack would let one game's detail sit under another's
and make B mean "the previous game" -- which is not what B means here.

### What Gaming offers beyond the theme (decided 2026-09-24)

ES-DE is the reference for what a gamelist can DO, not only for what it
draws. The theme decides the shape of a list; droidtop decides its
contents and the actions on it, and the floor is ES-DE's own gamelist
options and main menu plus what a handheld needs that a desktop does not.
Everything here is reached from the gamelist options menu (Select), the
Games section's own options menu, or the Settings section, drawn in
droidtop's chrome (§7j, §7k); every item is one catalog item or one menu
row, never both.

**Filters.** The options menu's Filter screen is ES-DE's
(`GuiGamelistFilter`): a text filter (a name substring, typed on the
on-screen keyboard), favourites, completed, kid game, broken, hidden,
genre, players, rating, developer, publisher, release year, alternative
emulator and controller. Each is a multi-select over the values the list
actually holds, the state is per system and per collection in the shell's
own prefs, a filtered list says so in its header (`gamelistinfo`'s filter
count) and in the hint row, and "Reset filters" is a row of the same
screen. **Hidden entries are left out of every list unless the hidden
filter is on** — the carousel's counts, the unthemed grid, the PC surface
and the Launcher's Games grid alike; today the flag is written by the
metadata editor and read by nothing.

**Sorts.** Name, rating, release date, developer, publisher, genre,
players, last played, times played and, inside a collection, system;
each ascending or descending; chosen per system and per collection, with
one default in Settings (Default sort order). Times played reads
`playCount`; a playtime sort arrives with measured playtime (§7g).

**Jump to letter and Random** stay as they are. **Search across the
library** is the text filter applied to the All games collection, opened
from the Games section's options menu (Y on the carousel opens that menu,
which is what the `Y Info` hint had been promising).

**UI modes** full, kiosk and kid (`UiMode`) stay as they are: kid mode
lists kid-game entries only and hides Settings; kiosk hides Settings and
the metadata editor; leaving either is a held press on the Quick Menu's
System tab.

**Screensaver.** ES-DE's four kinds — dim, black, slideshow and video —
after Off/2/5/10/15/30 minutes (today: the slideshow only), with the
slideshow's and the video's source (all games, favourites or one
collection), interval, and a name overlay as their own rows. A on a
slideshow or video launches the game shown; any other key wakes the
shell. Android's own display timeout still powers the panel down, and
the row says the screensaver shows only when its timer is the shorter.
Holding Select for the Quick Menu is decided at the shell's root in the
PREVIEW pass, on the way down, before any screen sees the press: a KeyDown
with a repeat count, or a KeyUp a long-press timeout after its KeyDown for
a source that sends no repeats. A hold takes every edge of the press, so
the screen under it never sees a short press; a short press passes through
untouched. Counting KeyDowns since the last KeyUp the root saw failed
because a short press's KeyUp is taken by the menu it opens (dq-shell2-01),
and reading the hold on the way back up lost to the screen that had already
opened its options (dq-shell2-02). While the
screensaver shows it has the whole window: the tab bar and the hint row
stand down.
The setting has one definition (`ScreensaverPrefs`) that the row writes
and the shell's idle timer OBSERVES: the row lives inside the shell, so a
value read once at start left a newly chosen timer on Off until the app
restarted (rig, build 814).

**Media viewer.** One pager per game over every media type droidtop has
for it: images (built), the preview video (ExoPlayer, unmuted, with
pause and seek on the hint row) and the manual (the platform's own
`PdfRenderer`, page by page, no dependency). Opened from the game's
detail and from the gamelist's options menu; the `open_with` chips (§12)
stay beside it for a person who prefers another viewer.

**Theme settings.** Theme, variant, colour scheme and aspect ratio
(built), plus the remaining ES-DE axes: font size (`fontSize`),
transitions (the `<transitions>` profile selection of "View
transitions": automatic, a declared profile, builtin-slide, builtin-fade
or instant) and controller family (the `controller` badge and helpsystem
glyphs). Each row is offered only when the active theme declares that
axis, as ES-DE greys them out. Navigation sounds have an on/off row. The
two `ThemePrefs` writers that exist with no reader (transitions,
controller family) are what these rows write, and the renderer reads
them.

**Browse themes** opens on the index and, when the index is empty or
older than a week (the downloaded `themes.json`'s own mtime), fetches it
in place first (the raw-file fetch reports real byte counts while it
runs; a failed fetch with no list says so and A retries); there is no
separate "Sync theme index" row, and the screen uses
the shell's gutter and palette tokens like every other screen.

**Gamelists are flat, by decision.** ES-DE lets a gamelist enter
subfolders; droidtop does not. The reasons people make folders are
covered elsewhere — attached content by §7h's DLC rule, one game in
several folders by §7m's parts and versions, deep trees by the walk's
own recursion — and a folder is one more level for a thumb to back out
of. Consequently the `folder` badge slot and the textlist's
`secondaryColor` are permanently inactive, `gamelistinfo`'s folder case
never occurs, and the "standing gap" wording that older passages of this
section attach to them is closed by this decision, not by building
folders.

**Controller mapping in the shell** is the A/B swap (§7b Controller) and
nothing more: Android already maps a pad's buttons, the shell has eight
actions, and full remapping belongs to the thing running the game (an
emulator's own settings, enginehost's controller screens, gamenative's
input profiles). The unread remap persistence in `GamepadAction` goes,
one mechanism.

**Where things live.** Settings is configuration. Live device state and
one-shot device actions — network, volume, brightness, Do Not Disturb,
VPN, Bluetooth, Battery, Swap screens, Reinitialize displays — are rendered by
the Quick Menu's System tab only: the catalog's System group carries a
`quickOnly` flag and every Settings renderer skips it
(`GamingSettingsCatalog.settingsGroups`), keeping under System just
Screens (main screen, game launch target, second-screen roles), Software
updates and Android settings. The Quick Menu shows those configuration
rows too, by id (`QuickTiles.CONFIGURATION_IDS`), as the same items.

**Battery tile (Droidtop/tracker#84).** The System tab's Battery row shows the
live level, "charging" and Android's Battery Saver state ("83%, charging",
"12%, saver on"), from the same `BatteryManager` sticky broadcast the themed
`systemstatus` element reads, plus `PowerManager.isPowerSaveMode` (no
permission). Pressing it opens the system's own Battery Saver screen, the same
way Network and Bluetooth open theirs: an app cannot switch power-save mode
(`setPowerSaveModeEnabled` is signature-only), so droidtop does not fake a
toggle. It is one more catalog item in the one QuickTiles mechanism. Not
built: estimated remaining playtime (Android exposes no non-privileged
estimate) and a governor or performance-mode control (needs root or a vendor
API, and root is never the standard path). One-shot library actions live in
the options menu of the list they act on and on the folder pages —
Rescan library, Scrape all systems and Find orphaned media in the Games
section's options menu (and Rescan on Game folders, the same item by id;
orphaned media is one row that finds on the first A and deletes on the
second, recomputing first), Scrape this system in a system's gamelist
options menu — and
the Settings section keeps no action rows but Check now (updates),
Update platform databases and Rebuild the library index (Data). A
setting exists in one place and a count is one number: the carousel and
the PC grid agree on what they count or say what each counts.

**Accounts and sources, not one screen per provider (directed
2026-09-28).** Users see games and systems. A source — a store, a
scraper, a plugin, a site — is a detail on a game and a filter, never
its own screen; droidtop is adding more of them, and provider-organized
settings do not scale the way a game-organized library does. Before this
decision a store sign-in lived on its own "Stores and folders" screen
(`pc_stores`), a scraper's credentials were split across one settings
group per provider on the Scraper screen (ScreenScraper, TheGamesDB,
IGDB, SteamGridDB), and Plugins/App integrations/Jobs were buried three
levels deep under Console systems, which is about ROM systems and had
nothing to do with any of them — four different places doing the same
job of "manage where droidtop gets something from." All of it now lives
in one settings-catalog screen, **Accounts and sources**
(`AppSettingsCatalogs.accountsAndSourcesScreen`, registry id
`accounts_and_sources`, reached from Settings > Library next to Scraper
and Console systems): one row per account or source — Steam, GOG, Epic,
Amazon Games, ScreenScraper, TheGamesDB, IGDB, SteamGridDB, Plugins, App
integrations, Jobs — showing that source's real status in the value
column (signed in / not, configured / not, how many active) with its own
actions and fields inside the row it opens. The screens that used to
carry these rows keep only what is genuinely theirs: `pc_stores`
(retitled "PC setup") keeps game folders, the Windows/Wine setup and
Downloads, linking into Accounts and sources for the store sign-ins
themselves; the Scraper screen keeps scrape BEHAVIOR (which source is
active, what content to fetch) and links into Accounts and sources for
credentials; Console systems keeps platform management and Enginehost,
with Plugins/App integrations/Jobs moved out entirely. Per-game
management (runner, versions, links, updates) and per-system settings
were already on the game's own detail and the system's own screen
respectively (§7i, §7f) and stay there — this decision is about
consolidating the PROVIDER/ACCOUNT layer, not those.

### One consistent way into Settings (directed 2026-09-28)

**Audit.** Before this pass droidtop already had one real, working route
from every mode into settings -- `BackButtonMenu`'s mode switcher (long-press
Back, the Android home screen's own long-press menu, the Desktop taskbar's
"Modes" button, and Gaming's own "Switch mode" quick-settings tile all open
the SAME dialog, whose "Modes and settings" row opens Global settings) -- plus
Desktop's own taskbar carries a second, direct "Settings" button straight to
`SettingsDesktopFragment` (Desktop's own settings, not Global's; a
deliberate, non-duplicate distinction -- every mode's own settings catalog
leads with a "Global settings" row to reach the other one, the same pattern
Gaming's Settings section already uses). What was missing was specific to
Gaming, where the owner's ask was sharpest:

- **Controller: no direct route from the Quick Menu.** The System tab
  already rendered the settings catalog's live `quickOnly` group as tiles,
  but that is a *view* of one settings GROUP, not a way to reach the
  Settings SECTION itself (Library, Appearance, Input, and everything
  else). The only path from the Quick Menu was two hops through "Switch
  mode" -> "Modes and settings", which lands on Global settings, not
  Gaming's own -- and Start, the button most pads and most players already
  read as "menu", did nothing at all in the shell.
- **Per-system settings** (folder, emulator/player, BIOS -- Settings >
  Library > Console systems) had no route from a system's own gamelist:
  its options menu offered "Scrape this system", "Get games" and a
  launch-screen choice, but nothing to configure the system itself.
- **Search** (`SettingsSearchIndex`, built 2026-09-26) was already real and
  already controller-driven (D-pad moves the result list, A picks) -- no
  gap found here.

**What changed, no new mechanism added:**

- **Start/Menu opens the Quick Menu**, exactly like R2 (`GamepadShell`'s
  key handler, `QuickMenu`'s own toggle-closed check) -- additive, since
  Start dispatched nothing in the shell before this. R2 stays the one
  named on screen (the R2 pill); Start needs no pill because most pads
  already read it as "menu" without one.
- **The Quick Menu's System tab gained a "Settings" tile**
  (`GamingSettingsCatalog.ID_SYSTEM_OPEN_SETTINGS`, in the existing
  `quickOnly` System group, right after "Switch mode") that deep-links
  into Gaming's own Settings section the same way "Browse themes" already
  does -- `EXTRA_GAMING_START_SECTION` back into the running shell's own
  `deepLinkToken` effect -- rather than a second settings surface or a
  hand-rolled navigation call from inside the tile grid. Drawn with a new
  gear glyph (`QuickGlyph.SETTINGS`) distinct from the existing
  `ANDROID` glyph "Android settings" already used, so the two
  settings-shaped tiles read as different destinations.
- **A system's gamelist options menu gained "System settings"**
  (`GamelistOptionsMenu`, shown whenever the list has a real console
  system id), opening the SAME registered Console systems screen
  (`SettingsScreenRegistry.get("console_systems")`) Settings > Library
  already renders, through the same generic `CatalogNavigator` dialog the
  menu already uses for "Get games" -- not a second folder/emulator
  picker. It first landed listing every system's folder (a known gap,
  since closed 2026-09-29): the row now deep-links, passing the system
  it was opened from through `SettingsScreenRegistry.get`'s argument so
  the SAME screen builder re-opens parameterized and its folder section
  holds just that system's rows -- the same targeted deep link the
  menu's "Get games" row already uses, with the screen falling back to
  its full list for a system with no folder on any games root. Settings
  > Library itself keeps opening the unparameterized full list.

Touch was already covered per surface and stays that way, deliberately
not consolidated into a single new widget: Gaming's own "Settings" tab in
the section tab bar (touch-reachable per §7j, `SectionTabBar`'s
`onQuickMenu` pill besides it), Desktop's taskbar "Settings" button, and
Standard's own long-press-wallpaper settings entry (stock launcher3
behaviour, left alone per this repo's "vendored trees ... hook or extend,
never rewrite" rule) are three different, already-obvious touch
affordances for three different shells with three different settings
surfaces -- collapsing them into one shared widget would be a fourth
mechanism competing with three that already work, not a consolidation.

### Settings and menu scrolling polish (directed 2026-09-28)

**Audit, not a guess.** No device rig was free for a Perfetto/`dumpsys
gfxinfo` trace in this pass (see below); the fix traces to a concrete,
readable main-thread cost, not a hunch: `CatalogRowView` calls a
`NestedScreenItem`'s `valueLabel` lambda directly inside Compose
composition (`AppSettingsCatalogs.kt`'s own doc comment already states
the intended contract -- "Read here, on IO: a value label is drawn on
the main thread" -- meaning the EXPENSIVE read happens once during the
suspend `groups()` build, and the lambda just returns the captured
result). Console systems' per-folder rows broke that contract: their
`valueLabel` called `resolvePlayer(ctx, resolved)` again from inside the
lambda, which walks every known player for that system and calls the
PackageManager (`isPackageInstalled`) for each one -- real binder IPC,
not free -- and Compose invokes a row's composable body on every
recomposition of that row, including every scroll frame that brings it
onto screen. A system list with many folders configured (exactly what
Console systems is) paid that cost on every fling. Fixed by resolving
the player ONCE per folder inside the existing `withContext(Dispatchers.IO)`
block (which already computed it once for the subtitle, wastefully
computing it a second time for the value column) and closing over the
result; no other `valueLabel` in the codebase used this pattern.

**Missing list keys**, the other concrete, addressable cause: none of
`SettingsCatalogView`'s three `LazyColumn`s (the settings list itself,
the choice picker, the search results) nor the Quick Menu's two lists
(the System tab's tile grid, the Notifications tab) passed a `key` to
`itemsIndexed`. Without one, Compose keys a row by its POSITION in the
list, so any state change anywhere (a toggle flipping, an async status
line landing, a selection moving) cannot tell "this is still the same
row" from "a different item is now here," which costs a wider
re-measure than the one row that actually changed and can lose a
row's own remembered state (`MenuRow`'s `bringIntoViewRequester` effect)
on the way. All five now key by the catalog item's own id (or the
notification's own key) -- the same identity every other mechanism in
this codebase already keys by (search results, pending focus).

**Not done in this pass, and why:** an animated (rather than instant)
focus/press transition on `Modifier.selectionFrame` -- the shell's one
shared selection idiom, used by every menu row, chip, tile and card --
was considered (design-language "press and focus animations") but
would require making a `Modifier` extension function `@Composable`
across 9 files and 14 call sites with no device to verify the result
against, for a purely cosmetic change unrelated to the reported
janky-scroll symptom. Flagged as a follow-up rather than shipped
unverified.

**Needs a rig check:** confirm the fix with real frame numbers, not
just the code-level reasoning above. On `emulator-5560`:
1. Install the CI debug APK built from this commit.
2. Open Gaming > Settings > Library > Console systems with at least
   a handful of system folders configured (mixed installed/missing
   emulators, so `resolvePlayer` has real candidates to walk).
3. `adb -s emulator-5560 shell dumpsys gfxinfo dev.droidtop.app reset`,
   then fling the list top-to-bottom several times by touch and by
   D-pad, then `adb -s emulator-5560 shell dumpsys gfxinfo dev.droidtop.app`
   for the frame-time histogram and janky-frame percentage.
4. Repeat on the previous build (`efd887ba` or earlier) for a real
   before/after, since this build already carries the fix.


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
| GOG library, auth, manifests, downloads, cloud saves | `service/gog/` (11 files) | wired — appears in PC library |
| Epic, same shape | `service/epic/` (12 files) | wired — appears in PC library |
| Amazon, same shape | `service/amazon/` (11 files) | wired — appears in PC library |
| itch.io library, API key auth, downloads | `service/itch/` (10 files) | wired — appears in PC library |
| Loose/DRM-free Windows games in a folder | `utils/CustomGameScanner.kt` | wired — appears in PC library |
| Per-game compatibility rating | `GameCompatibilityStatus` | shown in detail |
| Automatic per-game workarounds | `gamefixes/` | applied at launch |
| Playtime + last-played | `LibraryPlayHistoryDao` | shown in detail |
| Mods / Workshop | `mods/`, `workshop/` | not yet exposed |

`data/LibraryItem.kt` + `GameSource` (STEAM, GOG, EPIC, AMAZON, ITCH,
CUSTOM_GAME) is already the unified model, and `sync/FrontendSyncManager`
exists specifically to publish installed games to a frontend launcher
like ES-DE. droidtop **is** that frontend, in-process — so it reads
the DAOs directly rather than consume that manager's exported file drops.

So the gap was never "droidtop cannot discover Windows games." It is that
`PcGameProvider.scan()` reads Wine container shortcuts and `SteamAccess`
wraps `SteamService` alone. Everything else is built and unplugged.

itch.io is now wired alongside the other stores (API key auth, not OAuth).
Origin/Uplay unverified.

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

### A root is a place, not a picker result (directed by the rig, 2026-09-11)

One library across every source has an edge nobody had looked at: the
library has to be *nameable* in the first place. droidtop could only ever
learn a games root from a SAF tree URI, which means it could only ever be
told about places Android is willing to call a storage volume. That is a
narrower set than "places this app can read", and the difference is not
exotic — it is where real libraries live. An emulator's host share
(BlueStacks mounts one at `/mnt/windows/BstSharedFolder`: readable by the
app, not a volume, not mirrored under `/sdcard`) left the Android 9 rig
with no way to point droidtop at the user's games at all. So do mounts a
rooted device adds itself, a USB disk under `/mnt`, and any tree URI
whose volume does not follow the `/storage/<volumeId>` convention
`resolveStoragePath` has to reverse.

The design position, which is the same one §7g takes about sources: the
*place* is the fact, and the way the user named it is not part of the
library model. A picked tree and a typed path are two input methods for
one thing, so they write the same `droidtop_games_root_paths` set, are
scanned by the same walk, and are listed and removed in the same UI. No
"advanced" second list of roots, no second scanner, no per-origin
behaviour — which is what would have grown if the typed path had been
added as an escape hatch beside the real mechanism instead of as a peer
of it.

Two consequences worth stating, because both are deliberate:

- **A typed path is checked at entry, not at scan time.** The scanner's
  question (absolute, exists, is a directory, `listFiles()` returns
  something) is asked while the user is still looking at the field, so a
  path that is wrong, or that this app is not allowed to read, is refused
  with the reason. The alternative — store it and let the next scan find
  nothing — turns a typo into a silent empty library, which is the
  failure mode droidtop's scanner honesty rules (§7h) exist to prevent.
- **Typing a path is not a permission.** It reaches only what the app can
  already read: on API 30+ that is All files access, on API 26-29 the
  legacy runtime permission (§7b). A path the app has no right to is
  refused by the same entry check as a path that does not exist, because
  from the library's point of view they are the same fact.

### The library is an index; a walk is what refreshes it (directed 2026-09-17)

Until this rule nothing outside the console-ROM provider kept a scan result.
Every start of the process walked every games root again and drew "No games
detected yet." until it had, two minutes on the rig for a library that had
not changed. That was accumulation, not design: the progressive scan hid the
wait, the ROM cache fixed the provider that was slow at the time, and the
engine and PC providers that became most of the library got neither.

**The index.** `LibraryIndexStore` keeps each provider's last COMPLETE scan
result as one slice per `LibraryProvider.indexKey`, persisted in the index
database described in the next subsection (an index this build cannot read
is a walk, never an error). `Library` reads the index
first and publishes every slice it has at once; that is what the shell
draws at start. A provider whose slice is missing walks, streaming
progressively as a first run always did. A completed walk becomes the new
slice; a failed or cancelled walk leaves the old one alone.

**What walks.** A walk runs when a provider has no slice, when the root set
changed (`GamesRoots.rootsChangedSinceLastScan`, already detected and
already forcing a restart), and when the user asks (Settings › Rescan
library). Nothing else: a start of the process is not an event. On a
rescan the index stays on screen and each provider's slice is replaced only
when that provider's walk has finished, so a rescan never makes the library
vanish or shrink to a partial. `LibraryProvider.indexed` is false only for
the package manager's app list, which answers in milliseconds and changes
outside droidtop; everything that reads a filesystem is indexed.

**The index is updated incrementally, and nothing is dropped on the walk's
say-so (directed 2026-09-17).** A walk finishing one top-level folder of a
root replaces that folder's entries in the provider's slice at once; the
slice is not held back for the whole provider. A game the walk no longer
finds stays in the index in its own state, **missing** (shown as "broken -
missing"), with its favourite, play history, metadata and collection
memberships intact: a card is not thrown away because a drive was not
mounted this morning. A detected game can be marked as the REPLACEMENT of a
missing one from its detail; the missing entry folds into it (its facts move
to the new path) and disappears. Which detected game to offer first is
Pythia's own logic (`GameNaming`/`GameVersions`: same derived name, then the
0.6 similarity suggestion, as `_merge_version` / `record_ownership` /
`find_candidates` do it). Removing a games root removes that root's entries
outright; that is a choice, not a missing drive.

**How it is built.** A walk narrates itself: `LibraryProvider.scanProgressive`
emits `ScanStep`s, not growing lists. A `ScanStep.Segment` names the part it
is the answer for (`key`: a top-level folder's absolute path for the folder
walks, a console system's id for the ROM walk; `root`: the games root it is
under, or null for a part that is under none, which is what a store's own
database is) and carries everything that part holds NOW. A
`ScanStep.RootDone` names every part a root still has, so a folder that was
taken away can be told from one the walk has not reached: the first leaves
its games missing, the second leaves them alone. `LibrarySlice` is the index
entry for one provider, a list of those segments; `Library.libraryProgressive`
merges each step into the slice as it arrives, publishes the merged slice and
saves the parts that changed. A walk that fails or is cancelled leaves the
parts it never reached exactly as they were.

`LibraryEntry.missing` is the state, serialized with the entry, and it is NOT
the ES-DE `broken` metadata flag beside it: `broken` is the user's own
statement that a game does not work, `missing` is droidtop's statement that
the folder is not there. The card's second line and the detail's identity
line read "broken - missing"; the detail's primary button is disabled and
says the folder is not there, with the path under it, and Play is not
offered. Everything else on that screen stays, because its history, metadata
and collections are exactly what the entry is being kept for.

The fold is `Library.replaceMissing(missing, replacement)`, one function for
both entry points: it moves play history (`PlayHistoryStore.moveTo`, counts
added and the later last-played kept, Pythia's `record_ownership`
arithmetic), the favourite (`FavoritesStore.moveTo`), and whatever a provider
keeps of its own through `EntryFactsOwner.moveEntryFacts` -- the scraped
`game_metadata` row and the `collection_members` rows, both owned by
`ConsoleRomProvider`'s database for every kind of game, not only ROMs -- and
then takes the missing entry out of the index. A metadata row moves only into
an empty place, so a folder that has already been scraped keeps its own newer
scrape; the favourite crosses regardless, because it is the user's word and
not a scrape's. `MissingGames.candidates` orders the offer (same
`GameNaming.nameKey` first, then `GameNaming.similarity` at or above 0.6,
most alike first), and the same list is shown from both sides: "This replaces
a missing game" on a detected game, "Find its replacement" on a missing one.
`Library.keepOnlyRoots` is the drop: the shell calls it with the roots as
they are now when `GamesRoots` changes, and every part under a root that is
no longer configured goes, entries and all.

**One mechanism.** The ROM provider's own `RomDatabase` remains what makes
ITS walk fast; the index is what makes the START fast, across providers,
and it is the only thing that decides whether a walk happens at all. Play
history and favourites are applied to whatever list the library hands out,
index or walk (`withLibraryFacts`), so nothing about an entry differs by
where it came from.

### Where an update comes from (2026-09-25)

`GameVersion.latestKnown` / "an update is available" (7m) has one source:
**F95Checker's public index, `api.f95checker.dev`**, for games the user has
linked to their F95zone thread. It is the index F95Checker itself reads
(its `modules/api.py`, `fast_check` and `full_check`), ported through the
user's own Pythia (`plugin_sources/library/f95/f95_update_check.py`), and
it needs no F95zone account: neither call sends a cookie. Nothing else
claims an update; a game with no link says nothing about updates, which is
not the same as "up to date" and is not shown as such.

**How droidtop learns a game's thread: the user tells it.** A folder game's
detail has an "F95zone thread" row; the user pastes the thread's link (the
browser's `f95zone.to/threads/<name>.<id>/`, the short form, or the bare
number, `F95Thread.parse`). Nothing on disk says which thread a game is --
a game's own files do not carry it, and a folder name is not an id -- and
Pythia learns it the same way, from what the user told F95Checker (its
watch list, imported), never by guessing from a name. The link is the
GAME's, so it is written to every folder of the game at once and read from
whichever folder holds it (`LibraryGameGroup.f95Thread`); a missing game
folded into its replacement carries its link across (`GameLinksStore.moveTo`,
only into an empty place).

**The watch list, imported once** (`F95CheckerImport`, 2026-09-28; Settings >
Library > "Import from F95Checker"). Pasting a link per game is honest work
but the user has already told F95Checker every thread they watch, and that
is the one other source that can say which thread a game is. Its local
database, `db.sqlite3` (F95Checker's own `modules/db.py`), keeps one `games`
table whose row `id` IS the F95zone thread id -- `create_game` inserts
`thread.id` as the row's own id -- while custom rows (a game with no
thread) carry a negative id and `custom` set and are not watch-list
threads at all. The user picks that file through the system file picker and
droidtop reads it the same way the user's own Pythia reads it
(`plugin_sources/library/f95/plugin.py`: a read-only connection, negative
ids skipped): READ-ONLY, straight from the picked document's own file
descriptor (`/proc/self/fd`, `SQLiteDatabase`'s `OPEN_READONLY`) -- never
copied anywhere, never written, never held open past the reading of five
columns. Nothing leaves the device; the F95Checker index is not told.

Only the thread links are imported. The `name` and the two version strings
F95Checker keeps (`version`, the thread's newest, and `installed`, the
version the user marked installed there) are EVIDENCE for matching, never
data to write: a watch row names a library game when the two names are
equal the way `GameNaming.nameKey` compares names (case and punctuation
aside -- the same equality that already decides two folders are one game),
and the row CORROBORATES the match when either of its versions equals one
of the game's own versions the way `GameUpdates` compares versions (as both
sides write them, less a leading `v`). A name that is merely similar never
matches, for the same reason the scan never merges merely similar folder
names (its corpus holds three folder names 0.94 similar that are three
different games); similar names stay for a person to link by hand on the
game's own screen.

Nothing is linked by the import itself. The screen shows one row per name
match with both sides' versions on it; a corroborated match is shown marked,
every other match unmarked, and the two cases that make a pairing
ambiguous -- two watch rows sharing one name (a game and its mod), or one
watch row naming two library games -- are always unmarked, as is a game
already linked to a different thread. A game already linked to the row's own
thread is not offered at all. One action then links the marked games, with
a confirm step, through the ONE write path a pasted link already uses
(`Library.linkF95Thread`: every folder of the game at once, and the ask
about the new thread at once), so an import changes nothing a paste would
not, and reads the library the library already published
(`backgroundScanState` over the game kinds, grouping and matching in
memory; a walk is never started for it).

**What is kept.** Two tables in the library's own database beside play
history and favourites (`PlayHistoryDatabase`, `game_links` and
`f95_threads`): the user's links, and for each linked thread the index's
last-changed stamp, the version it gave, when it was asked and whether the
thread is gone. They are LIBRARY FACTS like play history: joined onto every
list the library publishes (`LibraryEntry.f95Thread`, `latestKnown`), never
written by a walk, never in a game record.

**When it asks, and how little** (`F95UpdateCheck`). Rounds ride the slow
pass's clock and its conditions -- only while something observes the
library, never in battery saver -- but run in their own coroutine: a walk
never waits for the network, and an answer reaches the lists when it
arrives (`Library.checkUpdatesInBackground`, then `republish`). A thread is
asked about at most once every six hours; linking a thread, or the detail's
"Check for an update now", asks about that one thread at once, but not
twice within a minute. A round is Pythia's: one fast check of up to ten
threads per request, and a full check only for a thread whose last-changed
stamp moved past the one kept (or that has no version yet). Requests are a
second apart; one round runs at a time; a failure (the index down, no
network) changes nothing it did not finish and the next round asks again.
The index refuses a whole batch when one id in it is not a thread, so a
refused batch is asked one thread at a time, and a thread it does not know,
or answers 400/403/404 for, is recorded as gone.

**When it is an update** (`GameUpdates.available`). Pythia's rule: the
version the thread gives is an update when it is none of the game's own
versions, compared as both sides write them less a leading `v`. It is the
game's fact, so a game that already has the thread's version in any folder
claims nothing, and every version row of a game that lacks it says so. Two
cases claim nothing: a thread that gives no version (blank, F95Checker's
`N/A`), and a game none of whose folders names a version, where there is
nothing to compare -- Pythia skips that case too.

**One wording, everywhere it shows** (`GameUpdates.line`, "v0.9.6 is
available"): the card's second line (from `LibraryGameGroup.displayEntry`'s
`availableUpdate`, which only the whole game can know), the detail's
identity line under the title, the F95zone thread row, and every "Parts and
versions" row.

### One file per game is the truth; the index is a light layer over it (directed 2026-09-21)

The user, on the console's performance and on what the index should be: "We
can build the index from the actual game files, rebuild it slowly over time
(or force a faster rebuild when needed), and use that index on startup." "I
propose separate databases." "The JSON files are lovely, because we can build
one file per game, reference the index, check the file when we actually need
to check a game, and update the index when needed... the JSON files mean
database format changes don't hurt as bad, and make exports/backups more
trivial. Plus, the JSON file should contain the actual launch information and
stuff, enginehost flags, etc. That makes the index database even faster, less
to load." And: "we also need to optimize the scanning, JSON reads/updates...
we're just layering an index on TOP of those fixes. The index can sit in ram,
even, which is the point."

This is the storage under the section above, which says WHEN a walk runs,
what a part is, and what happens to missing games. (Until 2026-09-21 each
provider's slice was one JSON file under `files/library-index/`; the
records and the index database replaced it.)

**What was wrong underneath (read from the code, 2026-09-21).** Costs that
grow with the square of the library, all inside a walk:
`Library.libraryProgressive` rewrote the provider's WHOLE slice file after
every finished folder; after every finished folder it re-sent the ENTIRE
library list to the shell; and for each of those sends `withLibraryFacts`
queried play history and favourites for EVERY id again. Separately, nothing
an entry needs at launch was kept: `EngineGameProvider.launch` calls
`resolveEntry`, which detects the engine and the game root again from the
folder, and the ROM launch resolves its system again. These are fixed first;
the index does not paper over them.

**The game record.** One JSON file per game, the source of truth for what
a walk knows about that game: identity (id, provider, kind, system), where
it came from (games root, the part of the walk that found it), the entry as
the walk produced it (title, sort names, media, state flags such as
missing and hidden), and its LAUNCH FACTS, which until now were re-derived
on every launch: for an engine game the game root, the engine and its version, the
executable, the Enginehost target and requirements; for a ROM the file, the
system and any per-game emulator choice; for a PC game its store install.
Records carry their own `formatVersion`; an unreadable record is a game to
detect again, never an error. They live under `files/library/games/`, named
by a hash of the id and sharded by its first byte so no directory grows
without bound and no game's own filename ever reaches the filesystem (the
j2me lesson, 7g). A record is written when a walk finds or changes that game,
and at no other time; it is read when that ONE game is needed: opened,
focused in a view that shows its metadata, launched.

**What the record is not the owner of.** Three things are the person's or
a scraper's rather than a walk's, and each keeps its own store keyed by the
game's id: scraped metadata and collection memberships
(`ConsoleRomProvider`'s `RomDatabase`, `game_metadata` and
`collection_members`, for every kind of game), and favourites and play
history (`PlayHistoryDatabase`). A walk joins scraped metadata onto the
entries it finds, so a record carries a copy as of its last walk; favourites
and play history are joined when the library publishes (`Library`'s
`LibraryFacts`).
Moving a game's facts to another id (`Library.replaceMissing`) is therefore
a move in each of those stores, not an edit of one record.

**The index.** A separate database (`library-index.db`, its own file, not the
play-history database), holding only what a LIST needs for every game: id,
provider, root, part, kind, system, title and sort names, the flags lists
filter on (missing, hidden, favourite, completed, kid game, broken), the
short metadata lists sort and filter on (genre, players, rating, release
date), the artwork a grid shows, and where the record is. Plus one row per
part: its folder's modification time and when it was last walked. It is
DERIVED: every column comes from a record, so a schema change is "drop and
rebuild from the records", which touches no games root and takes seconds;
that is what "format changes don't hurt" means. **A rebuild never shows a
smaller library than the one on screen** (decided 2026-09-25): it walks no
folder, so it cannot know a game is gone, and a game whose record cannot be
read (never written, an older shape, a corrupt file) is kept as it is shown
(`LibrarySlice.including`) and written back, which writes its record from
the list. Only a walk decides a game is missing. Settings' "Rebuild the
library index" says how many listed games had no record. It used to publish
the records alone, and 168 engine games became 6 until the next walk (rig,
build 814). It is small enough to read
once at start and keep in memory, and that in-memory index is what the shell
draws from; the database is its persistence.

**Updates are the size of the change.** A finished part replaces that part's
rows in one transaction and writes only the records that differ; play
history and favourites are joined once per id and then again only for an id
whose history or favourite changed. The shell is still handed the whole list,
at most every 250 ms while a walk runs (a changed-ids stream to the shell was
not built; see the decisions below).

**Rebuilding over time.** After the first walk the index is kept honest by a
slow pass, not by the user remembering to rescan: at low priority, a part at
a time with pauses between them, compare each part's folder modification time
with the index and walk only the parts that changed; a root that is not
mounted is skipped, never emptied. "Rescan library" is the same pass with no
pauses and no modification-time shortcut. Removing a root still drops its
rows and records (7g, above); nothing else deletes a record.

**Backups and exports** are the `files/library/` tree: the records are the
data, and any index can be rebuilt from them.

**Implementation decisions (2026-09-21 onwards).**

- `GameRecord.launch` is a closed `LaunchFacts` union (`Engine`/`Rom`/`Pc`/
  `None`) rather than one loosely-typed bag, matching `LibraryEntryKind`'s
  own per-mechanism split. `Engine` carries the whole `EnginehostTarget`
  (now `@Serializable`) alongside its own `runtimeRequirements` copy, so a
  launch reads one record and never calls back into `EnginesDatabase`.
- The index database (`library-index.db`, `RoomLibraryIndexStore`) keeps
  `LibraryIndexStore`'s existing `load`/`save` shape rather than replacing
  it with a new interface: `Library`'s own walk/merge/publish loop
  (`libraryProgressive`) is unchanged, only what's underneath one call is
  now a real, columned database instead of one JSON file per provider.
  `save` receives the whole merged slice each time (that's what `Library`
  already hands it) and diffs it against what it last wrote, so only the
  segments that actually changed become a `replacePart` transaction --
  "updates are the size of the change" without a second public API.
- One writer per provider (2026-09-24). `Library` holds ONE current slice
  per provider in memory, loaded from the index the first time anything
  asks, and every walk merges its steps into that slice under the
  provider's own lock before saving it; publications read it. Each walk
  used to keep a private copy loaded at its own start and save it whole
  after every step, so two walks of one provider at once (the slow pass
  and a rescan) overwrote each other: a removed root's games came back
  from the older copy, and a newly added root's records and rows were
  deleted because the older copy had never held them. Removing a root
  also moves a generation counter; a step from a walk that started
  before it, under a games root, is dropped, because that walk is
  reading the old root set.
- "Lists never read the record" means the *published* list never does:
  `RoomLibraryIndexStore.load()` hydrates each row's full `LibraryEntry`
  from its record ONCE, when a part is loaded or replaced, and caches the
  hydrated entry; every subsequent publish of the `Flow<List<LibraryEntry>>`
  serves that cache. This keeps every `LibraryEntry` field (media
  locators, scraped metadata, `pcInfo`, ...) exactly as before for the
  shell with zero edits, at the cost of one record read per game at
  load/merge time rather than zero -- still no games-root walk, and
  still far cheaper than re-detecting anything.
- "Publish CHANGES (added/changed/gone-missing ids) plus the current
  list" is satisfied at the PERSISTENCE layer (only changed parts are
  written to the database or the record store) rather than as a new
  shell-facing Flow, per this same section's explicit instruction to
  keep the existing `Flow<List<LibraryEntry>>` API so shell consumers
  need minimal edits. A changed-ids stream for the shell itself is not
  built.
- `parts.folderMtime` is the part's change stamp. For a part that is one
  folder the index reads that folder's own modification time; a part
  that is several folders stamps itself (`ScanStep.Segment.folderMtime`,
  see the slow pass below). 0 is "unknown", which the slow pass
  reads as "walk it," never "unchanged since forever."
- Removing a root (`Library.keepOnlyRoots`) is the one case where a
  segment disappears from a slice entirely rather than being replaced;
  `RoomLibraryIndexStore.save` detects a previously-known segment that
  is no longer present and deletes both its index rows and its
  records there, matching "removing a root still drops its rows and
  records; nothing else deletes a record."
- The slow pass skips unchanged parts in both folder-walking
  providers. `EngineGameProvider`'s parts are one folder each, so a
  part's own modification time is its stamp. `ConsoleRomProvider`'s part
  is a system under a root, several folders, and it stamps itself: one
  number over the modification times of the system's folders and of
  every folder its known ROMs sit in (a system may be sorted into
  subfolders). A ROM added, removed or renamed in any of those folders,
  or a subfolder added to one, moves the stamp; a ROM dropped into a
  subfolder that held none before does not, and "Rescan library" is the
  answer there. A folder that changed while it was being walked stamps
  the part "unknown", so it is walked again next round. The stamps are
  keyed by part AND root (`PartRef`), because a system id names a part
  under every root that holds that system. This replaced a full re-walk
  of every system folder every round (headers re-read, media re-resolved)
  for a library that had not changed.
  `PcGameProvider` stamps its two kinds of part too: each top-level
  folder of a root by its own modification time, read before the folder
  is walked, and the store part by one number over what that part reads
  (gamenative's store database and its write-ahead log, each Wine
  prefix's Desktop folder, the scanner's folders outside droidtop's
  roots, and the roots themselves). When the store stamp moved the whole
  provider is walked, because the store part suppresses Wine shortcuts
  against every folder game's install directory; the first round in a
  process walks it whole as well, because the walk is what records the
  install directories engine detection reads (`PcLibrary.knownInstalls`).
  A skipped folder keeps the installs and the scanner folders the last
  walk recorded for it. A changed compatibility cache or engine rule is
  not seen by the stamp; "Rescan library" is the answer there.
- The slow pass is a recurring loop, not a one-shot: it starts once,
  5 seconds after the first ordinary scan a shell asks for, and then
  repeats every 30 minutes (`Library`'s
  `SLOW_REBUILD_START_DELAY_MS`/`SLOW_REBUILD_INTERVAL_MS`), because
  "kept honest ... over time" describes an ongoing process, not a
  single pass after start. It runs only while something collects one of
  the library's lists (`Library.observed`, the lists' subscription
  counts; §2c): a round waits for an observer, and the last observer
  leaving cancels the round in progress. The Gaming shell and the
  Launcher's Games grid collect with the lifecycle, so a shell in the
  back stack is not an observer; the Desktop Start menu collects only
  while it is open. An observer returning after five minutes or more
  away gets a round at once (`SLOW_REBUILD_RETURN_MS`), and a round due
  in battery saver is skipped (`LibraryCore`'s `slowRoundAllowed`). A round never runs beside an ordinary walk:
  it waits until none is running, and a walk that starts cancels the
  round in progress (on a first start the ordinary walk IS the whole
  library, and the round used to read it all a second time beside it).
  A round covers only providers the index already holds a slice for;
  the app list, outside the index, is walked by every ordinary scan
  anyway. What a round changes is published into every list the shell
  observes whose kinds the provider covers; it used to go to an
  all-kinds list nothing observed, so its findings showed only after a
  restart. It runs on its own dedicated,
  `Thread.MIN_PRIORITY` single-thread dispatcher (`Library.slowDispatcher`)
  rather than the shared `Dispatchers.IO` pool every ordinary scan
  uses, so "low thread priority" is a property of the thread the walk
  work itself runs on, not just whichever coroutine collects the
  result. "A root that is not mounted is skipped, never emptied" is a
  real check (`EngineGameProvider`'s new `rootMounted` parameter,
  `scanRootsByFolder`) that only the slow pass supplies -- an
  ordinary scan/rescan keeps its pre-existing behavior (an unmounted
  root there already reads as zero folders) since changing that was
  out of this step's scope.
- A launch by id reads the record first (2026-09-24). A caller that
  holds only an id (a pinned game icon through `GameLaunchActivity`, the
  Desktop's Start menu) calls `Library.launch(id)`, which finds the game
  from its record, then from the index as the library holds it, and only
  then walks, and only the providers the index cannot answer for: the
  app list (outside the index) and a provider with no slice yet. A
  provider whose slice does not list the id is not walked; as of its last
  walk it does not hold the game, and a game added since is the rescan's
  job. Both callers used to walk the whole library (`scanAll`) to launch
  one game. It never throws: an unknown id, a game marked missing
  (refused, its files are not where the library last found them) and a
  launch that fails all come back as a reason to show, a toast from
  `GameLaunchActivity` and the Desktop's "A program didn't run" banner
  from the Start menu. `Library.launchInBackground` runs it in the
  library's own scope, because the Start menu closes on the same tap and
  its composition scope used to cancel the launch with it. The Start menu
  lists from the index (the same background scan state the other shells
  observe) instead of walking. No record is written for an app or a Wine
  shortcut for this; those are found in the index or by the scoped walk.

### Performance on the console: no per-item disk work where a list is drawn or walked (2026-09-24)

The code audit of 2026-09-24 found the console's slowness in four places,
each doing filesystem or parse work per item, on the wrong thread, or more
often than anything had changed. The decisions:

**Media is looked up in a folder listing, not by `stat`.** `EsDeArtwork`
answers every media question (artwork, the theme's `imageType` chain,
manual, video, the detail screen's media list) from a listing of the one
`<media root>/<system>/<type>` folder concerned, read once into a map of
lower-cased name to real name and kept. A ROM used to ask up to about 60
names by `stat` (seven types, two media roots, four extensions, plus
manual and video), at every walk and again at every cache load: about a
million FUSE calls for the rig's 18,000-file j2me folder. Lower-cased,
because Android's shared storage is case-insensitive and a `stat` lookup
was too. Freshness is the folder's own modification time, which moves
whenever a file is added to or removed from it: a listing is trusted for
two seconds, then one `stat` of the folder decides whether to list it
again, so media placed by ES-DE, a PC-side scraper or by hand appears
without a rescan. droidtop's own media writers (the scrapers, the
miximage generator, orphan cleanup) tell the lookup directly
(`EsDeArtwork.mediaWritten`) instead of waiting for that.

**The themed gamelist never resolves images in composition.** A
gamelist's carousel or grid runs the `imageType` chain for every game in
the list, and the list is the whole list ("All games" is the whole
library), republished every 250 ms while a walk runs. It used to do that
in `remember` on the main thread, for every game, at every publish. Now
each game's answer is worked out on the IO dispatcher, in chunks, and kept
per media locator across publishes; composition only reads answers
already known, and a game not yet worked out draws what a miss draws (the
element's default image, else its name) for the moment it takes. The
answers are worked out again only when `EsDeArtwork.mediaGeneration`
says a media folder actually changed, so a publish during a walk costs
the games it added and nothing else.

**Themes are parsed off the main thread; composition reads the parse
cache.** The system carousel parsed the active theme once per system
inside `remember`, on the main thread, before its first frame, and every
parse first re-listed the APK's theme assets and the user theme folder to
find out which theme was active. Now the discovered theme list is kept
until a theme is downloaded or the selection changes (the same
`ThemePrefs` listener that drops the parse caches), both caches are
concurrent maps, and the shell parses every carousel system's theme on
`Dispatchers.Default`, reading each system's logo out of that same parse
(one parse per system, not a separate one for the logo). A call site
whose parse is not ready yet keeps drawing the theme it last drew; the
one call site that has drawn nothing yet (the first frame after start)
still parses in place, once, because the alternative is drawing the
unthemed fallback and swapping the theme in, a visible flash at every
start.

**The PC surface groups games off the main thread.** Folding folders
into games (7m) derives a name from every folder by regex, and the PC grid
did it in `remember` on the main thread at every publish of a walk; the
game detail did the same over the whole Games list, compared every name in
it for replacement candidates, and listed the game's media folder, all
before its first frame. The grid's cards and the detail's grouping and
candidates are now worked out on the Default dispatcher and the media
listing on IO; composition reads the answers. Until the first grouping is
ready the grid draws nothing and its header says it is counting, rather
than a false "no games"; a republish keeps the cards already shown until
the new ones are ready. The detail's header names the game from its own
folder until the grouping answers.

**The slow pass reads only what changed, one writer at a time.** Its
round was a full rescan of every console system every 30 minutes and 5 s
after every start, beside the first walk, into a list nothing observed,
racing a rescan's writes. What replaced that (a change stamp per console
system, one current slice per provider, rounds that yield to ordinary
walks and publish into the observed lists) is recorded with the rest of
the slow pass's decisions in "One file per game is the truth" above.

**Drawing asks the disk nothing.** Whether a theme's file exists is a
fact of the parse: each parsed path answers once
(`EsDeThemeValue.Path.isFile`), and `ThemeAssets.loadTheme` asks every
path of a parse before caching it, so the renderer only reads answers
(it used to `stat` per element on every recomposition, audit P-Low). A
themed element's per-game media and a `gameOverridePath` file are looked
up on IO (`rememberOffMain` in the renderer), keeping the previous
game's answer on screen for the moment the next one takes. The console
game detail lists its scraped media, checks its manual and video files
and reads the systems for a scrape on IO, as the PC detail already did.
Onboarding's Appearance step reads its theme list and parses its
previews on IO.

**A key press recomposes nothing but what shows it.** The Gaming shell's
screensaver idle time was Compose state read as a `LaunchedEffect` key
in the shell body, so every key press and every touch recomposed the
whole shell to restart one timer. It is now a `MutableStateFlow` that
only the timer observes (`collectLatest`: each input cancels the pending
delay and starts a new one); writing it recomposes nothing.

**App icons are drawn once per version, never on the model thread.** The
Apps scan borrows Launcher3's `MODEL_EXECUTOR` (the icon cache refuses
any other thread), and it used to draw and PNG-encode every app's icon
there at every scan, stalling Standard's own model work behind it. Now
only the cache-owned calls stay on that thread (title and icon lookup,
Launcher3's icon state, `newIcon`); drawing, encoding and writing run on
the IO dispatcher. The file is named by package, `lastUpdateTime` and a
hash of Launcher3's own icon state for the app (`AppIconFiles`: locale,
SDK, themed-icon setting, resource hash, the day for a dynamic
calendar), so an app whose file already exists costs no drawing at all,
and a changed icon is a new path rather than a stale picture under an
old one. The drawing itself is `DrawableBitmaps.render`, the one
drawable-to-bitmap path for app icons (the Apps scan and the rows that
show an app's icon): on API 28+ it records the drawable into a `Picture`
and has `Bitmap.createBitmap(picture, w, h, ARGB_8888)` render it, which
goes through the hardware renderer when the picture holds a hardware
bitmap. A plain software `Canvas` refuses those ("Software rendering
doesn't support hardware bitmaps"), and Android 14's Clock icon
(Launcher3's `ClockDrawableWrapper`) is one: it listed with a blank plate
(rig dq-coordinator-23 F1). Files are written to a temporary name and renamed, and a
finished scan deletes every file no current app names (uninstalled
apps, older versions, the old `<package>.png` names, unfinished
writes); one scan at a time owns the folder.

### The scan's unit of work is a folder (directed by the rig, 2026-09-11)

Pointing droidtop at a whole-library root — the rig's games root is the
user's entire `G:\games`, stores and ROMs and engine games side by side —
broke the scan in a way no smaller root had shown. Build 523 surfaced 11
games out of hundreds and logged `ConsoleRomProvider timed out scanning`.
Three separate defects, each with its own rule now:

**A store's install tree belongs to the store provider.** The root's
`Steam` folder resolves to the real ES-DE platform id `steam` ("Valve
Steam", extensions `desktop`/`sh`), so the ROM provider walked the entire
Steam install — 1434 directories under `steamapps/workshop` alone. There
is exactly one prune rule, `ScanPrune`, and every walk that enumerates
folders asks it and nothing else: the engine detector's candidate folders
and nested search, the ROM walk, the games-root report, the ES-DE system
probe. It carries hidden folders and filesystem/sync markers (what the
detector used to own alone) plus the store rule: **a store library root is
recognised by its own marker, and a generic scan follows only the subtree
that holds installed games.** Stated that way rather than as a list of
folder names to avoid, because the list is long, version-dependent and
unknowable, while the games subtree is one documented path — the same rule
prunes `workshop`, `downloading`, `shadercache`, `temp`, `sourcemods` and
the 19 client directories of a real Steam install without naming any of
them. `steamapps/common` stays open, deliberately: a store-installed
engine game must flow through the same detection, grouping and launch
resolution as one in a games folder (§7g, yardstick item 5). Engine
detection may look inside `steamapps/common`; nothing looks inside
`workshop`. Every table entry is read off a real install and cited in
code; `Launcher` is deliberately not pruned, because a real GOG game in
this library ships its own.

**A store root is not a ROM system folder, whatever it is called.**
`steam` and `epic` are real ids in the platforms database ("Valve Steam",
"Epic Games Store") whose extensions are `desktop`/`sh`, because in ES-DE
they hold shortcut FILES. A folder that is an actual store library is a
different thing, and the ROM scan refuses it: build 525 still spent its
whole budget walking `steamapps/common` and listed five Linux launch
scripts as ROMs of system "steam". Store games are the PC surface's, with
their store facts, runners and install state (§7i).

**A console system is looked for two folders below a root, not one.** The
rig's root IS the whole library and the user's ROMs live at
`<root>/roms/<system>`, so at one level droidtop found no systems at all.
Not deeper, deliberately: platform ids are ordinary words (`android`,
`pc`, `windows`, `flash`) and a folder three levels down is inside a game,
where a subfolder named like a platform would invent a system that does
not exist. Two folders that resolve to the same system under one root
(`ps2/` beside `roms/ps2/`) are ONE unit of work, because the scan cache
is keyed on (root, system id).

**Where the system folders are is one walk, `SystemFolders`.** The scan,
the Console systems settings page, both "Scrape all systems" actions, a
gamelist's own scrape and the orphaned-media check all ask it. Each of the
others used to walk one level by itself, so on the rig they found no
system; the settings page guessed from file extensions instead and listed
every store folder as "Unrecognized" (UI pass 2026-09-24). A folder whose
name is no system's gets one only by the person's choice: "Choose a system
for another folder" marks it Not set (`SystemOverridePrefs.NOT_SET`), and
the system chosen on its page counts wherever the folder sits.

**Fixed (H6): the Console systems page now classifies folders.** A folder
that is a PC store root (Steam, GOG, etc.) or contains engine games shows
"Steam (PC games, detected per game: N games)" or "Engine games (detected
per game: N games)" with no system picker. Only folders that are truly
unrecognized ROM folders show "Not set" and offer the system picker.

**A time limit belongs to the unit of work it can bound, which is one
folder's own step.** The whole-provider timeouts (60 s streaming, 15 s not) are gone,
and what replaces them is `ScanBudget`: per folder, and checked *inside*
the walk. Both properties are the point. Per folder, because that is the
unit whose results can be kept when a sibling is slow — a budget that
discards everything it did not finish turns a slow scan into an empty one,
which is precisely what the rig saw. Inside the walk, because a coroutine
timeout cannot preempt a blocking `listFiles()` already in progress (a
real case on this device: a corrupted directory entry that hung `ls`
itself), so enforcing it from outside stops *waiting* without stopping
*walking*. Over budget means stop descending, keep what was found, and
name the folder it stopped in.

The budget covers a folder's **own** listing and detection, never its
subtree, and build 525 is why that distinction is in the spec: with a
subtree budget, `adult/` -- eleven engine folders, hundreds of games,
every individual step fast -- surfaced ONE game before its twenty seconds
ran out. Bigness is not pathology. What a budget must catch is a single
operation that does not come back (a directory of 18,126 entries on a slow
share; a corrupted entry that hung `ls` itself), and that is one folder's
own step. A folder whose own step runs over is skipped with its reason;
its siblings, its parent and every other root are untouched, and a large
healthy library is never truncated for being large. Total scan time is
therefore not capped, and must not be: results are published as they
arrive, so a long scan is a filling grid, while a capped one is a missing
library.

**Results are published as they arrive, per folder.** `ConsoleRomProvider`
already streamed per system folder and now gives each one a budget;
`EngineGameProvider` walked a whole root as one indivisible call and now
walks each top-level folder of a root separately, so `adult/renpy` appears
while `Steam` is still being read. A folder that fails or runs over costs
that folder: nothing already published is ever withdrawn.

**A scan's log is one line per folder and one per root.** Counts, by the
rule that fired — `1434 x Steam owns this tree` — with games found and the
duration, never one line per skipped directory. Scanning the whole library
used to print several hundred `Not listing games in …: it is a hidden
folder` lines and bury the one line that mattered. The duration is in
every line because "is the scan bounded" is a question the log has to be
able to answer.

**"The roots changed" is a subscription, not a check.** Which folders are
scanned is a preference, and the event that invalidates every provider's
cache is a write to it -- so the shell SUBSCRIBES to that write
(`GamesRoots.changes`) and walks when it arrives, rather than asking once
when a screen happens to compose. Build 539 is why: onboarding runs as an
Activity stacked on top of the Gaming shell and adds the games folder
while the shell's composition is alive; finishing it resumes the shell
through `onResume`, which recomposes nothing, so the one-shot check had
already run against no roots and never ran again. The library sat on "No
games detected yet." for eleven minutes with 151 games in the folder the
person had just named, and only the settings rescan intent could start a
walk. The same subscription also makes the restart honest: a walk already
in flight is walking the OLD folders, so a roots change cancels it and
starts the new one (`Library.scanInBackground(restart = true)`) instead of
joining a scan whose answer is stale.

**The scan log has two sinks, because logcat is the device's and not
droidtop's.** Every line still goes to `droidtop.ScanLog`, and the same
line is appended to a rolling file droidtop owns,
`<external files>/logs/scan.log` (256 KB, one rotation), with one line per
process start naming the build. A rig session on build 539 walked a whole
library and could not find a single scan line in logcat afterwards, which
made every scan defect in that session undiagnosable -- "found nothing",
"never ran" and "ran, and its lines were evicted from a shared ring
buffer" look identical when the log is gone. A rig reads it with
`adb shell cat /sdcard/Android/data/dev.droidtop.app/files/logs/scan.log`.

And because the screen that changes *which* folders are scanned should be
able to act on that change, ROM folders offers the same "Rescan library"
action Gaming settings does — the same item, by id, so the in-shell
renderer's real in-place rescan serves both rather than one screen getting
a second, weaker mechanism.

### Launch resolution: keep the default, expose it

The Daijisho model stands: a candidate list per platform with a stated
default, and a per-game override over it. With exactly one candidate there
is no decision; with several the default is stated, never silent. Where
each choice is made:

- **A PC or engine game** states its resolved runner and why it won on its
  own detail ("Runs with", 7i). The engines database's `strategies` list
  ranks only runners that are available; the per-game override is
  `LaunchStrategyOverridePrefs`, set and cleared from that row's picker.
- **A console system's** default player is Settings › Console systems ›
  the system › Player (`PlayerOverridePrefs`; unset means the first
  installed player in the players database's order).
- **A ROM's** own override is ES-DE's `altemulator` field, edited as
  "Alternative emulator" in the game's metadata editor and read before the
  system's default (`ConsoleRomProvider.resolvePlayer`).

Wine is the declared fallback for Windows titles nothing else backs; a
native Linux depot still wins where one exists (§5a).

The direction still standing is one per-platform launch section (ordered
candidate list and default, reorderable) covering ROM players, engine
strategies and PC backends under one control. An engine's default order
is not editable today: it is the engines database's.

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
the two are never even in the same scan (the Gaming shell runs Games
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

### What was removed, and what stays

- **`runtime-remote-stream/`** is gone, with the `moonlight-common-c` and
  `mbedtls` submodules it used. Streaming is windowcast's exclusively
  (directed, restated 2026-09-01). `LibraryEntryKind.REMOTE_STREAM`
  **stays**: it is how a windowcast-launched entry appears in the same
  library model as everything else, which is the point of that model.
- **Lemuroid** is no longer a submodule: nothing built from it, and its
  detection code lives forked-in at `library-core/.../romdetect/` (four
  files) with its community ROM database bundled as
  `library-core/src/main/assets/libretro-db.sqlite`. The attribution and the
  GPL-3.0 notice are in `NOTICE.md`; extending the detector means editing
  those files, not chasing an upstream checkout.

### What the store services give, and what they do not

Read from the vendored gamenative tree (2026-09-24), because "What is best
for users" above promises more than the store services hold:

- **Sources** are wired: `PcLibrary` reads the Steam, GOG, Epic and Amazon
  DAOs and `CustomGameScanner` into one `PcLibrary.Game` shape, and
  `PcGameProvider` publishes them as ordinary entries.
- **Compatibility** is wired from `GameCompatibilityCache`, cached only: a
  scan never makes a network call or needs a signed-in account, so a game
  carries no rating until something else has filled the cache.
- **Playtime has no source to read.** `LibraryPlayHistoryDao` holds only a
  last-played time, written by gamenative's own launch path, which droidtop
  does not use. The GOG, Epic and Amazon rows have a play-time column that
  nothing in the tree writes (always 0). Steam's owned-games call does
  carry lifetime minutes, but it is a network call to a signed-in session,
  which a scan may not depend on. So `LibraryEntry.playtimeSeconds` stays 0
  and nothing droidtop draws may pretend otherwise: last played and play
  count are droidtop's own (`PlayHistoryDatabase`), and real playtime waits
  on droidtop measuring a session itself, one mechanism per launch path.
  The PC surface therefore offers no playtime sort (7i): a sort on a number
  that is 0 for every game is a control that does nothing.
- **Cloud saves** are not reached from droidtop. **`gamefixes/`** is left
  out of droidtop's prefix preparation on purpose (`WinePrefixPreparation`
  lists it with the other store-specific steps it does not run).

### Playtime is measured by the shell, one way for every launch path (decided 2026-09-24)

`playtimeSeconds` has been 0 since the play-history store was written,
by a decision that measuring needed "a different mechanism per launch
path". It does not. Every launch droidtop makes hands the screen to
something else and gets it back, and that hand-back is what a frontend
can honestly measure: a session begins when `Library.launch` dispatches
the entry and ends when a droidtop Activity next resumes with window
focus — the emulator, enginehost, the Wine activity or the native app
having finished or been left. `PlayHistoryStore` gains a session table
(entry id, start, end) and `playtimeSeconds` is the sum of a game's
sessions; last played is the latest end. Sessions shorter than fifteen
seconds are kept but not counted as playtime (a launch that failed or was
backed out of is not play), a session is capped at twelve hours (the
device fell asleep in a game), and a session left open by process death
is closed at the next process start with the time droidtop last saw the
foreground. A launch into a container program (a Desktop `exec`) is not
a session: the desktop is not left. The measurement is droidtop's own,
so it is the same for a ROM, an engine game and a Windows game, and a
store's own lifetime minutes (Steam's, over the network) are never mixed
into it. Playtime feeds the sort of the same name (§7f), the "most
played" gameselector, the companion's tiles (§4d) and the detail's
identity line.

### What a ROM file is, and what droidtop does not manage (decided 2026-09-24)

- **One entry per file, as in ES-DE.** Two dumps of one game (regions,
  revisions, a hack beside the original) are two entries; the title
  disambiguation shows the tag that tells them apart, and nothing merges
  ROM files. §7m's grouping is about folders, and a person who keeps
  two dumps chose to.
- **A multi-disc game is its `.m3u`.** When a system's extensions include
  `m3u`, an `.m3u` in a system folder is the entry and every disc image
  it names is that entry's disc, hidden from the list and from every
  count; a `.cue` likewise hides the `.bin` files it names. A folder
  that holds only one game's discs and their `.m3u` is that game (§7h's
  container rules), and a disc image nothing names remains an entry of
  its own.
- **An archive is an entry as it is.** Emulators read `.zip` and `.7z`
  themselves, so droidtop never extracts one. Identity for a
  single-file `.zip` is the inner file's CRC32, read from the archive's
  own central directory at no cost, which is what the libretro DATs
  match; a `.7z` and a multi-file archive are identified by name only
  and say so in the scan log.
- **Saves, states and achievements are the runner's.** droidtop does not
  read, write, back up or sync an emulator's save files or save states,
  and it does not integrate RetroAchievements: each of those belongs to
  the emulator, to enginehost (§7d) or to the Wine prefix, and the
  game's detail links to where the runner keeps them rather than
  modelling them a second time. Store cloud saves (§7g yardstick) are the
  store client's feature and are reached through its own screens.
- **An unmounted root is skipped by every walk, never emptied.** A games
  root whose storage volume is not mounted (`StorageManager`'s volume
  state, or the path failing to list) is skipped by an ordinary scan and
  a rescan exactly as the slow pass skips it, so pulling an SD card
  marks nothing missing; a `MEDIA_MOUNTED`/`MEDIA_UNMOUNTED` broadcast
  for a volume a root lives on restarts the walk the way a roots change
  does.
- **All files access is the library's floor on API 30+.** A SAF tree
  grant alone is not a games root: every runner droidtop launches needs a
  real path, so the scanner reads `java.io.File` and nothing else, and
  onboarding says that (§7b Storage). droidtop does not carry a
  `DocumentFile` walk beside the real one.
- **The person's own data is never in a destructive database.** Scraped
  metadata, collections and their memberships, favourites and play
  history live in stores whose schema changes are explicit migrations,
  never `fallbackToDestructiveMigration`; a store this build cannot
  migrate refuses to open and says so, rather than opening empty. Caches
  (the ROM cache's `rom_entries`, the index) may be dropped and rebuilt.
  A query over a list of ids is chunked below SQLite's variable limit
  (999 on Android 9 and 10), because a system folder can hold more.
- **A round runs when the shell returns.** Beside its 30 minute interval
  the slow pass runs a round when a shell comes back to the foreground
  after more than five minutes away (a game copied over USB appears
  when the person comes back to the shell), and no round starts while
  the device is in battery saver.

## 7h. Scraper honesty, and what counts as a game (directed 2026-09-02)

**PC games get PC-native sources (directed 2026-09-24).** PC and engine games are scraped from the
sources that actually cover them, tied into the same pipeline as the ROM scrapers (one mechanism):
SteamGridDB (grids, heroes, logos, icons; it needs the user's own free API key, entered once and
stored like the ScreenScraper login; droidtop's own client, not the art-only copy inside
vendored gamenative) and Lutris (cover art and year from its search; a description and genres
from its per-game record), alongside the Steam store data already used for games with a Steam app id. Which
source won for each field is recorded per game, for ROMs and PC games alike.
All of a game's flavour is scraped, not only art (for PC games the text comes first from IGDB, as
for ROMs; SteamGridDB has none and Lutris only a description and genres): descriptions, genres, developers and
publishers, release dates, ratings, series, platforms, links, and the game's profile as the source
presents it. That text is shown to players (detail pages, the companion screen), so it is worth
the same care as the art.

**How that is built (decided 2026-09-25).**
- **One source searches by name; every other source is asked by identity.** The selected
  PC source (Lutris, IGDB or SteamGridDB, `PcScraperSource`) is the only one ever given a
  game's name, so the "one source, never a silent fallback chain" rule below still holds for
  guesses. Once a game is identified -- by its own store id, by an exact unique name match,
  or by the person's pick -- `PcFlavour` asks every other configured source for THAT game by
  an id: IGDB by the game's Steam or GOG id (`external_games`, `external_game_source` 1 and
  5), the Steam store by its app id, Lutris's per-game record by its slug, SteamGridDB by its
  own id or the game's Steam or GOG id. No source can put another game's text on this one,
  because none of them is asked by name. A source with no key set is not asked; only the
  selected source's missing key refuses the pass (`ScraperReadiness`).
- **Ids travel.** Lutris's `provider_games` names a result's Steam app id and GOG product id,
  and IGDB's `external_games` does the same, so a Lutris or IGDB match of a folder game
  becomes a Steam/GOG identity and the keyless Steam store fills its developer, publisher and
  date. A GOG entry is identified by IGDB's record of its GOG id when IGDB is set up, as a
  Steam entry is by the store's own record.
- **Which source wins each field.** Text (description, developer, publisher, genre, date,
  rating, series, links): IGDB, then the Steam store, then Lutris's per-game record, then the
  match itself. Covers: SteamGridDB's portrait grid, then Steam's library capsule and header,
  then IGDB's cover, then the match's own; the first that downloads is kept. Hero, logo and
  icon: SteamGridDB. Hero art is filed as ES-DE's `fanart` and a logo as its `marquees` (what
  ES-DE's own scraper files a wheel logo under), so themes asking for those types find them;
  an icon has no ES-DE type and goes to droidtop's own `icons` folder. A store install has no
  layout lookup, so the row also carries each path (`hero_path`, `logo_path`, `icon_path`),
  and `LibraryEntry.mediaForImageTypes` answers `fanart` and `marquee` from them.
- **The per-field record.** `game_metadata.field_sources` (JSON, `FieldSources`) names the
  source of every field a scrape wrote -- ScreenScraper, TheGamesDB, libretro database,
  libretro thumbnails, gamelist.xml, IGDB, Steam store, Lutris, SteamGridDB -- and "you" for
  a field changed in the metadata editor. A scrape never writes over a field whose source is
  "you"; that is what makes "a rescrape keeps your edits" true for text, not only for
  favourites.
- **A refusal on the way is reported, not swallowed.** A source asked by identity that
  refuses is not asked again in that pass once it rejected its key (401, 403, or the
  Twitch sign-in's 400 for a wrong IGDB Client ID or Secret) or refused five
  times in a row, and the pass's summary (and a manual match's result) names it with its own
  sentence and, for a key, the setting to fix. The game is still written with what the
  other sources gave.
- **Lutris, definitively.** Its search (`/api/games?search=`) carries a cover and a year and
  nothing else; its per-game record (`/api/games/<slug>`, keyless JSON, checked live
  2026-09-25) adds a description and genres. Neither has a developer, publisher, full date,
  rating, series or links. The 2026-09-24 survey expected the per-game record to be HTML only;
  it is not. Its `gogslug` is not used (for Hollow Knight it names the soundtrack), its
  `provider_games` is.
- **Where players see it.** A PC or engine game's detail is the focused-game panel beside the
  library grid (PcLibraryView's FocusedGamePanel -- the 2026-09-28 redecision draws PC games
  over the theme's frame only, so that panel is the one surface that draws a PC game's own
  facts): the hero art (the cover when there is none) opens it, the scraped logo, when the
  scrape filed one in the metadata row (a store install has no ES-DE layout), names the game
  in its own lettering in place of the title text, and under that an "About this game"
  section: the description (its first lines, a stop), the developer, publisher, date, genre,
  series and rating, each drawn only when scraped, and one line saying where each field came
  from, in the words the scrape recorded (`FieldSources.LABELS` over the entry's
  `fieldSources` map -- "Description from IGDB. Cover and hero art from SteamGridDB. Rating
  edited by you."). ES-DE's hide-metadata flag hides the section, the same semantic that
  hides a ROM's md_ fields on the theme's canvas. Each link is a row that opens it, on Game
  options (Y or a long press, PcGameMenu). The companion screen adds the publisher (when it
  is not the developer) and the series. A pinned home-screen shortcut uses the scraped icon
  before the cover.

An overnight ScreenScraper pass over the user's real library — 46 ROMs
across 11 systems — returned HTTP 403 for **all 46** requests: zero
successes, zero exceptions, zero files written. The app reported it as
`no match for 46, 0 failed`. Two rules come out of that, and they are
binding on every scraper source, not just ScreenScraper.

**A refusal is not a miss, and the type system says so.** Every
source's lookup returns `ScrapeLookup` (`scraper/ScrapeLookup.kt`), which
is exactly one of `Found`, `NoMatch` (the server answered and its response
carried no game — the only outcome that is a statement about the user's
library) or `Refused(source, httpStatus, reason)` (the server would not
serve the request at all — a statement about the API, about credentials,
or about a quota, and about nothing else). ScreenScraper, TheGamesDB (the
automatic search, the manual picker's search and the by-id fetch), the
libretro-database DAT download, Lutris, IGDB (including the Twitch
sign-in it needs) and the Steam store all answer in it; none may turn a
non-200 into an empty list or a null. A transport failure stays a thrown
exception and stays counted as `failed`. The scrape summary reports all
four buckets separately and may never fold any of the other three into
"no match"; a pass that was refused everything it asked for leads with
that, naming the source that refused, instead of reporting a count.
`formatScrapeSummary` (ROMs) and `formatPcScrapeSummary` (PC and engine
games) are pure functions so this arithmetic is unit-tested rather than
only observable on hardware.

**The server's own reason is surfaced, not discarded.** ScreenScraper
(and most sources) answer a non-200 with a short human-readable
explanation in the response body. That body is read from `errorStream` under a hard 512-character
bound, has every non-blank credential the request carried redacted out of
it *before* anything else touches it, is stripped of any markup an
intermediary added, and then appears both in logcat (tag
`droidtop.Scraper`) and in the summary the user reads. Credentials
themselves are still never logged — only whether they are present.

**A repeated refusal ends the pass.** Five consecutive refusals stop the
run and report, in the ROM pass and the PC pass alike; 46 refusals paced
~11s apart buy no information that the first five did not. A whole-library
pass (Scrape all systems) reports each system's own sentence and stops at
the first system whose source refused everything, since every later system
would be refused the same way; it never reports a bare count of systems
"scraped".

**A source that cannot be asked refuses before it is asked, and says how to
fix it** (decided 2026-09-25, after "Scraped 3 systems." with TheGamesDB
selected and no key). `ScraperReadiness` is the one check: a selected source
whose key or account is not set (TheGamesDB's API key, IGDB's Twitch
credentials, SteamGridDB's API key, ScreenScraper with no application credentials) returns a
sentence naming where to get the key, the setting it goes in (Settings >
Library > Scraper > the source's group) and the sources that need none,
before any folder is walked or any request made; every ROM and PC pass, the
manual match and the picker ask it. A 401 or 403 from a source that takes a
key or an account adds that same setting to the refusal
(`ScraperReadiness.credentialFix`), and so does the one 400 that means a
rejected credential: Twitch's token endpoint answers a wrong IGDB Client ID
or Secret with HTTP 400, not 401/403 (observed, review of 64d6547d,
2026-09-29), and that refusal names the IGDB setting and silences the source
for the pass like any rejected key. Any other refusal (a quota, an outage)
is not presented as the person's to fix. A refusal is counted per request,
apart from what else then found the game: a ROM the source refused but the
keyless thumbnails gave a cover is found AND refused, and a source that
refused every request leads the summary with that refusal and its fix
whatever the thumbnails found (rig, dq-shell2-01: a wrong key read "found
1"). A JSON error body is reduced to its own sentence (`status`, `message`,
`error`), never shown raw. **A list follows its selection only as far as it takes to show it**
(settings, choice pickers, the Quick Menu): a row already wholly on screen
does not move, so a tap never scrolls the next row under the finger (rig,
dq-shell2-01), and the settings navigator keeps each depth's scroll and
restores it on return from a sub-screen (dq-shell2-02). A new result in the
options menu scrolls itself wholly into view. **"Rescan library" is one
action** (`LibraryRescan`, wired by :app to `Library.rescanNow`) wherever
it is offered: it says it started, waits for the walk to finish and says
what it found. **A result is shown whole**: the options menu
draws an action's result as wrapped text under the actions, in a panel
that scrolls, never as a row cut to a line, because the fix is the last
sentence.

**Not decided here, deliberately:** the cause of the 2026-09-01 403s.
Credentials were verified present, verified to descramble, and the
personal account was configured. The two candidates — a newly registered
ScreenScraper application pair still awaiting manual approval, and
`softname` needing to match the *registered application name* — are
documented in `ScreenScraperClient.refusalHint` and printed on a 403.
`softname` is **not** changed speculatively: only the person who
registered the application knows what it was registered as.

### PC and engine games: what the scrape asks, and of whom

The ROM scrapers index console dumps by platform id and file hash; none of
them covers a Ren'Py build in a folder or a Steam install, so PC and
engine games (`isPcOrEngineGame`: Wine/store entries and every detected
engine kind) have their own pass, `PcScraper` (`scraper/PcScrape.kt`),
built on the same model: user-initiated, one selected title source, ES-DE's
`downloaded_media` layout, the same `game_metadata` rows and the same
never-clobber-a-user's-edits write.

- **Title sources, one selected at a time.** Lutris (keyless; the default,
  because it works on a fresh install) returns covers and a year; IGDB
  (the user's own free Twitch application credentials, never droidtop's)
  also returns description, developer, publisher, genre, date, rating,
  series and links; SteamGridDB (the user's own free key) returns names
  and years, and its art once a match is chosen. Whichever is selected,
  the rest of an identified game comes from the others by identity (see
  "How that is built" above).
  A folder name is cleaned of version and platform tags before it is
  searched (`PcScrapeTitle`), and a result is applied without asking only
  when exactly one candidate matches the cleaned title exactly
  (`PcMatching`); anything less is counted as waiting on the user's
  **Choose match**, never guessed. A year with no month or day is shown in
  the picker and never written as a date.
- **A Steam game is identified, not searched for.** An entry whose id, or
  whose `PcInfo.storeId` (a store-installed engine game), is `steam:<appid>`
  is first looked up in Steam's own public storefront record by that id
  (`SteamStoreClient`, keyless). That is to a Steam game what a file hash is
  to a ROM, so it is applied without a picker and counted apart
  ("by store id"). It is not a fallback chain: only when the store has no
  public record of the app does the game go to the selected title source,
  and a store refusal is reported as one rather than quietly becoming a
  name search. The cover is the portrait library capsule, with the store
  record's own header image tried only if that download fails.
- **A scraped cover is a PC game's cover.** What a store row or a Wine
  shortcut brings on its own is Steam's 32-pixel client icon, an Epic icon
  or the icon inside an `.exe`. The PC provider therefore shows a scraped
  cover ahead of it (`withScrapedMetadata(scrapedArtworkFirst = true)`),
  and the scrape's "missing artwork" filter counts only a scraped cover as
  art for those entries. Engine games keep the ordinary order: their art is
  their own folder's or the ES-DE layout's.
- **A title is not a file name.** Media for an entry that is not a folder
  is filed under its title made safe for FAT/exFAT (`PcMediaLayout.fileSafe`),
  since a handheld's games root is usually an SD card.

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

### A folder that holds games is a container, in both walks (rig, 2026-09-16)

droidtop has two walks over a games root -- engine detection
(`GameEngineDetector`) and the PC folder scan (`PcFolderScan`) -- and the
rig showed what happens when only one of them knows the rule. The rules
below are one set, asked by both, in this order:

1. **A folder with two or more ENGINE games directly below it is a
   container**, whatever evidence it carries of its own. `adult/godot`
   holds two Godot games and one loose Godot Linux build left beside them;
   the loose build is precise Godot evidence, so the category folder
   became a game called "godot" and both games inside it were never
   walked. Two, not one: a folder with exactly one game below it is that
   game's wrapper or its payload, and both walks already have rules for
   that shape. Only the immediate children are tested, with the precise
   rules only, so this costs one directory listing per child.
2. **A folder that directly holds an executable and no engine evidence at
   all is a PC game, and its subfolders are its payload.** The engine walk
   stops there instead of descending: `Ghost Recon Breakpoint/benchmark`
   (an index.html and sixteen PNGs) and `The Movies/Docs` were listed as
   games by the database's weakest row, "there is a page here", while the
   games they sit inside were not listed at all.

   The rule is ONE function, `GameEngineDetector.isPlainPcGameFolder`, and
   every walk asks it. Build 540 is why that is written down: the walk
   applied it and `detectGame` did not, so the walk correctly returned no
   engine game for `Ghost Recon Breakpoint` while `detectGame` read the
   payload's `index.html` one level down, called the folder engine-owned,
   and `PcGameProvider` dropped its PC entry as a duplicate of an engine
   entry that was never created. Both folders vanished from the library
   entirely. A folder that holds an executable and no engine evidence of
   its own is a PC game, listed once, whatever sits beneath it.
3. **A folder that holds files of its own AND games below it is those
   games' root**, however many there are, and when exactly one game sits
   below it, that game's own markers are this folder's
   (`Humble/macdows95_windows/macdows95/{PLAY.bat, files/}` is the game
   `macdows95`, whose root is `files`; build 540 listed a game called
   `files`). The version-named wrapper
   (`BeingADik/BeingADIK-0.8.3-scrappy/{renpy,game}`) is the same rule
   recognised by the name instead of by the files. Neither applies inside
   a store tree, where `steamapps` holding one installed game must still
   yield the game. The one-game form of this rule
   could not see a Ubisoft install: `Far Cry 5` keeps its launcher files
   in the game folder and its executables in `bin` and `bin_plus`, so the
   list got `bin` and `bin_plus` and never Far Cry 5. `EA/SimCity` is the
   same shape with three payload folders. A container proper holds no
   files of its own, which is what still makes `EA`, `Ubisoft`, `adult`
   and a games root containers.

4. **Evidence that could have come from below only names a folder when
   it is that engine's own root layout** (rig, build 542). A detection
   rule that reads an unnamed subtree -- Unity's three-deep player search,
   the compiled-Ren'Py `.rpa`/`.rpyc` fallback -- proves a game is
   somewhere under a folder without saying where, so it matches at every
   folder on the way down and the OUTERMOST match is taken. `Pirated`
   holds three games (`PRAGMATA`, `The Movies`, `The Tenants Pets`); the
   third is a plain Unity install with `UnityPlayer.dll` in its own root,
   so Unity's probe matched at `Pirated` too, nothing below `Pirated` was
   precise, and the container took the entry while the Unity game appeared
   in no list at all.

   So a subtree rule whose evidence is found IN a folder, in a folder that
   also holds the executable that starts it, names that folder
   (`GameEngineDetector.engineHere`). Unity's own root is the player
   runtime beside the player; a folder holding the runtime and nothing to
   run is a payload folder, and the outermost-match rule still reads it
   correctly. This is deliberately narrower than "any subtree rule at
   depth 0": Ren'Py keeps its archives in the game's `game/` subfolder by
   that engine's own layout, so a depth-0 match there would name the
   payload rather than the game.

   The `.gamenative` file in `Pirated` is not what made this happen, and
   is not evidence of anything. gamenative writes that file into every
   folder its own scanner called a custom game
   (`app/gamenative/utils/CustomGameScanner.writeGameIdToFile`), and that
   scanner is the one-level rule `PcFolderScan` replaced -- so the marker
   in a store or category root is droidtop's own stale verdict, read back.
   Nothing in either walk reads it. It is a dotfile, so it is not "files
   of its own" for rule 3 either.
5. **A store's own install root is never a game**, in either walk, however
   much evidence its client leaves in it. `PcFolderScan` already had this;
   the engine walk did not, so a Steam library folder with one engine game
   under `steamapps/common` could be claimed by the outermost-match rule
   above and listed as a game called "Steam".

**A folder name only means a ROM system where a system folder can be.**
ES-DE's layout is `<root>/<systemId>/<rom>` and droidtop allows one
container level above it (`<root>/roms/<systemId>`), so nothing deeper is
a system folder however it is named, and nothing inside a store's install
tree is one at all. `Ubisoft/Far Cry 5/data_final/pc` and
`Ghost Recon Breakpoint/sounddata/pc` are game data four levels down that
match the real platform id `pc` (DOS games, `dosbox_pure`); they are what
build 540's log line `2 x it is a console system folder, scanned for ROMs
instead` was counting, and that line named neither of them.

**A scan line names the folders each rule fired on.** Counts by reason
replaced one line per skipped folder (several hundred on the rig) and are
still the shape of the line; the folders are now named beside the count,
up to six per reason and then `+N more`, relative to the folder the line
is about. A count alone cannot be acted on: "2 folders skipped" gives a
person no way to find the games behind them.

**A budget costs a folder its own evidence, never its subtree.** The
per-folder budget (SPEC 7g) bounds one folder's own step. When that step
runs over, the folder cannot claim to be a game on evidence a rule never
finished gathering -- but its children are still walked, each under a
budget of its own. `adult/RPGMaker` ran past 20 s on a cold scan of the
rig's shared folder and all six games under it were dropped with it. A
budget that drops a subtree loses real games, which is worse than the slow
scan it exists to bound; bigness is not pathology.

**droidtop's own answer is not read back out of a vendored preference.**
The folders `PcFolderScan` finds are turned into library items directly,
in the same pass, and only written to gamenative's `customGameManualFolders`
as a side effect for its own screens. They used to be written there and
read straight back: `PrefManager.setPref` hands the write to a DataStore
coroutine and returns, while `candidateFolders()` reads synchronously, so
the first scan after an install read the EMPTY set. That is why build 537
(upgraded, with a previous run's value in the preference) listed 171 games
and a freshly installed 539 listed 151 with every folder game missing.

## 7i. The PC surface — droidtop's own actions, on the theme's own layout (REDECIDED 2026-09-26)

**Superseded.** The 2026-09-10 decision below ("a PC in a box, not an
ES-DE system") had the PC/engine list break from the theme entirely: its
own fixed `PcSurface` grid (`PcGameCard`, a `LazyVerticalGrid`, its own
header, its own chip row, its own help row), rendered the same regardless
of which theme was active. Live use showed what that actually was: "we're
essentially using the gamenative menu with our theme, but its layout and
stuff need to be reactive to the theme" (the owner, 2026-09-26). A PC or
engine game is a game in Gaming mode's one library, and the reason every
console system's gamelist is themed is the same reason a PC game's should
be: so the games a person is looking at read as the theme they picked,
not as a screen the theme happens to be adjacent to.

**The redecision.** PC and engine games are now a `GameGroup.Pc` gamelist
like any console system's (§7f): the SAME `EsDeThemedView`/
`EsDeSystemListView` machinery, the active theme's own gamelist view
(its `<carousel>`/`<grid>`/`<textlist>`, whichever it declares, or none),
its element positions, sizes, variants and aspect ratios, and its
metadata elements (`md_image`, `md_video`, `md_description`,
`md_developer`, `md_rating`, `md_lastplayed` and the rest) bound to the
focused game exactly as a console ROM's are. `PcSurface`'s grid,
`PcGameCard` and its own chip row and help row are deleted outright, not
kept as a fallback: one mechanism draws every gamelist now.

What does NOT change: the runner model, availability states, overrides,
the game's own actions, ProtonDB, the Lutris import, and same-game merge
(all below, unchanged from 2026-09-10) — none of that is an ES-DE
concept, so none of it moves into the theme.

**Revised again the same day: an expanded themed view, not a plain one
(owner direction 2026-09-26).** The redecision above still had this
section's own actions reachable only through A opening a fixed detail
screen, which read as "the gamelist, then a second, different screen" —
not the single coherent surface a theme's own PC card should open into.
The owner's correction: "We don't want it to be the same as the others,
it's an expanded view, because PC is so much bigger... [Extended] with PC
regions that follow the theme's styling where the theme has no slot...
We can also add an L2 menu for extra PC actions and stuff." Three changes
from this:

- **A launches, exactly like a console ROM's, when the runner is ready,
  and runs the one setup action when it is not** — never opens a menu.
  This is the same decision the old fixed detail screen's primary button
  always made ("Play" when ready, *is the setup action* otherwise), made
  once now in the ONE launch handler
  (`PcRunnerOptions.resolveAndPlay`, called from `GamepadShell`'s
  `onLaunch`) rather than inside a screen of its own, so the gamelist's A
  and `PcGameMenu`'s own "Play"/"Set up" row can never disagree.
  **One exception (2026-09-29, Droidtop/tracker#140):** the Windows
  system-files setup is the one action that downloads several hundred
  megabytes, so the press alone never starts it. Both implicit routes --
  A on a not-yet-set-up Windows game, and the menu's own "Set up" row --
  stop on an offer that names what would be fetched (Wine and the
  Windows base system) and its size, and declines cleanly
  (`PcRunnerOptions.windowsSetupConsent`, installed by the shell the
  same way `LaunchDisplay.chooser` is; a process with no shell keeps the
  gate open, because its callers — Settings' setup row, the Steam
  sign-in's button — state the cost before the press). Once accepted,
  the setup's own progress lines render as chrome, not as the red
  launch-failure banner they used to share: a multi-minute download
  painted as an error is what made the silent start read as a crash
  (rig, build 1101: "Installing Windows system files... 0%" on a red
  banner, no prompt — verify-2026-09-29/bst/w1.png).
- **The PC gamelist is an EXPANDED themed view, not a plain one.** It is
  still the active theme's own gamelist -- its element positions, sizes,
  variants, aspect ratios, fonts and colours, exactly as any console
  system's -- extended with two droidtop-drawn regions the ES-DE element
  schema has no slot for at all, layered OVER the theme's canvas rather
  than shrinking it (the same rule the shell's help row already follows,
  §7j): a filter/organisation strip (source/store, engine, install state
  -- `PcExpandedOverlay`, replacing `PcSurface`'s own retired chip row)
  and a compact per-focused-game info strip (resolved runner, source,
  play time), both in droidtop's own palette
  (`MenuTokens`) so they read as droidtop's addition to the theme rather
  than a second competing look.
- **L2 opens `PcGameMenu`,** an ES-DE-style in-context menu (the same
  `GuiGamelistOptions` PATTERN `GamelistOptionsMenu` already uses for the
  whole gamelist's Select-button actions, scoped here to ONE game) for
  everything ES-DE genuinely has no concept of: the resolved runner and
  its picker, Wine/container settings, ProtonDB, the Lutris import, the
  F95 link and update state, same-game merge, and versions/segments.
  Checked against every other binding in `GamepadShell.kt`/`QuickMenu.kt`
  before choosing it: L2 was unclaimed (L/R are the sibling-system jump,
  R2 opens the Quick Menu on hold, L3/R3 are unused) -- the one gamepad
  region with nothing else on it. Y still opens the same menu for a
  PC/engine game (a player who has not learned the L2 convention still
  finds it, the same row Y already opens for a console ROM's own "Info"
  would have been if PC still needed one), and the menu is reached by
  touch through the gamelist's own hint row exactly like every other
  bound action (design language: "the hint row is the touch route to pad
  buttons") -- no on-screen row is L2-only.
  `PcGameMenu` replaced the old fixed-layout `PcGameDetail` screen
  outright (renamed, not kept as a second implementation): its hero-art
  header and scraped "About this game" text are gone from the menu
  itself. The 2026-09-26 revision believed the theme's own gamelist
  widget would keep showing a focused PC game's art and its
  description/developer/rating/genre while browsing, the same as a
  console ROM's; the 2026-09-28 frame-only redecision took PC games off
  that widget entirely, so those facts live on the focused-game panel
  instead (7h, "Where players see it" -- restored there, never dropped).
  What remains here is a Dialog-hosted menu over rows this section's
  action groups already produced (`rememberPcActions`), unchanged in
  substance.
  Since 2026-09-29 those rows are drawn under three section headers
  (Play, About, Fix and advanced; 13, "Gaming mode"), the same rows and actions,
  filed by the question a player opening the menu is asking. Since
  2026-09-30 only the first of those is the menu's top page: About and
  Fix and advanced are pages it opens (revision at the end of this
  section's 2026-09-28 redecision, above).

**PC is always visible (owner direction 2026-09-26: "PC should always be
visible").** Unlike a console system, whose card only ever appears once
it holds a game, `GameGroup.Pc` is forced into the carousel's group list
regardless of how many PC/engine entries the library currently has. An
empty PC group opens straight into first-run setup (`pc_stores`, the same
settings-catalog screen the "Stores and folders" row and this section's
own "First run" paragraph below already describe) instead of an empty
gamelist -- this is also what fixes the gap the previous revision above
left open: the group could not previously be OPENED at all with zero
entries, so its own documented first-run screen had no way in.

**What still costs a themed screen its point of difference.** ES-DE's
element schema has no element type for a runner, a prefix, install state
or a store login, so the theme's own canvas alone was never going to
carry a PC game's own concerns -- `PcGameMenu` and the two expanded-view
strips exist for exactly that reason. The theme owns the list's layout;
it was never going to own the runner picker.

**Known gap, left open rather than shipped half-built:** `GamelistSort`
(name/rating/release date/last played) has no "size" option, unlike
`PcSurface`'s own retired sort -- the expanded view's own filter strip
covers source/engine/install state, which was the larger of the two
gaps the previous revision above named, but a size sort still has no
home. Real follow-up work, not implemented in this pass.

The original decision text follows, still current except where a
revision above says otherwise.

**Redecided again 2026-09-28: droidtop's own content over the theme's FRAME
only, not the theme's gamelist widget.** Live use of the 2026-09-26
"expanded view" (above) showed the shape it actually produced: PC and
engine games still rendered through the theme's own primary list widget
(a real `<carousel>`/`<grid>`/`<textlist>`), which is right for a console
system with a handful of boxart-shaped entries and wrong for PC, whose
library is orders of magnitude larger and needs real per-game facts
(runner, store, install/update state) legible AT A GLANCE, not just on
L2. The owner's correction: "take the GENERAL menu layout from the
selected theme, but fill the rest in -- a blanket list like we currently
have for ArtBookNext is a terrible idea." Two changes from this:

- **The PC group's list is now a frame-only themed render.** `EsDeThemedView`
  gained a `frameOnly` mode (`esDeElementBindsGame`, `EsDeThemeRenderer.kt`):
  every element that binds to the FOCUSED GAME -- the primary list widget,
  `md_*` metadata, badges, rating, gameselector-fed art -- is dropped: only
  the theme's background, colours, fonts, header/logo, help area and
  proportions remain. `PcExpandedOverlay`'s two strips (layered OVER the
  theme's own full render) are retired along with the full render itself
  for this one group; `PcLibraryView.kt`'s `PcLibraryContent` draws the
  content area instead: a cover-art grid (`GameCard`, reused unchanged from
  every other card grid in this shell -- missing art now takes an optional
  theme-coloured plate, `GameCard`'s new `plateColor` parameter, the same
  per-system accent `SystemThemeColors.forSystem` already gives a drill-down
  screen) and a focused-game panel (hero art or the same plate, the
  scraped logo, the "About this game" facts with the line saying where
  each field came from -- restored to this panel 2026-09-29, 7h "Where
  players see it" -- the description, playtime, the resolved runner,
  source/store, update state, and a note that ProtonDB is asked for on
  `PcGameMenu` rather than fetched here -- compat
  info stays "evidence, never a gate," so this panel never fetches it on its
  own). `PcGameMenu` (L2, and now Y/long-press through `GameCard`'s own
  binding) is unchanged: everything ES-DE has no slot for still lives there.
- **Filter, sort and search are the one shared model (since 2026-09-30
  behind one Browse button and the filter dialog, see the revision below;
  first built as clearable chips), not the console Select-menu's
  "Sort"/"Show" rows.** `dev.droidtop.shell.
  gamepad.query.LibraryQuery` (`LibraryQuery.kt`/`LibraryQueryUi.kt`) is a
  UI-free, Context-free search+filter+sort pass any list can use --
  `LibraryQueryScope` states which facets and sorts a list offers, `apply
  To` filters then sorts, and `LibraryFilterDialog`/`LibrarySearchDialog`
  are the one filter dialog and search field (the chip row,
  `LibraryQueryChips`, was deleted 2026-09-30). The PC library's own scope offers store, engine, install state,
  favourites, played, recently played, genre, developer, year, update,
  missing art and hidden as facets (never runner/ready/ProtonDB -- those
  cost a folder walk or a network ask per entry, which this pass never
  pays for a whole list at once) and name/last-played/playtime/year/
  rating/SIZE as sorts -- closing the "no size sort" gap the 2026-09-26
  pass left open, by the same mechanism rather than a patch to
  `GamelistSort`. "Continue playing" and "Installed" (owner direction) are
  this model's own built-in `NamedLibraryView`s, exactly like a person's
  own saved view -- one mechanism for both, not a second "sections" concept.
  `GamelistOptionsMenu`'s "Sort"/"Show" rows are hidden for the PC group
  specifically (`systemId == PC_SYSTEM_ID`), since the chip row is now the
  one place that filter and sort live for it; its `"PC setup"` row (renamed
  from "Stores and folders" once store sign-in moved to Settings' "Accounts
  and sources") is unchanged.
- **Console gamelists are untouched.** `LibraryQuery` is shared
  infrastructure, not wired into any console system's list in this pass --
  a real follow-up, not implemented here.

**The PC library is a controller-first storefront view (owner, 2026-09-30,
Droidtop/tracker#148): "the PC UI is still pretty broken. We probably need
to redesign that tab specifically. There's an extra menu that I can't
scroll through with button inputs, etc, and it's kinda terrible for a PC
gaming UI."** The reference is Steam Big Picture and the Steam Deck
library: a grid of art, a game page with one big Play or Install button,
and a short menu of grouped actions. Reading the code found the "extra
menu" and two more places where a pad had no way in: `PcGameMenu` was one
flat list of up to two dozen rows (Runs with, Play, Install, F95, links,
scrape, collections, favourite, ProtonDB, replacement, merge, engine,
versions, the runner's settings), moved by a virtual cursor that acts on
the key UP edge only (no repeat down a long list, and the unhandled down
edge reaches Compose's own focus search); the filter chip row above the
grid had no D-pad route at all (the grid answered Up at its top row, and a
horizontal chip row cannot take Left/Right because those switch the
system); and the focused-game panel scrolled by finger only. Not
reproduced on a device from the session that made this change; the shape
of the fix is that no part of the PC tab is a long list or a touch-only
surface any more. What changed:

- **A still plays, Y opens the game's own page, L2 the short menu.** A on a
  card launches exactly as before (2026-09-26 decision above, unchanged:
  `PcRunnerOptions.resolveAndPlay`). Y or a long-press opens `PcGamePage`
  (`pc/PcGamePage.kt`), a full-bleed Dialog: art on the left; on the right
  the name (or the scraped logo), ONE big primary button (Play, the one
  setup step that makes it Play, or why it cannot, from `PcPlayState`),
  Favourite and Options beside it, and under them the About facts and
  where each came from, a column of focus targets the D-pad scrolls
  through. The page has no key handling of its own: buttons and blocks
  are real focus targets, so the pad's focus search moves between them, a
  focused block scrolls itself into view, and B is the system Back.
  `PcPlayState` (`pc/PcPlayState.kt`) is the ONE answer to "what does the
  primary button say and can it be pressed", read by the library's hero
  panel, the page and `PcGameMenu`'s first row.
- **The focused-game panel is short and never scrolls.** Art, name, the
  same big Play pill (a tap on it is A), the runner, source, play time
  and update, and three lines of description; everything longer lives on
  the page. The "About this game" facts and the field-source line moved
  there with it (7h), unchanged.
- **One Browse button replaces the chip row.** Sitting in front of the
  grid, it states the current shelf, game count, sort and search; A opens
  the filter dialog (`LibraryFilterDialog`), which now also carries the
  built-in shelves (All games, Continue playing, Installed) beside the
  person's saved views, and a Search row. Up from the top row of the grid
  lands on it through the grid's `focusProperties` (not a key handler),
  and Up from it is cancelled, so the tab bar is never reached (design
  language: the D-pad never reaches the top bar). `LibraryQueryChips` is
  deleted; "nothing is buried behind a dialog that a chip could have
  shown" (2026-09-28) gave way to every control being reachable by pad,
  because a horizontal chip row cannot take Left/Right.
- **`PcGameMenu` is short.** The top page holds Play (or the setup step),
  Runs with, Install/Manage install, Add to favourites, "Game info and
  links" and "Fix and advanced" (the latter two only when they have
  rows), and Close: at most nine rows. The two long lists, with the
  rows they always had, are the pages those two rows open; B goes back to
  the top page before it closes the menu. B is answered on both key edges
  so the platform never turns it into a second Back.
- **Input stays on the existing mechanisms.** Controller input is being
  unified into one pipeline (Droidtop/tracker#152); this change adds no
  new key handler beyond `PcGameMenu`'s existing virtual-cursor one
  (adjusted for the pages) and uses standard focus (`focusProperties`,
  focusable blocks, `ShellChip`) everywhere else, so there is little to
  move. The swapped-confirm layout (`ControllerPrefs.swapConfirmCancel`)
  is not honoured by the page's standard-focus A and B; that follows with
  the pipeline.

The original decision text below predates BOTH the 2026-09-26 and
2026-09-28 revisions; where they disagree with it, the revisions above win.

The user's framing (2026-09-10): "we explicitly want THAT category to
break from the ESDE theme, because of how much infrastructure we have to
build. It needs to be a PC in a box, like droidtop, controlling
detection, runners, and etc based on availability." Design pass and
build plan: `/root/coordination/research/pc-in-a-box/README.md`.

### Scope

The surface owns every game that is not a console ROM and not a native
Android app: store games (Steam, GOG, Epic, Amazon), Windows and Linux
games in a folder, and engine games (Ren'Py, RPG Maker, KiriKiri and the
rest of the engines database). It owns their discovery, their detection
results, the choice of what runs them, their install and prefix state,
their per-game overrides, and their metadata actions.

It does not own console ROMs, native Android apps, or anything in the
theme's system view other than the `pc` card itself.

**What "every game in a folder" means on disk.** A games root is walked
down through folders that are not games until games are detected, bounded
at four folders below the root, skipping console-system folders at every
level. Each detected game is one entry named by its own folder, and a
folder that merely CONTAINS games is never itself a game — a root added
above the engine folders (`GameSync/Adult/<engine>/<game>`) yields every
game inside it, not one game called "Adult". A folder that is itself a
game stops the descent, because a game's own subfolders (`game/`, `www/`,
`<name>_Data/`) are not further games. Detection rules that only prove "a
game is somewhere below here" (the compiled-Ren'Py archive fallback,
Unity's player search) match every folder between the game root and the
evidence, so the outermost match in a branch is the game.

### The model

**Entries.** One `LibraryEntry` per game, exactly as §7g requires — no
second model and no per-source screen. Source is a filter, never a
category. A folder-scanned game and a store game are the same object with
different `PcInfo`.

**Runners.** A runner is the named thing that runs a game, and it is
first-class vocabulary the user sees, not an implementation detail. There
are four: Wine (via the vendored gamenative engine, never root — the
`sealed` `WineEngine` makes that structural, §5b), a native Linux process
inside a container (§3), an enginehost plugin for the game's engine (§7d),
and Kirikiroid2. Kirikiroid2's row must state its real limitation — it
opens the app, not the game — wherever it is offered.

**Availability.** Every runner, for every game, is in exactly one of four
states, and the last two are different things:

- **Ready** — it can run this game now.
- **Needs setup** — possible on this device, one named action away. The
  row shows the action ("Install the Ren'Py plugin", "Set up Windows
  games", "Sign in to GOG"), never a failure.
- **Not on this device** — the requirement cannot be met by anything
  droidtop can do here. Shown, dimmed, with its one-line reason. Never
  silently absent: a user who does not know an option exists cannot decide
  about it.
- **Not for this game** — the game itself does not offer it (no `.exe`, so
  no Wine; not a KiriKiri game, so no Kirikiroid2). Hidden behind a "why
  not" expansion, because a column of "no" rows buries the real choice.

Availability is computed from real per-device and per-folder facts, never
from a table keyed on engine alone: a Windows executable in the folder, a
Linux build in the folder, a valid ImageFS and an existing prefix, whether
a root shell answers, whether enginehost is installed and can read the
folder, and which plugin bundles its capabilities provider reports.
Enginehost's bundle list stays **advisory** (§7d): droidtop annotates with
it and never gates a launch enginehost would otherwise resolve.

**Resolution order: availability first, the database's priority second.**
The engines database's `strategies` list only ranks what is already
available. The resolved runner and the reason it won are stated on the
game, which is what turns that priority from an invisible constant into
something the user can see. The runner's name carries the engine where
the engine is what it means — "enginehost (Ren'Py) — the default for this
engine" — because enginehost runs a Ren'Py game through one plugin and an
RPG Maker game through another, and "enginehost" alone does not say
which. One engine-naming table serves that row, the picker and
"Install the … plugin".

**Overrides.** Per-game runner choice is an override over that stated
default, editable where the game is and clearable back to the default.
This is the Daijishō default-with-priority model §7g already commits to,
now applied to PC entries as well as engine ones.

**Honesty about what cannot run yet.** A runner whose machinery exists but
whose output the user cannot see is **Needs setup with the real reason**,
not Ready. A Play button that is known in advance to produce nothing is
worse than an honest row, and the surface may not ship one. The Wine row
was that case until the renderer seam landed (§5b) and is now Ready when
the environment is provisioned; the rule and its one build-level switch
stay, because the next backend behind the same seam (FEX/arm64ec) will
need them again.

**Root never gates a Gaming game.** Native Linux inside a container
needs root today and is therefore "not on this device" on an unrooted
console. It is never the only route offered for a game that has another;
where it genuinely is the only one, the game says so with the reason
instead of offering a launch that cannot work. Root remains desktop-only.

**Root is used by Desktop mode's container stack and nothing else**
(directed 2026-09-24). Two older uses were removed rather than kept as
"optional on rooted devices":

- *Ending an emulator before relaunching it.* Players whose preset sets
  `killPackageProcesses` (7e2) were `su -c am force-stop`ped. They now get
  `ActivityManager.killBackgroundProcesses` (the normal
  `KILL_BACKGROUND_PROCESSES` permission, declared by `library-core`),
  which ends the emulator's processes while they are in the background,
  as they are when droidtop is in front launching. It cannot stop a
  foreground service, and on Android 14+ an app targeting 34 may only
  end its own processes, so there the call does nothing and the emulator
  is relaunched as it is, the same as it already was on every unrooted
  device.
- *Importing an upstream GameNative install.* It copied GameNative's
  Room database and DataStore out of `/data/data/app.gamenative`, which
  no non-root app can read, and Android offers no sanctioned hand-off of
  another app's private data. It is deleted, with its settings rows.
  Signing in to Steam/GOG/Epic/Amazon/itch.io in droidtop rebuilds the library.

### Views

**Entry point.** The theme's system card for the PC group, with the
theme's own transition. **The card never wears DOS or IBM branding**
(decided 2026-09-25): ES-DE's `pc` system is IBM PC and DOS and every theme
draws it that way, so the group themes as `windows` (ES-DE's Microsoft
Windows system) when the active theme's system view declares `windows` art
that exists, and otherwise as a folder no theme ships
(`ThemeAssets.NEUTRAL_PC_THEME_FOLDER`), so every per-system element falls
through to the theme's own defaults and the carousel draws the plain name
"PC". droidtop fabricates no art for it. The same folder is what the
system view, its neighbour slots and the accent read, since they all ask
through the group's one theme key; the companion screen and Desktop name
the group in text only. The `pc` id itself stays the group's: its system
id, its `downloaded_media` folder and its scrape. B returns to the carousel with focus
on that card. Nothing else in the system view changes.

**Library (REDECIDED 2026-09-26, revised again the same day -- see the
top of this section for both).** The PC group's list is the active
theme's own gamelist view, EXPANDED — the same `EsDeThemedView`/
`EsDeSystemListView` call every console system's gamelist renders
through, on `GameGroup.Pc`'s own folded, one-card-per-game list
(`LibraryGrouping`, §7m), laid out, positioned and sized by the theme,
showing the theme's own `md_image`/`md_video`/`md_description` and the
rest for whichever game is focused, moving by the theme's own
`<carousel>`/`<grid>`/`<textlist>` or, absent one, droidtop's headless
per-game Up/Down (the same fallback a themeless console gamelist already
used) — PLUS `PcExpandedOverlay`'s two droidtop-drawn regions layered
over that same canvas without shrinking it: a source/engine/install-state
filter strip (`PcSurface`'s own retired chips, filtering this gamelist
instead of a grid of its own) and a compact resolved-runner/source/
play-time strip for the focused game. The group is always in the
carousel, even with zero games, opening straight into first-run setup
when it is empty (see the top of this section).

A game's actions: A launches when the resolved runner is ready and runs
the one setup action when it is not, exactly like a console ROM's A (see
the redecision above — this no longer opens a screen). B returns to the
carousel, the shell's own back route in both its forms, matching every
other themed gamelist. X toggles favourite in place. Y and L2 both open
`PcGameMenu`, an in-context menu over everything ES-DE has no slot for:
the resolved runner and its picker, Wine/container settings, ProtonDB,
the Lutris import, the F95 link and update state, merge, and
versions/segments — there is exactly one place to look for what can be
done with a game beyond playing it. Select opens `GamelistOptionsMenu`
for sort/scrape/"Stores and folders", the same as any system's gamelist.

**Game detail.** In order: identity; a **Runs with** row carrying the
resolved runner, its reason, and the picker; a primary button that is
Play when the runner is Ready and *is the setup action* when it is not;
install and storage actions for store games; prefix and graphics; saves;
controls; engine settings; metadata, scrape, collections, favourite and
hide; and compatibility. The download queue is not this game's and lives
under Stores and folders. While the primary button is focused the hint
row names what A does ("A Play", "A Set up"; "A Launch" on a console or
app detail). Art narrower than 320px is not stretched across the hero:
the plate is drawn without it. An app's detail draws its icon at icon
size on the plate and offers App info and Uninstall (Android's own
screens); B is the hint row's, never a Back button beside it.

**Compatibility is evidence, never a verdict and never a gate.** It is
other people's results on other hardware. It is shown factually, it may be
filtered on by the user's own act, and it may never hide an entry, reorder
the library, or block a download (directed 2026-09-01).

**ProtonDB, read-only, asked for rather than fetched (§7e3, built
2026-09-25).** For a game with a Windows route or a known Steam app id,
the detail offers a "ProtonDB" row; selecting it looks up
`protondb.com`'s own public summary endpoint and, once found, opens
ProtonDB's own page for that app rather than droidtop rendering a
verdict of its own. The lookup runs only on selection, never on open —
the same "asked for, not fetched" rule gamenative's compatibility badge
already follows — and every outcome, including no reports, no known
Steam app id, or the request being refused, is a sentence on the row
itself rather than a silent blank. The Steam app id it looks up is the
game's own when it is a Steam entry, otherwise the id Lutris lists for a
game of EXACTLY the same name (`ProtonDbClient.steamAppIdFor`) — a
similar name is a suggestion, not an automatic identity, so it is never
guessed.

**First run.** An empty PC library offers concrete repairs — sign in to a
store (Steam, GOG, Epic, Amazon, itch.io), add a games folder, set up Windows games, see what is downloading
— never an empty grid. They are optional, skippable, and reachable again
from the surface's options menu, which is the SAME list: implementation
showed that "three first-run cards" and "the options menu" were the same
four actions, so they are one settings-catalog screen (`pc_stores`,
registered by `:app`) rendered in place by the catalog navigator the
shell's settings already use, rather than two implementations of the same
rows. Each row states the state it found — whether a store is signed in,
how many game folders exist.

### Relationship to the theme engine

**Redecided 2026-09-26** (see the top of this section): the theme now
owns the system view, the `pc`/`windows` card and its art, AND the
gamelist — its element positions, sizes, variants, aspect ratios and
metadata bindings for a PC or engine game, exactly as for a console ROM.
It needs no theme patch to do this: a theme's `windows` art where it has
some, its own defaults where it does not, the same fallback the entry
point already used before this redecision.

What the theme still does not, and structurally cannot, own: a runner, a
prefix, install state or a store login. ES-DE's element schema has no
element type for any of those, and its gamelist models "a game and its
metadata" rather than "a game, four runners and an override" — so that
half of this section (the game's own detail screen, its sections, the
Lutris import, ProtonDB) is unchanged droidtop UI, reached from the
themed gamelist via A on a game (opens detail, not launch) and via
Select's `GamelistOptionsMenu` ("Stores and folders"), never a fixed
screen drawn on top of the theme's canvas.

Engine games fold into this one PC entry, with engine as a filter inside
it, rather than appearing as invented per-engine systems in the carousel:
the shell has ONE group for the PC category, and it owns the `pc` system
id and theme folder outright. Everything that is not a console system's
ROM belongs to it — a detected engine game (which carries no system id at
all), a store or Wine title, a Linux-container game, and whatever a user
put in a games-root folder named `pc`, which can no longer become a
second card of its own that this surface would then render empty.

Droidtop's own chrome that the theme genuinely does not reach — the
game's detail screen, the Quick Menu, the settings catalog and the
adopted gamenative dialogs — still takes its colour and type from one
droidtop palette, not a separate look per screen.

### Relationship to the Quick Menu

The surface is a shell screen, so the Gaming Quick Menu (§7f) opens over
it unchanged. In-game is a separate surface and gets no new mechanism: an
enginehost game's in-game menu is enginehost's own, a Wine game's is
gamenative's own menu over its renderer, adopted rather than rewritten and
taught the same contract. The PC surface never invents a third in-game
overlay.

### Relationship to enginehost

droidtop routes; enginehost resolves. droidtop reads enginehost's
capabilities provider to say what is installed, asks enginehost to install
a missing plugin through enginehost's own configure intent, and links to
enginehost's own per-engine controller and save screens rather than
modelling them again. droidtop never side-loads a plugin itself and never
reimplements an engine's input model.

### Reuse, not reimplementation

`:runtime-windows` already compiles the whole vendored gamenative tree
(§9), so the store logins, install and download flows, container
configuration dialogs, compatibility badge and folder-game scanner are
present in the APK and need entry points, not ports (§7c's "increment 2").
Those entry points are `:app` Activities, because the Gaming shell
cannot depend on `:app` and these screens are Compose UI rather than
catalog data: the store's own app screen for one game (which brings its
install, verify, update, DLC and delete dialogs with it), the downloads
queue, an OAuth shim per store (Steam QR/password, GOG/Epic/Amazon OAuth,
itch.io API key), and the container-configuration dialog.
Which container a game's prefix row opens is droidtop's own question and
has one answer shared with the launch path — the game's own prefix when a
store app id keyed one, droidtop's single provisioned container otherwise
— so the prefix somebody configures is the prefix the game starts in.
Playing is never one of these screens' jobs: a runner is resolved on the
game's own screen, so their own play buttons return the user there rather
than opening a second launch path that could disagree (a store-installed
engine game is exactly that case — gamenative would run it under Wine,
droidtop runs it on enginehost).
What droidtop adds on top is what gamenative has no concept of at all:
engine games, enginehost routing, availability across four runners, the
per-game override, and the carousel entry point.

### One runner section, for the runner the game uses

The detail's sections are the actions on this game, and a section for a
runner it does not use is worse than nothing: it reads as a setting that
applies. A game running on enginehost gets enginehost's saves, controls
and engine settings; a game taking the Windows route gets its prefix, and
where saves and controls live inside it; a game with neither gets no
runner section at all, because the primary button already says a game
cannot run and a section of dead rows repeating that is not information.
No section is titled like its own first row -- "Prefix and graphics" over
a row called "Prefix and graphics" says one thing twice.

**The Lutris import entry point sits beside the per-game override it
sets (§7e3, built 2026-09-25).** A game taking the Windows route gets an
"Import a Lutris install script" row in its "Runs on Windows" section,
next to "Prefix and graphics" — the same section, because setting a
game's program from an imported script is the same kind of act as
picking one by hand, not a separate mechanism. Once an import has set a
game's program, that same section shows it as its own row ("Program:
…", naming the import as its source), selectable to clear back to what
droidtop detects on its own. This is the Daijishō default-with-override
model this section already commits to (see "Overrides" above), reached
by a second path.

## 7j. Portrait and touch-first chrome (directed 2026-09-10)

"Most people will be on phones without controllers." droidtop's own
chrome --- the tab bar, Quick Menu, PC game detail, gamelist
options, settings, onboarding and the launch chooser --- treats a screen
held upright with no pad attached as a primary target, not a degraded
one. Two rules carry the whole design.

**One layout system, no duplicated screens.** `LocalShellWindow` carries
the live window size class (Android's own compact/medium/expanded
thresholds) and orientation, and every screen measures itself from it.
There is no portrait COPY of any screen and no orientation branch beyond
the handful of places where the shape genuinely differs:

- the screen-edge gutter is one definition (48dp at TV distance, 16dp on
  a compact screen), not a number repeated at every call site;
- game cards and the PC grid size from the window rather than the
  console's 220dp;
- rows that can outgrow the width scroll instead of clipping (the PC
  filter chips, the hint bar);
- a modal panel's fixed width is capped by the window, because the half
  that falls off a phone's edge is the half with the buttons on it;
- the **Quick Menu** is a right-edge sheet in landscape and a **bottom
  sheet** in portrait. Its whole premise is that the shell stays visible
  behind it, and a full-height right-edge sheet on a tall screen IS the
  whole screen; the bottom sheet also puts its tabs in thumb reach.

**Touch dispatches the real press; it never re-implements it.** Every
screen decides what a button MEANS in one `onKeyEvent` block next to the
state it acts on. A touch affordance therefore sends a genuine key event
down the focused window (`rememberGamepadTouch`,
`GamepadKeyMap.keyCodeFor`) and travels that same path, so there is
exactly one definition of every action and touch cannot drift from the
pad.

**The shell owns the pad; Android's generic fallbacks never act on it.**
`Generic.kcm` gives every pad button a fallback key (A, Start and the thumb
clicks become DPAD_CENTER, B becomes BACK, X DEL, Y SPACE, Select MENU),
dispatched on both edges whenever the window leaves the button unhandled.
Every screen here acts on the UP edge, so the unhandled DOWN of A on a card
became a DPAD_CENTER pair that pressed the primary button of the detail the
A had just opened (build 552). The outermost node of every window
(`Modifier.ownPadButtons`: the shell's root, the Quick Menu's dialog)
consumes only the one fallback the shell means — B is Back — and gives B
its one meaning explicitly, the back dispatcher. Other gamepad buttons are
not consumed at the root; they either reach the focused element (which
handles A via `padSelectable`) or fall through to Android's default
handling, exactly like a physical pad press. A `BackHandler` is therefore a
complete answer to B for pad and touch alike -- a hint pill dispatches a
real `BUTTON_B` into the window and it arrives at the root exactly as a
pad's does -- and a screen with nothing focusable (an empty list) must have
one. D-pad, keyboard and volume keys are not pad buttons and pass through.

**That block goes AHEAD of the element's focus targets in the modifier
chain, never behind them.** Compose dispatches a key event to the
key-input modifiers between the ACTIVE focus target and the root:
`FocusOwnerImpl.dispatchKeyEvent` takes `activeFocusTarget
.lastLocalKeyInputNode()`, and that helper stops at the next `FocusTarget`
in the same chain (compose ui 1.7.2). `Modifier.clickable` delegates a
`FocusableNode` of its own, so in `.focusable().clickable { }
.onKeyEvent { }` the handler is behind a focus target and is never
dispatched at all -- only ancestors get the event. What hid it was
Android's own key-character-map fallback: an unhandled `BUTTON_A` is
re-sent as `DPAD_CENTER` (`Generic.kcm`), which `clickable` treats as a
click, so A appeared to work through the click path while every other
action written the same way (X for favourite, Y for a detail) was dead,
and every hint-bar tap -- a direct `dispatchKeyEvent`, which gets no
fallback -- did nothing (rig, build 548: the PC grid's own `A Open` hint
inert while `B` and `Y`, handled on ancestors, worked). With `ownPadButtons`
no longer consuming A, a hint-bar tap now reaches the focused element
exactly like a pad press.

**A hint row promises only what dispatches.** A row is this shell's touch
control surface, so a hint that names an action nothing handles is a
promise the screen does not keep: either the action exists by every route
the row implies, or the hint is not drawn. The Apps grid drew `Y  Info`
over tiles that handled only A, while the long-press beside them already
opened the app's own detail (rig, build 548).

Consequences:

- the persistent help bar stops being a legend and becomes the control
  surface: every hint is tappable (`TouchHintBar`), and it stays on a
  touch screen even when a theme draws its own help row, because that row
  is decoration and the bar is the only route to B/Y/Select without a pad;
  **when it stays, the theme's own row goes.** Real ES-DE gives the
  Window exactly ONE help bar (`Window.cpp:126`, `Window::setHelpPrompts`
  at `:884`; `HelpComponent.cpp:629` draws nothing when help is off) and a
  theme's `<helpsystem>` styles that one component rather than adding a
  second. droidtop keeps that count with one value, read by every side
  of it. A screen says what it HAS of its own (`HelpRowClaim`: nothing,
  a row of its OWN, or a THEME's `<helpsystem>`) and one function
  (`esDeHelpRowOwner`) says who draws (`HelpRowOwner`, published as
  `LocalHelpRowOwner`): the shell's `ButtonHintFooter` draws exactly when
  it says SHELL, a screen's own `TouchHintBar` exactly when it says
  SCREEN, and the themed renderer draws the theme's `<helpsystem>`
  exactly when it says THEME. A theme's row is a LEGEND -- it names
  buttons, it does not dispatch them -- so a touch-first window takes it
  over; a screen's own row is a real control surface with this screen's
  own actions in it (the PC surface: A opens a game, it does not launch
  it), so it is never doubled by the shell's bar in either shape.
  Independent conditions for the one row are how droidtop drew two,
  twice: the theme's row sliced in half by the bar over it in landscape
  with Slate (rig, build 546), and the shell's bar stacked under the PC
  grid's own row in portrait but not in landscape, because the PC
  surface claimed the row as a THEME's and a touch-first window then
  overrode a claim that was never a theme's (rig, build 547). A claim is
  also scoped to the screen that makes it, so a screen the shell is still
  fading out cannot answer for the screen arriving;
- **the row's CONTENT is built from what dispatches, not hand-picked per
  screen.** A hint is one `HintBinding` — action, label, and the
  condition under which that action is really bound here, right now
  (`shell-gamepad/input/HintBindings.kt`: `HintRow` for a screen's own
  row, `rememberHintList` where a plain pair-list is what the caller
  needs, as a themed gamelist's legend does) — and the row keeps only
  the bindings whose condition holds, so killing an action kills its
  hint in the same read rather than in a per-screen `buildList` that can
  drift from the key handler. H8's residue, closed by this: the shell's
  own footer no longer names Y over the GAMES carousel — at the carousel
  the focused thing is a system and Y acts on nothing (the themed system
  view's own hint list had already dropped it for that reason; the
  shell's bar draws over that same canvas on a touch-first window and
  always on the unthemed fallback, and it kept promising `Y Info` to a
  button that did nothing there) — nor over an empty or still-loading
  Apps grid, where no tile is focused for Y to open; and a themed
  gamelist's per-game hints (Launch/Info/Favorite/Game options) promise
  nothing while no game is under the cursor — a custom collection whose
  members are all gone from the library still opens a gamelist, and it
  dispatches none of them. The Quick Menu's Notifications row and the
  Launcher's games row were already condition-gated the ad-hoc way; they
  say it the one way now;
- **the one row is drawn in the one place laid out for it, and paints
  nothing there.** Real ES-DE draws its single `HelpComponent` ON the
  view, at the theme's own `<helpsystem>` position and with no background
  of its own; the view is not shortened to make room for it. So when the
  shell owns the row over a THEME's screen, droidtop's bar is drawn at
  that same position (`EsDeHelpRowSlot`, reported by the renderer from the
  merged element's `pos`/`origin` and applied after the row is measured,
  exactly as the theme's own bar is) with a transparent background, and
  the themed view gets the whole area in portrait that it gets in
  landscape. An opaque plate there covers the plate the theme drew for
  this row, which is what still read as "a strip below the canvas" after
  the bar had already moved onto it (rig, build 548). The claim is about
  the CANVAS, not about the element: a theme that declares no
  `<helpsystem>` still draws the whole window and still has a help
  position -- ES-DE's own component default, `0.012` of the width and
  `0.9515` of the height, `0.975` when the window is vertical, origin
  `0 0` (`HelpComponent.cpp:23-27`) -- so its canvas is not shortened
  either. Otherwise a theme was laid out into a canvas that changed
  height with droidtop's chrome, and the plate the theme drew for its own
  help row was left visibly empty above droidtop's bar (rig, build 547,
  DEcaffe in portrait). That plate is the THEME's art, not its
  `<helpsystem>`: droidtop suppresses the `<helpsystem>` element and
  nothing else, and never guesses that some `<image>` a theme declares
  was "really" a help-bar background;
- actions that had no on-screen name at all are now named and reachable:
  Select for gamelist options (PC's own "Stores and folders" among them,
  see §7i's 2026-09-26 redecision), Y for a themed gamelist's Info;
- **a card says what IT is, never the heading it sits under.** The line
  under a tile's or card's name is what the thing itself declares -- an
  installed app's own Android application category, a scraped game's
  genre -- and, when it declares nothing, what one entry of its kind is
  called in the singular (`LibraryEntry.kindLine`, `LibraryEntryKind
  .itemName`; `displayName` is the name of the GROUP and belongs to the
  heading). Filling it from the group name made all eighteen Apps tiles
  read "Apps", two lines below a heading that already said so (rig,
  build 547). Nothing is invented for it: an app that declares no
  category gets "Android app", not a guess;
- **long-press is Y** on a game card or app tile --- the same "act on
  this one" the pad reaches with a second button;
- a value that is **stepped** rather than opened --- a slider, a small
  cycling choice --- makes the two arrows the row already draws into two
  targets, because a touch screen has no Left/Right and a slider has no
  "open" to tap: it was otherwise pad-only, in the settings list a phone
  user has to use;
- **every scrolling screen the shell draws ends above the hint bar.** The
  bar is the last thing in the window, so a list measured against the rest
  of it ends exactly where the bar begins: the last row is sliced by the
  window edge and scrolling to the end never brings it clear (rig, build
  546, the settings list; build 548, a game detail's last card). The room
  is CONTENT padding, not a padding modifier -- a modifier shrinks the
  viewport and the row still ends against the bar -- and it is one value,
  `MenuTokens.HintBarRoom`, because it is one bar;
- the Quick Menu's notifications are rows, not a read-out: a tap moves
  the cursor and opens one, and dismiss/clear-all are on the hint bar
  instead of a legend naming buttons that were not there;
- **swipe steps** a themed carousel, textlist or grid
  (`Modifier.esDeSwipeSteps`). Those widgets own a cursor and move in
  whole entries rather than scrolling, so no Compose gesture applied to
  them at all before: a themed view could only be driven by a pad.
- **a looping carousel's wrap animates one step forward, not a dart back
  across the list (owner, on the RP5 console, 2026-09-25: "it cycles back
  to the beginning, but by darting back, not continuing to go
  forward").** The carousel's cursor index already wrapped correctly by
  modulo (`EsDeSystemListView.step`); its animated scroll position did
  not, and animated the raw `focusedIndex.toFloat()` as the `Animatable`
  target, so wrapping from the last entry to the first animated `camOffset`
  backward through every entry in between. Real ES-DE's `CarouselComponent
  ::onCursorChanged` picks the SHORTEST signed step instead (its own
  `posMax` handling), ported as `shortestCamOffsetStep`: the target is
  `camOffset`'s current value plus or minus one, whichever crosses fewer
  entries, so wrapping forward continues past `entryCount` and wrapping
  backward continues past `0` -- `camOffset` is therefore unbounded, and
  it is the RENDERED index that wraps by modulo (`EsDeSystemSlide.wrap`,
  `layoutEsDeCarousel`), never this animated position. Applies to all four
  real carousel types (`horizontal`/`vertical`/`horizontalWheel`/
  `verticalWheel`), which share the one `step`/`camOffset` mechanism. The
  textlist and grid are deliberately excluded: both are real ES-DE
  `ListLoopType::LIST_PAUSE_AT_END` (`EsDeSystemListView`'s own textlist
  and grid `step()`, `coerceIn(0, items.size - 1)`) and were never meant
  to wrap.
- a **tap on a themed entry is one selection, not two**: it moves the
  widget's own cursor onto the entry it hit --- through that widget's own
  `step()`, so the move carries the direction and animation the D-pad
  gives it --- and then acts on it. The carousel activated without moving
  its cursor, so backing out of a system landed on a different entry than
  the one just visited. A reflection is decoration and takes no taps at
  all; it used to be a second, invisible hit target for the entry it
  mirrors.
- the top-level **tab bar scrolls** and keeps the Quick Menu control
  pinned beside it. Four tab names do not fit across a 411dp phone, and a
  plain row pushes the last one --- in desktop mode, a tab with no other
  touch route --- silently off the edge.

**Every screen states its own way out, and the hint bar tells the truth
about it (rig, build 539).** The Gaming shell's Settings section declared
"no back available" while a nested settings screen was open, which took
the B hint out of the hint bar -- and that hint IS the touch route to B,
so a person on a touch screen had no way out of "Windows games" at all and
Game folders, Rescan library and Software updates became unreachable. Two
rules follow: a section that can go back says so, always; and a menu takes
B by every route it can arrive on -- the back dispatcher (what KEYCODE_BACK
and the hint bar's own tap become), and `KEYCODE_BUTTON_B`/Escape as
ordinary key events, which never reach that dispatcher at all.

The pad keeps everything. Touch affordances are additions; no key route
was changed or removed, and a pad plugged into a portrait phone behaves
exactly as it does on the console.

ES-DE's own Android answer is the same idea taken further from the UI: a
floating virtual gamepad overlay whose fingers are fed into the ordinary
input path as `DEVICE_TOUCH` presses (`InputManager.cpp:446-500`,
`InputTouchOverlay*` settings in `GuiMenu.cpp:1401-1436`). droidtop
routes touch the same way --- one input path, no second definition ---
but puts the targets on the real affordances rather than under a
translucent d-pad drawn over the screen, because droidtop's chrome is
its own, is laid out for the window it is in, and is the part a phone
user spends their time in. A themed view, whose element positions belong
to the theme's author, is where the swipe-steps gesture does the same
job the overlay would.

Rigs: the emulator `droidtop-portrait` AVD (1080x1920 at 420dpi = 411 x
731dp, a real 1080p phone) alongside `droidtop-1080p`, driven by the same
`run.ps1` with `-Portrait`; and a portrait BlueStacks instance.
Screenshots of both belong in the evidence for any chrome change.

**A handheld console is not "phone-sized" just because its short side is
compact.** `ShellWindow.touchFirst` treated any window whose short side
is under 600dp as a phone's, to catch a real phone rotated into
landscape (above). A Retroid Pocket 5's 5.5" 1080x1920 panel is a
768x432dp window in landscape, so its 432dp short side tripped that same
branch even though it is a console with its own D-pad and face buttons,
never a screen someone holds with no pad. On the owner's console this
put the shell's touch-sized pills (`ButtonHintFooter`, `minTouchTarget`
48dp) over the theme's own thin `<helpsystem>` legend instead of leaving
the legend to draw --- the pills are sized and padded for a fingertip,
the legend is not, so the substituted bar visually overlapped the system
carousel and the gamelist above it. The fix reuses the one existing
gamepad-detection rule (`ControllerPrefs.attachedControllers`, already
how droidtop asks "is a pad attached" everywhere else) rather than adding
a second, geometry-based guess: `ShellWindow.padPresent` carries that
answer, and the short-side branch of `touchFirst` only fires when no pad
is registered. A console's own buttons register exactly like an external
pad does, so this is the same rule, asked once. Portrait alone still
means touch-first regardless of a pad, unchanged from above: a pad
plugged into a portrait phone still gets the touch bar. No canvas
reservation was added for the pills --- the decision two paragraphs up
holds --- because the real bug was never the layout, it was believing a
dense small landscape panel was a phone.

**The top bar is reached by touch, L1/R1, or Page Up/Page Down -- never
by the D-pad itself (owner, 2026-09-27).** "Pressing up IMMEDIATELY jumps
up to the top bar, scrolling up in a list is impossible" -- reproduced on
the rig from the Games carousel's very first row and from the top of the
Settings list: Compose's own default 2D focus search (the same mechanism
section 7j already leans on for card-to-card navigation, see
`GamepadShell`'s own doc comment) has no notion of a screen boundary, so
an Up press with nothing focusable above the current row happily picked
the nearest tab bar `Text` by screen position instead of stopping. The
fix is in the one shared component every Gaming-mode screen sits under,
not per screen: `SectionTabBar`'s tab labels are no longer `.focusable()`
at all -- they take `onClick` for touch and nothing else, and carry no
`FocusRequester` -- so they simply do not exist as directional-search
candidates from anywhere, on any screen. The current tab still shows by
its raised fill; a focus RING there is no longer possible because a pad
can never park on it. L1/R1 (`SectionTabBar`'s own `ShoulderGlyph`,
`GamepadShell`'s onKeyEvent) remain the one dedicated route to switch
sections, and a keyboard's equivalent is Page Up/Page Down
(`GamepadKeyMap.DEFAULT`) -- the same key a browser or an IDE already
uses to move between tabs/panes, rather than inventing a droidtop-only
binding. A screen's own onKeyEvent handlers see focus arrive from
somewhere real, and Up at the very first row of a list now does nothing
(`FocusManager.moveFocus` returns false, same as it already did at a
grid's left/right edge, section 7f's "System → Game" drill-down) rather
than escaping the screen -- the list simply does not scroll further, it
does not jump anywhere. The one thing Up can ever find above a list is
the safe-mode banner's action (§10c), which is the point of that
follow-up: it is focusable only while safe mode is on, and the tab bar
it covers is not. Because the shell still needs *something*
focused before any real content loads (an empty library, or the very
first frame -- see `GamepadShell`'s own comment on `tabBarFocus`), the
initial-focus anchor moved off the tab bar entirely, onto an invisible,
zero-size `Spacer` living in the content area itself; it is requested
once and abandoned the moment a screen's own first row steals focus, the
same as before.

**A virtual-cursor menu must scroll its own selection into view (owner,
2026-09-27).** `MenuPanel`/`MenuRow` (Quick Menu, every Settings screen,
`GamelistOptionsMenu`, the same-game and manual-match pickers, the Lutris
importer, and the L2 PC game menu, `PcGameMenu`) do not use real Compose
focus for Up/Down at all -- each draws its own `selected`/`focusIndex`
state, moved by hand in a `MenuPanel.onKey` callback (`PcGameMenu`'s own
`focusIndex`, `EsDeNavigationSounds.play("scroll")`), because a Compose
`Dialog` silently drops key events unless something inside actually
holds focus (`MenuPanel`'s own doc comment) and it is that single
`Column` -- not each row -- which holds it. Nothing connected the moving
`selected` index back to `MenuPanel`'s `verticalScroll` container, so a
menu longer than one screenful (`PcGameMenu`'s full list: Runs with,
Play, Engine, F95zone thread, Manage install, Saves, Controls, Engine
settings, ProtonDB, Lutris import, same-game merge, versions/segments,
Stores and folders, Favourites) walked its selection off the bottom edge
in total silence -- Down kept moving `focusIndex`, the row it now pointed
at was still there in the data, but the screen never scrolled to show it,
which is what "all the new stuff added to the PC menu is inaccessible"
actually was: reachable in state, invisible on screen, functionally dead
for both a D-pad and the touch a person would otherwise use to scroll
past it. Fixed once in `MenuRow` itself with a `BringIntoViewRequester`
that asks whatever scrollable ancestor it has (`MenuPanel`'s own Column)
to scroll it into view exactly when it becomes `selected`, rather than
patching `PcGameMenu` alone -- every menu built on this row gets the same
fix for the same reason it shares the row in the first place (one
mechanism per job).

**Left/Right switch the system, L1/R1 switch the tab -- never the other way, anywhere (owner, 2026-09-27).** "Left and right arrows in game menus should switch consoles just like they do on the main screen. L1 and R1 are for menu switching." Before this, a system's own gamelist (`GamesSection`'s themed-gamelist `onKeyEvent`, covering PC and console groups alike -- one `orderedGroups` list, one handler) answered the shoulders TWICE: at the system carousel they already meant "switch section" (`GamepadShell`'s own top-level onKeyEvent), and inside a gamelist they ALSO quick-system-selected, consuming the press before it could bubble up -- a browsing user's L1/R1 press flipped systems instead of tabs depending on how deep they'd drilled, which is the inconsistency the owner is naming. Left/Right already did the real ES-DE `quicksysselect` jump at a gamelist's own edges (`ViewController.cpp:718/728`'s sound, carried over faithfully) and continue to, wrapping continuously through `orderedGroups`; the shoulder branch that duplicated it is deleted outright rather than reconciled, so L/R now falls through to the one place that has ever meant "switch section" -- consistent whether a gamelist is open or not, on the carousel, the PC list and every console list alike, since they all share this one handler. Up/Down never leave the list they're already in (previous rule, above) and Page Up/Page Down remain the keyboard's L1/R1.

**`Modifier.clickable()` is not "not focusable" (owner, on the console, 2026-09-28, Droidtop/tracker#1).** The rule two paragraphs up was real and the fix landed, but its mechanism was wrong: `SectionTabBar`'s tab labels (and the R2 Quick Menu pill beside them) kept only `.clickable(onClick = ...)` on the theory that a plain `clickable` with no explicit `.focusable()` could not be a focus target. It can -- `Modifier.clickable` always chains its own internal `.focusable()` so a hardware keyboard or gamepad can activate a clickable target, which is exactly the directional-search candidate this section already diagnosed once. Confirmed live: a uiautomator dump taken right after a cold launch showed the "Games" label itself `focused="true"`, before any Up press. The real fix is `Modifier.focusProperties { canFocus = false }` placed AHEAD of `.clickable()` in the same modifier chain -- it keeps the tap working while making that node genuinely unfocusable, the same pattern already proven for the touch hint bar (`TouchActions.kt`'s `TouchHint`, rig build 546). Applied to both the tab labels and the Quick Menu pill.

**A gamepad's D-pad or stick can report through joystick axes, not KeyEvents (owner, on the console, 2026-09-28, Droidtop/tracker#1).** With the tab bar genuinely unfocusable, the owner's next report was "Up is now swallowed, not moved" on the real console with a real gamepad -- progress (nothing wrong gets selected) but still broken. droidtop's whole shell reads D-pad navigation exclusively through Compose's `Modifier.onKeyEvent` (`GamepadKeyMap`), which only ever sees real `KeyEvent`s; a controller whose D-pad is a hat switch (`AXIS_HAT_X`/`AXIS_HAT_Y`) or whose stick is used for navigation (`AXIS_X`/`AXIS_Y`) reports through `MotionEvent` instead, and the whole codebase had zero `onGenericMotionEvent`/`AXIS_HAT` handling anywhere -- a device reporting that way never reached any of this navigation code, key or no key, regardless of what else got fixed downstream. `GamepadAxisNav` (`app/src/main/kotlin/dev/droidtop/app/GamepadAxisNav.kt`) translates axis crossings into the same synthetic `KEYCODE_DPAD_*` down/up pair a physical button sends, dispatched through `Activity.dispatchKeyEvent` -- the identical route `ForegroundShell` already uses for the second screen's synthetic navigation keys, reaching the same Compose focus/key machinery a real D-pad reaches with no `INJECT_EVENTS` permission needed. Edge-triggered, plus a `Handler`-driven hold-repeat rather than one driven by incoming motion samples, since a stick held rock-steady at full deflection can stop producing new samples entirely. Wired into `MainActivity.dispatchGenericMotionEvent`, cancelled in `onPause` for the same reason `ForegroundShell` clears its own target there. UNVERIFIED beyond reading the platform APIs as of this entry -- neither rig (BlueStacks, the stock-Android emulator) can produce a real hat-switch or stick `MotionEvent`; only `input keyevent`, which always synthesizes a real `KeyEvent`. Needs a rig check on the actual console with the real gamepad.

**Left/Right's quicksysselect now reaches every real gamelist widget, not just droidtop's own grids (owner, on the console, 2026-09-28, Droidtop/tracker#43).** The rule above ("Left/Right switch the system... anywhere") was never actually true for a themed gamelist's own `textlist`/`grid` widgets: `EsDeTextList` unconditionally consumed Left/Right ("so a stray horizontal press can't escape the list and move Compose focus onto another surface") -- reasoning that predates the top-bar focus fix two entries up and was really defending against THAT bug; with the top bar genuinely unfocusable now, the swallow no longer protects anything and was quietly eating the real feature instead. `EsDeGrid` had the same gap in a different shape: Left/Right always stepped within the current row (`coerceIn`), with no edge case at all, unlike droidtop's own unthemed grids (`GridPad.kt`), which already stop and bubble at a real edge. Both widgets now take a `gamelist: Boolean` parameter (the flag already existed one level up, in `EsDeSystemListView`, for per-entry image resolution, but was never threaded down into the widgets that actually own the keys) and bubble Left/Right to `GamesSection`'s existing sibling-system handler at a genuine edge, ONLY when rendering a gamelist -- a system-level textlist/grid (browsing systems themselves, not a system's games) keeps its previous behaviour, since droidtop has no established meaning for Left/Right there and changing it without a theme to verify against would be an unrequested regression. UNVERIFIED on the console as of this entry, same reason as the axis entry above.

**A LazyColumn's own scroll-follow and `MenuRow`'s `BringIntoViewRequester` must never both run for the same list (owner, on the console, 2026-09-28, Droidtop/tracker#2).** The "virtual-cursor menu" rule above fixed `MenuPanel`'s `verticalScroll` menus, but `MenuRow` is shared by the Settings catalog's own `LazyColumn`s too (`CatalogNavigator`, `CatalogChoicePicker`), which separately run `LazyListState.keepInView` (edge-aware, non-animated-jump; see its own doc comment) on every selection change. With no way to tell "I already have a scroll-keeper" from "I need one," every Settings row armed its own `BringIntoViewRequester` regardless, so a single Up/Down press fired two independent scroll animations against the same `LazyListState` at once -- two competing animations is what `dumpsys gfxinfo` showed as the jank behind "settings scrolling isn't smooth." `MenuRow` takes a new `ownScrollKeeping: Boolean` parameter (default `false`, the original always-on behaviour): `true` from the two Settings call sites that already keep their own scroll position, which skips `MenuRow`'s internal `BringIntoViewRequester` entirely there; left `false` everywhere else the row is used (Quick Menu, `PcGameMenu`, `GamelistOptionsMenu` and the rest of the `verticalScroll`-based menus, plus the Settings search-results `LazyColumn`, which has no `keepInView` of its own) so those keep the behaviour the original fix was for. Owner-confirmed fixed on the console ("fixed beautifully").

## 7k. The design system: one spacing scale, one type scale, one colour source

droidtop draws two kinds of surface. A **themed view** takes every colour, typeface and
measurement from the active ES-DE theme (section 7f) and is out of scope here. Everything
else — onboarding, the shell's chrome and menus, the settings catalog, the Quick Menu, a
PC game's detail screen, the desktop panels — is **droidtop's own chrome**, and all of it obeys
one system. A PC/engine gamelist is a themed view like any console system's (§7i, redecided
2026-09-26) and is out of scope here too.

**Spacing.** One responsive source, `ShellWindow`: the screen-edge gutter, the gap between
top-level tabs, the minimum grid item, the minimum touch target and the maximum modal width
are all derived from the window's own size class, never repeated as a number at a call site.
Between the gutter and the glyph there is one step scale, and every padding, gap and inset is
a step on it. A measurement that is not a step is a defect, not a preference. The minimum
touch target applies in every orientation and on every input, because a pad-shaped device
still has a touchscreen; it is not conditional on the window being touch-first.

**Type.** droidtop's chrome has its own type scale, supplied to the theme alongside the colour
scheme rather than inherited from the platform default, and each role has one documented job:
what a screen title is, what a row title is, what a row's supporting line is, what a section
label is, what a value is. Two screens in the same flow do not use different roles for the
same job. Body text is capped to a readable measure regardless of how wide the window is; a
full-bleed line on a 1280dp screen is a defect. One line of text that cannot fit is truncated
with an ellipsis and is reachable in full somewhere.

**Colour.** One source per surface family, and the families are named so a screen cannot pick
the wrong one. The shell's palette (`MenuTokens`) is absolute against its own grounds, so any
panel that hosts it is painted from that same palette — a platform scheme underneath a
hand-picked one is what produced white-on-white. It has two grounds and one set of text roles:
the menus' overlay surface, and the black `Ground` under the shell's own pages (a game's
detail, the editors and pickers the shell opens full-screen), with
the cards laid on it (`Card`, the brightened `CardFocused`, `CardInset`), the solid chosen chip
(`Selected`/`OnSelected`), the one primary action (`Launch`) and one faded label for anything
unavailable (`OnSurfaceDisabled`, faded by `ChromeColors.DisabledAlpha`). droidtop's chrome
outside the shell takes its colours from droidtop's own scheme. No screen defines a colour
inline: a hex value or a named platform colour in a screen is a defect. Every text-on-surface
pair in both palettes is covered by a contrast test — each text role over each ground and card
it is drawn on, not only the menu overlay.

**One anatomy per thing.** One row (optional leading category icon, title, optional supporting
line cut to one line, optional value, optional chevron; a chevron means "this opens", a value
means "this is set to", and neither stands in for the other). One selectable choice row. One
tile. One section label. One empty state. One selection idiom — an accent ring over a raised
fill (`Modifier.selectionFrame`, ring width `MenuTokens.FocusRingWidth`) — on every
droidtop-drawn focusable: rows, chips, tabs, buttons, cards and tiles alike; a focus rectangle
in one place and a card in another is two answers to one question, and a brightened card alone
is too faint to find at arm's length (UI pass 2026-09-24, M1). The ring means "the pad is here"
and nothing else: a current tab keeps only the raised fill, and a filter that is on is filled
with the accent and carries a check, so neither reads as focus. One chip (`ShellChip`). One
help/hint bar per screen, positioned inside the window. A settings row's whole text is on its
Info sheet: Y on the row, or a long press, shows its name, value, full explanation and last
status; the row itself keeps one line, so every row with a supporting line is the same height
(UI pass 2026-09-24, M14).

**The hint bar is console-sized, not phone-sized (owner direction 2026-09-25, on the RP5
console: "the pills are also too big").** `TouchHintBar`/`TouchHint` (`:shell-gamepad`,
`TouchActions.kt`) draw every hint chip at one compact size regardless of `touchFirst` --
`MenuTokens.HintChipMinHeight` (28dp), `HintGlyphTextSize`/`HintLabelTextSize` (12sp/13sp),
tight glyph-badge padding (`HintGlyphPaddingHorizontal`/`Vertical`, 6dp/1dp) and a 14dp chip
corner radius, inside a bar whose own vertical padding is `HintBarVerticalPadding` (6dp) and
whose reserved room (`HintBarRoom`, the CONTENT padding every scrolling screen leaves for it)
is 56dp, down from 72dp. The chip a finger taps and the chip that draws are two different
sizes: on a touch-first window the chip sits centred inside an invisible `Box` sized to
`MenuTokens.HintTouchTarget` (48dp, the same minimum every other touch control on `ShellWindow`
uses), so the drawn pill shrinks without shrinking what a finger can hit. On a pad-only window
(no `heightIn` on that outer `Box`) the chip is simply the compact legend, no invisible padding
at all. One set of tokens, so every screen with a hint bar changed at once.

**Section switching is named beside the tabs, not as a hint-bar pill (owner direction
2026-09-25, "Can remove the next/previous section pills").** L1/R1 still cycle the top-level
Games/Apps/Settings tabs exactly as before; only where that fact was SHOWN changed. `ShoulderGlyph`
(`:shell-gamepad`, `TouchActions.kt`) draws a small, unbordered "L1"/"R1" label -- no pill, no tap
target of its own, since the tab row it flanks already shows the switch's state -- and
`SectionTabBar` places one on each side of the scrolling tab row (`GamepadShell.kt`) whenever
there is more than one section to switch to. `ButtonHintFooter`'s `showSectionSwitch` hints
("Previous section"/"Next section" pills) are gone; the one place a screen names how to reach a
top-level tab row is now this one glyph, reused everywhere L1/R1 switches tabs (the shell's
section tabs, and the Quick Menu's own Notifications/System tabs, `QuickMenu.kt`, which dropped
its own ad hoc `tabHint` pill for the same `ShoulderGlyph`). The themed system carousel's own
`<helpsystem>` legend text ("R Switch section") is unchanged: that text is the THEME's own
render, styled by the theme rather than droidtop's pill chrome, so it was never one of the pills
being asked about.

**D-pad Up never leaves a grid for the top menu; the top menu has its own button (owner direction
2026-09-25, "don't let dpad up navigate to the top menu, it needs to be separate").** The Games
grid's own key handler used real ES-DE's own "no default arrow-key focus movement" gap as licence
to call `FocusManager.moveFocus(FocusDirection.Up)` at the TOP row, which does not stop at that
grid: Compose's focus search kept going and landed on `SectionTabBar`, so a D-pad press meant to
mean "there is nothing further up" instead silently reassigned the pad to switching
Games/Apps/Settings. Up at the grid's top row is now simply consumed and left there
(`GamepadShell.kt`'s `GamesSection`); nothing before this shell had a bound way to reach the tab
row by pad at all, since the row does not receive focus during ordinary play, so none was taken
away. The tab row's own control is L1/R1, unconditionally, whether or not anything is focused on
it (`esDeHelpRowOwner`/`GamingMenu`'s pad routing is untouched by this) -- the same binding
[ShoulderGlyph] now names on-screen. Pointer/touch is untouched either way: a tab is always a real
tap target of its own.

**Settings polish: category icons, real grouping, search (owner direction 2026-09-25, "settings
needs significant improvements to polish, layout, and all of that"; built by settingsui).** The
consolidated catalog/row-component shell (H4, above) was the foundation; the owner looked at it
running on the console and asked for the visual pass on top of it. Decided and built:

- **A category icon, on rows that open something.** `CatalogItem.icon` (`CatalogIcon`, an enum
  in `:runtime-common` so the model stays renderer-agnostic) is drawn once per
  `NestedScreenItem`/`SubScreenItem` — never on a leaf toggle/choice/slider, which would compete
  with the value column instead of helping the list scan by shape (Switch and Steam Deck icon
  categories, not every control). The Gaming shell's `MenuRow` (`:shell-gamepad`) maps it to a
  real Material Symbols glyph via `CatalogIconGlyphs.kt`'s one `CatalogIcon -> ImageVector`
  table, drawn in the same leading slot a row's accent rail uses (a row carries at most one of
  the two). The glyphs come from `androidx.compose.material:material-icons-extended`, pinned by
  the same Compose BOM every other Compose dependency here is, so a name this module references
  either compiles or fails the build — nothing hand-drawn, matching the "no fabricated assets"
  rule. **Not yet on the touch/Preference surface**: `CatalogPreferenceBuilder`
  (`:shell-default`) still sets `isIconSpaceReserved = false` on every preference it builds. Real
  vendored Murine/launcher3 drawables exist for some of these concepts (`ic_settings_general`,
  `round_rect_folder`, `ic_palette`, `ic_allapps_search`, `cloud_download_24px`…) but picking the
  right one per `CatalogIcon` needs a working build-and-screenshot loop to confirm they read
  right at row size, which this pass did not have; left open rather than shipped unverified.
  **Built (H4 shared-row-language pass, agent settingsh4, 2026-09-26):** `CatalogIconDrawables.kt`
  (`:shell-default`) maps the same `CatalogIcon` enum to the same Material Symbols Outlined
  choice `CatalogIconGlyphs.kt` uses, as real Android `<vector>` resources
  (`res/drawable/ic_catalog_*.xml`) fetched from `google/material-design-icons`
  (Apache-2.0) — the `_24px` "regular weight, no fill" variant, matching the Compose
  `Icons.Outlined.*` family exactly — rather than a second Compose dependency added to this
  forked launcher3 tree just to rasterize one glyph per row. `CatalogPreferenceBuilder.
  applyCatalogIcon` sets it (and `isIconSpaceReserved`) only for `NestedScreenItem`/
  `SubScreenItem`, the same "never on a leaf" rule. The vendored Murine/launcher3 drawables named
  above were never used: none of them is this same icon family, and the point of sharing a row
  language is that a setting reads as the same shape on both surfaces, not merely "has some
  icon".
- **Real section grouping wherever a screen had gone flat.** Console systems (`AppSettingsCatalogs.
  consoleSystemsGroups`) was the one management screen with no section label at all — six rows in
  one undifferentiated run, the same shape the Gaming settings home itself had before the
  2026-09-24 UI pass split it into `SHELL`/`LIBRARY`/`System`/`Input`/`Appearance` (`GROUP_GAMING`
  etc., `GamingSettingsCatalog`). Split into `Management`/`Integrations`/`Platform database`, the
  same section-label component every other screen already draws.
- **A pad/keyboard focus ring on the touch surface too (H4 shared-row-language pass, agent
  settingsh4, 2026-09-26).** The Gaming shell's rows always drew `selectionFrame` (`GamingMenu.kt`,
  a 3dp accent ring); `CatalogPreferenceBuilder`'s rows had no focus state at all — a real gap for
  a pad or a keyboard plugged into a phone/tablet running the Standard shell, since a stock
  Preference row has no visible focus of its own. `CatalogPreferenceNavigator.ensureFocusRing`
  attaches one `RecyclerView.OnChildAttachStateChangeListener` per fragment (survives every
  `rebuild()`'s `setPreferenceScreen`, so nested screens need no re-attachment) that makes every
  row a real focus target and gives it `catalog_row_focus_ring` — the same 3dp width and 10dp
  corner radius as `selectionFrame`, at the same accent hex as `DesignTokens.kt`'s
  `ChromeColors.LightPrimary`/`DarkPrimary` — as its `foreground`, so it draws over the row's own
  icon/switch/ripple rather than replacing them. This surface's existing always-on toolbar Up
  arrow (`SettingsActivity`, wired to the same `pop()` every renderer's B/Back already calls) is
  its equivalent of the shell's hint row: the shell draws one because its dark chrome has no
  toolbar at all, and this surface already has a real, always-correct one.
- **Search across settings, on the settings home, by pad and by touch.** `SettingsSearchIndex`
  (`:runtime-common`) builds a flat index ONCE per search session (`build`, suspend, IO-bound —
  roughly the cost of opening every settings screen once) by walking the root's own
  groups (depth 0) and, for a `NestedScreenItem` found there, one level into whatever
  `CatalogScreen` it opens (depth 1) — never further, and never into a screen a catalog builds
  for one instance (a single ROM folder, one platform, one container), which is what keeps this
  flat against the size of anyone's library instead of growing with it (the performance rule:
  no work that grows with the square of the library). A screen whose live `groups` costs
  library-sized work opts out of that walk with `CatalogScreen.indexGroups`, which the index
  reads instead: Console systems' `groups` walks every games root and game-counts every store
  and engine folder, which held "Indexing settings..." up for 10-40 s on a real device before
  the first result (Droidtop/tracker#136); its index rows are its management and
  platform-database groups plus the folder picker, so the per-folder rows — live library data,
  not settings to find by name — stay the screen's own business. Both surfaces get this for
  free: the Gaming overlay and the Preference dialog both call the same `build`.
  `search` is then a pure, in-memory
  substring filter, safe on every keystroke. In the Gaming shell, a synthetic "Search settings"
  row (`SEARCH_ROW_ID`) is prepended to the settings home's own row list — it rides the exact
  same focus order, Up/Down, A/touch and icon slot as every real row instead of a second
  mechanism beside them — and opens a full-screen `SettingsSearchOverlay` (a text field plus
  matching rows, each showing which screen it lives on); picking a result pushes that screen
  onto the settings home's own navigation stack, so B from it returns to Settings same as
  opening the row by hand would have. The index reaches the overlay as nullable state:
  `null` (the one-time `build` still running on IO) shows an "Indexing settings..." line
  instead of a false "No settings match", and the index landing re-runs the current query
  without retyping — the first search straight after opening "Search settings" raced the
  build and answered over an empty index (Droidtop/tracker#101). The Preference surface's
  dialog needs no such state: it shows an empty list while its own build runs and already
  re-submits the current query when that lands. **Built on the touch/Preference surface (H4
  shared-row-language pass, agent settingsh4, 2026-09-26):** `CatalogPreferenceNavigator.
  openSearch` builds the same `SettingsSearchIndex` over a synthetic root wrapping whatever
  `rootGroups` that fragment already renders, in a plain `AlertDialog` (an `EditText` plus a
  `RecyclerView`, filtered per keystroke the same way the Gaming overlay is) opened from a
  "Search settings" preference prepended to the settings home the same way the shell prepends
  its own search row; picking a result pushes the same one-level target the Gaming shell would.
  Wired into Global, Desktop and Gaming's Preference fragments (`SettingsGlobalFragment`/
  `SettingsDesktopFragment`/`SettingsGamingFragment`, `enableSearch = true`).

  **Fixed alongside it, in both renderers (rig, `p1-rig-settings-search-no-focus.md`): a picked
  result did not scroll to or focus the row it found.** The Gaming shell's `onPick` pushed the
  target screen (or did nothing at all when the result was already on the CURRENT screen) but
  never touched `selectionByDepth`, so the list landed wherever it already was — back on "Search
  settings" itself for a same-screen result, or row 0 of a freshly pushed screen. `CatalogNavigator`
  now carries a `pendingFocusId` set on pick and consumed by a `LaunchedEffect(rows, pendingFocusId)`
  once the rows that should contain it are the ones actually built — immediately for a same-screen
  result, or once a just-pushed screen's `groups()` finishes loading — calling the navigator's own
  `setSelected`, which the existing `keepInView` scroll-follow effect already picks up. The
  Preference surface never had ANY result-focus behaviour to fix (search did not exist there
  yet); its new `navigateToResult`/`focusOn` scroll to the matching preference by key
  (`PreferenceGroup.PreferencePositionCallback.getPreferenceAdapterPosition`) and request real
  View focus on it once the target screen's `PreferenceScreen` is set, the same "immediately, or
  after the screen finishes building" split.

  **Verified on the BlueStacks rig (agent settingsh4, 2026-09-26), the user way, by touch and by
  pad (`adb shell input tap`/`input text`, and `input keyevent KEYCODE_DPAD_*`/`KEYCODE_BACK` for
  the pad half — a real controller was not attached to this rig session).** Global settings
  (`SettingsGlobalFragment`) and Desktop mode's settings (`SettingsDesktopFragment`), reached
  directly via `am start -n dev.droidtop.app/com.android.launcher3.settings.SettingsActivity --es
  ":settings:fragment" <class>` (the same exported entry point `APPLICATION_PREFERENCES` uses):
  the "Search settings" row and `Containers` (a `NestedScreenItem`) both draw their real icon; no
  leaf row does; `Modes`/`Data` (Global) and the untitled/`Containers` grouping (Desktop) draw as
  section labels on both surfaces. Search from Global settings' own row found
  "Rerun onboarding" and landed on it scrolled into view WITH the accent focus ring, not back on
  the search row; D-pad down from there moved the ring to the next row, confirming the ring
  is a real focus target and not a one-off highlight. Search for "container" from Desktop found a
  depth-1 result ("Create a container", inside `Containers`) and pushed that real nested screen
  scrolled to and focused on that exact row; `KEYCODE_BACK` returned cleanly to Desktop mode's own
  list. Two real defects surfaced by this same rig pass and fixed before it re-ran clean (see the
  commit "Fix two rig-found bugs in the touch surface's new settings search"): the search
  dialog's `EditText`/result text rendered near-illegible pale-grey-on-white because it was built
  from the fragment's own (`ThemeOverride`-darkened) context instead of the `AlertDialog`'s own
  themed one; and the very first tap into a result drew no ring at all because `requestFocus()`
  right after a touch event is silently dropped unless the row is also `isFocusableInTouchMode`,
  not only `isFocusable` — the same trap DESIGN-LANGUAGE.md already names for a screen's initial
  selection, here on a row reached by a tap rather than at screen-open time. **Known minor
  cosmetic gap, not reopened as a defect:** the focus ring's rounded corners are clipped flush by
  the row's own bounds (no inset), so a row against another row's edge reads as a straight accent
  line rather than a rounded box — visible, correctly coloured and correctly positioned, just not
  as polished a shape as `selectionFrame`'s. Gaming shell parity for the same search+focus fix was
  re-checked on the same rig pass: "hint" from the Gaming settings home found "Show button hints"
  and landed on it with `selectionFrame`, not back on "Search settings" (the exact repro in
  `p1-rig-settings-search-no-focus.md`); D-pad down from there moved to "Screensaver" correctly.

**Copy is part of the system.** Sentence case, one dash convention (a spaced em dash, never
`--`), one name per concept, verb labels on buttons, no developer notation and no backend error
strings in a user-facing string. Ids, package names, URLs and paths sit behind a Details page,
never in a list row. A title that is a folder slug is drawn as words (`GameNaming.displayName`:
underscores, and dashes when there are two or more, become spaces in a name with no spaces);
the stored title is untouched, so matching and keys do not move.

**Language is part of the system.** Every user-facing string in droidtop's own chrome is a
string resource, and English is the source language. A sentence is one resource with
placeholders, never assembled in Kotlin from fragments, because word order is a property of a
language and not of the code. Plurals use plural resources. A translation is a set of the same
resources in another language; a string with no translation falls back to English per string,
never to a blank or an id. Themed views are excluded, as in ES-DE: a theme's own labels are the
theme's, resolved for the running language with `en_US` as the floor (§7b Appearance). Vendored
trees keep their own resources. Lint cannot be the gate for this rule: its `HardcodedText` check
reads XML layouts only and never sees a Compose `Text("...")`, which is where droidtop's chrome
writes its strings, and the app's lint block runs `checkOnly` NewApi/InlinedApi (the API-level
gate, §10b). The gate is a check over the Kotlin sources of the modules droidtop writes, with
the vendored trees excluded by path, as the API gate's baseline excludes them.

**Accessibility is part of the system.** droidtop's chrome is driven by a pad, a finger or a
screen reader through one focus order: every focusable is reachable by D-pad and by linear
navigation in the same order, and every control that shows no text (a glyph tile, a hint pill,
an icon button, a card's artwork) carries a content description that says what it is and what
it does, taken from the same string the hint row draws. Text and its ground clear 4.5:1 in both
palettes, a disabled control clears 3:1 (`ChromeColors.DisabledAlpha`), and the contrast test
covers every text-on-surface pair. The chrome honours the system font scale to 1.3 without
clipping: rows keep the one-row-height rule at each scale, and text that still does not fit is
abbreviated with an ellipsis. Colour is never the only signal for a state; the focused, selected
and disabled states each have a shape or weight of their own. Themed views are the theme
author's and are excluded, as in ES-DE. The touch-target minimum (§7j) already holds in every
orientation and on every input.

**What is checked and what is not (Droidtop/tracker#87).** Checked by unit test:
the contrast pairs (`MenuTokensContrastTest`), the colour-matrix maths
(`AccessibilityPrefsTest`) and the source rules below. NOT yet checked on
a device: the screen-reader half of this section (one focus order for pad and TalkBack, content
descriptions read aloud, the 1.3 font-scale layout) and the two controls below. Until a rig run with
TalkBack on, paired with D-pad-only navigation through Gaming's menus and the Quick Menu, cites its
result here, read those sentences as the rule the chrome is built to, not as a verified fact.

**Colour vision and text size (Droidtop/tracker#87, owner: "if it's simple. build it").** Two
choice rows in an **Accessibility** group of Global settings (a catalog, so Gaming's rows and
Standard's preferences both show them, reachable by D-pad and by touch), stored in the launcher
prefs (`pref_global_color_vision`, `pref_global_text_scale`) and applied by one mechanism,
`AccessibilityPrefs`, registered from the Application's activity lifecycle callbacks so no screen
opts in.
- *Colour vision:* Off, Protanopia, Deuteranopia, Tritanopia or Greyscale. The three are
  corrections, not simulations (the person's lost colour, measured against the Machado 2009
  dichromacy matrices, is moved onto the channels they can still tell apart, the redistribution
  Android's own daltonizer uses), applied as a hardware layer with a colour-matrix paint on each
  activity's decor view. That recolours Compose and View surfaces alike in every mode. Dialogs
  and popups are separate windows and are not filtered; a game running in another app is not
  droidtop's to filter.
- *Text size:* Normal, Large (1.15), Larger (1.3), Largest (1.5), multiplied onto the system font
  scale on each activity's own Resources before its content is inflated or composed (`sp` in
  Views and Compose's Density both read it); a change recreates the foreground screen.
  That recreate keeps the user's place (Droidtop/tracker#87): the Gaming shell's whole back
  stack (section, open group, detail, options screen, and the entry focused in each —
  `ShellBackStack.Saver`), and the Settings catalog's screen stack and per-depth selected
  row (`CatalogNavigator`'s savers, pushed screens re-resolved through
  `SettingsScreenRegistry` by id, so no live builder goes into saved state), are saveable and
  restored across it; and MainActivity applies Gaming deep-link extras only on a fresh
  create, because a recreate is not a new delivery (onNewIntent still is). Colour vision
  needs none of this: its filter recolours the live window without a recreate.
  Themed views follow it only where the theme's own sizes use the Density (they are the
  theme author's, as above). Layout at 1.5 is not rig-verified: the one-row-height rule was
  designed to 1.3.

**Not built, with reasons (owner, 2026-09-29: only if simple).**
- *Screenshot of the running game (#77).* The game runs in another app, so a capture needs the
  accessibility service's `takeScreenshot` (API 30, an opt-in Accessibility grant the Standard
  service does not hold) or MediaProjection (a consent prompt per session), a notion of "the
  running game" droidtop does not keep once it hands over, an entry point that is reachable
  while the game has focus, per-game storage and a gallery on the detail page. `PixelCopy` of
  droidtop's own window would capture the menu, not the game. That is a feature, not an entry.
- *Profiles and parental control (#78).* The UI modes (Kiosk, Kid) are the honest floor: they
  hide Settings and non-kid games, and stay without a passcode (see `UiMode`). A PIN on leaving
  them would be theatre while the Quick Menu can switch to Standard or Desktop and Android's Home
  and other apps stay reachable; a real one needs those closed first and separate per-profile
  state, which is a design of its own.
- *Users and guest (#79).* An app cannot switch Android users (`switchUser` is system-only), so
  the built version is a **Users and guest** row in Global settings that opens Android's own
  Users screen, offered only where the device supports several users and a Settings activity
  answers.

**The rule is checked, not trusted.** A unit test in `:shell-gamepad` and `:app` fails on any
`Color(0x` literal, any named `Color.*` constant and any `.dp` literal outside `DesignTokens.kt`,
`MenuTokens` and the themed renderer (whose measurements are the theme's), and on any
`MaterialTheme.typography` read outside the `TypeRole` table; the check reads the sources, so a
new screen cannot pass with a private palette. (Measured 2026-09-24: 53 colour literals, about
a hundred named colours and 273 `.dp` literals in the shell against 19 uses of `Space`, so the
contract is the file and not yet the screens.)

**Where it lives.** One file, `shell-gamepad/.../DesignTokens.kt`, in that module because it is
the one both the Gaming shell and `:app` can see — a token half the chrome cannot reach is not
a system. It carries `Space` (the step scale), `Measure.bodyMaxWidth` (the readable line),
`TypeRole` naming the job of each role with `DroidtopTypography` behind it, `MenuTokens` as the
shell's palette and row measures, and `ChromeColors` as the colour source for chrome outside
the shell. `DroidtopTheme` supplies the colour
scheme and the type scale together and defines neither itself. The window-derived
measurements — gutter, tab gap, minimum grid item, minimum touch target, maximum panel
width — stay on `ShellWindow`, which is the one place that asks how much room there is.

Two things implementation settled. A **disabled label** needs its own token: Material's stock
38% alpha lands at 2.3:1 on the light ground, which is not a control a person sees, so
`ChromeColors.DisabledAlpha` is the one value droidtop's chrome fades by and it clears 3:1 in
both palettes. And **onboarding takes the dark palette deliberately** rather than the system
setting (section 7b), which is what lets it use the shell's own menu row anatomy — those
tokens are absolute against the menu overlay surface and legible over a dark ground and
nothing else.

### Text in rows and tiles (directed 2026-09-30, tracker#154)

The owner could not read some settings items: rows were one line with
one-line titles, values and summaries cut by an ellipsis. The first
tester added: Settings are unusable in portrait, descriptions never use
room that is there, columns sit at percentages of the width so arrows
do not line up, the largest Text size cut everything off, and scrolling
text is disliked. The owner's decision (2026-09-30), verbatim: "make sure
settings entries are a consistent height, always. It makes scrolling more
even." The rule, in the shared components (`MenuRow`, `SettingsCatalogView`):

- **Every settings row is the same height, always.** `MenuRow(uniformHeight
  = true)` (the Settings catalog) is exactly `uniformRowHeight()` tall:
  two lines of the title style plus two lines of the summary style plus
  `MenuTokens.RowVerticalPadding` (14dp) top and bottom, at least
  `RowMinHeight` (72dp). It is computed from the current type scale in
  sp, so the Text size setting (the activity's font scale) makes every
  row taller together, and it never varies per row, in portrait or
  landscape. A row never grows.
- **A title wraps to two lines, a summary to two lines; the rest
  ellipsizes** on the row. The full text of the selected row is in the
  **detail strip** under the list (`CatalogDetailStrip`: the whole
  summary, plus the title and value when long; fixed four-line height, so
  the list never resizes), and in the Y Info sheet. This is the one
  mechanism for "text that does not fit"; no scrolling text in rows (the
  tester dislikes it).
- **Content-sized, aligned columns.** Every row of a list shares one value
  column (`LocalValueColumnWidth`), sized to the widest value any row can
  show (all labels of a small choice, so cycling never moves it), bounded
  by `ValueColumnMinWidth`/`ValueColumnMaxWidth`; arrows and values align
  down the screen. The label takes the rest and its summary wraps
  downward. No percentage-of-width positions.
- **Non-settings menus** (Quick Menu, game menus) use `MenuRow` without
  `uniformHeight`: at least `RowMinHeight`, growing with their text; the
  title wraps to two lines, the summary in full.
- **A label that must be one line (grid tile, carousel card, tab label)**
  scrolls while focused (`Modifier.focusMarquee(focused)`, `maxLines = 1`
  while focused); unfocused it wraps to the tile's budget and ellipsizes.
  Tiles use a minimum height. This is the only place scrolling text is
  allowed.
- A fixed `dp` height on anything holding text, other than the uniform
  row height derived above, is a bug. Text fields (`singleLine` input) are
  exempt.

**Header and footer are one frame; the header carries a quiet status readout (owner and first tester, 2026-09-29, Droidtop/tracker#157).** Gaming's own chrome (the section tabs on top, the hint bar below) is drawn by droidtop over every theme; a themed view's own canvas is not touched.

- *One frame.* Both bars are `ShellWindow.frameBarHeight` tall at least (`MenuTokens.FrameBarHeight` 44dp, `FrameBarHeightTouch` 52dp on a touch-first window so a hint's 48dp tap target fits), use the screen-edge gutter, and end in the same hairline (`MenuTokens.FrameHairline`, `Modifier.frameEdge`) on the side facing the content. The footer keeps its own plate (`MenuTokens.HintBar`): the owner keeps that as the SteamOS cue. The header deliberately has no plate, because a plate on top reads as a status area; the shared height, gutter, hairline and the shared pill height below are what make the two read as one frame. The footer draws no hairline when it has no plate (over a theme's canvas).
- *The selected tab's pill* is a fixed height (`TabPillHeight`, 32dp) with the label centred on the middle of its capitals (`Modifier.opticallyCentred`: first baseline minus 0.36 em), not on the line box, whose descender room made the pill bottom-heavy. The L1/R1 badges are the same height, drawn at the tabs' weight with the same centring, so they sit on the pill's line (`ShoulderGlyph(badge = true)`). L1/R1 are not drawn on a touch-first window with no pad attached; the Quick Menu's tab row keeps the small unbadged glyph.
- *Centred.* Where `StatusSlotMinDp` is available on both sides of the tab group (estimated from the tab count, so the choice never measures twice) the tabs sit in the middle of the bar: the status readout in an equal left slot and the R2 Quick Menu indicator in an equal right slot. Otherwise the tabs take the width and scroll (the rule that a row that can outgrow the window scrolls rather than clips), the readout shrinks (no battery percentage) and R2 loses its "Quick Menu" word on a compact-width window. The R2 indicator does not move: it stays top right near the R2 button (owner).
- *Status readout* (`StatusCluster`): clock in the system's 12 or 24 hour format, a connectivity glyph (Wi-Fi arcs scaled by signal, bars for wired and mobile, a slash for none, tinted `Danger` when there is no connection or no internet) and a battery glyph with the percentage (tinted `Affirmative` while charging, `Danger` at 15% or less). One muted colour otherwise, no plate, no notification icons, no SSID: it is three facts, not an Android status bar. It reads the same `SystemStatus` source as the Quick Menu and the companion (off the main thread), ticks on the minute boundary, speaks as one line to a screen reader, and a tap opens the Quick Menu. It is not a focus target, like the rest of the top bar.
- *Section headings* ("Apps", a games row's title) leave the room to the bar above them (`SectionListTopGap` 8dp, `SectionHeadingTopGap` 4dp) and sit `SectionHeadingGap` (16dp) clear of what they head.


## 7m. One game, its versions and its segments (directed 2026-09-16)

**Part and version folders are structure, not depth.** Both walks bound
themselves to `MAX_SCAN_DEPTH` title folders below a root so a mistakenly
added root is never walked whole. A folder whose name is a part marker or a
bare version (`GameNaming.isStructuralFolderName`: `Chap3+`, `Week 2`,
`12.0-scrappy`, `1.0`) is the structure of one game and costs the walk no
depth, and it is never itself the game when a game sits directly below it.
The rig's `adult/renpy/BeingADik/Chap3+/12.0-scrappy` is the case: five
folders down, one past the bound, and the walk stopped at `Chap3+`, claimed
it on the `.rpa` fallback and handed enginehost a folder with no game in it
(build 550).

A game is ONE entry in the library, however many folders it occupies. Two
real shapes in the user's own library, and they are the normative examples
this section is tested against:

- `adult/renpy/Fetish Locator/{Week 1, Week 2, Week 3}` is one game called
  **Fetish Locator with three SEGMENTS**. It was three entries that shared
  a cover and sorted apart from each other.
- `Goodbye Eternity` in two folders, `...-0.8.1-pc-animated-unc` beside one
  with no version in its name, is one game with **two VERSIONS**, and
  `v0.8.1` is what Play starts.

### A version is a FOLDER (decided 2026-09-17)

`adult/godot/Anomalous_Coffee_Machine_2-1.0.00_deluxe_linux.x86_64` is a
2 GB Linux ELF **file** sitting beside the folder
`Anomalous_Coffee_Machine_2_v1.2-deluxe_windows`. It is NOT a second
version of that game, and Anomalous Coffee Machine 2 correctly shows no
Versions section: it has one.

Decided from Pythia's own behaviour, because Pythia's version logic is
what droidtop ports. Pythia never considers a non-directory at all:
`pythia/scanning.py` enumerates game roots as `p for p in path.iterdir()
if p.is_dir()` in every one of its four discovery functions, and
`pythia/onboarding.py::preview` -- the entry point behind both its CLI and
its Qt UI, and the thing that produces the version label and the candidate
list -- refuses the path outright with "does not exist or is not a
directory" before any detection runs. A bare executable, an AppImage, a
`.zip` and a loose `.x86_64` export are therefore never version
candidates, and droidtop does the same.

The rule and its consequences, stated once: a version, a copy and a
segment are each a folder that a scan found a game in. A loose file beside
a game is not a game, not a version and not a copy; it is a file the user
left there. droidtop does not hide it, rename it or claim it -- it simply
has nothing to say about it. (If a bare-file release should ever become a
version, the change is in what a SCAN yields -- an entry for the file --
and not a second grouping rule; nothing in this section would change.)

### How a version row is named

A row in the Versions section is named by what it IS: its part, its
version, or -- when the folder name carries neither -- the folder's own
name. Never a pronoun. Build 542 named the unversioned `Goodbye Eternity`
folder "This version", which reads as the one you are already on in a list
whose whole purpose is switching to another.

### The model

`GroupedGame` is a name, a list of `GameVersion`, and a list of
`GameSegment` (which each hold versions of their own). A `GameVersion` is
a version string plus every `GameCopy` of it -- one install, with its
path, mods, language, platforms, source and whether it is installed --
because two copies of one version that differ by mods or language are two
copies, not two versions. The version/copy split is Pythia's
(`versions[] -> variants[]`), and so is the per-copy state
(installed / latest known / update available).

A **segment** is a part of a game: a week, a chapter, a part, an act, an
episode, a season, a volume, a day or a disc. The default is the newest
version of the first segment; `LibraryGameGroup` maps the model back onto
the `LibraryEntry` each folder actually is, so launching, artwork,
scraped metadata and runner resolution are unchanged and a themed ES-DE
gamelist (which lists entries, by ES-DE's own schema) still works.

### Where the logic comes from

Name, version, mods, language and segment are derived from folder names by
`GameNaming`, a rewrite of the user's own Pythia project's naming logic
(`pythia/onboarding.py`: `_NAME_VERSION_RE`, `_GENERIC_PART_PREFIX_RE`,
`_is_generic_part_leaf`, `_find_meaningful_ancestor_name`,
`_extract_version_only`, `_derive_name_version_mods_language`,
`_merge_version`; `pythia/datadir.py: classify_variant_tokens`). Pythia is
the user's own GPL-3 project and the reasoning is reused under droidtop's
licence as a rewrite with tests, not a file copy. Two rules carry most of
the value and both are Pythia's own corrections against a real library:

- A bare trailing number is part of the NAME, not a version (`Far Cry 5`,
  `Cyberpunk 2077`); only `v`-prefixed or dotted numbers are versions.
- A folder whose whole name is a part marker takes its name from the
  nearest titled ancestor, so `Week 1` never becomes a game.

droidtop adds two things Pythia has nowhere to put: a title that ENDS in a
part marker is that part of the game the rest of it names
(`ThiefofHeartsPart3-0.0.9-pc` sits beside `Part1` and `Part2`), and a
part-marker folder passed on the way up to the title is kept as the
segment (`BeingADik/Chap3+/10.0-sancho` is version 10.0 of chapter 3).

### What merges, and what only suggests

Two folders are the same game when their derived names are equal once case
and punctuation are dropped (`GoodbyeEternity` = `Goodbye Eternity`).
Similarity does NOT merge. Pythia's `NAME_SIMILARITY_THRESHOLD` of 0.6
(difflib's `SequenceMatcher.ratio`, ported exactly, because the threshold
was chosen against that measure) decides what Pythia SUGGESTS to the
person onboarding a folder -- only an exact path, a sync marker or a store
id is ever `certain` there. droidtop's scan has nobody to ask, and the
corpus says what automatic merging at 0.6 would cost:
`love_of_magic_book1`, `book2` and `book3` score 0.94 against each other
and are three different games; `Lust Academy` and `Lust Theory` score
0.61; `ARTEMIS` and `RTS` score 0.60. So similar names never merge, and
droidtop does not keep a library-wide list of "these two look alike"
pairs either: comparing every game with every other grows with the square
of the library and no screen asks for it. Similarity is used only where a
person is already choosing, which is the question below.

The same naming answers a second question, added 2026-09-17: which
detected game replaces a missing one (7g). `MissingGames.candidates`
offers same-`nameKey` games first and 0.6-similar ones after, in that
order, and the user chooses -- `Game v0.3` deleted and `Game v0.4`
unpacked beside it is the case it exists for, and it is Pythia's
`find_candidates` shape (certain, then suggested by descending ratio)
rather than a second measure of its own. A missing folder is still one of
the game's versions until it is folded away, so it keeps its row in
"Parts and versions" and that row's detail is where "Find its
replacement" lives.

### The same game: two entries made one by the user (2026-09-25)

A third question, and the one Pythia found live in its own library: a
game whose folder names drifted apart -- a rename between releases, two
roots that spell it differently -- lands as two cards, and never goes
missing, so the fold above never offers it. Pythia answers it with
`reconciliation.find_merge_candidates` and `merge`: pairs at least 0.6
alike, confirmed by a person. droidtop takes the answer and not the
all-pairs scan: a game that is here has "The same game as..." on its own
detail, offered only when some other game's name is at least 0.6 alike
(`SimilarGames.candidates`, `GameNaming.similarity`, most alike first),
computed for THAT game against each other game once, from that screen,
and nowhere else. Only games that are folders on this device are offered
on either side (a store row's name is the store's), and not a game that is
only missing (that is the fold's question).

Picking one (`Library.mergeGames`, one-way, confirmed with a second press)
makes the other game's every folder part of this game: the one fact kept
is the name those folders are grouped under (`GameLinksStore.setGameName`,
`LibraryEntry.gameName`, `GameGrouping.Found.name`), which the grouping
takes over the name a folder derives. Both games' folders stay where they
are and stay playable, as the merged game's versions and parts; nothing on
disk moves. What the two cards carried becomes the one card's, as the fold
does it: play history (counts added, the later last-played kept), the
favourite and collection memberships move from each card's entry to the
entry the merged card draws (its newest version). A scraped metadata row is
COPIED into an empty place and not taken, because its folder is still here
(`EntryFactsOwner.moveEntryFacts(keepSource = true)`). There is no unmerge
yet.

### The UI this needs, and no more

The PC surface draws one card per game and says how many folders it stands
for when the two numbers differ. The game detail gains one section --
"Parts and versions", or "Versions" when the game has no parts -- with a
row per part and per version saying what it carries (language, mods, an
available update) and marking the one that is open. Choosing a row opens
that folder's detail, so Play starts what the user chose. That is the
whole surface: the model's job is that a game is one entry, so what the
detail needs is a way to reach the other folders of it.

### The corpus

`library-core/src/test/resources/adult-folder-names-2026-09-16.txt` is the
real list of the user's game folders taken off the rig on 2026-09-16, and
`GameGroupingTest` runs every line of it through the grouping, prints the
result and asserts it: 79 folders become 76 games, three of which have two
versions, and no two different games are merged.

### Ownership and "Get it on" (directed 2026-09-28, first slice built 2026-09-29)

A game can be owned on a store. `LibraryEntry.ownership()` says which: a
store row's own id, or the store id a folder absorbed when engine
detection claimed a store's install directory (7g's ownership rule),
limited to the stores droidtop reads (Steam, GOG, Epic, Amazon, itch.io,
in that order). A local folder is never an ownership: the library is the
local copies and the card already says so. The game's options menu
(`PcGameMenu`) shows the stores as one muted line, "Owned on Steam and
GOG", built only from the game's own entries; it is a fact, not a badge
on the card.

A game owned on no store that has scraped links saying where it can be
bought or where its developer takes support shows them on that menu as
plain rows: "Get it on Steam", "Get it on GOG", "Get it on itch.io",
"Get it on DLsite", "Support the developer" (Patreon, SubscribeStar)
(`StorePages`). They read the links a scrape already wrote and nothing
else, so no lookup happens as the menu opens. They are information, not a
pitch: no popups, no grid badges, and one "Hide these for this game" row
dismisses them for that game (`StoreLinkPrefs`). A game a store owns gets
none of it, and its scraped links stay as the plain Links rows.

**Direction, not built**: one game across stores is one library game,
identified by its store id, then a store cross-reference an identified
scrape learned (IGDB's `external_games`), then the DLsite RJ code, the
F95zone thread and the name the user confirmed. Titles never join that
ladder: title, developer and year only SUGGEST a store row on the game's
"The same game as..." picker, and only a person confirming folds it (with
a split on the store row's own options). `LibraryGrouping` and
`Library.mergeGames` stay the one mechanism for it; there is no second
grouping. Two folds are not the person's to split: the shared store id of
a folder that is a store's install, and a shared F95 thread.
### Switch content: an update and a DLC are parts of one game (2026-09-29)

A Switch library is not folders but packages, and a package knows what it
is without being opened: a title ID is 16 hex characters whose LOW 13
BITS say what the content is (switchbrew "Title list") -- clear, the base
game; `0x800`, its update; bit 12 set with the add-on's own index in the
low 12 bits, a DLC. The base game all three belong to is the ID with its
low 13 bits cleared. `01007ef00011e000` is therefore the base,
`01007ef00011e800` its update and `01007ef00011f001` its first add-on.

`SwitchContent` reads that ID from exactly two places, both outside the
crypto, in this order:

1. a `[TitleID]` tag in the filename (the `[TitleID][vN][DLC]`
   convention scene release names carry), which also yields the `[vN]`
   version tag;
2. the ticket's NAME inside a PFS0 container (.nsp/.nsz): a ticket is
   named `<rights id>.tik` and a rights id is the title ID followed by
   its master-key revision, so the first 16 characters of the name are
   the title ID. Only the PFS0 file TABLE (a few KB at the head of the
   file) is read to learn the name. The ticket's own BYTES are never
   opened -- they are the one part of a Switch package that carries key
   material, and droidtop never reads, writes or ships console keys.

Past those two, only the file's own nature: a cartridge dump (.xci/.xcz)
is base content; an explicit `[DLC]` tag without a title ID says DLC and
nothing more. An .nsp/.nsz with no tag and no readable ticket says
NOTHING -- it stays its own row exactly as before this section existed,
rather than becoming a pretend base game.

What the answer is FOR is the fold, `SwitchGameGrouping`: an update and
a DLC are parts of ONE game and never games of their own. The fold keys
on the title ID alone, never on a name -- `Zelda [01007ef00011e800]` and
`Breath of the Wild [01007ef00011e000]` are the same game under two
names, so no naming rule may be allowed to match them. One row per base
game carries `SwitchGameFacts` (its update and which version tag the
filename carried, how many ADD-ON PACKAGES it has -- by index, not by
file count -- and where those files are); the update and DLC files fold
away. A part whose base game is not in the library stays its own row,
marked `loose`: droidtop has nowhere to put it and hiding a file the
person owns is not an option. The gaming shell draws the folded list
(every gamelist and collection resolves against it, one fold for all
surfaces, computed off the main thread like the PC fold; per-file
classification is cached by modification time so a re-fold costs a stat,
not a header read), and the base game's detail says what its files add
up to -- "Update v131072 · 2 DLC" -- in `SwitchGameFacts.line()`.

Three gamelist filters read the same facts (`GamelistFilter`): **Has
DLC**, **Missing update** (a base game with no update beside it -- the
row that can honestly say so, since an unidentifiable file claims
nothing), and **DLC without base game** (the loose rows).

Two honest boundaries: droidtop's platforms database decides which
extensions scan as Switch content at all, so a format it does not list
never reaches this fold; and the classification says nothing about a
base game it cannot identify, so updates for such a file read as loose
rather than folding by guesswork.

### How Android Switch emulators install updates and DLC (research, 2026-09-29)

Researched for a future "Install update/DLC in \<emulator\>" action. The
yuzu lineage (Eden, Citron and Sudachi are its active Android branches)
answers the question in its own sources; what follows cites what was
actually read on 2026-09-29.

**There is no install intent.** The only `intent-filter`s the lineage's
`EmulationActivity` declares are generic `application/octet-stream` ones
(VIEW over the `content` scheme, plus the NFC `TECH_DISCOVERED` action it
reuses as a file hand-off), and a `DocumentsProvider` at
`${applicationId}.user` exposing the app's user folder to SAF -- verified
in the two reachable lineage manifests:

- Lemon (Eden branch): `src/android/app/src/main/AndroidManifest.xml`,
  github.com/Ghael-V/Lemon-Project (EmulationActivity filters:
  `android.intent.action.VIEW` + `application/octet-stream`,
  `android.nfc.action.TECH_DISCOVERED`; `.features.DocumentProvider`
  authority `${applicationId}.user`; a `<queries>` block for finding
  OTHER yuzu-family emulators' document providers).
- Citron: same file and same filters in github.com/citron-neo/emulator
  (`org.citron.citron_emu`, `.features.DocumentProvider`).

No Switch-specific MIME type exists in either manifest -- an earlier
draft of this section claimed one and was wrong. Eden's and Sudachi's own
repositories are not reachable from here (the names tried return 404/451
on GitHub), so their CURRENT builds are cited only through droidtop's
bundled players database (`players-database.json`, a snapshot of the
pinned `Droidtop/droidtop-platforms` submodule shipped as an asset),
which records their real packages (`dev.eden.eden_emulator`,
`org.sudachi.sudachi_emu(.ea)`, `org.citron.citron_emu`) and the launch
intents that ship for them -- all of them `EmulationActivity` LAUNCH
intents, none an install.

**Installation happens in the emulator's own UI, into its private
NAND.** Lemon's `utils/InstallableActions.kt` (same repo) is the
lineage's install flow, verbatim: the app opens a SAF picker and hands
the chosen documents (a `List<Uri>`) to
`verifyAndInstallContent(activity, fragmentManager, addonViewModel,
documents, programId)`, which warns when
`NativeLibrary.doesUpdateMatchProgram(programId, uri)` says a file does
not belong to the game, then installs each document with
`NativeLibrary.installFileToNand(uri, progressCallback)`. The flow's own
`InstallResult` treats `BaseInstallAttempted` as an ERROR -- the
install-content path exists for updates and DLC, and a base game in it
is refused. The same file also shows the keys-install flow
(`processKey`) exists; droidtop never reads, writes or automates keys,
so that route is out of scope by standing rule.

**The other route is external content folders.** Lemon's
`utils/AddonUtil.kt` fixes the lineage's valid per-game drop-in
directories: `cheats`, `exefs`, `romfs`, `romfslite`, `romfs_ext` -- a
person (or a tool with SAF access to the emulator's DocumentsProvider)
can place per-title content there without installing to NAND.

**What an "Install update/DLC in \<emulator\>" action can honestly be.**
Since no tested emulator exposes an install intent, and the generic
octet-stream VIEW intent starts the handed file as a GAME rather than
installing it, the action cannot hand the file over. What the citations
above do support: opening the emulator's own launcher activity (both
manifests declare `MAIN`/`LAUNCHER` on their `MainActivity`) with
droidtop telling the person WHICH file to pick in the emulator's own
picker, and naming the emulator's user-folder document provider for the
external-content route. An action that claims to install would be
fabrication; an action that opens the door and says which file to pick
is real, and is the shape this research supports.

## 8. Licensing

`vendor/gamenative` and `vendor/droidspaces` are GPL-3.0. Winlator itself
is LGPL-2.1 and reaches droidtop only through `vendor/gamenative`'s
`com.winlator` tree; Lemuroid is GPL-3.0 and reaches it only through the
four forked-in `romdetect` files and the bundled `libretro-db.sqlite`.
Neither is vendored as a submodule. `vendor/sway`, `vendor/wlroots` (protocol definitions only — not
compiled for Android, see `:host-bridge`), and `vendor/wayland`/`vendor/
wayland-protocols` (same — codegen/headers only) are MIT. `vendor/
go-containerregistry` is Apache-2.0. `vendor/proot` (Termux's PRoot) is
GPL-2.0-or-later, and the talloc it links is LGPL-3.0-or-later. NOTICE.md
lists every vendored and forked-in source with its licence. `shell-default`'s fork source (Murine
Launcher, itself derived from AOSP Launcher3) is also Apache-2.0.
`input-keyboard`'s fork source (Hacker's Keyboard) is also Apache-2.0.
labwc (§2's second compositor preset alongside sway — installed as a package
inside the container image, not vendored/compiled by droidtop itself) is
GPL-2.0; combining it doesn't change the project's overall GPL-3.0
position, license-compatible the same way the other GPL sources already
are.

Combining GPL-3.0 sources with the rest is license-compatible, but it means
**the combined project must be distributed under GPL-3.0** — no closed-
source distribution of the merged app. Confirm this is acceptable before any
implementation work beyond scaffolding.

## 9. Module map

[settings.gradle.kts](../settings.gradle.kts) is the authoritative list and
carries each module's rationale; most modules also have their own README. The
dependencies below are the `project(...)` lines in each module's build file.

```
app                    → the application: DesktopSessionService, MainActivity, onboarding,
                          and the host for the second-screen keyboard and trackpad
                          (SecondScreenInput); depends on every module below
host-bridge            → native Wayland client + JNI: frame passthrough, input injection,
                          and the host<->container clipboard bridge (§6d);
                          depends on runtime-common
runtime-common         → shared types and interfaces (ContainerRuntime, ContainerLayout,
                          DisplayOutput, RootfsImage, modes, settings catalogs, …);
                          depends on nothing
runtime-windows        → Wine/Box64, compiling the whole vendored gamenative tree
                          (vendor/gamenative, see below); no display code of its own;
                          depends on runtime-common and library-core (it supplies the
                          "pc" library entries)
runtime-linux-root     → DroidSpaces (vendor/droidspaces), namespaces/cgroups, needs root;
                          used only by Desktop mode's container stack; depends on
                          runtime-common
runtime-linux-noroot   → proot-based (vendor/proot, Termux's PRoot, packaged in
                          nativeLibraryDir; see §3), no root; depends on runtime-common
input-seat             → unified input seat; depends on host-bridge, runtime-common
library-core           → the unified library and its metadata (§7g); depends on
                          runtime-common, and on shell-default + IconLoader for the
                          launcher's own app-icon machinery
display                → secondary-display behaviour for every mode, in one place (the
                          single SECONDARY_HOME activity + mode registry, §4c); depends
                          only on runtime-common
shell-default          → Launcher mode: forked-in Murine Launcher (AOSP Launcher3-derived);
                          depends on runtime-common and the forked sub-projects under
                          shell-default/ that settings.gradle.kts includes: IconLoader,
                          Animation, Shared, WMShared, msdl, flags, systemUIPluginCore
                          and the SettingsLib-* modules (HiddenApi is included too,
                          but no module depends on it)
shell-desktop          → Desktop mode's Android-side half (§2a): cross-container task
                          manager + frame passthrough, NOT the taskbar/app launcher
                          (that's container-side); depends on host-bridge, input-seat,
                          library-core, runtime-common
shell-gamepad          → Gaming mode: the ES-DE-themed gamepad shell (§7f); depends on
                          library-core, runtime-common
input-keyboard         → forked Hacker's Keyboard: a real Android IME, and (§6c) the
                          second-screen keyboard hosted as an ordinary window on the
                          second screen; no project dependencies

Outside the Gradle build:
build-scripts/         → build-vendor-deps.sh (cross-compiles the native vendor code),
                          proot patches, and the CI checks (XML comments, class-load API)
vendor/                → upstream trees, as git submodules (.gitmodules); see NOTICE.md
reference/             → screenshots used as visual references
```

**Dead-code removal (P3, 2026-09-25):** `MediaAppBrowserClient` (library-core presence client, no callers) deleted; `shell-default/upstream-unused-reference/` (18 MB, 1,086 uncompiled files) and `fix_segmented.py` removed; `GameGrouping.suggestions`, `GamingPrefs` setters (`setDefaultSection`/`setShowHints`/`setAppsGridColumns`), `ThemePrefs.setControllerFamily`/`setTransitionsSetting`, and `ConsoleRomProvider.rescan()` deleted; empty-DSN Sentry SDK (`io.sentry:sentry-android:7.20.1`) removed from `shell-default/build.gradle`, its manifest auto-init meta-data and `CrashReporting.kt` already gone; no AI attribution.

### Three ROM-entry stores collapsed to one; `rom_entries`/`scan_metadata` retired (D1, 2026-09-26)

`RomDatabase.rom_entries` and `scan_metadata` (the persistent ROM-scan cache) are deleted; the index (`RoomLibraryIndexStore`, backed by `library-index.db`) and the per-game JSON records (`files/library/games/`) replace them as the single sources. `Library.scanAll()` and `Library.scanKinds()` are deleted (the index-backed `LibraryProgressive` and `Library.find()` already cover what they did); `ConsoleRomProvider.scan()` now walks fresh folders without the cache filter. The `game_metadata`, `collections` and `collection_members` user-data tables stay in `RomDatabase` and survive a non-destructive migration (`MIGRATION_10_11` drops the two pure-cache tables). No AI attribution.

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

The Hilt graph lives on `DroidtopApplication` (`@HiltAndroidApp`) so
gamenative's `@AndroidEntryPoint` activities run as droidtop's own `:app`
hosts; the vendor manifest's components are curated into the module
manifest rather than merged wholesale; and the entry points into the
container-configuration UI are the two §7c names.

## 10. Build order

The order work lands in, where one piece depends on another:

1. **Non-root first.** Gaming and Launcher features never need root.
   Desktop mode's containers run on the no-root backend (`ProotRuntime`,
   §3) unless root is available, when `ContainerRuntimeFactory` picks
   `:runtime-linux-root` (DroidSpaces), the only place root is used.
2. **The library before the shells.** Every mode shows games from
   `:library-core`;
   a shell feature that needs something the library does not carry adds it
   there first (§7g), so no shell grows a private copy of library data.
3. **Desktop mode's frame path before its polish.** host-bridge's
   `wlr-screencopy` capture and virtual-input injection were the one piece
   no prior art proved; they are shown working against sway on the stock
   emulator (§3). Whether they hold up on the handheld is §11's first risk.
4. **Windows games through gamenative's own presentation** (§5b):
   `:runtime-windows` compiles the whole vendored gamenative tree and
   presents a Wine guest in gamenative's X server view, so Wine for games
   does not wait on anything in the container stack.

## 10a. Build environment

**Builds are CI.** Nothing is built locally for a change to count:

- `.github/workflows/android-build.yml` builds the release and debug APKs
  on every push to `main` that touches more than docs (a single
  `./gradlew :app:assembleRelease :app:assembleDebug`), uploads them as the `droidtop-apk` artifact, runs
  the class-load API gate against the release dex
  (`build-scripts/check_class_load_api.py`), and publishes to the release
  channel (§10b); pull requests build but do not publish.
- `.github/workflows/android-checks.yml` runs on the same push as its own
  run: lint (`:app:lintDebug`) and every module's unit tests.
- `.github/workflows/release-promote.yml`, run by hand, publishes an
  existing build to `testing` or `stable` (§10b).
- `.github/workflows/commit-hygiene.yml` rejects commits carrying AI
  attribution.

The two Android workflows share one setup, `.github/actions/android-setup`
(JDKs, Go, apt toolchain, SDK/NDK cache, vendor-deps cache, and
`gradle/actions/setup-gradle`, which caches the Gradle user home: wrapper,
dependencies, and the local build cache that `org.gradle.caching` turns on).
Cache entries are written only by runs on `main` and read by every run.

Both Android workflows run on `ubuntu-24.04` with a pinned SDK
(platform 36, build-tools 36.0.0) and NDK 27.0.12077973, JDK 17 and 21, Go,
and the Gradle wrapper (`gradle/wrapper/gradle-wrapper.properties`, 9.3.1).
The comments in the workflow files and the setup action record why each setup step is the way it
is.

**Native vendor code** is cross-compiled by
`build-scripts/build-vendor-deps.sh`, once per ABI, before Gradle runs; the
result is cached keyed on the vendor submodules' commits and
`build-scripts/proot-patches/`:

- `libffi` and `libwayland-client` (Meson, a native scanner then the
  cross library) for `:host-bridge`;
- `droidspaces`, a static musl executable, into `:runtime-linux-root`'s
  assets (`assets/bin/droidspaces-<abi>`). It is the one binary that runs
  through `su`, so it can be extracted to app storage; `BundledBinary`
  re-extracts it whenever the APK's `lastUpdateTime` changes and picks the
  asset for the device's primary ABI from `Build.SUPPORTED_ABIS`;
- `crane` (vendor/go-containerregistry), built `CGO_ENABLED=1` against the
  NDK clang on every ABI -- Go's pure-Go resolver finds no
  `/etc/resolv.conf` on Android and falls back to localhost:53 -- into
  `:runtime-common`'s `jniLibs` as `libcrane.so`;
- `proot` and its two loaders, patched from `build-scripts/proot-patches/`,
  into `:runtime-linux-noroot`'s `jniLibs`.

Everything the app executes as itself (crane, proot) ships in
`nativeLibraryDir`, because Android refuses exec() of a file an app
extracted to its own storage above targetSdk 28 (§3).

**ABIs.** droidtop ships `arm64-v8a` (real hardware) and `x86_64` (x86
devices and emulators). The release channel publishes one universal APK
holding both; ABI splits also build an `x86_64`-only APK, uploaded but not
published, for x86 devices whose package manager installs the universal
APK as arm64 under ARM translation (`app/build.gradle.kts`, `splits`).

**Dependency versions.** One version source per dependency: module
build files name catalog aliases, never inline version strings.
droidtop's own modules read the `libs` catalog
(`gradle/libs.versions.toml`); the trees compiled out of `vendor/` keep
the fork's own catalog (`vendor/gamenative/gradle/libs.versions.toml`,
registered as the second catalog `gn` in `settings.gradle.kts`) so
their versions track the fork through the ordinary vendor sync instead
of a hand-maintained duplicate that drifts. A deliberate divergence
from a vendored version is a settings-level override next to that
registration (`version("dagger-hilt", "2.57.2")`), never an edit under
`vendor/`. The build's one SNAPSHOT dependency is
`io.github.joshuatam:javasteam` (the vendored JavaSteam tree,
`:runtime-windows`), and it stays one by decision (2026-09-26): the
fork (github.com/joshuatam/JavaSteam, branch `gamenative-latest`) has
published no tag and no release, and Maven Central holds nothing
under `io.github.joshuatam`, so the artifact exists only in Sonatype's
`maven-snapshots` repository, which `settings.gradle.kts` declares
for exactly that dependency. The pin moves onto a fixed artifact when
the fork cuts a real tag; no guessing a version meanwhile.

## 10b. Releases and updates (directed 2026-09-02)

Distribution is three different problems wearing one word, and each gets the
strongest mechanism that is actually available to it -- nothing pretends to a
capability Android does not grant.

**Versioning.** "Is this newer" must be answerable by machines. The
`versionCode` (also the `-dev-N` suffix in `versionName`) is a plain monotonic
integer, so Android itself refuses downgrades and the update check is a
single integer comparison. Until 2026-09-24 it was `github.run_number`, which
belongs to the workflow file: renaming or splitting `android-build.yml` would
have restarted it at 1 and every installed build would have refused every
later one. It is now the number of commits reachable from the built commit
(`build-scripts/release_channel.py version-code`, counted through the GitHub
API because CI checks out one commit): it belongs to the commit, grows with
every commit on `main` as long as `main` is never rewritten (the rule is
rebase and push, never force), and a rebuild of a commit gets the same
number. The switch moved the number up, not down (run 587, commit count
about 700), so no installed build saw a downgrade. Every build carries
`release-info.json` -- formatVersion, versionCode, versionName, apkName,
apkSha256, commit -- published by the same workflow run that built the APK.

**Build history, not one rewritten release (owner, Droidtop/tracker#119:
"droidtop releases just constantly rewrite the same release. We need
histories and fixes.").** Through 2026-09-28 every push to `main` replaced
the same GitHub release (`latest`): the download link never moved, but
nothing else survived a push either -- no earlier build's APK, no record of
what changed. `android-build.yml`'s publish job now runs
`release_channel.py publish-build`, which creates a brand new, permanent
release per build, tagged `v0.2.0-dev.<versionCode>` (matching `versionName`
with a `v` in front) and marked prerelease while every version is 0.x-dev.
Its notes are generated from the commit subjects since the previous
per-build release (`build_notes`, via the shared `categorize_commits` /
`render_sections`): grouped Added (subject starts with "add"/"adds"/"added"),
Changed and Fixed ("fix"/"fixed"/"fixes") -- Keep a Changelog's own
vocabulary, one line per subject, merge commits and any line naming a
private plugin (`PRIVATE_NAMES`) dropped, never a link to the private
tracker. A rebuild of the same commit (workflow_dispatch, or a retry after
this exact run was cancelled) reuses the same tag and replaces only that
release; every other version's release is untouched, so nothing is ever
pruned by this path (old releases are kept; a future pruning pass would
still keep every non-dev version and at least the last 50).

**Version bump to 0.2.0 and CHANGELOG.md (owner, Droidtop/tracker#119,
2026-09-28).** `BASE_VERSION` in `release_channel.py` is the one place the
version base lives; `app/build.gradle.kts`'s `versionName` and this script's
own `versionName`/tag both read from it, so a future bump is one edit. The
per-build suffix changed shape too, `-dev.<versionCode>` (a dot, matching
Keep a Changelog's own dotted identifiers) rather than `-dev-<versionCode>`
(a dash): `v0.2.0-dev.1234`, not `v0.1.0-dev-1234`. `CHANGELOG.md` (Keep a
Changelog format: an Unreleased section, then one section per version,
newest first) is droidtop's human-readable version history, separate from
the per-build Unstable release notes above: release notes are generated
from raw commit subjects for every single dev build and read by testers
following Unstable, most of them developer-facing ("Container audio: bridge
proot..."); CHANGELOG.md entries are curated, in end-user language, and
only written when a real version is cut, which is not every dev build.
Both documents group the same way (Added / Changed / Fixed) and share the
same commit-categorizing code (`categorize_commits`, `render_sections` in
`release_channel.py`) -- one mechanism, not two -- but CHANGELOG.md is never
machine-written: `release_channel.py changelog-entry FROM TO` prints that
grouping for a commit range so a person (or an agent acting on the owner's
behalf) can rewrite it into plain language and paste it in, the same way
the 0.2.0 section was seeded from droidtop's git history to date. Never a
tracker link, a private plugin name, or an AI model/tool name in it.

Two channels, one job each, deliberately not the same mechanism (one
mechanism per job, not one mechanism forced onto two different jobs): every
push to `main` needs a release nobody has to name, so Unstable is the
version-tagged history above and the updater finds "newest" by asking the
GitHub API which per-build release is newest
(`AppSelfUpdate.fetchNewestBuild`, matching the same tag pattern
`release_channel.py`'s `BUILD_TAG_RE` does) rather than reading a fixed URL.
Promoting to Testing or Stable is a rare, deliberate action where the person
promoting wants one unchanging download link, so those two stay the
original moving-pointer release (`release_channel.py publish`, tag =
channel name, asset-swap on republish) and the updater still reads them by
their fixed tag. Enginehost mirrors the pre-2026-09-28 shape (its
`agent/engine-bundles` line still publishes one rolling release) and has not
been changed here; Droidtop/tracker#119 is a droidtop issue.

**What is published is a release build (2026-09-21).** Through build 556 CI
published the `debug` variant, the only build type the app had, and on the
console everything was slow: startup, menus, seconds between a press and its
effect. A debuggable package is never compiled ahead of time (the installed
app's dexopt state was `extract`), ART runs it without inlining so that a
debugger can attach anywhere, and the baseline profiles Compose ships are not
installed for it. CI now builds `:app:assembleRelease`: not debuggable, signed
with the same persistent key so it installs over any earlier build,
`androidx.profileinstaller` on the classpath so library baseline profiles are
installed, and `<profileable android:shell="true">` so the shell's profilers
still attach to the build people actually run. The asset is `droidtop.apk`;
the updater reads the name from `release-info.json`, so installed debug
builds update to it by themselves. Code shrinking (R8) is
deliberately the next step and not this one: the vendored launcher,
gamenative and keyboard trees load classes by name and through JNI, and their
keep rules have to be proven on a device before a shrunk build is published.
The debug variant still exists for local work and is what lint and the unit
tests run on.

**Channels, and the debug APK beside the release one (directed 2026-09-22).**
The user: "add two toggles to the update and etc checker: branch (so, stable,
unstable, etc), and a debug checkbox, along with a warning if it's enabled."
A channel is a GitHub release (or, for Unstable, a release list) carrying
`release-info.json` and both APKs: Unstable (every push to main, one new
per-build release each time -- see "Build history" above), `testing` and
`stable` (published by the `release-promote.yml` workflow, run by hand,
each one moving-pointer release at its channel's own fixed tag). Promotion
builds nothing (changed 2026-09-24; it used to build the current main again,
so Testing could carry bytes nobody had tried and a main that had moved on).
The build run uploads its APKs WITH their `release-info.json` as the
`droidtop-apk` artifact, and promotion publishes exactly that artifact: by
default the commit the newest per-build release carries, or a commit named
by hand, and only when that commit's `android-build.yml` and
`android-checks.yml` runs on main both succeeded. Artifacts are kept for the
repository's retention period (90 days by default), so a commit older than
that can no longer be promoted.

Testing and Stable publishing never deletes a release (changed 2026-09-24;
it used to delete and recreate, and a run cancelled between the two left the
channel with no release, which every installed build reads as "nothing
here"). One script, `build-scripts/release_channel.py publish`, serves both:
it moves the channel's tag to the commit (one ref write), uploads the new
files under a `next.` prefix while the old ones keep serving, then swaps
each asset (delete old, rename new, `release-info.json` last). An
interrupted publish leaves the release in place with either build complete,
or for about a second a new APK beside the old `release-info.json`, which
the updater rejects by digest and retries at its next check; the next
publish clears any leftover `next.` uploads. Asset names and
`release-info.json` fields are unchanged. Unstable publishing is a
different job with a different mechanism (`release_channel.py
publish-build`, "Build history" above): a brand new release per commit, not
a moving pointer, so there is nothing to swap in place -- an interrupted run
either published its release or did not, and a retry replaces only that
same commit's release. The device picks a channel in Settings; the default
is Unstable, because it is the only channel droidtop has ever had, and a
channel nothing has been promoted to yet simply reports that there is
nothing there.

The token that can write releases never shares a job with the build (decided
2026-09-24, Droidtop/enginehost `docs/security/2026-09-24-ci-supply-chain.md`
H3). `android-build.yml`'s build job, which runs Gradle, its plugins and the
vendor-deps scripts, has a read-only token and checks out without leaving it
in `.git/config`; a separate `publish` job with `contents: write` downloads the
`droidtop-apk` artifact and runs only `release_channel.py publish-build`,
the same shape `release-promote.yml` uses for `release_channel.py publish`.
Every action is pinned to a commit SHA; moving one is a reviewed commit.

**Debug-build-installed, "Install debug builds" Off -- investigated, not a
bug (rig, p1-dt-updater-debug-build-mismatch, 2026-09-27).** The console was
found on a debug-signed install with this toggle Off and Build channel
Unstable; the concern was that Check now would either misreport "already
current" (comparing against the wrong track) or hand a release-signed APK to
the installer over a debug-signed app and fail with a raw signature
mismatch. Neither happens: `AppSelfUpdate.fetch`'s `wantDebug` only changes
which of the channel's two APK names/digests it reads out of the SAME
`release-info.json` entry (one `versionCode` for both, written by one build
job -- see "What is published is a release build" above), so "is this newer"
is unaffected by the toggle either way, and both APKs are signed with the
SAME persistent CI key (`app/build.gradle.kts`, both `debug{}` and
`release{}` set `signingConfig = signingConfigs.getByName("droidtop")`) --
exactly the "installed debug builds update to it by themselves" design
already recorded above, confirmed by re-reading the actual signing config
rather than assuming debug and release diverge the way a locally-built,
unsigned debug APK would. The one real gap was the Settings copy: the
toggle's subtitle said nothing about this case, reading as if turning it off
might strand a debug install. `AppSettingsCatalogs.updatesScreen` now shows
a third subtitle when the running build is itself debuggable
(`ApplicationInfo.FLAG_DEBUGGABLE`) and the toggle is Off, naming what
actually happens: Check now still finds and installs the release build.

**Branch protection on main (2026-09-25).** `main` is protected via the
GitHub API: force-pushes and deletion are blocked, linear history is required,
and the "Android build" workflow must pass (strict status checks). This
prevents a mistaken or compromised push from publishing a signed APK to the
Unstable channel without a successful build.

Every build publishes BOTH variants: `droidtop.apk` (release) and
`droidtop-debug.apk` (the same code, debuggable). The debug APK exists
because making the published build a release build took `adb shell run-as`
and on-device inspection away with it -- the storage-redesign agent could not
list `files/library/games/` on the rig for exactly this reason -- so the
debuggable build has to remain installable on demand. `release-info.json`
gains `debugApkName`/`debugApkSha256` as ADDED keys at the same
`formatVersion: 1`: a build from before this change reads the same document
and sees the release APK it always did. The "Install debug builds" switch
carries a warning naming the cost in the numbers that were measured, the same
build both ways on the same device and library (build 567 on the BlueStacks
rig: 3955/3765/4023 ms debug against 685/738/728 ms release), because a person
who leaves it on has quietly chosen a build that starts five times slower.

**Fat APKs only (user, 2026-09-24: "We build fat APKs").** The channel
publishes one universal APK (arm64-v8a + x86_64) per build type and nothing
per ABI. An Android-x86 device with ARM translation installs a fat APK as
arm64-v8a only when its x86_64 library set is incomplete (the ABI picker's
rule, §3), so the fix for such a device is a COMPLETE x86_64 set: every
native library the arm64 set ships (gamenative's included) is built for
x86_64 too. The AGP `splits` block and the x86_64-only artifact that were
added the same day as a stopgap are removed once the set is complete.

The list of what is missing is read from each build, not kept here:
`build-scripts/abi_sets.py` runs on the universal release APK in
`android-build.yml` and prints every arm64 name with no x86_64 build. It
reports while the splits exist and becomes a failing gate
(`--require-complete`) in the change that removes them. On 2026-09-24
(build of `ce75426`) 24 of the 39 arm64 libraries were missing, all
gamenative's. `:runtime-windows` builds their x86_64 half with AGP's
CMake, restricted to x86_64 so arm64 stays upstream's prebuilt set
(`runtime-windows/native/CMakeLists.txt`, shims in `native/shims/`).

**The x86_64 Windows runtime (user, 2026-09-24).** arm64 keeps upstream
GameNative's binaries untouched. An x86_64 device runs real x86_64 Wine,
not box64: `Droidtop/proton-wine-tux` already builds an Android/bionic
x86_64 Proton (`--host=x86_64-linux-android28`) as the same `.wcp` module
ContentsManager installs, and on x86_64 it runs natively. The arm64
libraries with no x86_64 source are not reimplemented; each gets an
x86_64 build that does that job on x86_64's own stack, read from what
gamenative's code actually calls:

- `libwinlator_11`: Java calls only `GPUHelper` (Vulkan version and
  extensions) and `Drawable`/`Pixmap`; built from the fork's
  `winlator/gpu_helper.c` and `asurfacerenderer/drawable.c`.
- `libwinlator`: built from the fork's sources without the empty
  `patchelf_wrapper.cpp`; nothing constructs `PatchElf`.
- `libextras`, `libvulkan_renderer`: built from the fork's sources against
  an adrenotools shim whose `adrenotools_open_libvulkan` opens the system
  `libvulkan.so`, the only adrenotools call they make.
- `libhook_impl`, `libmain_hook`: pass-through `android_dlopen_ext` hooks;
  x86 has no Adreno driver to redirect to.
- `libkgslshim`: an `ioctl` that forwards to libc; x86 has no KGSL.
- `libvortekrenderer`: its JNI reports no context, so
  `VortekRendererComponent` never starts; x86_64 Wine reaches Vulkan
  directly.
- `libsteambootstrap`: see Steam below.
- upstream projects: lsfg-vk (`liblsfg-vk-layer`, the fork's submodule),
  `libevshim` (the fork's source against `vendor/SDL2`'s headers; it
  dlopens SDL at run time), the OpenXR loader (`vendor/OpenXR-SDK` at a
  release tag), and PulseAudio 13.0 (`libpulse`, `libpulseaudio`,
  `libpulsecommon-13.0`, `libpulsecore-13.0`) with libsndfile 1.0.28 and
  libltdl, built by `build-scripts/build-vendor-deps.sh` from
  `vendor/pulseaudio` and `vendor/libsndfile` with Termux's 13.0-era
  Android patches. The AAudio sink is Termux's `module-aaudio-sink.c`
  extended with the `volume`, `performance_mode` and `low_latency`
  arguments GameNative's `default.pa` passes; its modules and `pactl` go
  in `pulseaudio-gamenative-x86_64.tzst`, the x86_64 counterpart of the
  arm64 asset.

**Steam on x86_64 is the Linux client in proot (user, 2026-09-24).** On
arm64, `libsteambootstrap` brings up Valve's Android arm64
`libsteamclient.so` (`steam-androidarm64-*.tzst`, fetched by
`BionicSteamAssetsDependency`) and Proton's `lsteamclient` bridge talks to
it, the same shape as Proton on Linux. No x86_64 Android client is known,
and the bootstrap's source is withheld. So on x86_64 the Linux x86_64
Steam client runs in a proot glibc rootfs (the non-root container path)
and Proton's x86_64 `lsteamclient` talks to it; the Windows Steam client
inside Wine is never used. What this costs, stated so nobody mistakes it
for free: proot traces every syscall of the client (two stops each), and
the Steam client is syscall-heavy (network, files, threads, futexes);
every Steamworks call a game makes (many poll callbacks every frame)
crosses a process boundary instead of an in-process call; a second
libc's userland stays resident beside Wine; and bring-up adds proot's
start to the launch. It does not work where the kernel refuses
`ptrace(PTRACE_TRACEME)` (the BlueStacks rig, §3); there the Steam path is
unavailable and says so.

The splits block, the x86_64-only artifact and the proot check's
install-the-other-APK text go when `abi_sets.py` reports nothing missing.

Copying an arm64 binary into the x86_64 set does not count: the picker
treats an x86_64 set that holds an ELF of another machine type as invalid.

**The minSdk gate in CI.** droidtop's minSdk is 26 and every module
declares it, but until 2026-09-11 nothing checked it, and two calls that do
not exist on API 26 shipped and crashed the app on an Android 9 rig. CI now
runs `:app:lintDebug` with `checkOnly` NewApi/InlinedApi and
`checkDependencies` on. Lint and the unit tests are their own workflow run
(`android-checks.yml`) beside the APK run (`android-build.yml`) on the same
push: the APK run ends when the APK is published, and a red check never costs
the artifact or delays it. The gate's scope is one mechanism and one exception: every
module droidtop writes -- `shell-default/src`, the launcher fork's own
sources, included -- is strict, and a NewApi error there fails the build;
the trees droidtop vendors rather than writes are covered by
`app/lint-baseline.xml` instead -- the gamenative tree that
`:runtime-windows` compiles from `vendor/gamenative` by `srcDir`, and
shell-default's vendored AOSP sub-libraries, `:WMShared`
(`shell-default/wm_shared`), `:msdl` (`shell-default/msdllib`) and
`:Shared` (`shell-default/shared`). A baseline rather than a source-set
exclusion, because an exclusion would hide those trees permanently while a
baseline accepts only the findings it names: a call the next vendor sync
brings in, or one added while porting that code into droidtop's own UI, is
not in the file and still fails. The baseline is generated by lint itself
(it writes the file and aborts when the file is missing; CI uploads it with
the lint report) and then filtered down to the vendored paths before being
committed -- it is never hand-written, and it never grows to cover our own
code.

**Update check (droidtop and enginehost APKs).** On a schedule the user
sets -- never, every day, every week or every month (day is the default)
-- at process start, the app downloads that one small file unauthenticated
and compares versionCode. Privacy is the design constraint, not an
afterthought: nothing about the device, settings, or library is sent --
GitHub sees an IP address fetching a public release asset, which is the
floor for fetching anything at all. Offline or failed checks are silent and
simply retried after the next interval; an "only on unmetered networks"
option skips the scheduled check on mobile data. Settings > Software
updates holds the schedule, that option, when the last check ran, and a
manual "Check now"; "never" means zero automatic update traffic. Updates
are never forced: the check only reports, and installing is always the
user's tap (enginehost's plugin bundles can additionally be installed
automatically by a separate, default-off switch). In enginehost the same
pass also refreshes the plugin catalogs and the engine detection rules, so
"Check now" there is the whole pass.

**"Update now" (directed 2026-09-11).** The schedule is the wrong
instrument when a build has just been published and the device is in front
of you, and the console is a play device rather than a test rig: builds
reach it through droidtop's own updater, not a reinstall. So the forced
pass exists and has one implementation, `UpdateNow.runNow`, reached two
ways. Over adb:

    adb shell am broadcast -a dev.droidtop.UPDATE_NOW -n dev.droidtop.app/.UpdateNowReceiver

and from Settings > Software updates, the row "Check now", which is the
page's only check (its value is the installed version, its line when it
last checked; a check-only row beside it was a second way to do one job
and was removed, UI pass 2026-09-24 L4). It
checks the release feed immediately, and when the published `versionCode`
is higher it downloads, verifies and hands the APK to PackageInstaller
straight away -- the system's own confirmation is the only prompt that
remains. What it bypasses is droidtop's own gating and nothing else: the
frequency (including "never"), the interval since the last check and the
unmetered-only option all come from one function,
`AppSelfUpdate.mayCheck(forced = ...)`, which the scheduled pass calls with
`forced = false` and this one with `forced = true`, so the bypass cannot
drift from what it bypasses. Guard: `UpdateNowReceiver` is exported but
declares `android:permission="android.permission.DUMP"`, which only shell
(what adb runs as), root and the system hold. That permission is the whole
guard: a receiver cannot learn the sender uid of an adb broadcast (on API
34+ `getSentFromUid` reports only senders that opted in, which adb never
does), so there is no uid re-check. Every forced pass logs its outcome under
the tag `DroidtopUpdateNow`, which is also where the outcome
of every forced pass is logged. Enginehost gets the same trigger,
`dev.enginehost.UPDATE_NOW`, extended to its plugin catalogs.

**Self-update (both APKs) -- what is honestly possible.** A normally
installed app cannot silently replace itself. What it can do, and what is
built: download the release APK, verify it against the published sha256,
open a PackageInstaller session for its own package, and let the system
take over -- Android verifies signing-key continuity against the persistent
CI key (which is why that key exists) and asks the user to confirm; the
first time, the user must also grant "install unknown apps". On Android 12+
the session requests `USER_ACTION_NOT_REQUIRED`, which the system honors
only when the app is its own installer of record (true from the second
in-app update onward) -- genuine silent updates where allowed, the
confirmation dialog everywhere else. No installer permission is assumed, no
root is used (root stays desktop-only), and nothing is sideloaded around
the platform's checks. droidtop asks `canRequestPackageInstalls()` before
it downloads: when "install unknown apps" is not granted it opens that
setting for droidtop and says what to turn on, rather than letting the
installer stop at "not allowed to install unknown apps from this source".
The Check now row then reports the installer's own answer for the session
-- installed, cancelled, or refused with its reason -- and never "handed to
the installer" as if that were an outcome (rig, dq-shell2-01).

**Engine-plugin bundles -- where auto-update genuinely lives.** Bundles are
enginehost's own signed payloads, so replacing one is not an APK install
and the platform imposes no dialog; the trust model imposes the gates
instead. Within one `bundleId`, a strictly higher `pluginVersion` from the
same origin (verified against the same pinned key) is an update and
replaces the old build in place; a different origin or ID never is.
Enginehost checks daily against exactly the repositories it has bundles
installed from, can optionally auto-install (off by default), and in every
path the execution approval stays bound to the exact archive digest and
signer -- an updated bundle re-prompts before it runs anything. Approval is
never inherited; there is deliberately no path that skips it. Details:
enginehost's docs/plugin-catalog.md (Updates) and docs/engine-bundle-format.md.

**Publishing bundles -- the gate is hardware evidence.** Plugin CI signs a
bundle on every push, but a green build proves packaging, not that the
runtime boots; the project's bar for a published release is "installed and
booted a real game on hardware". Promotion is one deliberate command --
enginehost's `scripts/promote-plugin-release.py` -- which re-verifies the
bundle offline against the key document committed at the run's own commit,
requires an `--evidence` statement of what was seen on-device (written
verbatim into the release notes, never invented), assembles a draft, and
publishes only when every asset is up. Published releases are permanent
(older games may pin older builds). A line's channels mean the same as
droidtop's: every push publishes `unstable`, a rig pass on a real game
promotes that exact build to `testing`, and nothing goes to `stable`
before the line's 1.0. A `testing` build is never older than the fix a
rig found (a line whose loader fix landed on `unstable` is re-promoted,
not left with the bug on the channel people are told to use), and a
promotion never publishes a bundle missing one of the two ABIs. The
promotion command refuses `stable` for a 0.x plugin version, so a
mis-click cannot publish one.

## 10c. Diagnostics, crash recovery and privacy

A handheld is used away from a computer, so the evidence for a defect has
to be gathered on the device, by the person holding it, and handed over
in one motion. Three mechanisms, one folder, and one privacy rule.

**One folder for everything droidtop records about itself:**
`<external files>/logs/` (`Android/data/dev.droidtop.app/files/logs/`),
readable on an unrooted device over adb, USB file transfer and any file
manager. It holds `scan.log` (§7g), `desktop-container.log` (§3), the
update log, and crash notes (below). Every file rolls at a fixed size with
one rotation, so the folder never grows without bound. No file in it ever
carries a credential: scraper and store secrets are redacted before a line
is written (§7h's rule, applied to every sink), and a full library path is
the most private thing a log holds.

**A crash is written down before anything else happens.** An uncaught
exception in droidtop's process, in any mode, becomes a crash note in that
folder (`crash-<epoch>.txt`: build, mode, shell screen, the exception and
its stack, and the last hundred lines of `scan.log`), written synchronously
by the uncaught-exception handler before the process dies, and the ten
newest notes are kept (`CrashRecovery`; the screen is the foreground
Activity's class, tracked by a lifecycle callback). The handler then hands
the crash to whatever handler was installed before it, so Android's own
crash handling follows; there is no restart mechanism of droidtop's own (the
Murine fork's Recovery library rebuilt a blank white task after a crash and
is gone). Crash reporting is **local only**: the Sentry SDK
that shipped with an empty DSN reported nowhere and is removed rather than
pointed at a server, because a crash report leaves the device only when the
person sends it (below). There is no automatic upload and no switch to
turn one on. Debug builds carry the one way to crash on purpose: a
confirmed action in Global settings > Data (next to Share diagnostics, the
screen the crash-loop route itself opens) throws on the main thread and
reaches the same uncaught-exception handler as a real crash, so the note,
the counter and the third-crash route can be exercised on a device;
release builds carry no such action.

**Safe mode after a crash loop.** The Gaming shell renders third-party
themes, and "a theme must never be able to kill droidtop" (§7f) cannot be
proven for every theme. When the process has crashed twice within a minute
of starting, the next start of the Gaming shell draws its unthemed fallback
surface instead of the active theme and says so at the top of the screen,
with one action that draws the theme again. The stored theme choice is not
changed. A third crash in the same window starts the app on Global settings
(the catalog the shell draws itself, §7) rather than in any shell, so a
person can always reach Data, Rerun onboarding and Share diagnostics. The
counter resets on any start that lives for a minute. Implementation: one
counter in a private preferences file (`droidtop_crash_state`), written with
a synchronous commit by the crash handler and incremented only when the
process died less than a minute after starting; `MainActivity` (the entry of
every non-Standard shell) sends a start to Global settings when it reads
three and backs the counter off to two, so the start after that is the
unthemed Gaming shell rather than settings again. Safe mode is
`ThemeSafeMode`: `ThemeAssets` resolves no active theme while it is on, which
is the state the shell already draws its unthemed surface for. The banner and
its "Use the theme again" action are drawn by `MainActivity` over the Gaming
shell. The action is droidtop's own pad-first button (`PadButton` on
`padSelectable`, the idiom the rest of droidtop's chrome uses), not a Material
button, which never answers the pad's A. And reachability needed the same
treatment the rest of the pad's range already had: the system carousel, the
unthemed game grid and the settings catalog no longer swallow Up at their
true top edge, so the pad walks Up through the real rows above each list --
the filter chips, a "Continue Playing" card -- and ends on the banner's
action, the topmost row while safe mode is on; the tab bar it covers stays
unfocusable (§7k), so Up still finds nothing above any list when no banner
is up. A, Enter and a touch all press the action. Sub-screens that own
their whole surface (Settings' choice pickers and search results) keep
their own edges. The Standard launcher entry is not routed through this:
it does not render themes.

**Share diagnostics** is one action in Global settings > Data. It zips the
logs folder, the settings export (§7 Data, with every `droidtop_*`
credential key left out), the platform-database snapshot ids (§7e2), the
installed theme names and the enginehost version, and opens the system
share sheet with the archive. It never sends anywhere by itself. The same
action is reachable from a crash note's own restart screen, so the report
can be sent before the crash is reproduced. Implemented as one builder
(`DiagnosticsArchive`): a key is a credential when its NAME carries
password, secret, api key, token, login, client id, auth or the
ScreenScraper user and developer ids, so a new credential key that follows
the naming is left out with no list to extend; the GitHub token (§12a) is in
its own Keystore-backed file and is never in the settings export at all.
Files are capped at 1 MiB each (the newest bytes), the archive is written
to `<external files>/diagnostics/` -- the same root as the logs folder, so
like the logs it can be checked on the device without root (over adb, USB
file transfer or a file manager; the 2026-09-29 rig check could not read
the cache copy at all) -- and each build replaces the previous archive. The
build, Android version, device, snapshot, Enginehost version and theme
names go in `info.txt`.

**Privacy.** droidtop sends nothing about the device, the library or the
person anywhere. The complete list of hosts it talks to, each for one job
the person asked for: GitHub releases (§10b, its own and enginehost's update
check: an unauthenticated fetch of one small file, or with the person's own GitHub token when they added one for plugin sources, §12a); the droidtop-platforms
repository on GitHub (§7e2, database refresh); GitLab's ES-DE theme index
and the theme repositories a person chooses to download (§7f); the scraper
a person selected, with credentials the person entered (§7h); the OCI
registries an image reference names (§3); and the store backends a person
signed in to through the vendored client (§7g). There is no analytics, no
telemetry, no usage statistics and no crash upload, so there is no
"send usage data" setting: a switch for a thing that does not exist would
be a lie either way. The update check and the database refresh run on the
schedule the person set and never on mobile data when "unmetered only" is
on (§10b).

## 11. Open risks to verify hands-on, not assume

- Whether sway's headless backend + `wlr-screencopy` performs well enough
  for gaming-relevant latency and framerate on the handheld. It is shown
  working on the stock x86_64 emulator (§3); an arm64 device is untested,
  and this is novel usage no prior art measured.
- Whether Wine's native Wayland driver (as opposed to Xwayland) is solid
  enough for Wine run inside Desktop mode's container; Xwayland inside the
  container is the fallback. Wine games launched from the library do not
  depend on it: they present through gamenative's X server (§5b).
- Whether a sibling container can share the primary's Wayland socket
  cleanly. The bind-mount design is implemented for DroidSpaces (§3); the
  proot backend's hardware run lists sibling containers as not yet
  verified.

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
- Media apps such as Spotify (now playing and control through the
  platform's media session and `MediaBrowserService`, per §7e).
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
  `{system.folder}` (the real scanned directory), `{query}` (a search
  string the person types: an `acquire_content` integration whose
  template uses it is offered as a text field instead of a button, and
  committing the text runs it, so it never runs with the value missing).
- `capability` is a closed set (`acquire_content`, `open_with`) because
  the trust shape genuinely differs — handing over one file to a video
  player is not the same as handing over a writable games folder.
- Integrations live in `<external files>/integrations`
  (`Android/data/dev.droidtop.app/files/integrations/`), are **never
  bundled, downloaded or synced**, and an integration whose package isn't
  installed is hidden rather than offered-and-broken. Which apps someone
  hooks into their own launcher is their business. The folder is the
  external one, not `filesDir`, because a person without root cannot
  reach `filesDir` at all (the App integrations screen used to tell them
  to drop a file there, rig 2026-09-24): the external folder is reachable
  over USB, adb and a file manager. The screen's one action, "Add
  integration file", opens the system picker for a `.json` and copies the
  picked file into that folder; its empty state names the folder and
  offers that row, never a path with no action beside it. (This copy is
  of a configuration file the person wrote for droidtop, not of a game
  file; the no-copy rule is about games.)
- Surfaced per-system: an `acquire_content` integration appears as an
  action inside that system's own settings screen, where the system id
  and its destination folder are both already known.

**`open_with` surfaced (2026-09-02).** It was declared and documented
from the start but had zero call sites: a user could write a valid
`open_with` integration and nothing would ever invoke it. It is now
offered on a game's own detail screen (`EntryDetailScreen`), one chip per
real file droidtop has and has no viewer of its own for, which today is
exactly two:

- the scraped **manual** (`downloaded_media/<system>/manuals/<rom>.pdf`),
  which droidtop resolves at scan time and then only ever renders a badge
  for — there is no PDF reader anywhere in droidtop;
- the scraped **preview video**, which today can only auto-play muted
  inside a theme element and cannot be opened, paused or scrubbed.

Three rules make this an *addition* rather than a substitution, and they
are the decision, not the implementation detail:

1. **`open_with` may never claim the game file itself.** Which app
   launches a ROM is already owned end-to-end by the player database,
   per system and per game, with its own real override UI. An
   integration that could also claim the launch path would be a second
   mechanism for a job that already has one, and a silent way to change
   a behaviour the user configured somewhere else.
2. **A target must be a file droidtop genuinely has and genuinely cannot
   open.** Scraped artwork is not a target: droidtop has its own media
   viewer for it. This is what keeps the capability from creeping into
   "hand droidtop's jobs to other apps".
3. **Nothing is ranked or auto-picked.** Two declared `open_with` hooks
   produce two chips and the user chooses. droidtop does not decide.

The grant is the narrow one the existing `am start` path already
produces: a read-only FileProvider `content://` URI for that one file,
revoked when the receiving task dies.

**Known limitation, confirmed against a real app:** an integration can
only drive an app as far as that app's own exported surface allows. The
first intended target, a ROM downloader, declares only
`MAIN`/`LAUNCHER` — so droidtop can open it but cannot hand it a system
or a destination, and extras are simply ignored. Making that case work
needs an intent surface added to the *target* app, not more integration
machinery here.

### 12a. Plugin half — REDECIDED (2026-09-25)

**This replaces the whole of the previous 12a (2026-09-02/09-24).** That
text routed integration plugins through Enginehost as signed subplugins
running in Enginehost's own process, reached over its capabilities
`ContentProvider`. The owner withdrew that on 2026-09-25: droidtop and
Enginehost are separate apps, and nothing in droidtop's plugin system may
depend on Enginehost, run inside it, or be installed through it — to
droidtop, Enginehost stays just another emulator (§7d). What survives
from the old text: the JSON/plugin split from §12 ("a plugin exists
where droidtop needs something back"), and the trust-boundary checklist
below, now applied to droidtop's own install path instead of
Enginehost's.

**Plugins are installed, approved and run in droidtop's own context.**
droidtop downloads or accepts nothing through Enginehost's apparatus; it
has its own manifest format, its own signing/pinning, its own install
path, and its own approval screen (Settings → App integrations →
Plugins, beside the JSON integrations §12 already built — the two are
the same idea, "hook something into droidtop", at different trust
levels, and belong on the same path for that reason).

**The sandbox is for compatibility and stability, not security.** A
plugin here can do anything droidtop's own UID can do — there is no
second permission model, no dropped capability, no [SecurityManager].
What droidtop buys instead is crash containment: a `native_bundle`
plugin's code runs in an isolated `:pluginhost` process (same UID,
separate process), reached over one binder interface
(`IPluginRuntime`/`IPluginRuntimeCallback`, `plugin-host` module). A
plugin that throws, hangs, or native-crashes takes `:pluginhost` down,
never `:app`; the binder `DeathRecipient` and a per-plugin crash
callback both funnel into `PluginCrashPolicy`, which disables that one
plugin and shows why, and the launcher keeps working. This is
deliberately weaker than Enginehost's own "no internet, no arbitrary
file access" runtime sandbox direction (§7d) — droidtop's plugins share
droidtop's actual permissions — and every doc comment on the binder
boundary says so again, so nobody mistakes crash containment for a
security boundary later.

**Plugin kinds, and the one that's built.** `PluginKind` is an open set,
one runner per kind:

- **`native_bundle`** — real Android/Kotlin code: a dex payload
  (`classes.jar`, a zip containing `classes.dex`) plus optional native
  `.so` libraries, loaded by `DexClassLoader` in `:pluginhost` and driven
  through `DroidtopPlugin` (`onLoad`/`invoke`/`startJob`/`onUnload`).
  Native code ships arm64-v8a AND x86_64 whenever it ships any `.so` at
  all — the standing bundle rule (§7d) — enforced by
  `PluginBundleInstaller.structuralProblems()`. **This is the only kind
  with a working runner today.**
- **`python`** — **built 2026-09-26**, replacing the Chaquopy line
  above (kept in git history, not here, per "state from code"): Chaquopy
  turned out to only compile CPython INTO whichever app applies its
  Gradle plugin at that app's own build time, with no supported path to a
  separate, later-downloadable artifact — see `PluginKind.kt`'s own git
  history for the full finding. The runtime this kind actually uses is
  CPython's **official Android build**: Android has been a
  python.org-supported platform since 3.13 (PEP 738), and
  python.org/downloads/android/ publishes real, versioned, per-ABI
  archives at `python.org/ftp/python/<version>/python-<version>-<abi>-linux-android.tar.gz`
  (confirmed against the 3.14.7 release: `aarch64-linux-android` and
  `x86_64-linux-android`, ~22 MB each, licensed under the PSF License —
  the same terms as CPython itself). Each archive's `prefix/lib/` holds
  `libpython3.14.so`, the stdlib (`prefix/lib/python3.14/`, including
  `lib-dynload`'s compiled extension modules and OpenSSL/sqlite3's own
  `.so`s), matching the shape the CPython Android testbed
  (`github.com/python/cpython/tree/3.14/Android/testbed`) extracts into
  an app's own files dir and points `PYTHONHOME` at.
  - **`PythonRuntimeManager`** (`plugin-host`) downloads the artifact for
    the device's own ABI on first use — never bundled in the base APK —
    verifies its SHA-256 against a pinned list
    (`plugin-host/src/main/assets/python-runtimes.json`), and extracts
    only `prefix/lib/**` (dropping the unused C headers under
    `prefix/include`) into `filesDir/python-runtime/<version>/<abi>/`,
    laid out exactly like the archive's own `prefix/` so it can be handed
    straight to the bridge as `PYTHONHOME`. The download is a separate,
    explicit, progress-shown Settings action (an `AsyncActionItem` in the
    Plugins screen, the same "long action with live status text" shape
    droidtop's settings catalog already uses elsewhere) — never triggered
    implicitly by loading a plugin, so a 22 MB fetch never happens
    silently inside a capability call's 15-second watchdog.
  - **The embedding bridge** (`plugin-host/native`, `libdroidtoppy.so`,
    itself bundled in the base APK — only CPython is downloaded) `dlopen`s
    the downloaded `libpython3.14.so` and resolves only the small,
    genuinely ABI-stable subset of the C API by hand
    (`Py_InitializeEx`, `PyRun_SimpleString`, `PyImport_ImportModule`,
    `PyObject_CallFunction`, `PyUnicode_From/AsUTF8`, `PyErr_Fetch`) —
    deliberately not the `PyConfig`-based init the testbed's own
    `main_activity.c` uses, since `PyConfig`'s field layout is tied to
    the exact CPython build it was compiled against, which the testbed
    controls (one pinned checkout) but this bridge does not (a runtime
    chosen and downloaded independently of `libdroidtoppy.so`'s own
    build). `PYTHONHOME` is set as a plain environment variable before
    `Py_InitializeEx`, not through a config struct, for the same reason.
    One interpreter per `:pluginhost` process (CPython has no
    stable-ABI-safe way to run fully independent interpreters), with
    every python-kind plugin loaded as its own uniquely-named module via
    a small bootstrap script so two plugins' globals never collide.
  - **`PythonDroidtopPlugin`** adapts a `plugin.py` file to the same
    `DroidtopPlugin` interface a `native_bundle` plugin implements, so
    `PluginRuntimeService`'s `loaded` map, `invoke`/`startJob`/`unload`
    call sites, and `PluginCrashPolicy`'s crash-containment story need no
    per-kind special casing beyond `loadPlugin` picking the right
    adapter. A python-kind manifest ships one payload file, `plugin.py`,
    exporting `on_load(data_dir)` / `invoke(payload_json) -> json_str` /
    `on_unload()` — JSON in, JSON out, same shape as the binder boundary
    a native_bundle plugin crosses. A plugin whose runtime isn't
    downloaded yet fails to load with that reason, same non-crashing
    "not ready" signal a native_bundle plugin's own connection failure
    already produces — it does not disable the plugin.
  - **Sample**: `samples/plugin-sample-py-statustile`, the python
    analogue of `plugin-sample-statustile`; its `build.sh` needs no
    compiler at all (a python-kind plugin's payload is its own source),
    so the `sample-plugin-python` CI job runs it with no Android
    SDK/NDK setup.
  - **Not built**: `startJob` for python-kind plugins (defaults to "not
    supported", same as any `DroidtopPlugin` that doesn't override it);
    a catalog-repo install source for the runtime itself (today's pinned
    single version in `python-runtimes.json` is hand-updated, matching
    how plugin bundles themselves are installed today).
- **`flutter_embed`** — **built and rig-verified 2026-09-26**, added
  2026-09-25 for a real forthcoming case: an existing Flutter/Dart app the
  owner wants to turn into a plugin rather than rewrite natively. Its own
  paragraphs below ("The `flutter_embed` kind") have the full design,
  feasibility citation trail, and the three real bugs `dq-flutterembed-01`
  found and fixed before a plugin could actually run.

**The plugin API design ([docs/plugin-api.md](plugin-api.md), decided
2026-09-28).** A droidtop device is a general-purpose computer, and plugins
extend it in context across all three modes (owner, 2026-09-28).
`docs/plugin-api.md` is the design for that. It is normative for
everything the API adds from here on, and this section stays the record
of what is built. The decisions, briefly:

- **Three directions, one broker.**
  - **Extension points:** droidtop calls into a plugin, declared in
    `provides`.
  - **Host APIs:** the plugin calls droidtop, gated by `permissions`.
  - **Events:** droidtop tells the plugin something happened, declared
    in `subscribes`.
  - Every plugin → host call goes through one broker in `:app`. The
    broker checks the caller's own grant, applies quotas and writes an
    audit entry. The host builds every intent, path and request itself
    from data, and plugins never receive objects.
  - Plugins never draw UI. They fill droidtop's own rows, tiles, menus
    and sheets.
- **One contract, one adapter per kind.** `native_bundle`, `python` and
  `flutter_embed` carry the same JSON envelope (`handle` in,
  `host.call` out). No kind has a feature the others lack, and a shared
  conformance script checks this.
- **Plugin-provided APIs.** A plugin can `export` an API and others can
  `require` it. droidtop brokers every such call, and the caller needs
  its own grant for the provider's permission, so A never reaches root
  through B without the user granting A. **Root and Shizuku are not host
  features.** They are exported by official provider plugins (a Shizuku
  provider, and a root/Magisk-module provider) through standard
  interfaces (`priv.shell`, `priv.packages`, `priv.settings`,
  `root.modules`). A `requires` on those must be optional, which keeps
  root an enhancement that is never required.
- **Permissions.** There are 66 of them, in three tiers:
  - normal, granted at approval;
  - dangerous, ticked at approval or asked on first use, and only ever
    during a user-initiated call;
  - critical: dangerous plus a written warning, with a few restricted
    to official origins.
  They are revocable under Accounts and sources → Plugins. Providing a
  high-risk extension point is a consent item too. A same-key update
  keeps its approval, but any dangerous access it newly asks for waits
  for the user.
- **Honest enforcement.** Today's `:pluginhost` shares droidtop's UID,
  so permissions bound only what the host does on a plugin's behalf.
  The proposed contained tier closes that: one `isolatedProcess` per
  plugin, everything through the broker. Full trust (droidtop's UID,
  one process per plugin) stays for providers that need Shizuku or root
  and for contract 1 plugins.
- **Compatibility.** Contract 1 bundles keep working unchanged. One
  translation maps each existing capability, event and `PluginContext`
  method onto the new model (`docs/plugin-api.md` §6).
- **Decisions made while building the broker** (`docs/plugin-api.md` §6,
  "As built"):
  - a call in `ask` state prompts only during a user-initiated call, and
    only where a surface can draw the sheet. Everywhere else it fails with
    `PERMISSION_DENIED` and the plugin's row says it wants the permission.
  - A grant file that cannot be read means `ask`, never `granted`.
  - A plugin whose required API has no runnable provider is Waiting: it is
    not called and not disabled, and it resumes by itself.
  - A plugin compiled before `handle` existed is served through its
    contract 1 capability. `AbstractMethodError` from that missing method
    is not a crash.
  - A metadata plugin never replaces a value a built-in scraper found. It
    fills the gaps, and each field records the plugin's name. ES-DE's model
    of one selected scraper source is not changed by it.
  - A context action whose plugin answers `enabled: false` is not offered
    at all, rather than shown greyed. One that does not answer in 500 ms is
    offered.
  - Root and Shizuku stay provider plugins, but the Shizuku binder has to
    arrive at an app-declared provider, so `:plugin-host` declares Shizuku's
    own `ShizukuProvider` in `:pluginhost` and carries its client library as
    transport. Only the official Shizuku provider plugin uses it; other
    plugins get privilege through `priv.*` with their own grant.
  - Quit to Library asks a `priv.packages` provider to force-stop the
    emulator when one is installed, and otherwise keeps the honest fallback
    (Android 13 gives a non-privileged app no way to end another app's
    game). droidtop's running-game state still clears only when the game
    really ended.

The catalogue has 89 entries across ten areas (library and content,
launch and runtime, UI, system and device, desktop, other apps,
identity, data, developer, platform). The phased roadmap is 4 P0, 17 P1
and 47 P2 items. Umbrella: Droidtop/tracker#53.
`docs/plugin-catalog.md` (the 2026-09-25 launcher-seam scope note) is
folded into `docs/plugin-api.md` §3 C and deleted.

**The API surface** (`PluginCapability`, a closed set — the trust shape
differs per capability, same reasoning §12's `IntegrationCapability`
already uses):

- `acquire_content` — search a source, download into a configured
  library folder, report progress. The plugin form of the JSON half's
  `acquire_content`: droidtop hands over the destination folder PATH and
  the query, never a database handle, and results become games only
  because droidtop's own scan finds the files afterward — a plugin never
  registers a second `LibraryProvider` and never contributes a library
  entry directly. (This resolves §12a's old "may a plugin contribute
  library entries" question: no, only by writing real files into a real
  folder droidtop already scans, same as the JSON half.)
- `metadata_source` — a scrape source: given a game's known facts,
  returns candidate metadata/media alongside droidtop's built-in
  scrapers.
- `library_action` — an action on a game's own entry, the plugin
  analogue of `open_with` for cases a bare Intent can't cover.
- `status_tile` — a status/control on droidtop's quick surfaces
  (network, VPN, a reading), refreshed on droidtop's own schedule, never
  a background loop the plugin owns.
- `settings_rows` — rows rendered in droidtop's own settings style (the
  existing `CatalogScreen`/`CatalogItem` model), never plugin-drawn UI.
- `app_status` — status and actions for ONE OTHER INSTALLED APP the
  plugin manages, distinct from `status_tile` (droidtop's own ambient
  state) and from a per-system/per-library-entry row. Added 2026-09-25
  for a real cross-cutting need: an app-management/patch-tool-shaped
  plugin reporting what it knows about a specific package and offering
  actions on it.

A plugin declares the subset it implements; droidtop never calls a
capability a plugin didn't declare.

**The long-running job shape.** `invoke()` is a single request/response
call bounded end to end by a watchdog (`PluginRunner.CALL_TIMEOUT_MS`,
15s). A real job — a patch, an install, a multi-minute scan — needs more:
`IPluginRuntime.startJob` returns a `jobId` immediately and the plugin
reports progress/completion later through
`IPluginRuntimeCallback.onJobProgress`/`onJobComplete`, running on its
own thread in `:pluginhost` between binder calls, not bounded by the
per-call watchdog. `DroidtopPlugin.startJob` defaults to throwing
`UnsupportedOperationException`, which the runner turns into a clean "no
job support" result rather than a crash — most plugins only ever
implement `invoke`.

**A plugin may declare it holds a live connection to another app.**
`PluginManifest.boundServiceTargets` names package(s) a plugin may bind
a live service/binder connection to, alongside its one-shot
`PluginContext` calls. Declaring a target is not itself a grant droidtop
can enforce technically (the isolation is process-crash containment, not
a permission system, as above) — it is what the approval screen shows
before the user approves, exactly like `requestsRoot`, so "this plugin
talks to app X in the background" is never a silent surprise.

**A shared Shizuku-availability check.** `PluginContext.hasShizukuAccess()`
answers "is Shizuku's manager installed and has the user granted
droidtop its permission" with one lightweight, no-client-library check
(package presence + `checkSelfPermission` on Shizuku's own granted
permission string), so plugins that want a privileged call without full
root don't each reimplement the pairing handshake. It only answers
whether the path is available; a plugin still declares `requestsRoot` or
`boundServiceTargets` for what it actually intends to do with it.

**Two generic per-installed-app checks, added 2026-09-26.**
`PluginContext.isAppInstalled(packageName)` and `.launchApp(packageName)`
answer "is this OTHER package installed" and "open its own launcher
Activity", the same shape `hasShizukuAccess()` already special-cased for
Shizuku's one package, generalised for any `app_status`-shaped plugin
that manages a different installed app: `isAppInstalled` tells "not
installed" apart from "installed but not doing what I need" (Shizuku's
own installed-but-not-granted state, for one), and `launchApp` hands the
user to that app's own setup/pairing/config screen without the plugin
ever holding a `Context` of its own to build the `Intent` — both live in
the isolated `:pluginhost` process next to `hasShizukuAccess`, same
"PackageManager presence check, never throws" shape.

**`hasRootApproval()` was dead code until 2026-09-26.**
`PluginRuntimeService` built a fresh `PluginContext` per load but never
threaded `PluginRecord.rootApproved` across the `loadPlugin` binder call
— `hasRootApproval()` always returned `false`, so a plugin that declared
`requestsRoot` and got both the plugin and its root request approved on
the settings screen could still never see it granted. `IPluginRuntime.
loadPlugin` now carries `rootApproved` (`NativePluginRunner.load` already
had the whole `PluginRecord`, just never passed the one field on); the
process also checks the device itself (`su -c id`) before granting it,
cached for that process's lifetime, so `hasRootApproval()` finally means
"device has root AND user approved" the way its own doc comment always
said it did.

**Root is an opt-in, per-plugin enhancement — never a requirement, never
standard.** droidtop's own launcher and handheld code still never needs
root (§3d, §7); that standing rule is unchanged. The one exception is
plugin-level and narrow: a plugin may declare `requestsRoot` and, when
the user approves BOTH the plugin and its root request
(`PluginRecord.rootApproved`), use root on a device that actually has it
— an example being a plugin built from an existing ROM-downloader app's
own code, where root lets it interface directly with other apps' code
and data instead of only through droidtop's narrower API. Three things
hold this to "enhancement, not mechanism" (owner directive, 2026-09-25):

1. A plugin that never declares `requestsRoot` never gets it — there is
   no implicit or ambient root for plugin code.
2. A plugin's CORE function must keep working on a device with no root
   and on one where the user declined the root grant; `PluginContext.
   hasRootApproval()` folds "device has root" and "user approved" into
   one check specifically so a plugin can gate the enhancement cleanly
   rather than building two divergent code paths that both have to work.
3. droidtop never treats root as the normal path anywhere in the plugin
   host itself — `PluginRuntimeService` and `PluginStore` need no root
   for anything they do; only plugin code that explicitly asked, and was
   explicitly granted, ever calls into it.

**Install sources.** A user-picked file through the system picker
(`PluginStore.importFromPicker`), the same "Add integration file" shape
§12's JSON half already uses — plus, since 2026-09-28, any origin whose
key the user has trusted under "Keys you trust" (next paragraph): a
bundle from such an origin verifies and installs through exactly the
same path as an official one. The catalog (below) is a fetch step in
front of that same install path, not a second one.

**User-trusted origin keys ("Keys you trust", built 2026-09-28).** Until
this change `PluginOriginKeys` pinned exactly one origin ("droidtop",
shipped in the binary and certified by droidtop itself), so nobody else
could publish a droidtop plugin at all: every third-party bundle died in
`PluginBundleInstaller.install` with "signature doesn't verify against
the pinned key". The owner's revision of the design (2026-09-28) is also
explicit that users should not normally add keys BY HAND — the primary
path FETCHES the key from the source the user adds; manual paste/file is
the secondary path for sources that publish no key.

- **Three tiers, one resolution order.** An origin is OFFICIAL
  ("droidtop" — the one pinned entry in `PluginOriginKeys`, certified
  exactly as before: pinned in the app binary; the §8 production-root
  follow-up note stands), USER-TRUSTED (an origin the user chose to
  trust, stored in droidtop's own private storage, never in the pinned
  set), or unknown (refused outright, as always). `PluginOriginKeys.
  resolve` checks the official pinned key FIRST, then user-trusted
  keys, so a user-trusted entry can never shadow the official origin
  even if the store file were edited by hand — and `UserOriginKeys.add`
  refuses the origin id "droidtop" outright: an origin may not claim
  the official id. Plugin ids stay namespaced `<origin>.<name>`
  (`PluginManifest.structuralProblems`), which together with that
  refusal means no third-party origin can produce an id under the
  official "droidtop." namespace either.
- **The primary path: trust-on-first-use from a source.** The Plugins
  screen's "Keys you trust" screen (Settings → App integrations →
  Plugins → Keys you trust) takes a plugin source URL — a GitHub repo
  (`https://github.com/<owner>/<repo>`), a catalog index (any https
  URL ending in `.json`), or a plain https root — and fetches the
  source's PUBLISHED key: the well-known file `droidtop-plugin-key.json`
  at the source's root (for a github.com URL droidtop derives
  `https://raw.githubusercontent.com/<owner>/<repo>/HEAD/droidtop-plugin-key.json`
  itself, so the user can paste the address they actually see in a
  browser), or the catalog index's own `origin`/`key` fields. Nothing is
  trusted at fetch time. The screen shows WHO (the origin id) and WHICH
  key (a SHA-256 fingerprint of the SPKI bytes, in groups of four so it
  is comparable by eye) and says what trusting does NOT mean
  ("third-party source, not official — droidtop has not vetted it"),
  and the user confirms once. https only: a plaintext fetch would make
  the TOFU step itself the attack.
- **The user's own GitHub token for plugin sources (owner, 2026-09-28,
  Droidtop/tracker#16).** Accounts and sources has a "GitHub token" row: the
  person pastes a fine-grained or classic token themselves (droidtop never
  creates, fetches or fills one). It lifts GitHub's unauthenticated request
  limit for update checks and reaches plugin sources in private repositories:
  the source key file, the catalog index and the bundle download. Stored
  AES-256-GCM under a non-exportable Android Keystore key in a private
  preferences file of its own (`GitHubTokenStore`), so it is never in the
  shared settings preferences and therefore never in the settings backup or
  in Share diagnostics; shown masked (last four characters), removable, and
  testable (the Test row asks api.github.com/user for the login and the
  request allowance). `GitHubAuth` is the one place that decides where it may
  go: an `Authorization: Bearer` header on https requests to `api.github.com`,
  `github.com` and `raw.githubusercontent.com` only, decided again on every
  redirect hop (redirects are followed by hand). `objects.githubusercontent.com`
  is deliberately NOT on the list: a private release asset (the
  `api.github.com/.../releases/assets/<id>` URL, sent with
  `Accept: application/octet-stream`) redirects to a pre-signed URL there, and
  that host rejects a request carrying both the signature and a token. No token
  set means exactly the unauthenticated request of before; the token is never
  logged and never in a URL.
- **The key file format plugin authors publish** — the whole contract,
  deliberately two fields:
  `{ "origin": "acme", "key": "<base64 of a P-256 public key as X.509 SubjectPublicKeyInfo — the same SPKI shape the official pinned key uses>" }`
  as `droidtop-plugin-key.json` at the source's root; a catalog index
  carries the same two fields at its top level. `publicKey` is read as
  a spelling of `key`. The key is validated
  before it is ever shown: base64 → X.509 SPKI → EC → exactly P-256
  (`PluginOriginKeys.parseSpki`); anything else is refused.
- **A changed key is never silently accepted.** Trusting a source
  stores origin → key plus the source URL it was fetched from, so the
  row shows provenance. A later fetch of a source that publishes a
  DIFFERENT key for an already-trusted origin stops dead: nothing is
  written; the screen shows a warning naming BOTH fingerprints (the
  one you trusted, the one now published) and the only way forward is
  an explicit "replace the stored key" confirmation. This is the
  rotation-vs-compromise fork and droidtop cannot tell the two apart,
  so the human who took the original TOFU decision makes the call.
  Plugins signed by the old key stop verifying the moment a key is
  replaced or removed, exactly as under "Removal" below. The manual
  paste/file path never replaces at all — a different key for an origin
  you already trust is refused with that reason; rotating by hand means
  removing the origin and adding it again, two explicit steps.
- **Removal is real.** Removing a user-trusted key makes every plugin
  signed by it untrusted at once: updates are refused (install fails
  signature verification again, the same "unknown origin" refusal),
  the plugins stop running (`verifyInstalled` consults the user keys
  too, so `runnableFor` and every `PluginCrashPolicy` gate refuse them
  and the disable reason says "signature no longer verifies"), and the
  Plugins screen flags the line instead of showing "Running".
  Re-adding the same key restores them — approval, bound per archive
  digest, was never destroyed, only signature resolution was.
- **The badge.** Every plugin's line on the Plugins screen carries its
  trust tier: "Official" (origin certified in droidtop's binary) or
  "Added by you" (user-trusted origin — the label never borrows
  "Official"). A user-origin whose key is gone shows the flagged
  not-trusted state instead.
- **Storage.** `filesDir/plugin-user-keys.json`, droidtop's private
  storage, writable only by the app, written via a temp file + rename
  so a failed write cannot leave a half-written store:
  `{ "<origin>": { "key": "<SPKI base64>", "source": "<url it was fetched from, absent when pasted by hand>" } }`.
  The official origin has no entry there and never can.

Unit-tested in `plugin-host` (`UserOriginKeysTest`, `PluginSourceKeysTest`,
`BundleSignatureTest`, `PluginBundleInstallerTest`): official verify,
user-key verify, unknown origin refused, user key removed then refused,
user origin claiming "droidtop" refused, official-first resolution, and
the changed-key case never writing without the explicit replace.
**The catalog (built 2026-09-28).** The second install source, beside
the file picker `PluginStore.importFromPicker` still offers, is the
catalog: built in droidtop's own code against its own trust rules, not
Enginehost's. It mirrors Enginehost's plugin catalog
(`plugins/index.json` in the same droidtop-platforms repo) conceptually —
one small index file listing what has been published, fetched from
`raw.githubusercontent.com` with no API allowance, a signed bundle per
release, the signature verified against a pinned key before anything is
trusted — but the trust model stays the one this section already fixed:
droidtop pins its own keys, a plugin must be approved before it runs, and
what the index says is display data, never a trust decision.

- **The index** is `droidtop-plugins/index.json` in droidtop-platforms,
  a sibling of Enginehost's `plugins/index.json` in the same repo (same
  distribution story: one file, one raw URL, the same platform-database
  base-URL override the app already has for that repository applies to it).
  Its format (schema version 1):

      {
       "schemaVersion": 1,
       "generatedAt": "<ISO-8601 UTC>",
       "origins": [
        {
         "origin": "<the manifest origin id, e.g. droidtop>",
         "trust": "official" | "third-party",
         "key": {
          "formatVersion": 1,
          "algorithm": "SHA256withECDSA",
          "origin": "<the same origin id>",
          "publicKeySpki": "<base64 SubjectPublicKeyInfo, EC P-256>",
          "keySha256": "<hex SHA-256 of the DER>"
         },
         "plugins": [
          {
           "id": "<origin>.<name>, the plugin id>",
           "label": "<display name, from the signed manifest>",
           "description": "<one line, or null>",
           "releases": [
            {
             "version": "<the manifest's version string>",
             "stream": "stable" | "testing" | "unstable",
             "publishedAt": "<ISO-8601 UTC, or null>",
             "manifestSha256": "<hex SHA-256 of the signed manifest.json bytes>",
             "bundle": {
              "name": "<file name>",
              "url": "<https download URL>",
              "size": <bytes>,
              "sha256": "<hex SHA-256 of the whole .droidplugin.tar.xz>"
             }
            }
           ]
          }
         ]
        }
       ]
      }

  Everything in it is UNTRUSTED: a tampered index can only hide entries,
  lie about labels, or point at a download whose own bytes still have to
  verify (the whole-file SHA-256 against the index's `sha256`, then the
  bundle's manifest signature against the pinned key and every payload
  hash, inside the existing install path) before any of it is acted on —
  the same "the index can make the catalog fast or stale, not lying"
  posture Enginehost's own index lives by. The `key` block is
  cross-checked, not trusted: an origin is offered at all only when this
  build of droidtop pins a key for it (`PluginOriginKeys`) AND the
  index's `keySha256` matches that pin's own fingerprint, so a tampered
  index cannot rebind a pinned origin to a new key; an origin whose key
  this build does not pin is listed but not installable. (Pinning a new
  origin is an app change today — a `PluginOriginKeys` entry — and the
  Enginehost-style offline root that certifies new origins without an app
  change remains the deferred step §12a already noted for
  `PluginOriginKeys` itself, now with a real second use.) An index with
  an unknown `schemaVersion` is refused whole (a newer format is not
  guessed at), the same validate-before-replace posture as the platform
  databases.
- **What the catalog offers.** Only `stable`-stream releases: a plugin's
  offer is its newest stable release, judged by BOTH its version string
  and `publishedAt` (owner, 2026-09-28): when one ties or a date is
  missing, the other decides; when both speak they must agree. A newer
  version published before an older one means the index was built wrong,
  so that plugin is offered nothing and its row says the catalog is
  inconsistent, rather than droidtop guessing. droidtop never
  offers a testing/unstable release for install or update (the index may
  carry them; this build does not act on them). An installed plugin has
  an UPDATE when the newest stable release's `manifestSha256` differs
  from the installed record's `archiveDigest` — version strings are
  deliberately not compared, the digest is the identity. An installed
  plugin with no catalog entry at all (side-loaded through the file
  picker) has no catalog update; that path is unchanged.
- **Trust over updates.** This is the one rule the catalog changed in
  this section's own checklist (point 4): approval was previously bound
  to the exact archive digest and never carried over to new bytes. Now
  approval is bound to the plugin's identity under a key — the record
  stores the fingerprint of the key it was approved against
  (`PluginRecord.approvedKeySha256`) alongside the digest — so an update
  whose signature verifies against the SAME pinned key carries the
  APPROVED state (and the enabled/root-approved bits) over to the new
  digest in one step. That is the point of a catalog: the user already
  decided to trust this plugin from this origin, and re-asking on every
  byte change would turn every update into a second approval ceremony.
  What does NOT carry over: a DENIED state (the user said no to this
  plugin; its update re-enters at PENDING like a first install), an
  update signed by a DIFFERENT key (a rotation is a new trust decision),
  and a first install (nothing to carry). Records written by builds
  before this rule have no key fingerprint and get no carry-over on their
  first update — one re-approval, the safe direction.
- **The flow — REDESIGNED (2026-09-28, agent pluginsui).** Settings →
  Library → Accounts and sources → Plugins (moved under uisources's
  Accounts and sources consolidation, above; still the one tidy settings
  area, no new top-level surface). The old shape was one flat list mixing
  per-plugin rows, the Python/Flutter runtime rows, Keys you trust and
  the file picker in installation order — "blindly shoving things into
  the list", the owner's own words. The screen is now four groups:
  - **Installed** — one row per installed plugin (its capabilities in
    plain words and its trust badge as the subtitle, its state — Needs
    approval / Running / Disabled / Crashed / Denied — as the value),
    opening that plugin's own detail page (titled with the plugin's
    display name, never its `<origin>.<name>` id) rather than spreading
    its actions across the parent list. The detail page groups: status
    (Approve/Deny or the Enabled toggle, root use if requested), "What
    it provides" (the capabilities summary as the row under that one
    header — the title is never repeated as a row — plus the real
    `status_tile`/`settings_rows`/`app_status` callers the "real UI
    callers" pass above added, carried over unchanged), a **Runtime**
    group that exists only for a
    python/flutter_embed-kind plugin and shows that plugin's own
    download/remove action (moved off the parent screen — the owner's
    direction that a runtime row belongs where the plugin that needs it
    is, not as a top-level list item; automatically fetching it as part
    of approval, with consent, is follow-up work once pluginapi's
    permission model lands and there is a real consent step to hang it
    off), **Version** (the installed version, and an "Update to
    <version>" action when the catalog has one — same `PluginCatalog.
    install` path as below) and **Uninstall**, ending in the page's own
    **Advanced** fold: plugin id, origin and the full-hex archive digest
    live behind it, off the page's face, the same fold the parent screen
    keeps its advanced rows in. A per-plugin permission grant/revoke
    list belongs here too once agent pluginapi's permission model
    (`docs/plugin-
    api.md`) exists; this page has the group structure for it already
    but no invented controls ahead of that data being real.
  - **Updates** (only shown once a plugin is installed) — "N updates
    available" or "Up to date" against the last fetched catalog index,
    or "Catalog not fetched yet" when no index has been fetched (never
    "Up to date" on a catalog that was never read; the subtitle points
    at Add > Browse catalog), with "Update all"
    (`PluginCatalog.updateAll`) when any exist.
  - **Add** — "Browse catalog" (the catalog screen below) and "Install
    plugin file" (the file picker), both install sources in one place
    instead of the file picker being the very last row of the old flat
    list.
  - **Advanced** — "Keys you trust" (unchanged). A single-item group
    today; anything else that is genuinely advanced (rather than
    per-plugin) configuration lands here rather than back at the top
    level.

  The catalog screen itself (opened from Add) is unchanged from the
  paragraph below: one row per catalog plugin — label, description and
  version, the row's own action being Install (not installed), Update to
  <version> (an update is available), or no action at all with
  "Installed <version>" in the value column (current); unpinned-origin
  plugins and plugins with no stable release yet are listed without an
  action and say why. Install and update are the SAME download-verify-
  install path (`PluginCatalog.install`): fetch to cache, whole-file
  SHA-256 against the index's `sha256` first, then the existing
  `PluginBundleInstaller` validation in full, so a catalog bundle gets no
  shortcut past signature/hash checks; a new id lands PENDING and asks
  for approval on that plugin's own detail page the way a picked file
  does. "Update all" runs the same path per plugin, reports each one's
  result, and says so in its summary. Debug-only rows (the status-tile
  test call, the forced-crash test) stay on the plugin's own detail page,
  gated on a debuggable build exactly as before — they are plugin-scoped
  already, not a reason to add a separate top-level debug group.

  Tracked in Droidtop/tracker (app:droidtop, P1): the redesign above.
- **Staleness.** The last successfully fetched index is kept in
  `filesDir/plugin-catalog/index.json`; the catalog screen re-fetches on
  entry when that copy is missing or more than an hour old, and always
  offers a manual refresh row; a failed refresh falls back to the copy
  and says which one it is showing. "Update all" fetches fresh itself
  before comparing. The index is a snapshot and says when it was made
  (`generatedAt`), the same posture Enginehost's index has.
- **Not built:** the droidtop-platforms side that populates
  `droidtop-plugins/index.json` (its own generator and workflow,
  mirroring `generator/plugins_index.py`, in that repository — until it
  exists the app's catalog is empty and the file-picker path is the only
  install source, which is a working state, not a broken one); the
  deferred droidtop root key that would certify new origins without an
  app change; and offering testing/unstable streams.

**The trust-boundary checklist** (unchanged in substance from the
2026-09-02 text, now checked against `PluginBundleInstaller` and
`PluginStore` instead of Enginehost's install path):

1. A hash is always required and always verified — every payload file's
   SHA-256, checked at install AND re-checked before every activation
   (`PluginBundleInstaller.verifyInstalled`), so a file edited on disk
   after approval is a plugin that stops running, not one that keeps
   going unverified.
2. No plugin may shadow a protected id (`PluginBundleInstaller.
   PROTECTED_IDS`) or an id another origin already installed under;
   plugin ids are namespaced `<origin>.<name>` and the namespace is
   itself a structural validation failure, not a runtime check.
3. Everything validates before any plugin code runs: signature, every
   hash, the manifest schema, capabilities against the closed set, the
   contract version, both ABIs when native libraries ship. A plugin that
   fails any step never has its code loaded, not even to ask it to
   describe itself.
4. Signing is real, not a placeholder gesture: ECDSA P-256/SHA-256 over
   the exact manifest bytes, verified against a per-origin public key
   (`BundleSignature`, `PluginOriginKeys`): the official pinned key first,
   then any user-trusted key "Keys you trust" stores (above). Approval is bound to the
   plugin's identity under a key, not to one byte-string: the record
   stores the digest it was verified against
   (`PluginRecord.archiveDigest`, a SHA-256 over the signed manifest)
   AND the fingerprint of the key approval was bound to
   (`PluginRecord.approvedKeySha256`). A re-install of the exact same
   bytes keeps its state; an update with different bytes keeps it too
   when the new bundle's signature verifies against the SAME pinned key
   (the catalog's whole job, "The catalog" above), and starts back at
   PENDING when the key differs, when the origin differs, or when the
   plugin was never APPROVED in the first place. A DENIED state never
   carries over under any digest.
5. droidtop treats what a plugin returns as untrusted input: every
   binder payload is capped (`PluginRunner.MAX_RESULT_BYTES`, 256 KiB),
   parsed against the capability's own shape, and never used as a path,
   a FileProvider URI, an intent target or a launch template without
   droidtop's own validation.
6. Disable and uninstall leave nothing running: `PluginStore.setEnabled`/
   `uninstall` and `PluginCrashPolicy`'s own disable path all go through
   the one `PluginRecord`, and `PluginStore.runnableFor` is the only
   thing a real call site should iterate, so a disabled plugin's rows
   simply stop appearing rather than needing every caller to re-check.

**What is built vs. open.** Built: the manifest format and validation,
signing/hashing, install/uninstall/enable/approve, the approval screen,
the plugin catalog (the droidtop-platforms index, install/update/Update
all from Settings → Plugins, "The catalog" above),
the `native_bundle` runner (isolated process, binder API, crash
containment via `PluginCrashPolicy`), the job shape, the `python` runner
(`PythonRuntimeManager`, the `plugin-host/native` dlopen bridge,
`PythonDroidtopPlugin`, above), and two sample plugins
(`samples/plugin-sample-statustile` for `native_bundle`,
`samples/plugin-sample-py-statustile` for `python`) each exercising
`status_tile` end to end, including a deliberate forced crash for testing
the disable path. Both samples' unsigned payloads are built by CI
(`sample-plugin`, `sample-plugin-python` in
`.github/workflows/android-build.yml`); signing either into an
installable `.droidplugin.tar.xz` still needs droidtop-dev's private key
(`sign.sh` in each sample's own folder) and is not something CI ever
does. The `flutter_embed` runner (`FlutterRuntimeManager`,
`FlutterDroidtopPlugin`, above) and its own sample
(`samples/plugin-sample-flutter-statustile`) are built and rig-verified
the same way (`dq-flutterembed-01` — see "The `flutter_embed` kind"
below), including `startJob` (built 2026-09-26, above). Built 2026-09-28:
the user-trusted origin keys ("Keys you trust", above) — the store, the
official-first resolution, the source-key fetch with its TOFU confirm
and changed-key warning, and the trust badge on the Plugins screen.
Open: the catalog half of a plugin source (listing plugins in, and
downloading bundles from, a source whose key you trusted — the KEY half
is what "Keys you trust" built); `startJob` support for
python-kind plugins; and the rig check for the python leg specifically
(queued, `device/QUEUE.md`) — the `native_bundle` leg's own rig check
(`dq-plugins-01`) already passed.

**The rest of the plugin API finally has real UI callers (built
2026-09-27).** Until this change, `settings_rows`, `app_status` and
`startJob` were real, working capabilities with NO reachable caller
anywhere in droidtop's own UI -- the ONLY generic trigger on the Plugins
screen was `status_tile`'s own debug-shaped "Call ... status tile" row,
and `droidtop-plugin-retroarch`'s own rig check (agent `retroarch`,
2026-09-27) confirmed this concretely: its `app_status` actions and its
`download_core` job existed and worked underneath, but nothing in
droidtop's UI could reach them. This closes that gap, ES-DE style
(actions on the surface that already shows the thing, settings only for
configuration) and entirely in terms of the existing shared catalog
model (`CatalogScreen`/`CatalogItem`, `runtime-common`'s
`settings` package) -- neither renderer (the Preference surface,
`:shell-default`'s SettingsActivity; the in-shell themed section,
`:shell-gamepad`'s `GamingSettingsCatalog`) needed a single line of new
UI code, since both already render whatever this catalog model
describes.

- **`settings_rows`** (`PluginSettingsRows`, `library-core`): first real
  caller. `invoke(SETTINGS_ROWS, {"target": target})` ->
  `PluginResult.success(values = <a flat map>)`, each value shown as its
  own read-only row (a plugin's key never shown, only its value, written
  as a complete sentence -- the shape `RetroArchPlugin.handleSettingsRows`
  already shipped before there was anywhere to show it). `target` is
  `"global"` for the plugin's own entry under Settings > App integrations
  > Plugins (`AppSettingsCatalogs.pluginsScreen`, `:app`), or
  `"system:<id>"` when a future plugin targets one system's own settings
  screen -- a plugin that ignores the arg (every plugin today) returns
  the same rows regardless.
- **`app_status`** (`PluginAppStatus`, `library-core`): first real
  caller of both `invoke(APP_STATUS, {"action": "status"/"launch"})`.
  `PluginAppStatus.sourcesFor(context, packageName)` answers "which
  installed app_status plugins currently report managing this package"
  by calling each one's own `status` action and matching its returned
  `package` value -- on-demand only (one settings-screen open), never
  from list rendering, per the standing performance rule. Wired into two
  real surfaces: the plugin's own entry in the Plugins screen (global,
  no known target package), and -- "where droidtop shows an installed
  app" -- a system's own Player choice screen
  (`AppSettingsCatalogs.folderScreen`), which now looks up
  `sourcesFor` against the CURRENTLY RESOLVED player's real package and
  adds that plugin's status/actions right there. A plugin may also offer
  ONE generic text-entry job from its status answer (`job`, `jobArgKey`,
  `jobLabel` values -- see `PluginAppStatus`'s own doc comment for the
  exact convention); `RetroArchPlugin.statusResult` uses this to offer
  "download a core by name" without droidtop knowing anything
  RetroArch-specific. **Not built**: a droidtop-owned surface for the
  Standard shell's drawer long-press menu (AOSP `launcher3`'s own popup
  menu, `:shell-default`) -- real scope, deliberately left open rather
  than a shallow, unverified change to that vendored tree; the Player
  choice surface above is the one this pass actually shipped and rig-
  checked.
- **Jobs, one shared mechanism (`PluginJobsCenter`, `plugin-host`;
  `PluginJobsScreen`, `library-core`).** Before this, `AcquireContentSources`
  held its own private `PluginCrashPolicy`/callback pair per download,
  invisible to anything but the screen that started it.
  `PluginJobsCenter.start` is now the ONE place a plugin job is started
  from anywhere in droidtop -- a Get-games download, an `app_status`
  text-entry job, an event-hook reaction (below) -- tracked in one
  process-lifetime registry (`entries(): StateFlow<List<Entry>>`) a Jobs
  screen renders live, with cancel (best-effort, per `DroidtopPlugin.
  cancelJob`'s own contract) alongside progress. `AcquireContentSources.
  startDownload` now calls through it instead of building its own
  policy -- same public behavior, one mechanism underneath. Reached from
  Settings > App integrations > Jobs (`SCREEN_JOBS`, registered next to
  Plugins), on both renderers via the same shared-registry mechanism
  every other cross-module screen already uses.
  - **Two real bugs found on the rig (dq-pluginui-01, 2026-09-27) and
    fixed the same day, both in `plugin-host`.** First, a race in
    `PluginJobsCenter.start` itself: it compared an incoming callback's
    jobId against a `var` only assigned AFTER `NativePluginRunner.startJob`
    returned, and added the job's own `Entry` to `state` only after
    that too -- but :pluginhost dispatches a job to its own executor
    (and can call back) the INSTANT the AIDL `startJob` call arrives on
    its side, not after the call returns, so a small, fast job (a real
    buildbot core zip) could complete before either existed, silently
    dropping the update and freezing the row at "Starting.../0%"
    forever even though the plugin genuinely finished. Second, and
    deeper: `PluginRuntimeService` held ONE global
    `IPluginRuntimeCallback` field (`setCallback`), but every concurrent
    `NativePluginRunner` connection from `:app` binds the SAME
    `:pluginhost` Service instance -- `PluginJobsCenter` deliberately
    opens one dedicated connection per job so several can run at once,
    so a second job (or even just a settings screen making its own
    short-lived `invoke()` call) silently STOLE callback delivery from
    every earlier connection still bound, freezing an earlier job's row
    even after the first bug's own fix.
  - **The fix, in order.** (1) `IPluginRuntime.startJob` now takes a
    CALLER-chosen `jobId` instead of generating and returning one --
    `PluginJobsCenter.start` picks it before calling anything, so
    `Entry.jobId` IS the real job id from the very start; no ordering
    requirement, no race. (2) `IPluginRuntime.registerCallback`/
    `unregisterCallback` replaced `setCallback`; `PluginRuntimeService`
    now holds every connected caller in a `RemoteCallbackList` and
    broadcasts every job-progress/job-complete/plugin-crashed event to
    ALL of them (`NativePluginRunner.unbind` unregisters its own
    callback so a closed connection stops paying for broadcasts).
    Broadcasting to everyone means every caller can now receive OTHER
    jobs' events too, so `PluginJobsCenter.start`'s own progress/
    complete closures filter on `eventJobId == jobId` -- a plain `val`
    comparison fixed before any call that could produce an event, not a
    race. Unit-tested in `PluginJobsCenterTest.kt` via a new
    `PluginJobRunner` interface (`PluginCrashPolicy` is the real
    implementation; tests substitute a fake that fires callbacks at
    arbitrary, caller-controlled times, including "before `startJob`
    itself returns" -- the exact ordering that broke) -- no real
    Android Service/RemoteCallbackList/binder connection needed to cover
    the routing logic itself.
- **Event hooks (`PluginEvent`, `plugin-host`; `PluginEventBus`,
  `library-core`).** The OUTGOING half of the plugin API, mirroring
  `PluginCapability` (the incoming half): droidtop fires a closed,
  versioned (`PLUGIN_EVENT_CONTRACT_VERSION`) set of events at approved
  plugins that declared them in a new manifest field,
  `PluginManifest.subscribedEvents` (an id droidtop doesn't recognise is
  silently never matched, not a validation failure -- forward/backward
  compatible the same way an unrecognised capability id already is).
  Carried over the SAME `IPluginRuntime.invoke` binder call
  `PluginCapability` calls use (`PluginRuntimeService.invoke` checks
  `PluginEvent.fromId` first, since the two id namespaces are disjoint by
  construction) -- no new AIDL method, since an event is already "JSON
  in, JSON out, one watchdog-bound call" like any capability invoke, just
  answered by `DroidtopPlugin.onEvent` (default no-op success) instead of
  `invoke`. `PluginCrashPolicy.notifyEvent` checks the subscription
  BEFORE any load/bind, so a plugin that ignores every event costs
  nothing on droidtop's own state changes.
  - **The one event that exists**: `PluginEvent.DEFAULT_PLAYER_CHANGED`,
    fired from the one real write path that changes a system's default
    player (`AppSettingsCatalogs.playerChoiceItem`'s `onSelect`, via
    `PluginEventBus.notifyDefaultPlayerChangedAsync`) with the resolved
    player's id/name/package and the core that player will actually
    launch with. The `core` arg is the chosen entry's OWN core when its
    `LIBRETRO` extra names one -- `libretroCoreId` (`library-core`
    `consoles`) reads it out of the template with the same tokenizer
    that builds the launch Intent, so both real `.so` shapes
    (`<core>_libretro_android.so`, droidtop's own
    `<core>_android.so`) reduce to the same buildbot core id. The real
    case that demands it: psx's configured core is `mednafen_psx`
    (platforms-database.json) while the players database's six
    RetroArch entries each name theirs in the template, so choosing
    "Retroarch - beetle psx hw" launches `mednafen_psx_hw` -- an event
    reporting the system core would make a manager plugin ensure the
    wrong core. Entries naming no core (the generated
    `DefaultPlayers.retroArch` entry embeds the system's value, a
    standalone emulator carries no LIBRETRO extra, and a malformed
    template must never break the player-choice write path) fall back
    to the per-system core setting, `ConsoleSystemDef.retroArchCore`;
    an absent fact arrives as "" (no package, no core). The args map is
    built by one internal function, `defaultPlayerChangedArgs`
    (`PluginEventBus.kt`), so the payload contract is unit-tested
    without a plugin runtime (`LibretroCoreIdTest`,
    `DefaultPlayerChangedArgsTest`). This `core` semantics is
    `PLUGIN_EVENT_CONTRACT_VERSION` 2 (1 always reported the system's
    configured core). A plugin's answer MAY ask
    droidtop to start a job in reaction: `values["startJob"]` names a
    `PluginCapability.id`, `values["job"]` names the job, every OTHER
    returned value becomes that job's own args --
    `PluginEventBus.notifyDefaultPlayerChanged` reads exactly that shape
    and hands it straight to `PluginJobsCenter.start`, so the reaction
    job is tracked and shown exactly like a job the user started by
    hand. `droidtop-plugin-retroarch`'s `RetroArchPlugin.onEvent` is the
    first (and so far only) real subscriber: when the event names an
    installed RetroArch package as the new player and the core
    isn't downloaded yet, it asks for `JOB_DOWNLOAD_CORE` -- the actual,
    cited reason this mechanism was built (`RetroArchPlugin` also gained
    a real `cancelJob` in the same change: it checks a cancellation flag
    every 64 KiB inside its own download loop, since
    `HttpURLConnection`'s blocking read has no other cooperative
    cancellation point -- `DroidtopPlugin.cancelJob`'s default is a
    no-op, so this had to be real plugin-side work, not just a droidtop
    UI wiring change).
  - **A real bug meant this could never fire for ANY plugin (found and
    fixed 2026-09-27, dq-pluginui-01).** `PluginRecord.toJson`/`fromJson`
    (`plugin-host`) never carried `PluginManifest.subscribedEvents` at
    all. `PluginStore.installed()` -- what `PluginEventBus` and every
    other real call site reads -- always goes through this exact round
    trip (`record.json`, written right after install and re-read on
    every later call), so a plugin's real, signed subscription was
    silently reset to empty the moment droidtop persisted its own copy
    of the record, regardless of what the manifest actually declared.
    Confirmed live: `droidtop-plugin-retroarch` v11d72ad's manifest
    genuinely declared `subscribedEvents: ["default_player_changed"]`
    and ps2's own real platform data genuinely carries
    `retroArchCore: "pcee2"` (checked directly against
    `droidtop-platforms`), so setting ps2's Player to RetroArch on the
    rig SHOULD have started a job and did not -- `PluginEventBus`'s own
    `it.manifest.subscribedEvents` filter was checking a set this bug
    had already zeroed out. Fixed by carrying the field through both
    directions of `PluginRecord`'s own JSON, with a round-trip unit test
    (`PluginRecordTest.kt`) reproducing the exact bug (a manifest that
    DOES subscribe, round-tripped the same way `PluginStore` does).

**Rig-checked on BlueStacks (2026-09-27, agent `pluginui`)**: settings_rows,
app_status (both the Plugins screen and a system's Player choice screen),
and starting/tracking a job from the UI all passed against
`droidtop-plugin-retroarch`'s manifest+code update (subscribedEvents,
`onEvent`, job hints, real `cancelJob`). The two bugs above were found
live on the rig during that same session (a job's row freezing, and the
event hook producing no job), root-caused, fixed, unit-tested, and
re-verified on BlueStacks the same day: two concurrent core downloads
plus reopening the app_status screen mid-download all completed
correctly (the callback-theft bug no longer drops or misroutes any of
them), setting a system's Player to an installed RetroArch with a real,
undownloaded core now starts the reaction job, and cancel stopped a
large in-flight download before it finished.

**The "Get games" UI, unifying both acquire_content mechanisms (built
2026-09-26).** Until now `acquire_content` had a real JSON half (§12) and
a real plugin half (above) that could install, approve and show
"Running", but nothing in droidtop's own UI ever actually called a
plugin's `invoke`/`startJob` for this capability -- the settings screen's
only generic plugin trigger was the `status_tile` "Call ..." debug row.
`AcquireContentSources` (`library-core`) is the one mechanism now: it
lists every installed source (plugins declaring `PluginCapability.
ACQUIRE_CONTENT` via `PluginStore.runnableFor`, and JSON integrations of
the matching `IntegrationCapability` via `IntegrationStore.available`,
plugins first) and builds the ONE "Get games" `CatalogScreen` both real
surfaces push: a system's own settings screen (`AppSettingsCatalogs.
folderScreen`, :app) and Gaming's gamelist Select-button options menu
(`GamelistOptionsMenu`, :shell-gamepad, pushed through the same
`CatalogNavigator` its own Settings section already uses, so the query
field gets real controller/touch text entry for free). A JSON source
keeps its original one-way shape (a text field or button that fires an
`am start` and never hears back); a plugin source opens its own search
screen -- a live query field and a focusable results list, each result a
real `AsyncActionItem` that starts the download job and shows its
progress inline, then rescans the library (`LibraryRescan`) on success so
the file appears without a separate manual step. No installed source at
all shows a plain row saying so, rather than hiding the whole "Get games"
entry.

**The wire contract this UI defines** (none existed before it -- this is
the first real caller of a plugin's `acquire_content` `invoke`/`startJob`
anywhere in droidtop): `invoke(ACQUIRE_CONTENT, {"action": "search",
"query", "systemId", "systemName"})` answers
`PluginResult.success(values = {"entries": <a JSON array string>})`,
where droidtop reads only `title` (or `name`), `platform`, and a size
label (`size_str`, `sizeLabel`, or a `links` array's first entry's own
`size_str`) from each element -- everything else is opaque and
round-tripped back unread. `startJob(ACQUIRE_CONTENT, {"action":
"download", "entry": <that exact JSON object>, "destinationPath": <the
system's real, already-resolved folder>, "linkIndex": "0"})` is the
download, progress/completion over the normal job callback. Deliberately
generic (no plugin-specific field names): any `acquire_content` plugin
speaks this same shape.

**A shared ABI-choice bug picked the wrong runtime on a real x86_64
device (found and fixed 2026-09-26).** `FlutterRuntimeManager.currentAbi`,
`PythonRuntimeManager.currentAbi` and `PluginRuntimeService.
nativeLibraryDirFor` all picked x86_64 only when the device's
`Build.SUPPORTED_64_BIT_ABIS` did NOT also list arm64-v8a. BlueStacks
lists both (arm64-v8a for its own ARM-translation layer, x86_64 as the
real native ABI), so all three fell through to arm64-v8a on a real
x86_64 process. Found running the "Get games" UI's first-ever real
search against a real flutter_embed plugin: `:pluginhost` tried to
`dlopen` the downloaded arm64-v8a `libflutter.so` and failed with
"has unexpected e_machine: 183 (EM_AARCH64)". Fixed identically in all
three: x86_64 wins whenever it is present, full stop, since it is always
the real native ABI when listed at all.

**A python-kind plugin could never actually run (found and fixed
2026-09-26).** The Plugins settings row and the "Download Python runtime"
action both worked and were reachable on BlueStacks (Android 9) all
along, on both settings surfaces and via search — that could not be
reproduced despite retesting dq-pyplugin-01's own repro on the exact
reported build/commit. What was real and did reproduce every time: once
approved, a python-kind plugin's "Call ... status tile" always failed
with "plugin failed to load", no process, no exception, nothing in
logcat. `NativePluginRunner.load()` (`plugin-host`) required
`record.manifest.entryClass` to be non-null for every plugin and returned
`false` before even binding `PluginRuntimeService` when it was not — but
`PluginManifest.structuralProblems()` only requires `entryClass` for
`native_bundle`; a python-kind manifest legitimately has none
(`PluginRuntimeService.loadPlugin`'s own comment: "entryClass is simply
unused on the python path"). Fixed by defaulting to `""` for that case
instead of bailing out, the same "unused" placeholder the service side
already assumed the caller would send.

Re-verified on BlueStacks with the signed `plugin-sample-py-statustile`
bundle after this fix: the plugin now genuinely loaded, but its first
real invoke() crashed `:pluginhost` outright with SIGSEGV (fault addr
0x10, null pointer dereference, tombstone_14) — a second, separate bug in
the python-kind native bridge (`plugin-host/native`), not in the
entryClass path above. Root cause: `nativeInit`
(`native/src/droidtoppy_jni.c`) ran `Py_InitializeEx` on whichever binder
thread happened to handle that plugin's `loadPlugin()` AIDL call, which
implicitly gives THAT thread the GIL and never released it, and nothing
in this file ever called `PyGILState_Ensure`/`Release`. Every later call
(`nativeLoadModule`/`nativeCallFunction`/`nativeUnloadModule`) arrives on
whichever thread the binder pool happens to pick for that AIDL call —
almost never the same OS thread `Py_InitializeEx` ran on (the crashing
thread's own name, `Binder:1644_2`, was not the loading call's thread) —
and calling into libpython with no `PyThreadState` on the calling thread
is undefined behavior. Fixed the standard way CPython's own embedding
docs describe for a multi-threaded host ("Non-Python-created Threads"):
`nativeInit` now releases the GIL with `PyEval_SaveThread()` once setup
finishes, and every other entry point brackets its Python calls in
`PyGILState_Ensure()`/`PyGILState_Release()` (both stable ABI since 3.2,
resolved by `dlsym` like every other symbol in this file). CI now also
keeps plugin-host's unstripped `libdroidtoppy.so` as its own artifact
(`droidtoppy-native-symbols`) so a future native crash's tombstone can be
symbolised against the exact commit that built it.

Re-verified end to end on BlueStacks after the GIL fix, fresh CI build:
download the Python runtime, install and approve the signed sample
bundle, "Call ... status tile" now genuinely succeeds ("called OK",
repeated calls stayed stable, no crash), and "Debug: force ... to crash"
still disables the plugin the same as before — droidtop's main process
was unaffected throughout, confirming crash containment was never the
problem, the bridge's own thread-safety was.

**The `flutter_embed` kind (built 2026-09-26).** Built by agent
`flutterkind` on top of the `python` kind and the already-passed
`native_bundle` rig check, per the build-order decision above. Feasibility
was checked against real Flutter engine source and real, live artifact
URLs before any code was written — not assumed:

1. **`libflutter.so` from outside the APK.** `FlutterJNI.loadLibrary(Context)`
   is the ONLY place the engine loads its own native library, and it is a
   plain, non-final, public method (`ReLinker.loadLibrary(context,
   "flutter")`, i.e. `System.loadLibrary("flutter")` under the hood — which
   only ever searches this process's own installed native library
   directory, confirmed against `flutter/engine`'s own `FlutterJNI.java`
   and `ReLinker`'s `SystemLibraryLoader.java`). `FlutterJNI` is
   subclassable and `FlutterLoader` has a public
   `FlutterLoader(FlutterJNI)` constructor that takes an injected
   instance — both confirmed via `javap` against the real, pinned
   `flutter_embedding_release` jar (below), not guessed from docs.
   `FlutterDroidtopPlugin.DownloadedFlutterJNI` overrides just that one
   method to `System.load()` the absolute path `FlutterRuntimeManager`
   downloaded, and `FlutterEngine`'s
   `(Context, FlutterLoader, FlutterJNI, String[], boolean)` constructor
   takes that custom loader directly — no `FlutterInjector` singleton, no
   reflection, no hidden API.
2. **`libapp.so` (the plugin's own AOT snapshot) from outside the APK.**
   `FlutterLoader`'s own source adds `--aot-shared-library-name` TWICE —
   once as a bare name, once as `nativeLibraryDir + File.separator +
   aotSharedLibraryName`, with the comment "provide a fully qualified path
   ... as a workaround for devices where [the bare name] fails" — and
   caller-supplied `dartVmArgs` (from `FlutterEngine`'s constructor) are
   appended LAST, after both of those, so "last occurrence wins" for a
   repeated flag lets `FlutterDroidtopPlugin` pass its own
   `--aot-shared-library-name=<plugin's own installDir>/lib/<abi>/libapp.so`
   and have it win. Both of these are confirmed straight from
   `FlutterLoader.java`'s real source, not inferred.
3. **The engine binary itself is real and downloadable.** Two Google-owned
   CDN buckets are involved, confirmed live on 2026-09-26 by actually
   downloading from both at the exact pinned version
   (`af7e796e161ae0bb1ff0758c71a7105418bd9ded`, the current stable-channel
   engine as of that date):
   - `storage.googleapis.com/flutter_infra_release/flutter/<version>/android-<arch>-release/artifacts.zip`
     — the same bucket `flutter precache` itself downloads from — contains
     one file, `flutter.jar` (an Android native-library jar, not a
     dex/class jar), holding `lib/<abi>/libflutter.so`. This is what
     `FlutterRuntimeManager` downloads at runtime, verified against
     `plugin-host/src/main/assets/flutter-runtimes.json`'s pinned sha256
     for each ABI.
   - `storage.googleapis.com/download.flutter.io` — the Maven repo
     Flutter's own Gradle plugin (`FlutterPlugin.kt`) adds for
     `io.flutter:flutter_embedding_release:1.0.0-<version>`, the Java-only
     embedding classes (`FlutterEngine`/`FlutterJNI`/`FlutterLoader`/
     `MethodChannel`/...). This is a normal COMPILE-TIME
     `plugin-host/build.gradle.kts` dependency — a few hundred KB of plain
     JVM bytecode with no Dart/native engine code in it, the same
     "small glue code ships in the base APK" call already made for
     `PythonBridge`'s native glue. What must never be bundled, and isn't,
     is the actual Dart/Skia/Impeller engine binary (`libflutter.so`,
     tens of MB per ABI) — that stays a `FlutterRuntimeManager` download,
     same as CPython's `libpython3.14.so` is for the `python` kind.
   Unlike python.org's own release archives (kept indefinitely, per
   `PythonRuntimeManager`'s header), this is one upstream's storage
   retention behaviour, not a published guarantee — a GCS bucket listing
   query against a handful of engine hashes going back to 2022 all still
   returned real files on 2026-09-26, but if a future pinned version's
   artifact ever disappears, the fix is re-hosting that exact version's
   bytes under a droidtop-controlled URL with the SAME pinned hash, never
   silently drifting to a newer, unverified one.
4. **Licence.** The Flutter engine and its Android embedding are
   BSD-3-Clause (`flutter/flutter`'s own `LICENSE`); redistributing the
   downloaded binary and depending on the Java classes at build time both
   carry only the standard BSD attribution requirement, satisfied via
   `NOTICE.md`.

**`flutter_assets` loaded from outside the APK — confirmed working,
rig-verified 2026-09-26.** Everything above resolves entirely through
public, non-final Flutter classes. Assets are different: the engine reads
`flutter_assets/` (the kernel blob, the AOT's own data, fonts, images)
through Android's real `AssetManager`, which only ever reads zip-shaped
sources — APK assets, or another zip/apk added via
`AssetManager.addAssetPath(String)`. That method is public through API 28
and reflective-only since (a long-standing technique real Android
plugin-hosting frameworks still use, not invented here).
`FlutterDroidtopPlugin.loadAssetsIntoEngine` repacks the plugin's own
extracted `flutter_assets/` tree into a small zip and calls
`addAssetPath` on it reflectively. `dq-flutterembed-01` confirmed on
BlueStacks (Android 9) that this reflective call is NOT refused: the
documented fallback (repackaging `flutter_assets` as a tiny per-plugin
Android split/APK) was never needed. This was the risk this section
originally flagged as the one open feasibility question — it was not,
in the end, where a real device actually broke; see "Rig-verified
2026-09-26" below for what did.

**What's built.** `FlutterRuntimeManager` (download/verify/extract
`libflutter.so`, mirroring `PythonRuntimeManager`'s shape exactly —
`plugin-host/src/main/assets/flutter-runtimes.json` pins the exact engine
version and per-ABI sha256), `FlutterDroidtopPlugin` (the `DroidtopPlugin`
adapter: constructs the engine, bridges `invoke` over one `MethodChannel`
per plugin at `dev.droidtop.pluginhost/<pluginId>`, same JSON-in/JSON-out
shape `PythonDroidtopPlugin` already uses), and `PluginRuntimeService`'s
dispatch for `PluginKind.FLUTTER_EMBED`. `PluginManifest.runtimeVersion`
is new: a flutter_embed manifest must declare the EXACT engine version its
`libapp.so` was built against (a Dart AOT snapshot's format is tied to the
exact engine build, not a version range — confirmed against real Flutter
tooling's own "Snapshot not compatible with the current VM configuration"
failure mode), checked byte-for-byte against
`FlutterRuntimeManager.pinnedVersion()` before activation; a mismatch
disables the plugin with that reason rather than failing deep inside
native engine init. Settings → Plugins gets the same "separate, explicit
download" row shape the Python runtime already has
(`AppSettingsCatalogs.kt`). Sample:
`samples/plugin-sample-flutter-statustile`, a minimal Dart app (one
`status_tile` handler) built by a pinned Flutter SDK in CI
(`sample-plugin-flutter` job) and signed the same way every other sample
is (`sign.sh`, droidtop-dev's key only).

**The readiness handshake (found needed 2026-09-26, part of the
flutter_embed contract).** The acquire_content UI's own first real
`invoke()` call against a flutter_embed plugin
reliably failed with a MethodChannel `PlatformException(channel-error,
Unable to establish connection on channel ...)` -- `FlutterEngine.
dartExecutor.executeDartEntrypoint()` only STARTS the plugin's Dart
isolate; it returns before that isolate's own `main()` body has actually
run far enough to call `setMethodCallHandler`. `FlutterDroidtopPlugin.
onLoad()` used to return as soon as `executeDartEntrypoint` did, so the
very next `invoke()`/`startJob()` call could be posted to the engine's
platform thread before Dart's own handler existed at all -- a real race,
not a timing coincidence specific to one plugin (the trivial sample's own
rig check, `dq-flutterembed-01`, happened not to call `invoke()`
immediately enough after load to hit it).

Fixed with a no-sleep, no-polling handshake: every flutter_embed plugin's
Dart entrypoint MUST call `_channel.invokeMethod('ready')` as the very
first thing it does right after `setMethodCallHandler` (`samples/
plugin-sample-flutter-statustile/lib/main.dart` does this now).
`FlutterDroidtopPlugin.onLoad()` creates a `CountDownLatch` right before
`executeDartEntrypoint`, registers a `"ready"` handler in its own
`handleIncomingCall` dispatch that counts it down, and -- after
`executeDartEntrypoint` returns -- awaits that latch (bounded by
`PluginRunner.CALL_TIMEOUT_MS`, the same watchdog `loadPlugin`'s AIDL
call is already covered by end to end) ON ITS OWN CALLING THREAD, never
the main thread: the "ready" call itself arrives as an ordinary
MethodChannel message delivered on the main Looper, so waiting for it
FROM the main thread would deadlock the very thread that has to deliver
it. A plugin that never signals ready fails `onLoad` with a clear
message naming the missing call, the same "throwing is how onLoad
reports failure" contract every other load failure already uses -- not a
silent hang.

Re-verified on BlueStacks after this fix: the acquire_content UI's search
call against the flutter_embed plugin reached real Dart code and
returned a real answer, with no channel-error.

**Not built:** `startJob` for flutter_embed (same "not supported until a
real plugin needs it" default every other kind starts with); the
`flutter_assets`-outside-the-APK technique's rig verification (above); a
shared single `FlutterEngine`/runtime-download UX across multiple
flutter_embed plugins beyond the one shared `libflutter.so` download
already in place (each plugin still gets its own `FlutterEngine`
instance — the owner's "one shared engine" framing was about not paying
the runtime-download cost twice, which this satisfies; a literally shared
*instance* running two plugins' Dart code in one isolate group is a
different, larger feature this build didn't attempt).

**`startJob` for flutter_embed, built 2026-09-26.** A real forthcoming
flutter_embed plugin needs long-running jobs with progress (a download),
so this landed ahead of the "not supported until a real plugin needs it"
default every other kind still uses. `FlutterDroidtopPlugin.startJob`
posts one `"startJob"` call to Dart's own `MethodChannel` (the same
channel `invoke` already uses) carrying `{jobId, capability, args}`, then
returns immediately -- Dart is expected to answer later by calling BACK
into the host on that same channel (`MethodChannel` is bidirectional on
one `BinaryMessenger`; `FlutterDroidtopPlugin` now also calls
`setMethodCallHandler` on it, which `invoke`'s host-to-plugin-only
direction never needed) with `"jobProgress"`/`{jobId, percent,
statusLine}` zero or more times and exactly one `"jobComplete"`/`{jobId,
result}`, dispatched to the matching `PluginJobProgress` via an in-memory
`jobId -> PluginJobProgress` map. `cancelJob` forwards `{jobId}` to Dart
the same fire-and-forget way (best-effort, as the interface already
documents).

This also fixed a real, pre-existing gap in `DroidtopPlugin.startJob`
itself, not something flutter-specific: `PluginRuntimeService.startJob`
already generates a `jobId` (returned to droidtop's own caller) but never
handed it to the plugin's own `startJob()`, so nothing implementing
multiple concurrent jobs could ever correlate a later `cancelJob(jobId)`
back to a specific one. Fixed by adding `jobId` as `DroidtopPlugin.
startJob`'s first parameter (no existing override to migrate --
flutter_embed above is the first kind to implement it at all).
**Rig-verified 2026-09-26 (`dq-flutterembed-01`), three real bugs found
and fixed before a flutter_embed plugin could actually run at all:**

1. **`PluginCrashPolicy` never let a flutter_embed plugin run.** The
   Settings UI's "Call ... status tile" action goes through
   `PluginCrashPolicy.invoke()`/`startJob()` (called from
   `AppSettingsCatalogs.kt`), NOT directly through
   `PluginRuntimeService`'s own dispatch. That class's own kind gate was
   still `!= PluginKind.NATIVE_BUNDLE && != PluginKind.PYTHON` -- never
   updated when the flutter_embed runner landed earlier the same day. A
   flutter_embed plugin could install, get approved, and show "Running",
   but the one path the UI actually calls always answered "no runner for
   kind flutter_embed yet" before ever reaching `FlutterDroidtopPlugin`.
   Fixed with one `RUNNABLE_KINDS` set covering all three kinds.
2. **`FlutterEngine` construction must happen on the main thread.**
   `onLoad()` runs on whatever thread `NativePluginRunner`'s AIDL call
   lands on inside `:pluginhost` (a binder thread) -- but `FlutterEngine`'s
   constructor, `FlutterLoader`'s init and
   `DartExecutor.executeDartEntrypoint` are all `@UiThread`, enforced by
   Flutter itself ("Methods marked with @UiThread must be executed on
   the main thread"). `runOnMainThreadBlocking` now posts `onLoad`'s
   whole body to the main `Looper` and blocks the calling thread on a
   `CountDownLatch` (the same shape `invoke()` already used for
   `MethodChannel` calls), rethrowing on the caller so `onLoad`'s normal
   throw-to-report-failure contract is unchanged.
3. **`DartEntrypoint.createDefault()` depends on a singleton this class
   deliberately never touches.** That factory reads
   `FlutterInjector.instance().flutterLoader()` -- the process-wide
   singleton -- and throws ("DartEntrypoints can only be created once a
   FlutterEngine is created") if IT was never initialized, which it
   never is here on purpose (this class's own header comment: "never
   touching the process-wide `FlutterInjector` singleton at all"). Fixed
   by building the `DartEntrypoint` by hand from OUR OWN `flutterLoader`
   instance's `findAppBundlePath()` -- already initialized by the
   `FlutterEngine` constructor that just ran -- instead of calling
   `createDefault()`.

After all three fixes: the signed sample plugin's real Dart code ran
inside `:pluginhost` and returned its real value ("Hello from Dart,
running inside :pluginhost (flutter_embed)"), confirming `flutter_assets`
loading from outside the APK works (point 1 above, "Rig-verified" in
that section) was never actually the blocker. The forced-crash check
also passed: `:pluginhost` died (confirmed via `ps` and logcat's
"Process dev.droidtop.app:pluginhost ... has died"), droidtop's own
process and UI stayed fully responsive throughout, and the plugin was
disabled with "call failed across the binder" -- the same crash
containment shape the `native_bundle` and `python` kinds' own rig checks
already confirmed.

**Open question for a real Flutter/Dart app being embedded this way:**
this build's own sample only exercises `status_tile`, the simplest
capability, over one MethodChannel handler for `"invoke"`. A real app
being adapted this way still needs to work out how its own capability
calls map onto that Dart `main()`/`"invoke"` handler, and whether its own
Dart dependencies (image/network libraries with their OWN native code)
fit inside a single `FlutterEngine`'s asset/native-lib model the same way
this sample's trivial UI-less Dart does — untested here, and specific to
whatever plugin is being built, not this runner itself.

**Search fan-out, the Sources API and the Recommendations API
(2026-09-28).** The owner's rule for this whole piece: "droidtop doesn't
implement plugin specific features. If the search function already
exists in droidtop, the plugin should handle the rest" — and "we need to
think in terms of APIs." Three pieces, kept deliberately separate:

**1. The Sources API (`dev.droidtop.library.integrations.GameSourceProvider`,
`library-core`).** The one interface droidtop's UI talks to for "search
for a game" and "where can I get this game" — a built-in store and a
content plugin are interchangeable implementations of the same interface;
droidtop's UI never imports or branches on a store's or a plugin's own
type.

```kotlin
interface GameSourceProvider {
    val id: String
    val label: String
    suspend fun search(context, query, platform): Result<List<AcquireContentResult>>
    suspend fun lookup(context, title, platform, ids = emptyMap()): Result<List<AcquireContentResult>> // default: search by title
    suspend fun options(context, result): List<AcquireContentOption> // default: result.options
    suspend fun acquire(context, result, choice, destination, onProgress, onComplete): AcquireContentJob?
}
```

- **Result shape** (`AcquireContentResult`): `id`, `title`, `subtitle`,
  `platform`, `artUrl`, `sizeLabel` (kept for the older per-system "Get
  games" screen), `options: List<AcquireContentOption>` (`label`,
  `index`), and `raw` — the exact JSON object round-tripped back to the
  source, unread beyond these fields (unchanged trust rule from before
  this change: a plugin's own values are untrusted).
- **Threading/timeout/errors**: every method is `suspend` and expected to
  do its own work off the caller's thread; a plugin implementation is
  bounded by the plugin runner's own watchdog (`PluginRunner.CALL_TIMEOUT_MS`,
  15s) for `search`/`lookup`, with `PluginSearchAggregator.PER_SOURCE_TIMEOUT_MS`
  (16s) as the caller-side backstop for a source that never returns;
  `search`/`lookup` return `Result.failure` rather than throwing across
  this boundary. A future store adapter must honor the same shape.
- **Versioning**: this is droidtop's own internal Kotlin interface, not a
  wire contract. `PluginGameSource` is the one real implementation today,
  adapting a plugin declaring `PluginCapability.ACQUIRE_CONTENT` onto this
  interface via the EXISTING `invoke`/`startJob` wire contract
  `AcquireContentSources`'s own doc comment documents (unchanged: `invoke`
  action=search returns `{"entries": <json array>}`; `startJob` action=
  download takes `entry`/`destinationPath`/`linkIndex`). `ACQUIRE_CONTENT`
  IS the "source" extension point in `PluginCapability`'s existing closed
  set — that capability already reaches a plugin identically regardless
  of kind (`native_bundle`, `python`, `flutter_embed`), since
  `PluginRuntimeService`'s `invoke()`/`startJob()` dispatch is kind-agnostic
  by construction (one `DroidtopPlugin` interface, one adapter per kind:
  `NativePluginRunner`, `PythonDroidtopPlugin`, `FlutterDroidtopPlugin|
  FlutterRuntimeManager`) — no new capability id or contract-version bump
  was needed to make `acquire_content` a proper "source" extension point;
  it already was one. **Not built in this change**: built-in store
  adapters (Steam/GOG/Epic/Amazon/itch/DLsite, and later official
  re-release markets) as further `GameSourceProvider` implementations —
  each needs its own store search/catalog API or scrape, separate
  infrastructure from this interface; a dedicated per-kind `source`
  sample plugin beyond the existing `acquire_content` wire contract
  samples already exercise; a distinct `recommender` plugin capability
  (Recommendations stays droidtop's own and plugin-independent by owner
  directive below, so this is not planned unless that direction changes).
  Tracked on Droidtop/tracker (see the Recommendations issue below).

**2. `PluginSearchAggregator` (`library-core`) — the ONE generic search
fan-out mechanism** every existing game-search surface asks instead of
hand-rolling its own plugin loop: `searchAll(context, sources, query,
platform)` runs every `GameSourceProvider.search` concurrently
(`kotlinx.coroutines.async`), each individually bounded by
`PER_SOURCE_TIMEOUT_MS` and individually failure-isolated (a source that
throws or times out contributes nothing and never delays or fails
another source's results); `merge` keeps each source's own result order
and the sources' own encounter order, no cross-source resorting, so a
source's own relevance ranking survives into the UI's "Get more" group.
Unit-tested (`PluginSearchAggregatorTest`) against fake
`GameSourceProvider`s under real (not virtual-clock) short delays: merge
order, a throwing source not affecting others, a source slower than the
timeout being dropped while a fast one still returns, and parallel (not
sequential) execution.

**Search surfaces wired (2026-09-28).** The shared `LibraryQuery` search
(`LibrarySearchDialog`, `shell-gamepad/query/LibraryQueryUi.kt` — the
component the "one shared filter/sort/search component reusable by
console lists" commit built, today consumed by the PC library view,
`PcLibraryView.kt`, which IS "the PC view's search dialog": one and the
same component) now debounces the typed query (350ms) and fans it out via
`PluginSearchAggregator.searchAll` to `GameSources.plugins(context)`,
rendering a "Get more" group below the local match count using the
existing `MenuRow` row component, labelled by each hit's source. Picking
a result with more than one `AcquireContentOption` opens a droidtop sheet
(`SourceOptionsDialog`) to choose one; picking a single-option (or
option-less) result, or confirming a choice, calls
`GameSourceProvider.acquire` directly — progress and completion surface
through the EXISTING jobs mechanism (`PluginJobsCenter`/the Jobs screen),
not a new progress UI in this dialog, which only shows one
acknowledgement line. A cross-system list (the PC library) has no natural
download destination; `LibrarySearchDialog` takes an optional
`systemFolder: File?`/`systemId: String?` — null means Get More still
shows results, but picking one explains there's nowhere configured to
download to here, rather than silently sending an invalid path to
`startJob`. **Console gamelist search (built 2026-09-29).** A console
system's (or collection's) Select menu has a "Search" row that opens the
SAME `LibrarySearchDialog`, with that system's id and its games folder as
the download destination, so "Get more" works there and a picked download
lands in the system's own folder. The typed text narrows the gamelist
through `matchesSearchText`, the one text rule `LibraryQuery.matches` also
uses (title, genre or developer); the row reads "Search: <text>" while a
search is on, and clearing the text in the dialog clears it. The search
lasts as long as the gamelist stays open. The PC group keeps its own chip
row. **The launcher's drawer search is the same dialog too** (built
2026-09-29, Droidtop/tracker#12): the drawer's search field opens
`LauncherSearchActivity`, which draws `LibrarySearchDialog` with the installed
apps and the library's games as local results, and gets the "Get more" group and
the Recommendations from the dialog itself. `LibrarySearchDialog` gained two
optional parameters for it, a `summary` line and a `results` slot for the local
rows, and its column scrolls; the console and PC callers pass neither. See the
Launcher mode section, "Launcher search", for the seam and what was deleted.

**3. The Recommendations API (`dev.droidtop.library.integrations.RecommendationProvider`,
`library-core`) — droidtop's OWN feature, never plugin-fed, never
plugin-named** (owner directive, verbatim: "droidtop gets its own,
plugin-independent Recommendations feature... it never mentions or
depends on any plugin"):

```kotlin
interface RecommendationProvider {
    suspend fun recommend(context, scope: RecommendationScope, limit: Int): List<Recommendation>
}
sealed interface RecommendationScope { Overall; Platform(systemId); BecauseOf(ownedTitle) }
data class Recommendation(title, platform, reason, artUrl?, description?, score)
```

Shaped as an interface for the same reason as the Sources API: a future
re-ranking provider (a server-side model, a second local heuristic) can
be added or swapped with no UI change. `LocalSimilarityRecommendations`
is the one built-in implementation, and its ranking math is pure and
unit-tested (`LocalSimilarityRecommendationsTest`):
- `weightedRating(rating, count, priorMean, priorCount)` — the standard
  IMDB/Bayesian formula, so a title with few votes is pulled toward the
  catalog average rather than letting "5 votes of 100" outrank "5,000
  votes of 90" (owner's own example); kept for the catalog pool below,
  which is where vote counts exist;
- `SimilarityIndex` — the library's genre/developer signal folded once
  (weight by play), so scoring a candidate is two map lookups and the
  ranking is linear in the library, never quadratic; 0..1 so library size
  doesn't inflate it (`librarySimilarity` is the same index behind the
  old list-shaped signature);
- `playWeight(playtimeSeconds, daysSinceLastPlayed)` — how much one played
  game counts toward similarity: log-scaled playtime (an hour and a
  hundred hours don't differ 100x) times a recency multiplier (played in
  the last week counts most).

**What `recommend()` returns today (built 2026-09-29).** It ranks the games
already in the user's library that they have NOT played, by how much they
resemble what they do play: genre (0.6) and developer (0.4) matched
against the played games, each weighted by `playWeight`, plus a small
bonus for the game's own scraped rating (0..1, `RATING_BONUS` 0.25). Only
what droidtop really holds is used (`LibraryEntry` genre, developer,
rating, play count, playtime, last played); a game that resembles nothing
played is left out, hidden and broken games are skipped, a played game is
never recommended, and with no play history the answer is empty rather
than a guess. `Platform(systemId)` narrows to one console system;
`BecauseOf(title)` uses that one game as the whole basis and its `reason`
says so; otherwise the `reason` names the played game it most resembles
("Same genre as ...", "Same developer as ..."). The provider is handed a
supplier of the entries and runs on `Dispatchers.Default`.
The empty search field of `LibrarySearchDialog` shows the top five as
"Recommended for you" (PC library only today, computed only while the
dialog is open); picking one puts its title in the field.

**The catalog pool is still the remaining input.** The owner's fuller
signal set — IGDB `total_rating`/`total_rating_count`/`similar_games`,
ScreenScraper ratings, a RetroAchievements popularity proxy — needs a
catalog of games the user does NOT own, fetched and cached by droidtop.
That is a second candidate pool in `rank` (the Bayesian rating and the
same `SimilarityIndex` apply to it unchanged), and it is what gives
"where to get it" something to look up (`GetMoreComposer.composeEmpty`), a
recommendation from the library itself being owned already. Nothing is
fabricated in the meantime: no title enters a list that is not in the
library. The remaining work is tracked on Droidtop/tracker,
"Recommendations: droidtop's own games-you-might-like feature": the
catalog fetch and cache, RetroAchievements popularity, the on-device
index's background rebuild, store adapters for lookup, and the
"Top rated on <system>" presentation.

**Layering — how the three pieces compose (owner directive, verbatim
below each rule).** A separate `GetMoreComposer` object
(`library-core/integrations/RecommendationProvider.kt`) holds the
composition so neither API needs to know about the other:
- *"search results = library + Sources.search"* — `GetMoreComposer.
  composeSearch` is exactly `PluginSearchAggregator.searchAll`, unchanged
  from point 2 above; the local library match and the Sources fan-out are
  two separate lists the UI already renders separately (the existing
  local list, then "Get more" below it).
- *"'Get more' with no query = Recommendations.recommend ∩ Sources.lookup"*
  — `GetMoreComposer.composeEmpty(context, sources, recommendations,
  scope, limit)` asks the Recommendations API for `scope`'s ranked
  candidates, then fans EACH candidate's title+platform out to every
  `GameSourceProvider.lookup` the same way `composeSearch` fans out a
  typed query, pairing each `Recommendation` with whichever sources
  answered (`RecommendedRow(recommendation, hits)`).
- *"most ROMs have no official source... show the recommendation anyway,
  and simply list no source"* — `composeEmpty` keeps every recommendation
  in its result list regardless of whether any source matched;
  `RecommendedRow.hits` is simply empty, never dropped.
- *"'Where to get it' = Sources.lookup for one game"* — the same
  `GameSourceProvider.lookup` call `composeEmpty` already makes per
  recommendation is the whole mechanism for a single game's own
  "where to get it" row; no second lookup path exists or is needed.
- *"stores and markets could themselves be source plugins in future...
  keep the list a single mechanism, fed by both the built-in stores and
  plugins, so adding a source never needs new UI"* — this is exactly why
  `composeEmpty`/`composeSearch` both take `List<GameSourceProvider>`
  rather than "plugins" specifically: `GameSources.plugins(context)` is
  today's only real source of that list, and a store adapter is a second
  `GameSourceProvider` added to the same list, not a UI change.
- **Get more's empty-query state**: `LibrarySearchDialog` shows the
  library recommendations above directly. `GetMoreComposer.composeEmpty`
  (recommendations paired with `lookup` results) is not called by a
  surface yet; it is for the catalog pool, whose games are not owned and
  so have somewhere to be got from.

## 13. UI v2 — end-user redesign direction (dtv2ui audit, 2026-09-28)

**Scope of this pass.** A code- and spec-level audit (SPEC, DESIGN-LANGUAGE.md,
the Gaming/Settings/PC-surface sections above, and the settings-catalog and
Quick Menu source) done from a cloud session with no device rig time. It is
NOT the hands-on, screenshot-by-screenshot walk of every mode the owner
asked for — that still needs a rig pass (see "Needs a rig check" below and
`/repos/ws/dtv2ui/review-notes.md`). Findings below are grounded in what the
code and this file already say, not device observation; where this pass
would have needed a screenshot to confirm something, it says so rather than
asserting it.

**Framing.** §7j, §7k and DESIGN-LANGUAGE.md already got droidtop most of
the way from "software a developer touches" to "software an end user
touches" — one selection idiom, one spacing/type/colour source, the Quick
Menu's status-header-plus-tiles pattern, capture-style input binding, the
hint row as the touch route to pad buttons. The `## 7i` PC-surface history
above (three redecisions in three weeks, each one walking back a more
developer-shaped answer toward a themed, task-first one) is the clearest
evidence of the "accumulation" the owner named: not bad taste, but each
mode having grown its own answer to the same handful of questions
("how does a person reach settings", "how does a person see what a thing
is", "how does a list get filtered") before a shared answer existed. UI v2
is not a rewrite of the shell; it is finishing that convergence
deliberately, mode by mode, instead of letting the next surface reinvent it.

### Gaming mode

**Today.** Real per §7f/§7i/§7j: themed carousel and gamelists, an
expanded frame-only PC library view with a `LibraryQuery` Browse button, a game page, `PcGameMenu`
on L2/Y, Quick Menu with a Settings tile (`## One consistent way into
Settings`, above), Start opening the Quick Menu.

**What still reads as accumulation:**
- Three different entry points into "configure this system" exist because
  they were added at three different times: the gamelist options menu's
  "System settings" row, the Settings catalog's own Library > Console
  systems row, and the Quick Menu's Settings tile. The first no longer
  accumulates: since 2026-09-29 it deep-links to this system's own
  section of the Console systems screen ("One consistent way into
  Settings", above, same date), so the route a player is most likely to
  take when a system just needs its emulator fixed lands on exactly
  that system; the other two remain generic entry points into the same
  screen, each individually justified in the sections above.
- `PcGameMenu` (ES-DE-style, L2/Y) carried runner, container settings,
  ProtonDB, Lutris import, F95 link/update, and merge/versions in one flat
  list — a direct translation of what `## 7i` calls "everything ES-DE has
  no slot for", not a list a first-time player would recognise as grouped
  by task ("play differently" vs. "about this copy" vs. "fix a problem").
  This no longer accumulates: since 2026-09-29 the same rows and actions
  sit under three section headers — Play (Runs with, Play/Set up,
  Install/Manage install), About (Owned on, store links, the F95 thread
  and its update state, compatibility and ProtonDB, scrape/match/media/
  collections; favourite moved to the top page 2026-09-30), and Fix and advanced (the replacement fold,
  same-game merge, the engine pin, versions/segments, and the runner's
  own settings rows) — drawn with the same `MenuSectionLabel`/`MenuRow`
  shell every other in-context menu uses, nothing new. ProtonDB landed
  under About rather than the "Advanced" this sketch first named: it is
  read-only evidence about the game (§7i, "evidence, never a verdict"),
  not an internal that changes how this copy runs, which is what "Fix
  and advanced" holds. The old mechanism-named group titles ("Game
  management", "Runs on Windows") and the "Links"/"Compatibility"
  sub-headers folded away — every row already says what it is in its own
  subtitle. The premise itself (that the flat list read as ungrouped)
  was never confirmed on the rig: the 2026-09-28 walkthrough collapsed
  before reaching `PcGameMenu`, so the regrouping was cut from code alone
  and the rig item at this section's end is what says whether it reads
  right. Since 2026-09-30 About and Fix and advanced are sub-pages of a
  short top page (7i, "The PC library is a controller-first storefront
  view"), so no single screen of the menu is longer than about nine
  rows.

**v2 direction:**
- Keep everything else: the frame-only themed render, the chip-row
  filter/sort model, capture-style input, L2/Y binding.

### Launcher mode

Not covered in this pass — the SPEC sections for launcher (§2c "Launcher
mode", the survey against Nova/Apex) already describe a task-first home,
drawer and search; this pass found no code-level contradiction of that in
the time available, but did not walk it live. Needs its own rig pass
before a v2 subsection here is worth writing, rather than a desk-based
guess about a stock-launcher-derived surface (`vendor/`-adjacent code,
"hook or extend, do not rewrite").

### Desktop mode

Not walked live this pass either. The one code-visible pattern worth
flagging for the rig pass: the Desktop taskbar carries its own direct
"Settings" button straight to `SettingsDesktopFragment` (§ "One consistent
way into Settings", above, calls this "a deliberate, non-duplicate
distinction" from Global settings) — verify on-device that the two
settings surfaces are visually distinguishable enough that a player
opening "Settings" from the taskbar can tell it is Desktop-scoped, not
"the" settings; the SPEC text asserts the distinction is deliberate but
does not record a rig check of whether it reads that way.

### Settings, everywhere

**Today.** One `AppSettingsCatalogs.kt`-driven catalog per mode, search
across all of them (`SettingsSearchIndex`), Quick Menu tiles as a live
view of one `quickOnly` group, Accounts and sources / PC setup as
catalog-registered screens.

**What reads as accumulation:** the catalog is flat groups of rows;
nothing in the code read this pass distinguishes "a setting most players
touch once" (game folders, controller mapping) from "a setting only a
person debugging something needs" (per-engine container internals,
digest/plugin trust details, `## Debug-credentials pathway`-style rows).
DESIGN-LANGUAGE's own lesson log already names this shape of problem
repeatedly ("one concept, one name", "state belongs in the value column")
but has no lesson yet about disclosure depth.

**v2 direction:** an `advanced: Boolean` flag on the existing catalog row
model (additive to `AppSettingsCatalogs.kt`, not a new screen type), with
advanced rows collapsed behind a per-group "Advanced" row that expands in
place — same `CatalogRowView`/`LazyColumn`, same search index (search
still finds an advanced row by name; expanding is a display concern only,
never a plugin-vs-official-style capability gate). This is additive to
every mode's existing catalog rather than a second settings mechanism.

### Onboarding

Not re-audited this pass; DESIGN-LANGUAGE's lesson log already carries
detailed, dated onboarding lessons (skip granted permissions, rationale
before prompt, Back inside the flow, progress counted from the real
pipeline) that read as already-applied fixes, not open problems. No new
direction proposed without a live walk to check against those lessons.

**Needs a rig check.** This whole section is directional, not verified:
walk onboarding, Gaming (Art Book Next and decaffe), Launcher, Desktop
(including Containers) and Settings everywhere by D-pad and touch on
`emulator-5560`, screenshot each screen, and check specifically: the
three-paths-to-system-settings claim above, whether the Desktop taskbar
Settings button reads as Desktop-scoped versus Global, and whether
`PcGameMenu` now reads as grouped under Play / About / Fix and advanced
the way its 2026-09-29 regrouping claims — and, since the original
flat-menu premise was never confirmed on the rig (the 2026-09-28
walkthrough ended before reaching it), whether the old flat list would
even have read as ungrouped, so a revert stays a live option if the
grouping reads worse. Log findings against this section and
correct it — this is a starting hypothesis from the code, not a ledger.
