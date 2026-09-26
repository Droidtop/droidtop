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
- [7i. The PC surface — "a PC in a box", not an ES-DE system (directed 2026-09-10)](#7i-the-pc-surface--a-pc-in-a-box-not-an-es-de-system-directed-2026-09-10)
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
| Gestures: double-tap to sleep, swipe-down to notifications | HAVE, exposed in Settings | `LauncherPrefs.GESTURE_DOUBLE_TAP_SLEEP`/`GESTURE_SWIPE_DOWN_NOTIFICATIONS`, toggled from `SettingsHomeFragment` (`DOUBLE_TAP_TO_SLEEP`, `SWIPE_DOWN_NOTIFICATIONS`), applied in `WorkspaceTouchListener.java`/`NotificationSwipeController.kt` |
| Assignable gesture *actions* (Nova's "map any gesture to any action", not just the two fixed ones above) | **LACK** | no such mapping layer exists; backlog |
| App-drawer/QSB search over droidtop's own library (games, not just installed apps) | **LACK** | `DefaultAppSearchAlgorithm.java` only ever produces `AdapterItem.asApp`; backlog |
| A home-screen widget of droidtop's own (a "full computer" feature neither Nova nor Apex can offer, since they have no game library) | **built this change** | `ContinuePlayingWidgetProvider.kt` (see below) |
| Global settings, Desktop settings rendered in the shell's own row component, pad-navigable | HAVE (fixed 2026-09-24/25, UI pass H4) | `DroidtopWideSettings.kt`, `SettingsGlobalFragment.kt`'s `CatalogPreferenceNavigator` |
| Icon-pack/drawer/hidden-apps settings pages left as stock Android preference UI | HAVE, and correct: H4's own fix text scopes the shell's row component to Global/Desktop only, and explicitly keeps these stock | `docs/audit-2026-09-24/ui-assessment.md` H4 |
| Plugin contributions in the launcher (status tiles, search providers, app actions, launcher widgets from third-party engines/tools) | **not yet buildable** — plugin API is being rebuilt (agent `plugins`, §12/12a) | seam only, see below |

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
without a second registration mechanism. Scope recorded in `docs/plugin-catalog.md`
(2026-09-25, agent `plugins`): the registry/catalog pattern is the only
interface; no plugin surface code is built until §12a manifest/API lands.
Left undone deliberately: no plugin-facing API, no plugin search results,
no plugin widgets. Agent `plugins` owns when §12a is ready for this to be
wired up for real.

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
(dq-onboard-01). It is gone; the Sentry SDK's own handler reports the
crash (`CrashReporting`) and Android's own crash handling follows.

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
the container it is booting, not only a connected one). The service is
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
socket name, and the keymap memfd (§6b). Not verified yet: an arm64
device (the Retroid console), the Debian plan, labwc, sibling containers
through this backend, clipboard, rotation, and the second screen.

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
  Not built yet from the design below: Restart, Recreate from the image,
  storage used, Start with droidtop, Sockets and Mounts.
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
  only: a second headless output in the compositor, sized to the display,
  with the taskbar's "move to" action reaching it), or **Mirror** (Android's
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
  parked (§4c) and why the Presentation is dismissed on `onStop`.
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
- **Running jobs and downloads** -- no droidtop-owned `ScanProgress`/download-queue store
  exists for any surface to read yet (Settings' own scan rows read provider state
  directly, not a shared live-progress store); a companion tile for it needs that store
  built first, wherever it is built, so every surface reads the one thing rather than the
  companion inventing its own.

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
  (decided 2026-09-24): `:host-bridge` binds
  `wlr-foreign-toplevel-management-unstable-v1` beside screencopy and the
  virtual-input protocols, and the taskbar lists every toplevel the
  compositor has — title, app id, which container it came from where the
  helper knows, focused and minimized state — activating one on tap,
  minimizing it on a second tap, closing it from its long-press menu, and
  moving it to another output where one exists (§4). Android tasks
  droidtop itself launched onto the desktop's display (a game, a Wine
  activity) appear in the same bar from `LaunchDisplay`'s record, so one
  bar answers "what is running" across containers and Android alike. The
  Start menu lists the primary container's installed applications and the
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
gap. droidtop has a first-run tutorial for what onboarding cannot ask about (controls, navigation,
sections, launching, the Quick Menu, switching modes, where help is); its content comes from the
rig's new-user passes, which try to break onboarding on purpose.

