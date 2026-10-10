# NOTICE

This project is distributed under the GNU General Public License v3.0 (see
[LICENSE](LICENSE)), as required by combining GPL-3.0-licensed sources.

## Vendored / forked sources

- **GameNative** — droidtop's fork https://github.com/Droidtop/gamenative-tux
  (a standalone app project; no longer a submodule here) of
  https://github.com/utkarshdalal/GameNative — GPL-3.0.
  `runtime-windows` carries GameNative's Windows runtime, lifted from the
  fork at `0f08762e` (docs/SPEC.md §9): its `com.winlator` tree (Winlator's
  runtime as GameNative ships it, package and headers unchanged) and the
  GameNative-authored files that runtime uses (container utilities, the
  component-list model and its installer, the prefix setup helpers from
  `XServerScreen.kt`, the x86_64 guest-library and graphics pins, the
  downloaders, the folder scanner, `TouchGestureConfig`, a cut-down
  `PrefManager`), moved to `dev.droidtop.runtime.windows.*`.
  Its native sources (winlator, extras, asurfacerenderer, xconnectorpatch,
  evshim) are copied into `runtime-windows/native/upstream/`, and its prebuilt
  arm64 libraries and asset payloads are re-hosted unmodified as a release of
  this repository (`build-scripts/windows-runtime-prebuilt.sh`); some of those
  libraries (`libvortekrenderer`, `libredirect-bionic-wx`, the Adreno hook
  libraries) are upstream binaries without published source. The x86_64
  guest-library and software-Vulkan recipes (`build-scripts/x86_64-*`) moved
  from the fork too.
- **virglrenderer** — `runtime-windows/native/upstream/virglrenderer`, as
  GameNative carries it — MIT.
- **libadrenotools** — https://github.com/bylaws/libadrenotools (GameNative's
  copy, Pipetto-crypto/libadrenotools) — BSD-2-Clause. Its headers only, in
  `runtime-windows/native/upstream/extras/adrenotools/include` (LICENSE beside them).
- **talloc** — `build-scripts/talloc`, Samba's talloc as GameNative's proot
  tree carried it — LGPL-3.0-or-later. Linked into droidtop's proot build.
  The `stores` module is GameNative's Epic, GOG, Amazon Games and itch.io
  store code (`app.gamenative.service.{epic,gog,amazon,itch}`, its store
  models and DAOs and the helpers they use), lifted out of the fork at
  `d6336076` into `dev.droidtop.stores.*` and reworked to run without
  GameNative (docs/SPEC.md §7g "Stores"). Its Steam client is lifted the
  same way into `dev.droidtop.stores.steam`, from the fork at `0f08762e`:
  `SteamService`'s connection, sign-in (QR code, password and Steam Guard),
  licence and product-info reads and depot downloads, its Steam models,
  converters and DAOs, `KeyValueUtils.generateSteamApp`, `LicenseSerializer`
  and `CaseInsensitiveFileSystem`. GameNative's authors hold the copyright in
  that code; it stays under GPL-3.0.
- **JavaSteam** — https://github.com/Longi94/JavaSteam — MIT, as GameNative
  builds it (https://github.com/joshuatam/JavaSteam, branch
  `gamenative-latest`, the `io.github.joshuatam:javasteam` and
  `javasteam-depotdownloader` artifacts): the Steam protocol, sign-in and
  depot downloader under droidtop's own Steam client (`:stores`). A Maven
  dependency, not vendored.
- **ZXing** — https://github.com/zxing/zxing — Apache-2.0. Its `core`
  artifact draws droidtop's QR codes (Steam's sign-in, the scraper key
  setup). A Maven dependency, not vendored.
- **Winlator** — https://github.com/brunodev85/winlator — LGPL-2.1.
  The upstream of GameNative's `com.winlator` runtime tree, and so of
  `runtime-windows/src/main/java/com/winlator`, which carries that tree as
  GameNative ships it.
