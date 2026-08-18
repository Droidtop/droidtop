# runtime-common

Shared types every runtime and shell module builds against: `Container`,
`ContainerRole` (PRIMARY vs SIBLING — see [docs/SPEC.md](../docs/SPEC.md#containers)),
`ContainerBackend` (DroidSpaces vs proot), and `DisplayOutput`/`WindowPlacement`
for the merge-by-default / pop-out-to-its-own-display model.

This module depends on nothing else in the repo. `runtime-windows`,
`runtime-linux-root`, `runtime-linux-noroot`, `library-core`, and both shells
depend on it. Keep it that way — it's the seam that lets the two container
backends and the Windows runtime be interchangeable from the callers' side.