**Themes are chosen during setup (directed 2026-09-24).** Onboarding includes the theme
downloader (the same Browse themes screen Settings opens, one mechanism), so a person can pick and
download another ES-DE theme before they first see the Gaming shell, not only the bundled default.
Built 2026-09-25: the Appearance step's "Get more themes" draws `ThemeBrowserScreen` in place of the
step, and the theme list is read again when it returns. The downloader's git library is JGit 5.13,
the last line built for Java 8: JGit 6 and 7 call Java 11+ methods (`InputStream.readNBytes(int)`)
that Android has only from API 33, and both "Get more themes" and Settings > Browse themes crashed
Android 9 with NoSuchMethodError (dq-onboard-01). A library method the running Android lacks is a
failed download, never a crash (`ThemeDownloader` catches `LinkageError`). The browser has its own
hint row.

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
  tutorial's first A hit "Skip", and a tapped hint pill dispatched its key into a window with
  nothing focused; the window owns the pad (`ownPadButtons`), so B is Back; and
  a hint row (A Select, B Back) is the touch route to both. On the rig, A did nothing on Welcome, no
  focus showed anywhere, and D-pad Down went up to Back (dq-coordinator-24).
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
- **Game folders.** The single name for this concept, everywhere in droidtop. Two routes, both
  first-class: the system picker, and a typed path for what the picker cannot reach (an
  emulator's host share, a mount a rooted device adds, a USB drive), validated for real before
  it is stored. Readable folders the picker cannot offer are listed under "Found on this device"
  (directories under `/storage` other than the emulated internal storage, and under
  `/mnt/windows`, where emulators mount a host share), each with Add; the typed path's example
  names no folder, since a newcomer had to already know the share's path (dq-coordinator-24).
  Adding a folder starts the library's walk of it at once (§2c). Each added folder is a row
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
  icon on its home screen, and the first-run tutorial opens over the first frame of the chosen
  mode. It does not end by returning to the system home. "Android" opens the home
  screen droidtop holds, through the same `BackButtonMenu.openHome` the mode switcher uses;
  it is not a `MainActivity` shell.

### The first-run tutorial

Built 2026-09-25 (`TutorialActivity`). It covers what onboarding cannot ask about, in the order
the rig's new-user pass asked for (dq-coordinator-24, "Tutorial should cover"): getting around (the
controls, and that the hint row is also the touch route to them), finding and launching games, a
game's page (Play, what runs it, the one Windows download), the Quick Menu, switching modes,
Settings, and where help is. Rules:

- It tells a setup only what it has: the Gaming pages only while Gaming is on, the ways into a mode
  that this home screen and these modes actually have, and where the Home button goes
  (`TutorialPages.build`, pure over the setup, unit-tested). Button names come from
  `GamepadKeyMap.labelFor`, so a swapped pad reads right.
- It opens once, over the first frame of the mode onboarding opens into, and again from Global
  settings > "Show the tutorial". Every page but the last has "Skip the tutorial".
- It is driven like every droidtop screen: A and the filled button go on, B goes back a page (out,
  on the first), the hint row is tappable, the selection starts on the way on.

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
  doing so (its CLAUDE.md rule). droidtop's "Saves" row opens that
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
  continuity, rolling `latest` and `release-info.json` shape as droidtop
  (§10b); the debug installer activities live in the debug source set
  only.

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
5. "Browse themes" (Settings, and onboarding's own "Get more themes") offers Art Book Next exactly
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
older than a week (its clone's `FETCH_HEAD`), fetches it in place first
(a git fetch reports no count while it runs, so the screen says it is
fetching, then shows the list; a failed fetch with no list says so and A
retries); there is no separate "Sync theme index" row, and the screen uses
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
VPN, Bluetooth, Swap screens, Reinitialize displays — are rendered by
the Quick Menu's System tab only: the catalog's System group carries a
`quickOnly` flag and every Settings renderer skips it
(`GamingSettingsCatalog.settingsGroups`), keeping under System just
Screens (main screen, game launch target, second-screen roles), Software
updates and Android settings. The Quick Menu shows those configuration
rows too, by id (`QuickTiles.CONFIGURATION_IDS`), as the same items. One-shot library actions live in
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
  refuses is not asked again in that pass once it rejected its key (401, 403) or refused five
  times in a row, and the pass's summary (and a manual match's result) names it with its own
  sentence and, for a key, the setting to fix. The game is still written with what the
  other sources gave.
- **Lutris, definitively.** Its search (`/api/games?search=`) carries a cover and a year and
  nothing else; its per-game record (`/api/games/<slug>`, keyless JSON, checked live
  2026-09-25) adds a description and genres. Neither has a developer, publisher, full date,
  rating, series or links. The 2026-09-24 survey expected the per-game record to be HTML only;
  it is not. Its `gogslug` is not used (for Hollow Knight it names the soundtrack), its
  `provider_games` is.
- **Where players see it.** A PC or engine game's detail page opens with the hero art (the
  cover when there is none) and the logo in place of the title text, and has an "About this
  game" section under Play: the description (a stop for the pad; A shows the rest), the
  developer, publisher, date, genre, series and rating, each link as a row that opens it, and
  one line saying where each field came from. ES-DE's hide-metadata flag hides the section.
  The companion screen adds the publisher (when it is not the developer) and the series. A
  pinned home-screen shortcut uses the scraped icon before the cover.

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
(`ScraperReadiness.credentialFix`); any other refusal (a quota, an outage)
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