- **Lemuroid** — https://github.com/Swordfish90/Lemuroid — GPL-3.0.
  Four detection files were forked in (unmodified logic, package lines
  changed) as `library-core/src/main/kotlin/dev/droidtop/library/romdetect/`
  `SystemID.kt`, `MagicNumber.kt`, `RomDetectUtils.kt`, `SerialScanner.kt`,
  and its community ROM database ships as
  `library-core/src/main/assets/libretro-db.sqlite`. Not vendored as a
  submodule; those files and that asset are the whole of what droidtop uses.
- **DroidSpaces** — `vendor/droidspaces`, https://github.com/ravindu644/Droidspaces-OSS — GPL-3.0.
  `runtime-linux-root` is forked from this.
- **sway** — `vendor/sway`, https://github.com/swaywm/sway — MIT.
  Source reference only; not built by droidtop. The compositor that runs
  inside the primary container is the container distribution's own sway
  package, installed at provisioning.
- **wlroots** — `vendor/wlroots`, https://gitlab.freedesktop.org/wlroots/wlroots — MIT.
  Only protocol XML definitions are used (for `wayland-scanner` codegen in
  `host-bridge`); the library itself is not compiled for Android.
- **wayland** / **wayland-protocols** — `vendor/wayland`, `vendor/wayland-protocols`,
  https://gitlab.freedesktop.org/wayland/wayland, https://gitlab.freedesktop.org/wayland/wayland-protocols — MIT.
  Core protocol headers and `wayland-scanner` codegen inputs only.
- **go-containerregistry** — `vendor/go-containerregistry`, https://github.com/google/go-containerregistry — Apache-2.0.
  `crane` is used for OCI image pulling in the rootfs image acquisition path.
