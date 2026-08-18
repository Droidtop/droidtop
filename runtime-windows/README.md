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

Stub — `WineSession.launch` isn't ported yet.
