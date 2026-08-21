# droidtop

Turns an Android handheld into a real desktop — one shared Linux-style
desktop where Windows software runs via Wine/Box64 and Linux software runs
via containers, both composited together the way distrobox integrates
containers into a host desktop on real Linux. Any window can optionally get
its own display (a second screen, an external lapdock monitor) without
leaving the merged desktop as the default.

Full design rationale lives in [docs/SPEC.md](docs/SPEC.md) — read that
before touching any module. Each module also has its own README covering
what it does and, where relevant, what it changes versus the upstream
project it's forked from.

## Status

Pre-implementation. This is a Gradle multi-module scaffold with typed
interfaces and `TODO()` stubs, not a working build yet. See SPEC.md §10 for
build order and §11 for the open risks worth prototyping before investing
further.

## Layout

```
app/                    Application shell
host-bridge/             Thin native bridge to the primary container's compositor
runtime-common/          Shared interfaces (Container, DisplayOutput, RootfsImage)
runtime-windows/         Wine/Box64, forked from vendor/gamenative
runtime-linux-root/      Namespace/cgroup Linux containers, forked from vendor/droidspaces (needs root)
runtime-linux-noroot/    proot-based Linux containers, no root required, new code
input-seat/              Unified input seat (touch, gamepad, second-screen trackpad, lapdock)
runtime-remote-stream/   GameStream/Moonlight client, forked from vendor/moonlight-common-c
library-core/            Unified, launcher-ready library/metadata model
shell-default/           Default touch-first UI (ships first)
shell-gamepad/           Optional gamepad-console UI (built last, toggleable)
pc-helper/               Separate Go service for the remote gaming PC (Sunshine API, Steam install trigger)
vendor/                  Git submodules — upstream sources being forked from or referenced
docs/                    SPEC.md and design decisions
```

## Vendored sources

| Path | Upstream | License | Role |
|---|---|---|---|
| `vendor/gamenative` | [utkarshdalal/GameNative](https://github.com/utkarshdalal/GameNative) | GPL-3.0 | Wine/Box64 runtime fork base |
| `vendor/winlator-upstream` | [brunodev85/winlator](https://github.com/brunodev85/winlator) | LGPL-2.1 | Reference/diff only |
| `vendor/droidspaces` | [ravindu644/Droidspaces-OSS](https://github.com/ravindu644/Droidspaces-OSS) | GPL-3.0 | Rooted Linux container fork base |
| `vendor/sway` | [swaywm/sway](https://github.com/swaywm/sway) | MIT | In-container desktop compositor |
| `vendor/wlroots` | [wlroots](https://gitlab.freedesktop.org/wlroots/wlroots) | MIT | Protocol XML defs only (not built for Android) |
| `vendor/wayland`, `vendor/wayland-protocols` | [wayland](https://gitlab.freedesktop.org/wayland/wayland), [wayland-protocols](https://gitlab.freedesktop.org/wayland/wayland-protocols) | MIT | Core protocol/headers, codegen only |
| `vendor/go-containerregistry` | [google/go-containerregistry](https://github.com/google/go-containerregistry) | Apache-2.0 | `crane` — OCI image pulling |
| `vendor/moonlight-common-c` | [moonlight-stream/moonlight-common-c](https://github.com/moonlight-stream/moonlight-common-c) | GPL-3.0 | GameStream/Moonlight protocol client (pinned ENet fork included) |
| `vendor/mbedtls` | [Mbed-TLS/mbedtls](https://github.com/Mbed-TLS/mbedtls) (v3.6.2) | Apache-2.0 | TLS backend for moonlight-common-c (chosen over OpenSSL — CMake-native) |
| `vendor/libffi` | [libffi/libffi](https://github.com/libffi/libffi) | MIT | Runtime dependency of libwayland-client |

## Building the native dependencies

`:host-bridge` and `:runtime-remote-stream` need cross-compiled native deps
that Gradle's own CMake build can't produce (they need Meson/autotools, not
just CMake). Run this once before building either module:

```bash
build-scripts/build-vendor-deps.sh
```

Requires an Android SDK/NDK install and a Linux-like build environment
(WSL2 works) with `meson`, `ninja`, `autoconf`/`automake`/`libtool`, and a
few other standard packages — see the script's header comment for the exact
list this was verified against.

## UI Inspiration

`shell-gamepad`'s controller-first library UI draws design inspiration
from [Daijishō](https://github.com/TapiocaFox/Daijishou) and
[iiSU](https://github.com/iisu-network/iiSU) — neither is a fork target
or dependency; droidtop's implementation is original.

## Licensing

GPL-3.0 sources are combined into this project, which means **the combined
project is GPL-3.0** — see [docs/SPEC.md §8](docs/SPEC.md#8-licensing).

## Cloning

Submodules are shallow (`--depth 1`). Clone with:

```bash
git clone --recurse-submodules <this-repo>
```
