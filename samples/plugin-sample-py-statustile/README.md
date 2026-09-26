# plugin-sample-py-statustile

The `python`-kind sample plugin for droidtop's plugin host (docs/SPEC.md
12a): a `status_tile` that does nothing real, the Python analogue of
`samples/plugin-sample-statustile`, exercising install/approve/run/crash/
disable end to end through the embedded-CPython path instead of a
DexClassLoader.

- `src/plugin.py` -- the whole plugin. Implements `on_load`/`invoke`/
  `on_unload` as plain module-level functions (see the file's own
  docstring for the exact contract with `PythonBridge`/`PythonDroidtopPlugin`).
  Standard library only (`json`) -- a python-kind plugin can rely on
  nothing beyond what `PythonRuntimeManager`'s downloaded runtime carries.
- `manifest.template.json` -- everything about the manifest except
  `payload` (filled in by `build.sh` once `plugin.py`'s real hash is
  known). No `entryClass`, no native `.so` payload, so `abis` stays empty.
- `build.sh` -- hashes `src/plugin.py` and writes `build/manifest.json`.
  No compiler needed (a python-kind plugin's payload IS its source), so
  this runs anywhere `python3` and `sha256sum` exist, including plain CI
  with no Android SDK/NDK/kotlinc setup -- the "sample-plugin-python" job
  in `.github/workflows/android-build.yml` runs exactly this.
- `sign.sh` -- the only script here that touches the real droidtop plugin
  origin key; signs `build/manifest.json` and packages
  `droidtop.sample-py-statustile.droidplugin.tar.xz`. Run on droidtop-dev
  only (`/root/coordination/keys/droidtop-plugins/droidtop-origin-private.pem`);
  never in CI, never committed to this repo.

Installing this bundle also needs the Python runtime itself downloaded
first (Settings -> App integrations -> Plugins -> "Download Python
runtime", `PythonRuntimeManager`) -- this plugin's own approval/enable
does not trigger that download; the runtime is a separate, explicit,
progress-shown action by design (docs/SPEC.md 12a).
