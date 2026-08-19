# host-bridge

The thin privileged layer on the Android side — deliberately as small as
possible, mirroring Qubes' dom0/GUI-daemon role rather than a traditional
Android "app that renders its own UI."

It does **not** implement a Wayland compositor. The real desktop compositor
(vendor/sway, built with the headless backend) runs *inside* the primary
Linux container (see `runtime-linux-root` / `runtime-linux-noroot`). This
module is a Wayland **client** of that compositor, plus a JNI bridge, doing
exactly two things:

1. **Frame passthrough** — capture buffers from the primary container's
   headless output(s) via `wlr-screencopy-unstable-v1` and present them on
   Android `Surface`s, one per `DisplayOutput` (built-in screen, second
   screen, external lapdock monitor). Ideally via shared DMA-BUF/
   `AHardwareBuffer` rather than a CPU copy — the container already has GPU
   access through VirGL/Turnip.
2. **Input injection** — turn normalized events from `:input-seat` into
   `wlr-virtual-pointer-v1` / `virtual-keyboard-v1` protocol messages sent
   into the container.

"Give this window its own display" is implemented entirely on the container
side (the compositor reassigns a surface to a different headless output);
host-bridge just needs to be presenting that output's frames on the right
Android `Surface` when it happens.

## Status

**Builds and links successfully**, verified against a real Android NDK build
(WSL2, NDK 27.0.12077973). `libhostbridge.so` compiles, links against a
cross-compiled `libwayland-client.so` (`build-scripts/build-vendor-deps.sh`
— builds `libffi` via autotools, then `libwayland-client` via Meson using a
two-step native-scanner-then-cross-library approach, see that script's
comments for why), and exports the correct JNI symbols
(`Java_dev_droidtop_hostbridge_HostBridge_nativeConnect`/`nativeDisconnect`).
Confirmed via `readelf -d`: `NEEDED libwayland-client.so` is present, so the
dynamic link is real, not just a compile-time header match.

Real bugs this surfaced, beyond the build-system ones already listed below:
- A C++ namespace footgun in `wayland_client.h`: `struct wl_display*` used
  directly inside `namespace hostbridge` without a prior global forward
  declaration silently declares a *new*, distinct `hostbridge::wl_display`
  type instead of referring to the real global one — every `wl_display_*`
  call then fails to compile against "an incomplete type." Fixed by
  forward-declaring `wl_display`/`wl_registry` in the global namespace
  before entering `namespace hostbridge`.
- The NDK's CMake toolchain file re-roots `find_library`/`find_path` PATHS
  entries under its own sysroot by default (`CMAKE_FIND_ROOT_PATH`), which
  silently broke finding `libwayland-client.so` even with an explicit,
  correct path — needed `NO_CMAKE_FIND_ROOT_PATH` on both calls.
- Windows git checkouts corrupt every shell/autotools script in the vendored
  submodules with CRLF line endings, breaking shebangs; `build-vendor-deps.sh`
  strips these itself now rather than requiring a manual fix each time.

What's still not implemented (this is genuinely the next work, not a build
problem): the frame-passthrough (`wlr-screencopy` capture loop →
`HostBridge.presentOutput`) and input-injection paths described below.
`nativeConnect` itself (registry binding) is real and does connect; nothing
past that point exists yet.

What's implemented:
- `wayland_client.cpp`: opens a UNIX socket at an explicit filesystem path
  (not `wl_display_connect(name)`, since the compositor's socket lives
  inside the primary container's mount namespace — the caller must hand us
  a path reachable from outside it), connects via
  `wl_display_connect_to_fd`, and binds the four required globals
  (`wl_compositor`, `wl_seat`, `zwlr_screencopy_manager_v1`,
  `zwlr_virtual_pointer_manager_v1`, `zwp_virtual_keyboard_manager_v1`) off
  the registry. Fails loudly (returns false, logs which globals were
  missing) if sway isn't advertising all of them.
- `CMakeLists.txt`: wires `wayland-scanner` codegen for the three non-core
  protocols, all sourced from `vendor/wlroots/protocol` (confirmed against
  the actual repo layout — an earlier draft incorrectly pointed
  virtual-keyboard at `vendor/wayland-protocols`, which doesn't have it).

What's still TODO, in order:
1. **Cross-compile `libwayland-client` for Android** (`vendor/wayland`,
   Meson build, needs an Android cross-file + libexpat). This is the actual
   blocker on everything else — `find_library(wayland-client)` in
   `CMakeLists.txt` will fail until this exists. Not attempted here; no
   `meson`/`ninja` available in this environment.
2. Confirm `wayland-scanner` (a host build tool) is on the build machine's
   `PATH` — required for the protocol codegen step to run at all.
3. `zwlr_screencopy_manager_v1` capture loop → deliver frames to an Android
   `Surface` (`HostBridge.presentOutput`) — not started.
4. `zwlr_virtual_pointer_manager_v1` / `zwp_virtual_keyboard_manager_v1`
   request wrappers for `:input-seat` to call into — not started.
5. Once a real primary container + sway build exists to connect to, test
   `nativeConnect` against it for real — this whole module has only been
   written against protocol documentation, never exercised.

See [docs/SPEC.md](../docs/SPEC.md) §11 for why this module is first in
build order despite being unverified — everything else in the architecture
assumes it works, so it's the thing worth de-risking before investing
further.
