# plugin-sample-statustile

The sample plugin for droidtop's plugin host (docs/SPEC.md 12a): a
`status_tile` that does nothing real, so the install/approve/run/crash/
disable path can be exercised end to end without touching anything a
person cares about.

- `src/` — `StatusTilePlugin.kt`, implementing `dev.droidtop.pluginhost.DroidtopPlugin`.
- `manifest.template.json` — everything about the manifest except `payload`
  (filled in by `build.sh` once `classes.jar`'s real hash is known).
- `build.sh` — compiles, dexes and hashes: produces `build/classes.jar` and
  `build/manifest.json`. Touches no private key, so it runs in CI (the
  "sample-plugin" job in `.github/workflows/android-build.yml`, which
  builds `:plugin-host` first for the classpath and uploads these two
  files, then signs them) as well as locally.
- `sign.sh` — the only script here that touches the real droidtop plugin
  origin key (see `plugin-host/src/main/kotlin/dev/droidtop/pluginhost/BundleSignature.kt`);
  signs `build/manifest.json` and packages
  `droidtop.sample-statustile.droidplugin.tar.xz`. CI runs it with the `PLUGIN_SIGNING_KEY` repo secret
  (optional `PLUGIN_SIGNING_CERT` becomes `origin.cert`); locally run it on
  droidtop-dev, where the private half lives. The key is never committed to this repo.

To produce an installable bundle: download the `sample-plugin` CI
artifact's `build/manifest.json` + `build/classes.jar` (or run `build.sh`
locally against a `:plugin-host:assembleDebug` classpath), then run
`PLUGIN_SIGNING_KEY=/root/coordination/keys/droidtop-plugins/droidtop-origin-private.pem ./sign.sh`.
## Contract 2 and views

This plugin declares contract version 2 in its manifest (`provides` for
`ui.status_tile` and `ui.settings`, no `capabilities`). It implements
`DroidtopPlugin.handle` for the settings view (`op: view` on `ui.settings`)
and for the status tile (`op: state` on `ui.status_tile`). It also runs a
sample job (`startJob` for capability `settings_rows`, args `call` holding
the v2 envelope) that reports progress in 10% steps over ~3 s and completes
with a `message`. The settings view is a JSON document (info row, progress
row, button) rendered by droidtop's own renderer (`PluginViews`).

Since 1.1.0 it also shows the plugin UI points of docs/plugin-api.md 3
(Droidtop/tracker#316): a Quick Menu panel (`ui.panel`, op `panel`, with a
button whose `hello` call asks droidtop for a toast through the broker,
`ui.toast` `show`, permission `overlay.toast`), rows on a game's page
(`ui.game_section`, op `section`, which names the game only when droidtop
handed its identity over under `library.read`) and a shelf of the person's
favourites on Home (`gaming.rows`, op `rows`, built from the
`context.library` droidtop sends with `library.read`).

The rig item (`dq-plugins-01`, `/root/coordination/device/QUEUE.md`) uses
the resulting `.tar.xz`.

## Access

It runs contained (docs/plugin-api.md 5.3): an isolated process of its own, with no
network, no files and no permissions. Its toast, its library shelf and its vault value
are all broker calls, each one gated by a permission its manifest declares. The plugin's
page in Settings shows "Contained", and Advanced > Containment check shows the wall.
