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
   headless outputs via `wlr-screencopy-unstable-v1` and present them on
   Android `Surface`s, one capture per output (Droidtop/tracker#908). The
   Desktop shows the primary output only today; see "Not yet implemented"
   below for what showing a second one still needs.
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
  `wl_shm`, every `wl_output` (version 4 when offered, for its name — see
  "Outputs" below),
  `zwlr_screencopy_manager_v1`, `zwlr_virtual_pointer_manager_v1`, and
  `zwp_virtual_keyboard_manager_v1`. Fails loudly if any of the five
  required globals (compositor/seat/screencopy/vptr/vkbd) is missing.
  `ext_data_control_manager_v1` is bound too but is deliberately NOT
  required — a compositor without it still gives a usable desktop, just
  one whose clipboard does not cross. Starts a background dispatch thread
  (`wl_display_dispatch` loop) so screencopy's async frame-ready callbacks
  actually get delivered.
- **Outputs** (Droidtop/tracker#908) — `WaylandGlobals::outputs` holds every
  `wl_output` in bind order, with its name (`HEADLESS-1` under sway; only
  from wl_output v4), current mode and scale, and drops one on the
  registry's `global_remove`, stopping its capture. The first is the
  primary: `presentOutput("")`, `stopPresenting("")` and
  `setOutputSize("", ...)` mean it, which is what the unnamed Kotlin calls
  use. `setOutputSize` with a name goes to the wlr-output-management head of
  that name; the primary's goes to the head of its name, or the first head
  when it has none (`output_match.h`). All wanted sizes go out in one
  configuration, since a second one quoting the same serial would be
  cancelled.
- **Frame capture** (`presentOutput` → `startNextCapture` and the
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
  (scroll) requests, each followed by the required `frame()` call. One
  pointer, tied to no output, so sway maps absolute motion onto the box
  around every output in the layout: the primary while it is the only
  output, which is what the unnamed `injectPointerMotionAbsolute` relies on.
  For a named output, `HostBridge.injectPointerMotionAbsolute(outputName,
  ...)` places the point in that output's part of the layout
  (`OutputLayout.kt`, JVM-tested in `OutputLayoutTest`), from the heads'
  position, current mode, transform and scale, which the native client
  publishes at every wlr-output-management `done` (`outputPlacements()`,
  readable from any thread so motion never waits on the dispatch thread).
  A per-output pointer (`create_virtual_pointer_with_output`) was not used:
  `swaymsg reload` resets its mapping and sway has no output name to map it
  by again.
- **Clipboard, both directions** (`ext-data-control-v1`) — the compositor's
  `selection` event is read off a pipe on a worker thread and handed up to
  `HostBridge.onContainerClipboardText`; `offerClipboardText` claims the
  seat's selection with a fresh `ext_data_control_source_v1` and serves it
  from another worker. **Why this protocol and not `wl_data_device`:** the
  ordinary clipboard protocol only delivers a selection to the client
  holding keyboard focus on one of its own surfaces, and this module has no
  surface at all — it is a screencopy + virtual-input client by design. So
  `wl_data_device` is structurally unavailable here, not merely awkward.
  `ext-data-control-v1` exists for exactly this case (a privileged client
  observing and setting the selection without focus) and is the stabilised
  successor to `wlr-data-control-unstable-v1`; `vendor/sway` creates both
  globals unconditionally, so only the ext one is implemented — there is no
  runtime fallback to carry. Text MIME types only; 1 MiB cap, dropping
  rather than truncating; the primary (middle-click) selection is not
  bridged, since Android has no counterpart. Android-side policy — when a
  clipboard read is even permitted — lives in `ClipboardBridge` /
  `ClipboardSync`, and in `docs/SPEC.md` §6d.
- **Virtual keyboard** — key press/release requests. The protocol requires a
  valid XKB keymap be set before *any* key event is accepted; rather than
  cross-compiling `libxkbcommon` for Android just to generate one,
  `default_keymap.h` embeds a standard "us"/pc105 keymap generated once,
  on-host, via WSL's `libxkbcommon` (see that file's header comment) — no
  xkbcommon dependency on-device at all.
  Two virtual keyboards share the seat: the layout keyboard, whose keymap
  `setKeymap` replaces with one the container's own `xkbcli` compiled for the
  person's layout, and the text keyboard, which keeps the US map for keys
  Android derived from characters (`docs/SPEC.md` 6b "Keyboard layout").
- **Windows / task manager** (`wlr-foreign-toplevel-management-unstable-v1`,
  Droidtop/tracker#94) — `ToplevelState` tracks every toplevel the
  compositor reports (title, app id, activated/minimized/maximized/
  fullscreen), committed atomically on each handle's `done` event.
  `snapshotToplevels()` hands a copy to any thread; `activateToplevel`/
  `setToplevelMinimized`/`closeToplevel` run on the dispatch thread (like
  `setOutputSize`) since the handle they act on can be invalidated by a
  `closed` event arriving concurrently. Optional like `ext-data-control-v1`:
  absent means the taskbar's window list is just empty. `shell-desktop`'s
  `TaskbarWindowList` is the one consumer today, and it hides its minimize
  affordances while the session's compositor is sway: sway ignores
  set_minimized/unset_minimized outright while labwc implements them
  (Droidtop/tracker#145), and the protocol has no capability query to
  ask with. It also has no "move to another output" request — only
  activate/set_minimized/
  unset_minimized/close/set_fullscreen/set_rectangle — so that part of an
  earlier SPEC.md draft was corrected rather than implemented; see the
  single-output note below for what real "move to output" needs anyway.

### Known simplifications / not yet implemented

- **A second output is shown only when asked for.** Every output can be
  captured into its own `Surface` and take pointer input by name
  (Droidtop/tracker#908 D1a, D1b); the app's `DesktopSecondScreen` shows one
  on another display (D3, `HostBridgeInput`'s named
  `injectPointerMotionAbsolute` for its touch), but nothing calls it on its
  own until D5, and `toplevel`'s output_enter/output_leave are not tracked
  (which output a window is on is D4). An output's box is worked out on this side from its head, so for
  a scale that is not a multiple of 1/256 (the protocol's fixed point) it
  can be a logical pixel off the compositor's own.
- **CPU blit, not zero-copy.** Frames are copied pixel-by-pixel from the shm
  buffer to the `ANativeWindow`. Fine for a first correctness pass; will
  matter for real framerate once there's something live to measure against.
- **Coarse thread-safety.** Dispatch runs on one dedicated thread; input
  injection and `presentOutput`/`stopPresenting` are called from
  whatever thread the JNI caller uses. This relies on libwayland-client's
  documented guarantee that request marshaling is safe from other threads
  as long as only one thread ever calls `wl_display_dispatch` — that's the
  design here, but it hasn't been stress-tested.
- **Little global-removal handling.** An output that goes away is dropped
  and its capture stopped (its `Surface` keeps the last frame); the next
  output becomes the primary. Any other global going away is not handled;
  the compositor exiting is noticed as a lost connection.
- **Clipboard is text only.** Images and file lists on the container's
  selection are ignored rather than converted; the same applies in reverse.

See [docs/SPEC.md](../docs/SPEC.md) §11 for the broader open risks (sway
headless-backend performance, Wine's Wayland driver maturity, etc.) that
this module's implementation being "complete" doesn't resolve on its own —
those need a live compositor to actually test against.
