# NOTICE

This project is distributed under the GNU General Public License v3.0 (see
[LICENSE](LICENSE)), as required by combining GPL-3.0-licensed sources.

## Vendored / forked sources

- **GameNative** — https://github.com/utkarshdalal/GameNative — GPL-3.0.
  `runtime-windows` carries GameNative's Windows runtime, lifted from
  droidtop's former fork of it (Droidtop/gamenative-tux, retired,
  Droidtop/tracker#857) at `0f08762e` (docs/SPEC.md §9); the source of
  that commit is attached to the `windows-runtime-prebuilt-0f08762e`
  release of this repository, beside the last builds that carried its
  binaries. What was lifted: its `com.winlator` tree (Winlator's
  runtime as GameNative ships it, package and headers unchanged) and the
  GameNative-authored files that runtime uses (container utilities, the
  component-list model and its installer, the prefix setup helpers from
  `XServerScreen.kt`, the x86_64 guest-library and graphics pins, the
  downloaders, the folder scanner, `TouchGestureConfig`, a cut-down
  `PrefManager`), moved to `dev.droidtop.runtime.windows.*`; its data
  assets (the JSON lists, input-control profiles and icons, Box64 presets)
  in `runtime-windows/src/main/assets`. Its native sources (winlator,
  extras, asurfacerenderer, xconnectorpatch, evshim) are copied into
  `runtime-windows/native/upstream/` and built by droidtop for both ABIs.
  GameNative's Box64, FEXCore and WowBox64 builds and its Wine input DLLs
  are re-hosted unmodified in Droidtop/droidtop-components (`windows-runtime`
  release, from GameNative at `ce378f01`), with the binaries that have no
  published source: `libvortekrenderer`, `libkgslshim`, `libredirect`,
  `libredirect-bionic` and `libredirect-bionic-wx`. The x86_64
  guest-library and software-Vulkan recipes (`build-scripts/x86_64-*`)
  moved from the fork too.
- **virglrenderer** — `runtime-windows/native/upstream/virglrenderer`, as
  GameNative carries it — MIT.
