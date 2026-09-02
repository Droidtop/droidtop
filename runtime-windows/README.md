# runtime-windows

Windows compatibility, forked from [vendor/gamenative](../vendor/gamenative)
(GPL-3.0), which itself grafts Winlator's `com.winlator` runtime (Wine +
Box64 + DXVK/VKD3D) onto a Steam/Epic/GOG/Amazon library front-end.

We only want the runtime half. The library/front-end half is superseded by
`library-core` in this repo, which treats Wine sessions as one of several
equally first-class entry types alongside native APKs and Linux containers.

## What changes vs. upstream

Execution is upstream's own bionic model, kept rather than replaced: above
`targetSdk 28` Android refuses to `exec()` an extracted binary, so the guest
runs through `/system/bin/linker64` against the ImageFs root in app storage,
with no proot and no Linux container. That is why a Windows game needs no
root here — see `WineEngine`'s own doc comment and docs/SPEC.md §5b.

What is *not* kept is upstream's presentation layer: an Android SurfaceView
XServer per container. droidtop composes through `:host-bridge`. Upstream's
X server components are still started, headless, because Wine's X11 driver
has to connect to something; nothing renders their output yet, and wiring a
renderer to droidtop's own surface is separate, named work.

**Open risk, confirm before relying on this:** Wine's native Wayland driver
needs to be solid enough to use directly. If not, fall back to Xwayland
running inside the same container as a compatibility shim.

## Status

`WineEngine` is the backend-neutral entry point for running Windows
software; `BionicWineEngine` is the one implementation, and it needs no
root, so handheld and desktop mode use the same engine and the same prefix.
It drives gamenative's own `BionicProgramLauncherComponent`, so the real
box64/FEXCore environment and translator extraction come with it rather
than being re-derived here.

**The real `com.winlator` runtime is forked in wholesale as of this
session** (`src/main/java/com/winlator/`, 247 files, unmodified,
byte-for-byte from `vendor/gamenative`) plus its own real resource tree
(`src/main/res/`, 388 files) and the one subsystem it depends on outside
`com.winlator` itself (`src/main/java/app/gamenative/powercontrol/`, a
real, self-contained CPU/GPU performance-management layer — confirmed
zero Hilt/Room/Steam coupling before copying, unlike the rest of
`app.gamenative`). A handful of small, real, hand-written compatibility shims under
`src/main/java/app/gamenative/` (`BuildConfig.kt`, `PluviaApp.kt`,
`MainActivity.kt`, `PrefManager.kt`, `service/SteamService.kt`,
`utils/ContainerUtils.kt`, `utils/LsfgVkManager.kt`,
`utils/downloader/ContainerFilesDownloader.kt`,
`ui/screen/auth/EpicOAuthActivity.kt`, `events/AndroidEvent.kt`) satisfy
the real `app.gamenative.*` symbols forked-in `com.winlator` code actually
reads — each covers only the specific fields/methods a real call site
uses (confirmed by reading the call sites, not guessed), documented
per-file with what upstream's real version does and why it's stubbed
rather than forked. Four more (`data/ShooterModeConfig.kt`,
`data/TouchGestureConfig.kt`, `enums/Marker.kt`, `utils/MarkerUtils.kt`,
`SteamBootstrap.kt`) turned out to be genuinely self-contained — no
Hilt/Room/JavaSteam coupling — so those are forked in wholesale,
unmodified, same as `com.winlator` itself.

These shims are a stopgap to get this module compiling, not a
destination — droidtop will eventually want the real Steam/service layer
they stand in for (account login, library sync, the actual Gateway/CDN
calls). Revisit once droidtop has its own equivalent of that layer; until
then, treat every shim's "not wired up yet" as literal, not a finished
feature.

**Deliberately not forked in yet**: gamenative's own Application/DI/
database/Steam-networking/VR layer (Hilt, Room, JavaSteam, dynamic
feature modules) — confirmed via reading `com.winlator`'s actual imports
that none of it is needed for the runtime half this module wants today.
A much larger, separate fork pass once droidtop wants gamenative's own
Steam-library UI specifically.

**`runtime-windows` compiles cleanly** (confirmed via CI, not just
locally), and the fork is wired up rather than merely compiling: the
provisioning path drives upstream's `ContainerManager`/`ImageFsInstaller`/
launch-dependency machinery, and `BionicWineEngine` drives its guest
launcher. The native half of the vendored tree ships too — both `jniLibs`
roots are packaged, without which none of the above could run.

This module's Android `namespace` is `app.gamenative` (not droidtop's
usual `dev.droidtop.*`) specifically so the forked tree's own real
`import app.gamenative.R` references resolve via AGP's generated R
class without editing those files — the whole point of forking
unmodified is staying easily re-syncable against upstream.