## 7i. The PC surface — "a PC in a box", not an ES-DE system (directed 2026-09-10)

The user's framing: "we explicitly want THAT category to break from the
ESDE theme, because of how much infrastructure we have to build. It needs
to be a PC in a box, like droidtop, controlling detection, runners, and
etc based on availability."

In Gaming mode every console system is rendered by the ES-DE theme
engine (§7f) and one category is not: **PC**. The `pc` card stays in the
theme's own carousel, drawn from the theme's own `pc` art, and opening it
enters droidtop's own full-screen surface instead of a themed gamelist.
Design pass and build plan:
`/root/coordination/research/pc-in-a-box/README.md`.

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
  Signing in to Steam/GOG/Epic/Amazon in droidtop rebuilds the library.

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

**Library.** One grid over one list, with a header line of plain facts,
full-bleed rather than a centred column. Filters are one multi-select
chip row — source, install state and engine — plus a sort that cycles in
place, never separate screens. Those three are free: every value is
already on the entry. **Runner state is deliberately not a chip.**
Working it out means a filesystem walk and a provider query per game,
which is right for one open game and wrong for a whole grid, so it is
stated where it is needed rather than filtered on where it is not.
The chip row is reached and left by the pad like the grid: Up from the
grid's top row lands on Sort, the first chip; Left and Right move along
the chips and never leave the surface (at the grid's own edges they are
ES-DE's switch-system); one key handler above the chips and the grid owns
every direction (rig, build 814: Sort was touch-only, and Left from a chip
opened All games). **Every card grid moves by index** (`GridPad`): the
Games section's unthemed grid, this grid and the Launcher's Games grid take
both edges of a direction and move on the UP edge one card along the row or
straight down the column, scrolling the next row in first; at an edge they
answer "not handled" and the screen decides what the edge means. Compose's
own focus search moved on the DOWN edge as well (two cards per press) and,
searching geometrically among composed cards, took Down from the second
column to the first column of a partly visible next row (rig, dq-shell2-01).

A game's actions live on the game's own screen, opened with A, rather
than in a separate in-context menu over the grid: there is exactly one
place to look for what can be done with a game. B returns to the
carousel, the shell's own back route in both its forms, and the surface
draws its own hint row because the theme is not drawing one here.

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
store, add a games folder, set up Windows games, see what is downloading
— never an empty grid. They are optional, skippable, and reachable again
from the surface's options menu, which is the SAME list: implementation
showed that "three first-run cards" and "the options menu" were the same
four actions, so they are one settings-catalog screen (`pc_stores`,
registered by `:app`) rendered in place by the catalog navigator the
shell's settings already use, rather than two implementations of the same
rows. Each row states the state it found — whether a store is signed in,
how many game folders exist.

### Relationship to the theme engine

The theme owns the system view, the `pc` card, its art, its layout and its
transition out. It owns nothing past that card, for three structural
reasons: ES-DE's element schema has no element type for runner state,
install state, prefix configuration or store authentication; its gamelist
models "a game and its metadata" rather than "a game, four runners and an
override"; and the screens droidtop reuses here are Compose, so theming
them would mean rewriting them.

It needs no theme patch: a theme's `windows` art where it has some, its
own defaults where it does not.
Engine games fold into this one PC entry, with engine as a filter inside
it, rather than appearing as invented per-engine systems in the carousel:
the shell has ONE group for the PC category, and it owns the `pc` system
id and theme folder outright. Everything that is not a console system's
ROM belongs to it — a detected engine game (which carries no system id at
all), a store or Wine title, a Linux-container game, and whatever a user
put in a games-root folder named `pc`, which can no longer become a
second card of its own that this surface would then render empty.

Breaking from the theme carries an obligation: droidtop's own chrome —
this surface, the Quick Menu, the settings catalog and the adopted
gamenative dialogs — takes its colour and type from one droidtop palette,
not four separate looks.

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
queue, an OAuth shim per store, and the container-configuration dialog.
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
chrome --- the tab bar, Quick Menu, PC surface, game detail, gamelist
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
consumes every pad button nothing below it wanted and gives B its one
meaning explicitly, the back dispatcher. A `BackHandler` is therefore a
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
dispatched at all -- only ancestors get the event. What hides it is
Android's own key-character-map fallback: an unhandled `BUTTON_A` is
re-sent as `DPAD_CENTER` (`Generic.kcm`), which `clickable` treats as a
click, so A appears to work through the click path while every other
action written the same way (X for favourite, Y for a detail) is dead,
and every hint-bar tap -- a direct `dispatchKeyEvent`, which gets no
fallback -- does nothing (rig, build 548: the PC grid's own `A Open` hint
inert while `B` and `Y`, handled on ancestors, worked).

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
  Select for gamelist options, Y for the PC surface's stores and folders;
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

## 7k. The design system: one spacing scale, one type scale, one colour source

droidtop draws two kinds of surface. A **themed view** takes every colour, typeface and
measurement from the active ES-DE theme (section 7f) and is out of scope here. Everything
else — onboarding, the shell's chrome and menus, the settings catalog, the Quick Menu, the
PC surface, the desktop panels — is **droidtop's own chrome**, and all of it obeys one system.

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
the menus' overlay surface, and the black `Ground` under the shell's own pages (the game grid
and a game's detail, the PC surface, the editors and pickers the shell opens full-screen), with
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
  the same real cost as opening every top-level settings screen once) by walking the root's own
  groups (depth 0) and, for a `NestedScreenItem` found there, one level into whatever
  `CatalogScreen` it opens (depth 1) — never further, and never into a screen a catalog builds
  for one instance (a single ROM folder, one platform, one container), which is what keeps this
  flat against the size of anyone's library instead of growing with it (the performance rule:
  no work that grows with the square of the library). `search` is then a pure, in-memory
  substring filter, safe on every keystroke. In the Gaming shell, a synthetic "Search settings"
  row (`SEARCH_ROW_ID`) is prepended to the settings home's own row list — it rides the exact
  same focus order, Up/Down, A/touch and icon slot as every real row instead of a second
  mechanism beside them — and opens a full-screen `SettingsSearchOverlay` (a text field plus
  matching rows, each showing which screen it lives on); picking a result pushes that screen
  onto the settings home's own navigation stack, so B from it returns to Settings same as
  opening the row by hand would have. **Built on the touch/Preference surface (H4
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
about 700), so no installed build saw a downgrade. The rolling
`latest` release carries `release-info.json` -- formatVersion, versionCode,
versionName, apkName, apkSha256, commit -- published by the same workflow
run that built the APK. Enginehost mirrors this exactly (its
`codex/engine-bundles` line publishes the same shape of rolling release).

