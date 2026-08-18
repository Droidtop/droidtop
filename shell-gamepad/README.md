# shell-gamepad

Optional, toggleable gamepad-console-style launcher shell — a UX layer some
users will want and others definitely won't, so it must stay an alternative
to `shell-default`, never the required path. Reads from the exact same
`dev.droidtop.library.Library` as `shell-default`.

## Design references (UX only, not fork targets)

- **Playnite** — fullscreen controller-navigable mode, plugin-per-source
  library aggregation. `library-core` already follows this model.
- **AOSP Leanback** / **FLauncher** (Flutter) — best available OSS bases for
  the D-pad-navigable shell chrome itself; neither has library-scanning
  logic, which is fine since that's `library-core`'s job, not this module's.
- Daijishō, Beacon, ES-DE Android — closed-source or paid; useful only as
  interaction-pattern references, not forkable.

## Status

Empty — intentionally last in build order. Don't start this until
`library-core`'s `LibraryProvider` model has at least one working
implementation to build a real UI against.
