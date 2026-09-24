# NOTICE

This project is distributed under the GNU General Public License v3.0 (see
[LICENSE](LICENSE)), as required by combining GPL-3.0-licensed sources.

## Vendored / forked sources

- **GameNative** — `vendor/gamenative`, droidtop's fork
  https://github.com/bi0shacker001/gamenative-tux of
  https://github.com/utkarshdalal/GameNative — GPL-3.0.
  `runtime-windows` compiles the whole vendored tree (docs/SPEC.md §9).
- **Winlator** — https://github.com/brunodev85/winlator — LGPL-2.1.
  The upstream of GameNative's `com.winlator` runtime tree, and so of
  `runtime-windows`. Not vendored in this repository (the reference checkout
  was removed); no Winlator source is built here except by way of
  `vendor/gamenative`.
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
  vendored at `vendor/gamenative/app/src/main/cpp/proot/talloc`.
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
- **OpenXR-SDK** — `vendor/OpenXR-SDK` (release-1.1.63), https://github.com/KhronosGroup/OpenXR-SDK —
  Apache-2.0. Built into `libopenxr_loader.so` for x86_64.
- **SDL2** — `vendor/SDL2` (release-2.32.10), https://github.com/libsdl-org/SDL — zlib.
  Headers only, for building gamenative's `libevshim.so` for x86_64.
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
  CC-BY-NC-SA 4.0. droidtop's default theme on a landscape display.
- **Slate (slate-es-de)** — https://gitlab.com/es-de/themes/slate,
  vendored at upstream commit `c072efc`, by Leon Styhre and
  contributors (itself based on recalbox-multi by the Recalbox
  community prior to their 2018 licence change, with graphics from
  RetroPie's Carbon theme by Rookervik, vector graphics by Bezza191 and
  logotypes by Dan Patrick — see its own CREDITS.md) — CC-BY-NC-SA 4.0.
  ES-DE's own default theme, and the only bundled theme that declares
  vertical aspect-ratio variants (`16:9_vertical`, `4:3_vertical`), so
  it is droidtop's default on a portrait display (docs/SPEC.md §7f).

## Design references (not vendored, no code copied)

Moonlight Android (input interaction model, LAN host discovery approach),
KDE Connect Android (remote input reference), Playnite (library/plugin
model), distrobox (host-integration mechanism), Qubes OS (dom0/AppVM
architectural split). See [docs/SPEC.md](docs/SPEC.md) for how each
informed the design.
