# runtime-windows

Windows compatibility, forked from [vendor/gamenative](../vendor/gamenative)
(GPL-3.0), which itself grafts Winlator's `com.winlator` runtime (Wine +
Box64 + DXVK/VKD3D) onto a Steam/Epic/GOG/Amazon library front-end.

We only want the runtime half. The library/front-end half is superseded by
`library-core` in this repo, which treats Wine sessions as one of several
equally first-class entry types alongside native APKs and Linux containers.

## What changes vs. upstream

Upstream Winlator/GameNative instantiate an isolated Android SurfaceView
XServer per container. We remove that entirely: a `WineSession` here is just
a Wine process pointed at whatever container's Wayland socket it's supposed
to render into (primary, for the merged desktop; a sibling, if it's been
popped out to its own display — see `runtime-common`'s `WindowPlacement`).

**Open risk, confirm before relying on this:** Wine's native Wayland driver
needs to be solid enough to use directly. If not, fall back to Xwayland
running inside the same container as a compatibility shim — still no custom
Android-side XServer either way.

## Status

`WineSession.launch` is still a bare `wine <executable>` invocation (no
Box64/FEXCore wrapper selection, no DXVK/VKD3D env setup) — see that
class's own doc comment for exactly what's real vs. stubbed.

**The real `com.winlator` runtime is forked in wholesale as of this
session** (`src/main/java/com/winlator/`, 247 files, unmodified,
byte-for-byte from `vendor/gamenative`) plus its own real resource tree
(`src/main/res/`, 388 files) and the one subsystem it depends on outside
`com.winlator` itself (`src/main/java/app/gamenative/powercontrol/`, a
real, self-contained CPU/GPU performance-management layer — confirmed
zero Hilt/Room/Steam coupling before copying, unlike the rest of
`app.gamenative`). Two small, real, hand-written compatibility shims
(`app/gamenative/BuildConfig.kt`, `app/gamenative/PluviaApp.kt`) satisfy
the only two things forked-in code actually reads from `app.gamenative`
beyond `powercontrol` and its own generated `R` class — see each shim's
own doc comment.

**Deliberately not forked in**: gamenative's own Application/DI/database/
Steam-networking/VR layer (Hilt, Room, JavaSteam, dynamic feature
modules) — confirmed via reading `com.winlator`'s actual imports that
none of it is needed for the runtime half this module wants. A much
larger, separate fork pass if droidtop ever wants gamenative's own
Steam-library UI specifically, not attempted here.

**Not wired up yet, by design** — the fork exists as real, compiling
(or converging toward compiling — this was a large first pass, iterated
against real CI failures) source, not yet called from `WineSession` or
any droidtop shell. Bringing the code in and actually using it are
deliberately separate steps.

This module's Android `namespace` is `app.gamenative` (not droidtop's
usual `dev.droidtop.*`) specifically so the forked tree's own real
`import app.gamenative.R` references resolve via AGP's generated R
class without editing those files — the whole point of forking
unmodified is staying easily re-syncable against upstream.
