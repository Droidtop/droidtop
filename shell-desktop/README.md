# shell-desktop

Optional desktop-style shell — taskbar + start menu chrome wrapped around the
primary container's compositor output. Reads from the same
`dev.droidtop.library.Library` as `:shell-default`/`:shell-gamepad`, and
presents the live desktop via `:host-bridge`'s `HostBridge` (see
`docs/SPEC.md` §2/§4 for the Qubes-style split this depends on).

## Status

**UI chrome is real: taskbar, start menu (library list, launches entries),
clock, system tray, the Terminal entry, and the `SurfaceView` viewport that
`HostBridge.presentOutput` targets — which is also the input surface, driving
`:input-seat`'s `DesktopInputRouter` (see `docs/SPEC.md` §6b).**
`dev.droidtop.app.DesktopSessionService` does real orchestration now and
`:app` passes its live `HostBridge` + `DisplayOutput` through, so the
Idle/Connecting/Failed/Connected states this shell renders are the real
session's, not a placeholder.

The Terminal entry opens a real shell in the primary container by `exec`-ing
a terminal application into the shared compositor — not an Android-side
terminal view. `runtime-common`'s `ContainerTerminal` carries the argument
for that choice, and `docs/SPEC.md` §3d records it. The button is absent,
rather than present-and-disabled, when there is no live session: a Terminal
button that cannot open a terminal misrepresents what the desktop can do.

**What is still not proven:** none of it has been run against a live
compositor on real hardware.
