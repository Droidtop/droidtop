# plugin-sample-statustile

The sample plugin for droidtop's plugin host (docs/SPEC.md 12a): a
`status_tile` that does nothing real, so the install/approve/run/crash/
disable path can be exercised end to end without touching anything a
person cares about.

- `src/` — `StatusTilePlugin.kt`, implementing `dev.droidtop.pluginhost.DroidtopPlugin`.
- `manifest.template.json` — everything about the manifest except `payload`
  (filled in by `build.sh` once `classes.jar`'s real hash is known).
- `build.sh` — compiles, dexes, hashes, signs (with the real droidtop
  plugin origin key generated for this change; see
  `plugin-host/src/main/kotlin/dev/droidtop/pluginhost/BundleSignature.kt`)
  and packages `droidtop.sample-statustile.droidplugin.tar.xz`.

**Not run yet.** `build.sh` needs kotlinc, d8 and a compiled
`:plugin-host` classpath, none of which this change's session had without
running a local Gradle build (against this project's own "no local
build" rule). Building it is real, scoped follow-up work — either a small
CI job, or run by hand on droidtop-dev once `:plugin-host` has built at
least once. The rig item (`dq-plugins-01`,
`/root/coordination/device/QUEUE.md`) depends on that bundle existing.