- **libadrenotools** — https://github.com/bylaws/libadrenotools, as
  GameNative pins it: Pipetto-crypto/libadrenotools at `8483dfda`, with
  liblinkernsbypass — BSD-2-Clause. Vendored as a submodule at
  `vendor/libadrenotools`; droidtop builds its static library (linked into
  the arm64 libextras and libvulkan_renderer) and its hook libraries
  libhook_impl and libmain_hook. `runtime-windows/native/upstream/extras/adrenotools`
  points at it, where GameNative's sources include its headers from.
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
- **JavaSteam** — https://github.com/Longi94/JavaSteam — MIT, from
  GameNative's fork (https://github.com/joshuatam/JavaSteam, branch
  `gamenative-latest`) by way of droidtop's own (https://github.com/Droidtop/javasteam,
  branch `droidtop`, which adds a fetcher hook for droidtop's download
  engine): the Steam protocol, sign-in and depot downloader under droidtop's
  own Steam client (`:stores`). Vendored as a submodule at
  `vendor/javasteam` and built from source.
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
- **PRoot (Termux)** — `vendor/proot`, https://github.com/Droidtop/proot (branch `droidtop`: Termux's
  https://github.com/termux/proot with droidtop's changes as commits) — GPL-2.0-or-later. Built into
  `libproot.so` and its loaders, the separate
  executables `runtime-linux-noroot` runs containers through on a device
  without root. Linked with the single-file talloc (LGPL-3.0-or-later)
  in `build-scripts/talloc` (the copy GameNative's proot tree carried).
- **sandbox** — `vendor/sandbox`, https://github.com/bi0shacker001/sandbox — GPL-3.0-or-later.
  The process sandbox library droidtop shares with Enginehost (seccomp lockdown
  and syscall broker), linked statically into `libdroidtoppy.so` for the plugin
  processes' system-call filter.
- **notices** — `vendor/lib-ui` (its `notices` module), https://github.com/Droidtop/lib-ui — GPL-3.0-or-later.
  droidtop's own library, shared with Enginehost: the notice colours and the
  colour arithmetic, compiled into `runtime-common`.
- **hev-socks5-tunnel** — `vendor/hev-socks5-tunnel`, https://github.com/heiher/hev-socks5-tunnel — MIT,
  with its submodules hev-task-system, hev-socks5-core and yaml (MIT) and lwIP
  (BSD-3-Clause). Built into `libhev-socks5-tunnel.so`, the userspace IP
  stack behind droidtop's device VPN.
- **PulseAudio** — `vendor/pulseaudio` (v13.0), https://github.com/Droidtop/pulseaudio (branch `droidtop`, a fork
  of https://github.com/pulseaudio/pulseaudio) — LGPL-2.1-or-later (the libraries and modules built here). Built for
  both ABIs by `build-scripts/build-vendor-deps.sh`. The branch carries, as commits, Termux's Android changes and its
  `module-aaudio-sink.c` (termux-packages, https://github.com/termux/termux-packages, `packages/pulseaudio` at
  39437706663c), and upstream's atomic.h fix backported to 13.0.
- **libsndfile** — `vendor/libsndfile` (1.0.28), https://github.com/libsndfile/libsndfile — LGPL-2.1-or-later.
- **libltdl** — GNU libtool's libltdl, from the build host's `libltdl-dev` — LGPL-2.1-or-later.
- **dxbc** — DXVK's DXBC compiler as PancakeTAS/dxbc carried it for
  lsfg-vk, at `78ab59a8` (that repository no longer exists) — zlib
  (`build-scripts/lsfg-dxbc/LICENSE.md`). Kept as its exact tree in
  `build-scripts/lsfg-dxbc` and linked into droidtop's lsfg-vk layer
  (`.github/workflows/lsfg-vk-layer.yml`, from GameNative/lsfg-vk-android,
  MIT).
- **SDL2** — `vendor/SDL2` (release-2.32.10), https://github.com/libsdl-org/SDL — zlib.
  Headers only, for building GameNative's `libevshim.so`.
  Also the source of the controller vendor and product ids droidtop classifies external pads by, through
  `vendor/lib-input` (Droidtop/lib-input): its `SdlControllerIds.kt` is generated by its
  `third_party/sdl_controller_list/gen_sdl_controller_families.py` from SDL's `src/joystick/controller_list.h`
  (Copyright (C) Valve Corporation, zlib), and its `PadFamilies.classify` follows
  `SDL_GetJoystickGameControllerTypeFromVIDPID` (`src/joystick/SDL_joystick.c`, zlib).
- **mimalloc** — `vendor/mimalloc` (v2.2.7), https://github.com/microsoft/mimalloc — MIT,
  Copyright (c) 2018-2025 Microsoft Corporation, Daan Leijen. Built into `libmimalloc.so` for both ABIs, the allocator
  a game can choose for its Wine processes (docs/SPEC.md 9, "The allocator a game's Wine uses").
- **GStreamer** — `vendor/gstreamer` (1.24.13), https://gitlab.freedesktop.org/gstreamer/gstreamer — LGPL-2.1-or-later.
  Headers only, for building `libgstdroidtopff.so`, the media handoff element Wine's GStreamer loads (docs/SPEC.md 9,
  "Video in Wine games"). `runtime-windows/native/mediadecode/generated/gst` holds the three headers its build
  generates (`gstconfig.h`, `gstversion.h`, `gstenumtypes.h`), produced by its own meson configure at that version.
  Nothing of GStreamer is built into the APK; the element links against the Windows base image's own libraries.
- **GLib** — `vendor/glib` (2.80.5), https://gitlab.gnome.org/GNOME/glib — LGPL-2.1-or-later. Headers only, for the
  same element; `runtime-windows/native/mediadecode/generated/{glib,gobject,gmodule}` holds the headers its build
  generates (`glibconfig.h` and the visibility, version and enum-type headers), produced by its own meson configure.
- **libffi** — `vendor/libffi`, https://github.com/libffi/libffi — MIT.
  Runtime dependency of `libwayland-client`, cross-compiled by
  `build-scripts/build-vendor-deps.sh`.
