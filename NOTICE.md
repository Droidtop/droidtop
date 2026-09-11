# NOTICE

This project is distributed under the GNU General Public License v3.0 (see
[LICENSE](LICENSE)), as required by combining GPL-3.0-licensed sources.

## Vendored / forked sources

- **GameNative** — `vendor/gamenative`, https://github.com/utkarshdalal/GameNative — GPL-3.0.
  `runtime-windows` is forked from its `com.winlator` runtime tree.
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
  Runs unmodified (aside from headless-backend build configuration) inside
  the primary container as the desktop compositor.
- **wlroots** — `vendor/wlroots`, https://gitlab.freedesktop.org/wlroots/wlroots — MIT.
  Only protocol XML definitions are used (for `wayland-scanner` codegen in
  `host-bridge`); the library itself is not compiled for Android.
- **wayland** / **wayland-protocols** — `vendor/wayland`, `vendor/wayland-protocols`,
  https://gitlab.freedesktop.org/wayland/wayland, https://gitlab.freedesktop.org/wayland/wayland-protocols — MIT.
  Core protocol headers and `wayland-scanner` codegen inputs only.
- **go-containerregistry** — `vendor/go-containerregistry`, https://github.com/google/go-containerregistry — Apache-2.0.
  `crane` is used for OCI image pulling in the rootfs image acquisition path.
- **moonlight-common-c** — `vendor/moonlight-common-c`, https://github.com/moonlight-stream/moonlight-common-c — GPL-3.0.
  `runtime-remote-stream` is built directly on this for GameStream/Sunshine
  protocol support (pairing, app-list retrieval, stream launch). Includes its
  pinned ENet fork (`vendor/moonlight-common-c/enet`,
  https://github.com/cgutman/enet) as a nested submodule — required as-is,
  not substitutable with a generic ENet build.
- **mbedTLS** — `vendor/mbedtls` (pinned to v3.6.2), https://github.com/Mbed-TLS/mbedtls — Apache-2.0.
  TLS backend for moonlight-common-c, in place of OpenSSL — CMake-native,
  cross-compiles through the same NDK toolchain file Gradle already uses.
- **libffi** — `vendor/libffi`, https://github.com/libffi/libffi — MIT.
  Runtime dependency of `libwayland-client`, cross-compiled by
  `build-scripts/build-vendor-deps.sh`.
- **Hacker's Keyboard** — `input-keyboard`, https://github.com/klausw/hackerskeyboard — Apache-2.0.
  Forked in unmodified (aside from build-compat fixes) as the second
  screen's future persistent keyboard (docs/SPEC.md §4/§6) — vendored
  and made to compile as its own module; not yet wired up to any real
  input surface.

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
architectural split). Sunshine's REST API (https://docs.lizardbyte.dev) is
called over HTTP by `pc-helper`, not vendored or linked. See
[docs/SPEC.md](docs/SPEC.md) for how each informed the design.
