# NOTICE

This project is distributed under the GNU General Public License v3.0 (see
[LICENSE](LICENSE)), as required by combining GPL-3.0-licensed sources.

## Vendored / forked sources

- **GameNative** — `vendor/gamenative`, https://github.com/utkarshdalal/GameNative — GPL-3.0.
  `runtime-windows` is forked from its `com.winlator` runtime tree.
- **Winlator** — `vendor/winlator-upstream`, https://github.com/brunodev85/winlator — LGPL-2.1.
  Kept as an upstream reference only; not directly built into this project.
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

## Design references (not vendored, no code copied)

Moonlight Android (input interaction model, LAN host discovery approach),
KDE Connect Android (remote input reference), Playnite (library/plugin
model), distrobox (host-integration mechanism), Qubes OS (dom0/AppVM
architectural split). Sunshine's REST API (https://docs.lizardbyte.dev) is
called over HTTP by `pc-helper`, not vendored or linked. See
[docs/SPEC.md](docs/SPEC.md) for how each informed the design.