- **romgi** — https://github.com/caprado/romgi — MIT, Copyright (c) 2025 Christian Prado. The debrid
  request flows in `plugin-host/.../debrid/` (TorBox, Real-Debrid) are ported to Kotlin from its
  `lib/services/debrid/` (Droidtop/tracker#638). Permission is hereby granted, free of charge, to any person
  obtaining a copy of this software and associated documentation files, to deal in the Software without
  restriction, subject to the condition that the above copyright notice and this permission notice shall be
  included in all copies or substantial portions of the Software. The full text is in romgi's LICENSE.MIT.
- **Hacker's Keyboard** — `input-keyboard`, https://github.com/klausw/hackerskeyboard — Apache-2.0.
  Forked in as its own module (not a submodule): the upstream Java tree is
  unchanged apart from build-compat fixes and one hook in `LatinIME`, and
  droidtop's additions (the second-screen keyboard, docs/SPEC.md §6c) are
  separate Kotlin files.
- **CleverKeys** — https://github.com/tribixbite/CleverKeys at `c8cbb74b` — GPL-3.0, Copyright (C) tribixbite and
  contributors. Hacker Keyboard Reborn's GIFs and Stickers tabs (Droidtop/tracker#903 H5) port the parts of its
  offline GIF panel that fit: the insert policy and editor MIME check (`gif/GifInsertPolicy.kt`), the search rules and
  compound-word fallback (`gif/GifDatabase.kt`), the commitContent insert (`KeyboardReceiver.kt`) and the thumbnail
  memory cap (`gif/GifGridView.kt`), in `input-keyboard`'s `GifLibrary.kt`, `GifLibraryStore.kt` and `GifPanel.kt`,
  each of which names its origin. No CleverKeys data, GIF packs or artwork are included.
- **Keyboard motion and haptics, ported in part** (Droidtop/tracker#903 H12a; docs/SPEC.md 6a, "Motion and
  haptics"). Each ported file names its origin.
  - FlorisBoard, https://github.com/florisboard/florisboard at `bac346e8` — Apache-2.0, Copyright (C) 2022-2025 The
    FlorisBoard Contributors: the vibration amplitude rule of `lib/android/.../Vibrator.kt` (a strength in percent
    times a factor, as a share of 255, never below 1), as `KeyHaptics.amplitude` in `input-keyboard`.
  - Unexpected Keyboard, https://github.com/Julow/Unexpected-Keyboard at `0695efdc` — GPL-3.0: from
    `srcs/juloo.keyboard2/VibratorCompat.java`, a predefined effect on Android 10 and later and a timed buzz below
    it, each effect built once and kept, in `KeyHaptics` and `KeyHapticsPlayer`.
  - HeliBoard, https://github.com/Helium314/HeliBoard at `2d9869b2` — Apache-2.0 AND GPL-3.0-only: the rule that an
    animator duration scale of 0 means no animation (`EmojiPalettesView.isAnimationsDisabled`), in `KeyMotion`.
- **Keyboard dynamic colours, ported in part** (Droidtop/tracker#903 H12b; docs/SPEC.md 6a, "Themes, light and
  dark"). HeliBoard, https://github.com/Helium314/HeliBoard at `2d9869b2` — GPL-3.0-only, Copyright (C) HeliBoard
  contributors: the Material You tone choices of `app/src/main/java/helium314/keyboard/latin/common/Colors.kt`
  (`DynamicColors`: the ground in neutral 50 or 900, keys in neutral 0 or 800, the accent in accent1 200 by night and
  600 by day), as `DynamicKeyColors.derive` in `input-keyboard`, whose header names it; and the rule of
  `KeyboardTheme.getAvailableDefaultColors` that offers dynamic colours only on Android 12 and later, in `ThemePresets`.
- **Keyboard modes, ported in part** (Droidtop/tracker#903 H12c; docs/SPEC.md 6a, "Modes"). `KeyboardModeViews.kt` in
  `input-keyboard` names both in its header.
  - HeliBoard, https://github.com/Helium314/HeliBoard at `2d9869b2` — GPL-3.0-only, Copyright (C) HeliBoard
    contributors: the one-handed mode's controls of `app/src/main/java/helium314/keyboard/latin/KeyboardWrapperView.kt`
    (stop, switch side and resize buttons centred in the free side at 20%, 50% and 80% of the keys' height, the resize
    button dragged sideways, a scale kept per orientation and clamped), as `OneHandedFrame` and the one-handed rules of
    `KeyboardModes`.
  - FlorisBoard, https://github.com/florisboard/florisboard at `bac346e8` — Apache-2.0, Copyright (C) The FlorisBoard
    Contributors: the floating window's insets of `ime/window/ImeWindowController.kt` (`onComputeInsets`: content and
    visible insets at the window's bottom, touches taken only inside the window by `TOUCHABLE_INSETS_REGION`) and the
    clamp of `ime/window/ImeWindowProps.kt` (`Floating.constrained`: size between bounds, offsets kept inside the root),
    as `FloatingKeyboardFrame` and `KeyboardModes.clampFrame`, with its phone minimum widths.
- **Keyboard clipboard panel, ported in part** (Droidtop/tracker#903 H12d; docs/SPEC.md 6a, "Clipboard panel").
  `ClipboardRules.kt` and `ClipboardPanel.kt` in `input-keyboard` name both in their headers.
  - HeliBoard, https://github.com/Helium314/HeliBoard at `2d9869b2` — GPL-3.0-only, Copyright (C) HeliBoard
    contributors: the retention rule of `app/src/main/java/helium314/keyboard/latin/database/ClipboardDao.kt`
    (`clearOldClips`: an unpinned clip older than the limit goes, a pinned one never does) and its timing in
    `ClipboardHistoryManager.prepareClipboardHistory` (checked as the history is about to be shown, never while it is
    on screen), and `togglePinned` giving a clip the current time, as `ClipboardHistory.expire`, `ClipKeep`,
    `ClipboardHistory.setPinned` and `ClipboardHistoryStore.prepareForPanel`.
  - FlorisBoard, https://github.com/florisboard/florisboard at `bac346e8` — Apache-2.0, Copyright (C) 2021-2025 The
    FlorisBoard Contributors: the pinned, recent (five minutes) and other grouping of
    `app/src/main/kotlin/dev/patrickgold/florisboard/ime/clipboard/ClipboardHistory.kt`, as `ClipGroups`; and from
    `ime/clipboard/ClipboardInputLayout.kt`, the clip's menu (the whole clip and its time, Pin or Unpin, Delete, Paste)
    and the clear question as a card over the history dimmed to 12%, closed by a tap outside it, as `ClipboardPanel`.
- **Termux terminal-emulator and terminal-view** — `vendor/termux-app` (a submodule of https://github.com/termux/termux-app)
  at `v0.118.3` (`5b657c6a`) — Apache-2.0 (termux-app's own `LICENSE.md` names these two libraries, which carry
  Terminal Emulator for Android code, https://github.com/jackpal/Android-Terminal-Emulator, as Apache-2.0; the rest
  of termux-app is GPLv3 and is not built). Their `src/main` trees are built unchanged as the
  `:terminal-emulator` and `:terminal-view` modules: the VT parser, the pseudo-terminal JNI (`libtermux.so`) and the
  terminal View under droidtop's container terminal. `WcWidth.java` follows jquast/wcwidth (MIT). Upstream's
  `LICENSE.md` is in the submodule.
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
  as `shell-gamepad/.../MotionEffects.kt`, `Shine.kt` and `MotionTokens.kt`), the sliding focus ring (`ui/FocusGlide.kt`, as
  `FocusGlide.kt`), the non-focusable keycap (`ui/SettingsWidgets.kt`, as `Keycap` in `TouchActions.kt`), and the
  wide-art cover, hero fill, status chip and empty-backdrop glow (`ui/FrontEndArt.kt`,
  `ui/FrontEndGames.kt`, `ui/FrontEndWidgets.kt`, `ui/FrontEndContent.kt`, in `pc/PcCapsule.kt` and
  `pc/PcBackdrop.kt`), the fading foot of a long menu (`ui/SettingsWidgets.kt`, in `MenuPanel`, `MenuPanel.kt`),
  the page and launch floods (`ui/PageFlood.kt`, `ui/LaunchFlood.kt`, as `PageFlood.kt`), the switch and slider
  (`ui/SettingsWidgets.kt`, as `ShellSwitch` and `ShellSlider` in `ShellControls.kt`), the turning cog
  (`ui/FrontEndWidgets.kt`, the game page's gear), the Stop pill (`ui/SessionOverlay.kt`, the Quick Menu's
  Game section), the Resume row with its live dot and the attention dot (`ui/FrontEndRail.kt`, in
  `LeftMenu.kt`), and the store page's header chips and busy bar (`ui/StorePage.kt`, as catalog chips and
  progress rows in `CatalogNavigator.kt` and `MenuRow.kt`). Each ported file names
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

## Japanese typing (Mozc)

The keyboard types Japanese through Google's Mozc (Droidtop/tracker#903 H7c, docs/SPEC.md 6a "The engine seam").
`libmozc.so`, for arm64-v8a and x86_64, ships inside the APK: Mozc 3.35.6354 (https://github.com/google/mozc, commit
`0136c35782536cb43d710575c311cd4185982738`), built unmodified by Droidtop/tracker#903 H7b and attached to this
repository's `mozc-engine-3.35.6354` prerelease, from which `build-scripts/fetch-mozc-engine.sh` fetches it against
`input-japanese/mozc-engine.pin`. Four of Mozc's protocol files (`commands.proto`, `candidate_window.proto`,
`config.proto`, `engine_builder.proto`, BSD-3, same commit) are vendored unchanged in
`input-japanese/src/main/proto/protocol/`. The dictionary, `mozc.data` (the open Mozc dictionary, with the Japan Post
zip-code data), is not in the APK: the keyboard downloads it from the same prerelease the first time Japanese is used.

The terms below are those of the release's own `credits-mozc.html` (Mozc's credits file, beside the library in the
archive), for what the Android library and its dictionary contain. That file also lists Qt Base (LGPL-3.0) and the
Windows Implementation Library (MIT), which only Mozc's desktop builds use; neither is in the Android library. The
library also links LLVM's libc++ statically, from the Android NDK.

- **Mozc** — https://github.com/google/mozc — BSD-3-Clause:

  > Copyright 2010-2026 Google LLC
  > All rights reserved.
  >
  > Redistribution and use in source and binary forms, with or without modification, are permitted provided that the
  > following conditions are met:
  >
  > * Redistributions of source code must retain the above copyright notice, this list of conditions and the
  >   following disclaimer.
  > * Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the
  >   following disclaimer in the documentation and/or other materials provided with the distribution.
  > * Neither the name of Google LLC nor the names of its contributors may be used to endorse or promote products
  >   derived from this software without specific prior written permission.
  >
  > THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
  > INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
  > DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
  > SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
  > SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
  > WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE
  > USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

  The vendored protocol files carry Mozc's header of the same licence ("Copyright 2010-2021, Google Inc.").

- **IPAdic** (in the dictionary) — Nara Institute of Science and Technology, with entries from ICOT Free Software:

  > Copyright 2000, 2001, 2002, 2003 Nara Institute of Science and Technology. All Rights Reserved.
  >
  > Use, reproduction, and distribution of this software is permitted. Any copy of this software, whether in its
  > original form or modified, must include both the above copyright notice and the following paragraphs.
  >
  > Nara Institute of Science and Technology (NAIST), the copyright holders, disclaims all warranties with regard to
  > this software, including all implied warranties of merchantability and fitness, in no event shall NAIST be liable
  > for any special, indirect or consequential damages or any damages whatsoever resulting from loss of use, data or
  > profits, whether in an action of contract, negligence or other tortuous action, arising out of or in connection
  > with the use or performance of this software.
  >
  > A large portion of the dictionary entries originate from ICOT Free Software. The following conditions for ICOT
  > Free Software applies to the current dictionary as well.
  >
  > Each User may also freely distribute the Program, whether in its original form or modified, to any third party or
  > parties, PROVIDED that the provisions of Section 3 ("NO WARRANTY") will ALWAYS appear on, or be attached to, the
  > Program, which is distributed substantially in the same form as set out herein and that such intended
  > distribution, if actually made, will neither violate or otherwise contravene any of the laws and regulations of
  > the countries having jurisdiction over the User or the intended distribution itself.
  >
  > NO WARRANTY
  >
  > The program was produced on an experimental basis in the course of the research and development conducted during
  > the project and is provided to users as so produced on an experimental basis. Accordingly, the program is provided
  > without any warranty whatsoever, whether express, implied, statutory or otherwise. The term "warranty" used herein
  > includes, but is not limited to, any warranty of the quality, performance, merchantability and fitness for a
  > particular purpose of the program and the nonexistence of any infringement or violation of any right of any third
  > party.
  >
  > Each user of the program will agree and understand, and be deemed to have agreed and understood, that there is no
  > warranty whatsoever for the program and, accordingly, the entire risk arising from or otherwise connected with the
  > program is assumed by the user.
  >
  > Therefore, neither ICOT, the copyright holder, or any other organization that participated in or was otherwise
  > related to the development of the program and their respective officials, directors, officers and other employees
  > shall be held liable for any and all damages, including, without limitation, general, special, incidental and
  > consequential damages, arising out of or otherwise in connection with the use or inability to use the program or
  > any product, material or result produced or otherwise obtained by using the program, regardless of whether they
  > have been advised of, or otherwise had knowledge of, the possibility of such damages at any time during the
  > project or thereafter. Each user will be deemed to have agreed to the foregoing by his or her commencement of use
  > of the program. The term "use" as used herein includes, but is not limited to, the use, modification, copying and
  > distribution of the program and the production of secondary products from the program.
  >
  > In the case where the program, whether in its original form or modified, was distributed or delivered to or
  > received by a user from any person, organization or entity other than ICOT, unless it makes or grants
  > independently of ICOT any specific warranty to the user in writing, such person, organization or entity, will
  > also be exempted from and not be held liable to the user for any such damages as noted above as far as the
  > program is concerned.

- **Okinawa dictionary** (in the dictionary) — public domain, in its authors' words:

  > Public Domain Dataです。使用・変更・配布に関しては一切の制限をつけません。
  > 商品などに組み込むことも自由に行なってください。すでにいくつかの辞書には沖縄辞書が採用されています。
  > 勝手ながら、沖縄辞書に寄贈された辞書も in the Public Domain' 扱いとさせていただきます。

- **Japanese Usage Dictionary** (in the dictionary) — BSD-2-Clause:

  > Copyright (c) 2011, Hiroyuki Komatsu, Shoji Ogura, Tsuyoshi Horo
  > All rights reserved.
  >
  > Redistribution and use in source and binary forms, with or without modification, are permitted provided that the
  > following conditions are met:
  >
  > * Redistributions of source code must retain the above copyright notice, this list of conditions and the
  >   following disclaimer.
  > * Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the
  >   following disclaimer in the documentation and/or other materials provided with the distribution.
  >
  > THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
  > INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
  > DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
  > SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
  > SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
  > WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE
  > USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

- **Tamachi Phonetic Kanji Alphabet** (in the dictionary) — MIT:

  > Each datum on this page is copyrighted by Tamachi phonetic kanji alphabet development team and is released under
  > MIT License.
  >
  > Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
  > documentation files (the "Software"), to deal in the Software without restriction, including without limitation
  > the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
  > permit persons to whom the Software is furnished to do so, subject to the following conditions:
  >
  > The above copyright notice and this permission notice shall be included in all copies or substantial portions of
  > the Software.
  >
  > THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO
  > THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
  > AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT,
  > TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
  > SOFTWARE.

- **Japan Post zip-code data** (in the dictionary; kept, by the coordinator's ruling for the owner on #903) — "zipcode"
  and "zipcode for jigyosyo":

  > Copyright (c) 2010, JAPAN POST SERVICE CO.,Ltd.
  > All rights reserved.
  >
  > 郵便事業株式会社は著作権を主張しません。自由に配布していただいて結構です。
  >
  > 大口事業所個別番号番号データについては郵便事業株式会社は著作権を主張しません。自由に配布していただいて結構です。郵便事業株式会社への許諾も必要ありません。

- **Abseil** (in the library) — https://github.com/abseil/abseil-cpp — Apache-2.0
  (https://www.apache.org/licenses/LICENSE-2.0; the full text is in `credits-mozc.html`).
- **Protocol Buffers** (in the library; and protobuf-javalite, which reads Mozc's answers in the keyboard) —
  https://github.com/protocolbuffers/protobuf — BSD-3-Clause:

  > Copyright 2008, Google Inc.
  > All rights reserved.
  >
  > Redistribution and use in source and binary forms, with or without modification, are permitted provided that the
  > following conditions are met:
  >
  > * Redistributions of source code must retain the above copyright notice, this list of conditions and the
  >   following disclaimer.
  > * Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the
  >   following disclaimer in the documentation and/or other materials provided with the distribution.
  > * Neither the name of Google Inc. nor the names of its contributors may be used to endorse or promote products
  >   derived from this software without specific prior written permission.
  >
  > THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
  > INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
  > DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
  > SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
  > SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
  > WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE
  > USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
  >
  > Code generated by the Protocol Buffer compiler is owned by the owner of the input file used when generating it.
  > This code is not standalone and requires a support library to be linked with it. This support library is itself
  > covered by the above license.

- **LLVM libc++** (in the library, linked statically from the Android NDK) — https://libcxx.llvm.org — Apache-2.0
  with LLVM Exceptions (https://llvm.org/LICENSE.txt).

## Chinese typing (RIME)

The keyboard types Chinese through RIME (Droidtop/tracker#903 H8b, docs/SPEC.md 6a "Chinese through RIME").
`librime.so`, for arm64-v8a and x86_64, ships inside the APK: librime (https://github.com/rime/librime, commit
`56737cd29a4d393a104a2098ece3697aa2810465`) with its lua, octagram and predict plugins linked in, built by
Droidtop/tracker#903 H8a (`.github/workflows/rime-engine.yml`) on the CMake recipe of Trime
(https://github.com/osfans/trime, v3.3.13, GPL-3.0-or-later; the recipe only, nothing of Trime ships) and attached to
this repository's `rime-engine-56737cd2` prerelease, from which `build-scripts/fetch-rime-engine.sh` fetches it
against `input-chinese/rime-engine.pin`. droidtop's own JNI over it (`input-chinese/src/main/cpp`,
`libdroidtop_rime.so`) is droidtop's, GPL-3.0. The archive carries each library's licence file (`LICENSE-*`), the NDK
toolchain's notices and `VERSION`, which names every component's commit; the terms below are those files'.

The data, `rime-data-luna.tar.gz` (朙月拼音 luna_pinyin with the 八股文 essay vocabulary, compiled by rime_deployer,
and OpenCC's conversion data), is not in the APK: the keyboard downloads it from the same prerelease the first time
Chinese is used. It is built from rime/rime-prelude (`082425ea0684bca36474415d4a0e8db9b016487e`), rime/rime-essay
(`054920de4f54c9e5994276a96a4fc2a35cb51aa3`) and rime/rime-luna-pinyin (`56b934b099dfbeab842320f13aa8b461a6ab3e42`),
each LGPL-3.0 (https://www.gnu.org/licenses/lgpl-3.0.html), with one change: `luna_pinyin.schema.yaml` loses its
stroke reverse lookup. The exact sources it was compiled from, that change included, are published beside it as the
release's `rime-data-luna-sources.tar.gz`
(https://github.com/Droidtop/droidtop/releases/download/rime-engine-56737cd2/rime-data-luna-sources.tar.gz), and the
downloaded data carries their licence (`LICENSE-rime-data`) and `SOURCES.txt`. OpenCC's data in it is Apache-2.0.

- **librime** — https://github.com/rime/librime — BSD-3-Clause:

  > Copyright (c) 2014, RIME Developers
  > All rights reserved.
  >
  > Redistribution and use in source and binary forms, with or without modification, are permitted provided that the
  > following conditions are met:
  >
  > * Redistributions of source code must retain the above copyright notice, this list of conditions and the following
  >   disclaimer.
  >
  > * Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the
  >   following disclaimer in the documentation and/or other materials provided with the distribution.
  >
  > * Neither the name of the copyright holder nor the names of its contributors may be used to endorse or promote
  >   products derived from this software without specific prior written permission.
  >
  > THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
  > INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
  > DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
  > SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
  > SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
  > WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE
  > USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

- **librime-octagram** (in the library) — https://github.com/lotem/librime-octagram — GPL-3.0
  (https://www.gnu.org/licenses/gpl-3.0.html; the full text is the archive's `LICENSE-librime-octagram`). droidtop is
  GPL-3.0, so it may carry it; the library as a whole is distributed under GPL-3.0 as part of droidtop.
- **librime-lua** (in the library) — https://github.com/hchunhui/librime-lua — BSD-3-Clause:

  > Copyright (c) 2021, librime-lua Developers
  > All rights reserved.
  >
  > Redistribution and use in source and binary forms, with or without modification, are permitted provided that the
  > following conditions are met:
  >
  > * Redistributions of source code must retain the above copyright notice, this list of conditions and the following
  >   disclaimer.
  >
  > * Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the
  >   following disclaimer in the documentation and/or other materials provided with the distribution.
  >
  > * Neither the name of the copyright holder nor the names of its contributors may be used to endorse or promote
  >   products derived from this software without specific prior written permission.
  >
  > THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
  > INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
  > DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
  > SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
  > SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
  > WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE
  > USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

- **librime-predict** (in the library) — https://github.com/rime/librime-predict — BSD-3-Clause:

  > BSD 3-Clause License
  >
  > Copyright (c) 2023, RIME Developers
  >
  > Redistribution and use in source and binary forms, with or without modification, are permitted provided that the
  > following conditions are met:
  >
  > 1. Redistributions of source code must retain the above copyright notice, this list of conditions and the
  >    following disclaimer.
  >
  > 2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the
  >    following disclaimer in the documentation and/or other materials provided with the distribution.
  >
  > 3. Neither the name of the copyright holder nor the names of its contributors may be used to endorse or promote
  >    products derived from this software without specific prior written permission.
  >
  > THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
  > INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
  > DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
  > SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
  > SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
  > WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE
  > USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

- **Lua 5.4** (in the library, compiled in by librime-lua) — https://www.lua.org — MIT:

  > Copyright (C) 1994-2026 Lua.org, PUC-Rio.
  >
  > Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
  > documentation files (the "Software"), to deal in the Software without restriction, including without limitation
  > the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and
  > to permit persons to whom the Software is furnished to do so, subject to the following conditions:
  >
  > The above copyright notice and this permission notice shall be included in all copies or substantial portions of
  > the Software.
  >
  > THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO
  > THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
  > AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF
  > CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS
  > IN THE SOFTWARE.

- **Boost** (in the library, the parts librime uses) — https://www.boost.org — Boost Software License 1.0:

  > Boost Software License - Version 1.0 - August 17th, 2003
  >
  > Permission is hereby granted, free of charge, to any person or organization obtaining a copy of the software and
  > accompanying documentation covered by this license (the "Software") to use, reproduce, display, distribute,
  > execute, and transmit the Software, and to prepare derivative works of the Software, and to permit third-parties
  > to whom the Software is furnished to do so, all subject to the following:
  >
  > The copyright notices in the Software and this entire statement, including the above license grant, this
  > restriction and the following disclaimer, must be included in all copies of the Software, in whole or in part, and
  > all derivative works of the Software, unless such copies or derivative works are solely in the form of
  > machine-executable object code generated by a source language processor.
  >
  > THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO
  > THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE, TITLE AND NON-INFRINGEMENT. IN NO EVENT SHALL
  > THE COPYRIGHT HOLDERS OR ANYONE DISTRIBUTING THE SOFTWARE BE LIABLE FOR ANY DAMAGES OR OTHER LIABILITY, WHETHER IN
  > CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS
  > IN THE SOFTWARE.

- **glog** (in the library) — https://github.com/google/glog — BSD-3-Clause:

  > Copyright (c) 2024, Google Inc.
  > All rights reserved.
  >
  > Redistribution and use in source and binary forms, with or without modification, are permitted provided that the
  > following conditions are met:
  >
  > * Redistributions of source code must retain the above copyright notice, this list of conditions and the following
  >   disclaimer.
  >
  > * Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the
  >   following disclaimer in the documentation and/or other materials provided with the distribution.
  >
  > * Neither the name of Google Inc. nor the names of its contributors may be used to endorse or promote products
  >   derived from this software without specific prior written permission.
  >
  > THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
  > INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
  > DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
  > SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
  > SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
  > WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE
  > USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

- **LevelDB** (in the library) — https://github.com/google/leveldb — BSD-3-Clause:

  > Copyright (c) 2011 The LevelDB Authors. All rights reserved.
  >
  > Redistribution and use in source and binary forms, with or without modification, are permitted provided that the
  > following conditions are met:
  >
  > * Redistributions of source code must retain the above copyright notice, this list of conditions and the following
  >   disclaimer.
  >
  > * Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the
  >   following disclaimer in the documentation and/or other materials provided with the distribution.
  >
  > * Neither the name of Google Inc. nor the names of its contributors may be used to endorse or promote products
  >   derived from this software without specific prior written permission.
  >
  > THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
  > INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
  > DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
  > SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
  > SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
  > WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE
  > USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

- **Snappy** (in the library) — https://github.com/google/snappy — BSD-3-Clause:

  > Copyright 2011, Google Inc.
  > All rights reserved.
  >
  > Redistribution and use in source and binary forms, with or without modification, are permitted provided that the
  > following conditions are met:
  >
  > * Redistributions of source code must retain the above copyright notice, this list of conditions and the following
  >   disclaimer.
  >
  > * Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the
  >   following disclaimer in the documentation and/or other materials provided with the distribution.
  >
  > * Neither the name of Google Inc. nor the names of its contributors may be used to endorse or promote products
  >   derived from this software without specific prior written permission.
  >
  > THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
  > INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
  > DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
  > SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
  > SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
  > WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE
  > USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

- **yaml-cpp** (in the library) — https://github.com/jbeder/yaml-cpp — MIT:

  > Copyright (c) 2008-2015 Jesse Beder.
  >
  > Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
  > documentation files (the "Software"), to deal in the Software without restriction, including without limitation
  > the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and
  > to permit persons to whom the Software is furnished to do so, subject to the following conditions:
  >
  > The above copyright notice and this permission notice shall be included in all copies or substantial portions of
  > the Software.
  >
  > THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO
  > THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
  > AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF
  > CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS
  > IN THE SOFTWARE.

- **marisa-trie** (in the library) — https://github.com/s-yata/marisa-trie — BSD-2-Clause OR LGPL-2.1-or-later; used
  under BSD-2-Clause:

  > Copyright (c) 2010-2025, Susumu Yata
  > All rights reserved.
  >
  > Redistribution and use in source and binary forms, with or without modification, are permitted provided that the
  > following conditions are met:
  >
  > - Redistributions of source code must retain the above copyright notice, this list of conditions and the following
  >   disclaimer.
  >
  > - Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the
  >   following disclaimer in the documentation and/or other materials provided with the distribution.
  >
  > THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
  > INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
  > DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
  > SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
  > SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
  > WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE
  > USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

- **OpenCC** (in the library; and its data, in the download) — https://github.com/BYVoid/OpenCC — Apache-2.0
  (https://www.apache.org/licenses/LICENSE-2.0; the full text is the archive's `LICENSE-opencc`).
- **LLVM libc++** (in both libraries, linked statically from the Android NDK) — https://libcxx.llvm.org — Apache-2.0
  with LLVM Exceptions (https://llvm.org/LICENSE.txt; the archive's `NOTICE-ndk-toolchain`).

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