- **PRoot (Termux)** — `vendor/proot`, https://github.com/termux/proot — GPL-2.0-or-later.
  Built, with the patches in `build-scripts/proot-patches/`, into
  `libproot.so` and its loaders, the separate
  executables `runtime-linux-noroot` runs containers through on a device
  without root. Linked with the single-file talloc (LGPL-3.0-or-later)
  in `build-scripts/talloc` (the copy GameNative's proot tree carried).
- **sandbox** — `vendor/sandbox`, https://github.com/bi0shacker001/sandbox — GPL-3.0-or-later.
  The process sandbox library droidtop shares with Enginehost (seccomp lockdown
  and syscall broker), linked statically into `libdroidtoppy.so` for the plugin
  processes' system-call filter.
- **hev-socks5-tunnel** — `vendor/hev-socks5-tunnel`, https://github.com/heiher/hev-socks5-tunnel — MIT,
  with its submodules hev-task-system, hev-socks5-core and yaml (MIT) and lwIP
  (BSD-3-Clause). Built into `libhev-socks5-tunnel.so`, the userspace IP
  stack behind droidtop's device VPN.
- **PulseAudio** — `vendor/pulseaudio` (v13.0), https://github.com/pulseaudio/pulseaudio —
  LGPL-2.1-or-later (the libraries and modules built here). Built for x86_64 by
  `build-scripts/build-vendor-deps.sh` with Termux's Android patches and its
  `module-aaudio-sink.c` (termux-packages, https://github.com/termux/termux-packages,
  `packages/pulseaudio` at 39437706663c), kept in `build-scripts/pulseaudio-patches/`.
- **libsndfile** — `vendor/libsndfile` (1.0.28), https://github.com/libsndfile/libsndfile — LGPL-2.1-or-later.
- **libltdl** — GNU libtool's libltdl, from the build host's `libltdl-dev` — LGPL-2.1-or-later.
- **SDL2** — `vendor/SDL2` (release-2.32.10), https://github.com/libsdl-org/SDL — zlib.
  Headers only, for building gamenative's `libevshim.so` for x86_64.
  Also the source of the controller vendor and product ids droidtop classifies external pads by:
  `library-core/src/main/kotlin/dev/droidtop/library/controller/SdlControllerIds.kt` is generated by
  `build-scripts/gen_sdl_controller_families.py` from SDL's `src/joystick/controller_list.h`
  (Copyright (C) Valve Corporation, zlib), and the classification rules in `ControllerClassifier.kt`
  follow `SDL_GetJoystickGameControllerTypeFromVIDPID` (`src/joystick/SDL_joystick.c`, zlib).
- **libffi** — `vendor/libffi`, https://github.com/libffi/libffi — MIT.
  Runtime dependency of `libwayland-client`, cross-compiled by
  `build-scripts/build-vendor-deps.sh`.
- **Hacker's Keyboard** — `input-keyboard`, https://github.com/klausw/hackerskeyboard — Apache-2.0.
  Forked in as its own module (not a submodule): the upstream Java tree is
  unchanged apart from build-compat fixes and one hook in `LatinIME`, and
  droidtop's additions (the second-screen keyboard, docs/SPEC.md §6c) are
  separate Kotlin files.
- **Murine Launcher** — `shell-default`, https://github.com/alesimula/Murine-launcher — Apache-2.0.
  Itself derived from AOSP Launcher3 (Apache-2.0). Forked in as
  `shell-default` and its sub-projects (not a submodule) and edited
  directly; its `LICENSE` travels with it.
- **droidtop-platforms** — `vendor/droidtop-platforms`, https://github.com/Droidtop/droidtop-platforms.
  droidtop's own platform, player, engine and BIOS database, kept in its
  own repository so it can be updated between builds. `library-core`
  copies the pinned commit's databases into its generated assets at build
  time. The repository carries no licence file of its own.
- **DroidDeck** — https://github.com/Droid-Deck/DroidDeck — GPL-3.0, read at `9310d19`. Parts of
  the Gaming shell's look and motion are ported from its Compose front end (Kurt Himebauch,
  The412Banner, MaxsTechReview and contributors), reworked to droidtop's cursor selection and
  design tokens: the motion switch and its snap rule, Rise and the sheen (`ui/FrontEndScreen.kt`,
  as `shell-gamepad/.../MotionEffects.kt` and `MotionTokens.kt`), the sliding focus ring (`ui/FocusGlide.kt`, as
  `FocusGlide.kt`), the non-focusable keycap (`ui/SettingsWidgets.kt`, as `Keycap` in `TouchActions.kt`), and the
  wide-art cover, hero fill, status chip and empty-backdrop glow (`ui/FrontEndArt.kt`,
  `ui/FrontEndGames.kt`, `ui/FrontEndWidgets.kt`, `ui/FrontEndContent.kt`, in `pc/PcCapsule.kt` and
  `pc/PcBackdrop.kt`), the fading foot of a long menu (`ui/SettingsWidgets.kt`, in `MenuPanel`, `GamingMenu.kt`),
  the page and launch floods (`ui/PageFlood.kt`, `ui/LaunchFlood.kt`, as `PageFlood.kt`), the switch and slider
  (`ui/SettingsWidgets.kt`, as `ShellSwitch` and `ShellSlider` in `GamingMenu.kt`), the turning cog
  (`ui/FrontEndWidgets.kt`, the game page's gear), the Stop pill (`ui/SessionOverlay.kt`, the Quick Menu's
  Game section), the Resume row with its live dot and the attention dot (`ui/FrontEndRail.kt`, in
  `LeftMenu.kt`), and the store page's header chips and busy bar (`ui/StorePage.kt`, as catalog chips and
  progress rows in `SettingsCatalogView.kt` and `GamingMenu.kt`). Each ported file names
  the DroidDeck file it came from. No DroidDeck artwork, logo or wordmark is used.

## Bundled themes