**What is published is a release build (2026-09-21).** Through build 556 CI
published the `debug` variant, the only build type the app had, and on the
console everything was slow: startup, menus, seconds between a press and its
effect. A debuggable package is never compiled ahead of time (the installed
app's dexopt state was `extract`), ART runs it without inlining so that a
debugger can attach anywhere, and the baseline profiles Compose ships are not
installed for it. CI now builds `:app:assembleRelease`: not debuggable, signed
with the same persistent key so it installs over any earlier `latest`,
`androidx.profileinstaller` on the classpath so library baseline profiles are
installed, and `<profileable android:shell="true">` so the shell's profilers
still attach to the build people actually run. The asset is
`droidtop-latest.apk`; the updater reads the name from `release-info.json`,
so installed debug builds update to it by themselves. Code shrinking (R8) is
deliberately the next step and not this one: the vendored launcher,
gamenative and keyboard trees load classes by name and through JNI, and their
keep rules have to be proven on a device before a shrunk build is published.
The debug variant still exists for local work and is what lint and the unit
tests run on.

**Channels, and the debug APK beside the release one (directed 2026-09-22).**
The user: "add two toggles to the update and etc checker: branch (so, stable,
unstable, etc), and a debug checkbox, along with a warning if it's enabled."
A channel is a GitHub release tag carrying its own `release-info.json` and
both APKs: `latest` (what the updater calls Unstable, published by every push
to main), `testing` and `stable` (published by the `release-promote.yml`
workflow, run by hand). Promotion builds nothing (changed 2026-09-24; it used
to build the current main again, so Testing could carry bytes nobody had
tried and a main that had moved on). The build run uploads its APKs WITH
their `release-info.json` as the `droidtop-apk` artifact, and promotion
publishes exactly that artifact: by default the commit `latest` carries now,
or a commit named by hand, and only when that commit's `android-build.yml`
and `android-checks.yml` runs on main both succeeded. Artifacts are kept for
the repository's retention period (90 days by default), so a commit older than
that can no longer be promoted.

