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
  files as an unsigned artifact) as well as locally.
- `sign.sh` — the only script here that touches the real droidtop plugin
  origin key (see `plugin-host/src/main/kotlin/dev/droidtop/pluginhost/BundleSignature.kt`);
  signs `build/manifest.json` and packages
  `droidtop.sample-statustile.droidplugin.tar.xz`. Run on droidtop-dev
  only, where the private half lives
  (`/root/coordination/keys/droidtop-plugins/droidtop-origin-private.pem`);
  never in CI, never committed to this repo.

To produce an installable bundle: download the `sample-plugin` CI
artifact's `build/manifest.json` + `build/classes.jar` (or run `build.sh`
locally against a `:plugin-host:assembleDebug` classpath), then run
`PLUGIN_SIGNING_KEY=/root/coordination/keys/droidtop-plugins/droidtop-origin-private.pem ./sign.sh`.
The rig item (`dq-plugins-01`, `/root/coordination/device/QUEUE.md`) uses
the resulting `.tar.xz`.