Both ship inside the APK under
`shell-gamepad/src/main/assets/themes/` and are rendered unmodified by
droidtop's own ES-DE theme engine. Both are
**CC-BY-NC-SA 4.0** (Attribution-NonCommercial-ShareAlike): attribution
is required, changes must be indicated and published under the same
licence, and commercial distribution is prohibited. droidtop makes no
changes to either theme's files; the per-system metadata droidtop adds
for its own invented systems lives outside the theme, in the separate
`droidtop-theme-patches` overlay. Each theme's own `LICENSE` and
`CREDITS.md` travel with it in the asset tree. The logos and trademarks
they contain are copyright of their respective owners.

- **DEcaffe (decaffe-es-de)** — https://github.com/DEcaffe/decaffe-es-de —
  CC-BY-NC-SA 4.0. droidtop's offline-fallback landscape theme: what a
  fresh install renders before, or instead of, Art Book Next's own setup
  download below (docs/SPEC.md §7f).
- **Slate (slate-es-de)** — https://gitlab.com/es-de/themes/slate,
  vendored at upstream commit `c072efc`, by Leon Styhre and
  contributors (itself based on recalbox-multi by the Recalbox
  community prior to their 2018 licence change, with graphics from
  RetroPie's Carbon theme by Rookervik, vector graphics by Bezza191 and
  logotypes by Dan Patrick — see its own CREDITS.md) — CC-BY-NC-SA 4.0.
  ES-DE's own default theme, and the only bundled theme that declares
  vertical aspect-ratio variants (`16:9_vertical`, `4:3_vertical`), so
  it is droidtop's default on a portrait display (docs/SPEC.md §7f).

## Downloaded default theme

Not shipped inside the APK: droidtop's onboarding downloads this one theme
through the real ES-DE theme downloader (`ThemeDownloader`, the same
`gitlab.com/es-de/themes/themes-list.git` mechanism "Browse themes" uses)
during setup, and activates it in place of DEcaffe once the download has
actually finished. droidtop makes no changes to its files.

- **Art Book Next (art-book-next-es-de)** — https://github.com/anthonycaccese/art-book-next-es-de,
  by anthonycaccese — **CC-BY-NC-SA** (the theme's own README License
  section: "Creative Commons CC-BY-NC-SA", no separate `LICENSE` file in
  its repository). droidtop's recommended Gaming default (docs/SPEC.md
  §7f, "Default theme"), chosen for its aspect-ratio coverage and because
  it is the theme droidtop's own carousel/grid/textlist renderer parity
  work was measured against. Offered with no per-franchise character art;
  the logos and trademarks any theme's own bundled art contains remain
  copyright of their respective owners, same as the bundled themes above.

## Downloaded Windows components

Not shipped inside the APK: the Windows runtime downloads Wine and Proton
builds, DXVK, VKD3D-Proton, FEXCore, Box64/WowBox64, Adreno driver builds and
its base system on demand, from droidtop's component catalog
(https://github.com/Droidtop/droidtop-components, docs/SPEC.md §5a). Each
file there is re-hosted unmodified or linked where its maker publishes it,
and keeps its own licence, named per file in that repository's
`sources/mirror.json` or by its maker. Wine builds a person adds by link or
file are theirs. The Turnip feed labels there follow DroidDeck's
`gpu/TurnipReleases.kt` (https://github.com/Droid-Deck/DroidDeck, GPL-3.0).

## Interface sounds

The shell menu, dialog and toast sounds in `shell-gamepad/src/main/assets/ui-sounds/` are
eighteen files picked from "Interface Sounds" by Kenney (https://kenney.nl/assets/interface-sounds),
released under CC0 1.0 (public domain); used unmodified.

## Design references (not vendored, no code copied)

Moonlight Android (input interaction model, LAN host discovery approach),
KDE Connect Android (remote input reference), Playnite (library/plugin
model, and the store library interface), Steam Big Picture / Steam Deck UI (layout, spacing,
type, focus and motion vocabulary, measured; no code, art, icons, fonts or sounds used), Lutris (store services), distrobox (host-integration mechanism), Qubes OS (dom0/AppVM
architectural split). See [docs/SPEC.md](docs/SPEC.md) for how each
informed the design.
