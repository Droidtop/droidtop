# Mozc's protocol, vendored

`protocol/` holds four files of google/mozc's `src/protocol`, copied unchanged (their BSD-3 headers intact) from commit
`0136c35782536cb43d710575c311cd4185982738` (Mozc 3.35.6354), the commit `libmozc.so` was built from
(`../../../mozc-engine.pin`, Droidtop/tracker#903 H7b):

| File | Git blob at that commit | Why |
| --- | --- | --- |
| `protocol/commands.proto` | `507c991a86122700bd2b12a1b4e6a6aac9891bf1` | `Command`, `Input`, `Output`: what `MozcJni.evalCommand` takes and returns |
| `protocol/candidate_window.proto` | `5e0979f98c783098206bf948dd95f8c133c30477` | imported by `commands.proto` (candidates and their annotations) |
| `protocol/config.proto` | `7d735374e40b2713450df98a7797d8c0ccd15161` | imported by `commands.proto` |
| `protocol/engine_builder.proto` | `3d97e1501f1c7ba3e7eadd46b954cb7c62379400` | imported by `commands.proto` |

They keep the directory `protocol/` because `commands.proto` imports them by that path. The build generates them as
protobuf lite Java (`input-japanese/build.gradle.kts`); their `java_package` is Mozc's own,
`org.mozc.android.inputmethod.japanese.protobuf`. The library and these files must come from the same commit: when the
pin moves to another Mozc release, copy the four files again from its commit and update this table.
