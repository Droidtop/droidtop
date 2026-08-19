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
   headless output via `wlr-screencopy-unstable-v1` and present them on an
   Android `Surface`. Currently single-output only (always the primary
   screen) — matches the merged-desktop MVP; see "Not yet implemented" below
   for what real multi-output support needs.
2. **Input injection** — turn normalized events from `:input-seat` into
   `wlr-virtual-pointer-v1` / `virtual-keyboard-v1` protocol messages sent
   into the container.

"Give this window its own display" is implemented entirely on the container
side (the compositor reassigns a surface to a different headless output);
host-bridge just needs to be presenting that output's frames on the right
Android `Surface` when it happens.

## Status

**Builds and links successfully; frame capture and input injection are
implemented, not just scaffolded.** Verified against a real Android NDK
build (WSL2, NDK 27.0.12077973) — `libhostbridge.so` compiles, links against
a cross-compiled `libwayland-client.so` (confirmed via `readelf -d`: `NEEDED
libwayland-client.so`), and exports all nine JNI symbols matching
`HostBridge.kt`'s `external fun` declarations exactly (checked with `nm -D`).

**What's genuinely NOT verified**: none of this has been run against a live
compositor — there's no primary container + sway build to connect to yet
(that's `runtime-linux-root`/`-noroot`'s job, still unimplemented). Compiling
and linking correctly is real signal (protocol usage, struct layouts, and
JNI signatures all have to be exactly right for that to happen at all), but
it is not the same as confirmed-working. Treat the implementation below as
"should work, written carefully against the protocol spec" rather than
"proven."

### What's implemented

- **Connection** (`WaylandClient::connect`) — opens a UNIX socket at an
  explicit filesystem path (not `wl_display_connect(name)`, since the
  compositor's socket lives inside the primary container's mount namespace),
  connects via `wl_display_connect_to_fd`, binds `wl_compositor`, `wl_seat`,
  `wl_shm`, `wl_output` (first one only — see multi-output note below),
  `zwlr_screencopy_manager_v1`, `zwlr_virtual_pointer_manager_v1`, and
  `zwp_virtual_keyboard_manager_v1`. Fails loudly if any of the five
  required globals (compositor/seat/screencopy/vptr/vkbd) is missing.
  Starts a background dispatch thread (`wl_display_dispatch` loop) so
  screencopy's async frame-ready callbacks actually get delivered.
- **Frame capture** (`presentPrimaryOutput` → `startNextCapture` and the
  `frame_*` callbacks) — a continuous capture loop: request a frame via
  `zwlr_screencopy_manager_v1_capture_output`, wait for the compositor's
  `buffer`/`buffer_done` events, allocate a `wl_shm` buffer backed by
  `ASharedMemory` (Android's `memfd_create` equivalent), call `frame.copy`,
  and on `ready` blit the shm buffer onto the target `ANativeWindow` (naive
  per-pixel BGRA→RGBA channel swap — correct, not optimized; a GPU
  blit/DMA-BUF path is real future work, not an oversight) before
  immediately requesting the next frame. `frame.failed` retries rather than
  giving up.
- **Virtual pointer** — relative motion, absolute motion, button, and axis
  (scroll) requests, each followed by the required `frame()` call.
- **Virtual keyboard** — key press/release requests. The protocol requires a
  valid XKB keymap be set before *any* key event is accepted; rather than
  cross-compiling `libxkbcommon` for Android just to generate one,
  `default_keymap.h` embeds a standard "us"/pc105 keymap generated once,
  on-host, via WSL's `libxkbcommon` (see that file's header comment) — no
  xkbcommon dependency on-device at all.

### Known simplifications / not yet implemented

- **Single output only.** `WaylandGlobals::output` tracks the first
  `wl_output` the compositor advertises; there's no list, and no way for
  the Kotlin side to pick a different one. Real multi-output support (the
  "pop this window to the second screen" feature from SPEC.md §4) needs
  that list plus a per-`DisplayOutput` capture session, not just one.
- **CPU blit, not zero-copy.** Frames are copied pixel-by-pixel from the shm
  buffer to the `ANativeWindow`. Fine for a first correctness pass; will
  matter for real framerate once there's something live to measure against.
- **Coarse thread-safety.** Dispatch runs on one dedicated thread; input
  injection and `presentPrimaryOutput`/`stopPresenting` are called from
  whatever thread the JNI caller uses. This relies on libwayland-client's
  documented guarantee that request marshaling is safe from other threads
  as long as only one thread ever calls `wl_display_dispatch` — that's the
  design here, but it hasn't been stress-tested.
- **No output-removal handling.** If the primary container's compositor
  restarts or the output disappears mid-session, nothing currently notices
  or recovers.

See [docs/SPEC.md](../docs/SPEC.md) §11 for the broader open risks (sway
headless-backend performance, Wine's Wayland driver maturity, etc.) that
this module's implementation being "complete" doesn't resolve on its own —
those need a live compositor to actually test against.
