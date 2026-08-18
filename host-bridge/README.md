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

**Nothing here has been compiled or run.** This environment has no Android
NDK, no `meson`/`ninja`, and no `cmake` installed — everything below is
written to be correct against the real Wayland/wlroots protocol APIs, but is
unverified. Treat it as a strong starting point for the first real build
attempt, not as proven-working code.

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
  protocols against `vendor/wlroots/protocol` and `vendor/wayland-protocols`.

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