Publishing never deletes a release (changed 2026-09-24; it used to delete and
recreate, and a run cancelled between the two left the channel with no
release, which every installed build reads as "nothing here"). One script,
`build-scripts/release_channel.py publish`, serves every channel: it moves
the channel's tag to the commit (one ref write), uploads the new files under
a `next.` prefix while the old ones keep serving, then swaps each asset
(delete old, rename new, `release-info.json` last). An interrupted publish
leaves the release in place with either build complete, or for about a second
a new APK beside the old `release-info.json`, which the updater rejects by
digest and retries at its next check; the next publish clears any leftover
`next.` uploads. Asset names and `release-info.json` fields are unchanged. The device picks a channel in Settings; the default is
Unstable, because it is the only channel droidtop has ever had, and a channel
nothing has been promoted to yet simply reports that there is nothing there.

The token that can write releases never shares a job with the build (decided
2026-09-24, Droidtop/enginehost `docs/security/2026-09-24-ci-supply-chain.md`
H3). `android-build.yml`'s build job, which runs Gradle, its plugins and the
vendor-deps scripts, has a read-only token and checks out without leaving it
in `.git/config`; a separate `publish` job with `contents: write` downloads the
`droidtop-apk` artifact and runs only `release_channel.py publish`, exactly as
`release-promote.yml` does. Every action is pinned to a commit SHA; moving one
is a reviewed commit.

**Branch protection on main (2026-09-25).** `main` is protected via the
GitHub API: force-pushes and deletion are blocked, linear history is required,
and the "Android build" workflow must pass (strict status checks). This
prevents a mistaken or compromised push from publishing a signed APK to the
`latest` channel without a successful build.

Every build publishes BOTH variants: `droidtop-latest.apk` (release) and
`droidtop-latest-debug.apk` (the same code, debuggable). The debug APK exists
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
newest notes are kept. The Murine fork's Recovery library keeps its job of
restarting the app, and it restarts into `MainActivity` — the mode
independent entry that renders whichever shell is enabled — never into
the launcher fork's `Launcher`, which `HomeRolePrefs` may have disabled
(a restart into a disabled component is a second crash); the note is what
makes the restart diagnosable afterwards. Crash reporting is **local only**: the Sentry SDK
that shipped with an empty DSN reported nowhere and is removed rather than
pointed at a server, because a crash report leaves the device only when the
person sends it (below). There is no automatic upload and no switch to
turn one on.

**Safe mode after a crash loop.** The Gaming shell renders third-party
themes, and "a theme must never be able to kill droidtop" (§7f) cannot be
proven for every theme. When the process has crashed twice within a minute
of starting, the next start of the Gaming shell draws its unthemed fallback
surface instead of the active theme and says so at the top of the screen,
with one action that draws the theme again. The stored theme choice is not
changed. A third crash in the same window starts the app on Global settings
(the catalog the shell draws itself, §7) rather than in any shell, so a
person can always reach Data, Rerun onboarding and Share diagnostics. The
counter resets on any start that lives for a minute.

**Share diagnostics** is one action in Global settings > Data. It zips the
logs folder, the settings export (§7 Data, with every `droidtop_*`
credential key left out), the platform-database snapshot ids (§7e2), the
installed theme names and the enginehost version, and opens the system
share sheet with the archive. It never sends anywhere by itself. The same
action is reachable from a crash note's own restart screen, so the report
can be sent before the crash is reproduced.

