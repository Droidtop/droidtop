# Plugin contributions to launcher surfaces — scope only

Blocked on agent `plugins` §12a manifest/API landing. This document is the
seam description: how plugin-fed launcher surfaces (status tiles, search
providers, app actions, launcher widgets) reach the launcher once the
§12a manifest, `plugin-api/` binder (`IPluginRuntime`/`IPluginRuntimeCallback`),
`PluginCapability`, and `PluginBundleInstaller` are real.

No plugin surface is built here. The registry/catalog mechanism below
is what a future change (agent `plugins`, once §12a lands) will wire.

## What reaches the launcher

Per SPEC.md launcher-parity (§4, launcher feature survey), the launcher
mode (`:shell-default`, stock Murine/Launcher3) and the gaming shell
(`:shell-gamepad`) each need surfaces that plugins can feed, without
the launcher knowing plugins directly.

The plugin-declared capabilities that touch launcher surfaces (`PluginCapability`,
`plugin-host/src/main/kotlin/dev/droidtop/pluginhost/PluginCapability.kt`):

- `status_tile` — a quick-surface tile (network, VPN, reading) refreshed on
droidtop's own schedule, never a plugin-owned loop.
- `library_action` — an action on a game's entry (`EntryDetailScreen`),
plugin analogue of `open_with`.
- `settings_rows` — rows in droidtop's settings style (`CatalogScreen`),
never plugin-drawn UI.
- `app_status` — status/actions for one installed app the plugin manages,
distinct from a tile (`status_tile`) or per-game row.

Capabilities that stay out of the launcher (`acquire_content`,
`metadata_source`) stay out of this scope; they feed the library, not
launcher chrome.

## The registry pattern (seam, not built)

SPEC.md launcher-parity (§4, lines 559-579) already names the mechanism:

> Once §12a's plugin API exists, a plugin-contributed status tile, search
> provider, app action or widget reaches Launcher mode through the same
> catalog pattern `DroidtopWideSettings`/`SettingsScreenRegistry` already
> uses for Global settings: a registry `:app` (or a new small module both
> `:shell-default` and the Gaming shell can see) populates from installed
> plugins, and each surface (the QSB's search results, a home-screen widget
> slot, a long-press app action) reads that registry rather than knowing
> about plugins directly.

That is the scope: the registry is the only interface between plugin
capabilities and launcher surfaces. No plugin surface code is written
in this change.

## How a plugin reaches the registry

From `plugin-api/` (`plugin-host/src/main/aidl/dev/droidtop/pluginhost/`):

1. Manifest (`PluginManifest`) declares the subset of `PluginCapability`
it implements, plus `pluginKind` (`native_bundle` built; `python` and
`flutter_embed` documented, refused at activation), `boundServiceTargets`,
and optional `requestsRoot`.
2. `PluginBundleInstaller` validates the signed bundle (`manifest.json`,
`manifest.sig`, payload hashes, ABI check for `.so`, origin key pinning,
namespace protection) before any code loads.
3. `PluginStore` holds approval (`PluginRecord.approved`, `archiveDigest`,
`rootApproved`). Approval is per-archive-digest: any manifest change starts
back at `PENDING`.
4. `PluginRuntimeService` (`IPluginRuntime`) loads the plugin into the
isolated `:pluginhost` process. `invoke()` is bounded by the per-call
watchdog; `startJob()`/`cancelJob()` drive long-running actions.
5. The launcher registry (not built) reads declared capabilities from the
approved plugin's manifest, never from plugin code directly, and surfaces
them through the existing catalog/registry paths.

## What this scope does NOT include

- No plugin-facing API beyond what `PluginCapability` and `IPluginRuntime`
already declare.
- No registry implementation in `:shell-default` or `:shell-gamepad`.
- No plugin widget provider (`AppWidgetProvider`) wired to the registry.
- No search-provider (`SearchProvider`) extension for plugin results.
- No plugin-built UI: settings rows render through `CatalogScreen`, tiles
through the existing quick-surface registry, actions through the long-press
menu model.

## Evidence this is scope, not an omission

- SPEC.md §12a (line 8610+) is rebuilt (2026-09-25) for droidtop-own install,
not Enginehost subplugins; the binder contract (`IPluginRuntime.aidl`) and
`PluginCrashPolicy` are in place; the sample plugin (`samples/plugin-sample-statustile`)
exists but its `build.sh` is explicitly unrun (needs `:plugin-host` classpath
from a CI build, against the "no local build" rule).
- SPEC.md launcher-parity (§4, lines 557-579) calls the plugin surface
"not yet buildable" and names the registry as the future seam.
- `docs/plugin-catalog.md` (this file) records the scope only; `docs/SPEC.md`
receives the design decision line below.
