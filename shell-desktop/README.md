# shell-desktop

Optional desktop-style shell — taskbar + start menu chrome wrapped around the
primary container's compositor output. Reads from the same
`dev.droidtop.library.Library` as `:shell-default`/`:shell-gamepad`, and
presents the live desktop via `:host-bridge`'s `HostBridge` (see
`docs/SPEC.md` §2/§4 for the Qubes-style split this depends on).

## Status

**UI chrome is real: taskbar, start menu (library list, launches entries),
clock, and the `SurfaceView` viewport that `HostBridge.presentOutput`
targets.** What's NOT real yet: `dev.droidtop.app.DesktopSessionService`
(the thing responsible for actually creating the primary container and
connecting a `HostBridge` to its Wayland socket) is still a TODO stub, so
`:app` currently passes `hostBridge = null` / `primaryOutput = null` into
`DesktopShell` — you'll see the "desktop session not started" placeholder,
not a live desktop, until that's implemented. The moment
`DesktopSessionService` produces a real `HostBridge` + `DisplayOutput`, wiring
them into this composable's existing parameters is the only change needed
here; no reachitecture required.