**Privacy.** droidtop sends nothing about the device, the library or the
person anywhere. The complete list of hosts it talks to, each for one job
the person asked for: GitHub releases (§10b, its own and enginehost's update
check: an unauthenticated fetch of one small file); the droidtop-platforms
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
- **`python`** — documented, not built, and **blocked on a real
  packaging conflict found 2026-09-26** in the runtime the same day's
  decision named. Chaquopy (MIT since 12.0.1, chaquo.com/chaquopy/license)
  is a Gradle plugin, not a library a plugin bundle can carry on its own:
  it compiles the CPython interpreter as a native component INTO the app
  that applies it, one native library set per ABI declared in that app's
  own `abiFilters`/`ndk.abiFilters` (chaquo.com/chaquopy/doc/current/android.html),
  and the standard library loads straight out of the APK's own assets at
  run time (`extractPackages` only copies files already inside the APK to
  app-private storage on first import for startup speed — it does not
  fetch anything). There is no supported path to produce "a Chaquopy
  runtime" as a standalone artifact a plugin host downloads later: the
  interpreter and stdlib are baked in wherever the Gradle plugin runs, at
  that module's own build time, not attachable post-build. Android's own
  answer to "ship this only when it's used" — Play Feature Delivery /
  dynamic feature modules — is Play Store-specific and droidtop is not
  Play-distributed (`build-scripts/release_channel.py`, GitHub Releases
  only), so that route is also closed. **This directly conflicts with the
  2026-09-26 decision's "downloadable component, fetched on first use,
  never bundled in the base APK"** — that requirement cannot be met by
  Chaquopy as it actually ships, so it is not implemented pending the
  owner choosing one of: (a) accept Chaquopy compiled into the base APK
  (its native libraries add several MB per ABI, chaquo.com/chaquopy/doc/current/android.html's
  own sizing note) and drop "downloadable"; (b) accept it as a genuinely
  separate installable unit — a standalone APK/AAR droidtop's own updater
  fetches and loads via context creation into a Chaquopy-built module,
  unverified whether that is supported outside Chaquopy's own single-app
  model and not investigated further without a decision to spend the
  time; (c) a different Python runtime with an actual split-delivery
  story (not researched). `PluginKind.PYTHON` still validates like any
  other kind and is refused ACTIVATION with a clear reason — never
  silently ignored — until one of these is chosen and built.
- **`flutter_embed`** — documented, not built, added 2026-09-25 for a
  real forthcoming case: an existing Flutter/Dart app the owner wants to
  turn into a plugin rather than rewrite natively (romgi). Same treatment as
  `python`: validates, refused at activation. **Build order decided 2026-09-26
  (owner delegated): after both the core plugin host's own rig check
  (`dq-plugins-01`) is green and the `python` kind lands** -- one shared
  Flutter engine instance is meant to serve every `flutter_embed` plugin, and
  sequencing it behind a verified host and a second working runner (not just
  the first, `native_bundle`) is cheaper than discovering a host-level bug
  while also bringing up a brand-new embedding.

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

**Install sources.** Today: a user-picked file through the system picker
(`PluginStore.importFromPicker`), the same "Add integration file" shape
§12's JSON half already uses. A catalog-repo source — the "official
origin + third-party key" idea droidtop-platforms already uses for its
own lists — fits the same shape later (an origin is already a first-class
concept in the manifest and the pinned-key map) but is not built: nothing
today resolves a plugin id against a remote catalog, so adding one is a
`PluginOriginKeys` entry and a fetch step in front of the same install
path, not a redesign.

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
   the exact manifest bytes, verified against a per-origin pinned public
   key (`BundleSignature`, `PluginOriginKeys`). Approval is bound to the
   exact archive digest (`PluginRecord.archiveDigest`, a SHA-256 over the
   signed manifest) and never carries over to a new digest — an update
   with different bytes starts back at PENDING, even for an id already
   approved.
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
the `native_bundle` runner (isolated process, binder API, crash
containment via `PluginCrashPolicy`), the job shape, and a sample plugin
(`samples/plugin-sample-statustile`) exercising `status_tile` end to end,
including a deliberate forced crash for testing the disable path. Open:
the `python` and `flutter_embed` runners; a catalog-repo install source;
and producing/signing the sample's actual bundle, which needs a compiled
`:plugin-host` classpath and a dex compiler this change's session did not
have without a local Gradle build (against this project's own "CI
builds, never local" rule) — `samples/plugin-sample-statustile/build.sh`
is the real, unexecuted recipe.
