# app

The application shell. Owns `DesktopSessionService` (primary container +
`HostBridge` lifecycle, kept alive independent of any one Activity) and
`MainActivity` (hosts whichever shell — `shell-default` or `shell-gamepad`
— the user has selected).

Depends on every other module; nothing depends on this one.
