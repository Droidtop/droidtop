# Upstream-unused-reference

Real upstream source (from Murine Launcher / AOSP Launcher3), **not part of
droidtop's actual build** — not included in `settings.gradle.kts`, not
referenced by `shell-default/build.gradle`'s `sourceSets`, not compiled by
anything. Kept here, clearly separated from live source, purely as
reference for future work.

- **`go/`** — the "Android Go" flavor variant. Irrelevant to droidtop's
  target hardware (a real handheld, not a Go-edition low-memory device).
- **`quickstep/`** — recents-animation/quickstep support. Excluded because
  it needs system-signature permissions no non-privileged app can hold —
  confirmed by real compile errors (local reimplementations of AOSP's
  internal Transitions-framework classes needing package-private
  `android.annotation` visibility only available inside a real platform
  source tree compile), not assumed. Worth revisiting only if droidtop
  ever targets a privileged/system-signed build.

See `shell-default/README.md` and `docs/SPEC.md` §7 for the full picture
of what's actually built vs. reference-only in this fork.
