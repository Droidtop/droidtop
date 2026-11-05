#!/usr/bin/env bash
# Adds droidtop's Venus recipe (build.sh beside this script) to a checkout of termux-packages as its own package,
# packages/mesa-venus, beside Termux's packages/mesa, which stays as it is. The mesa-xlib-gl precedent
# (build-scripts/x86_64-lavapipe/mesa-gl/prepare.sh).
#
# Taken from Termux's packages/mesa: every patch (the bionic fixes Termux maintains) except
# 0005-virgl-socket-path.patch, replaced by droidtop's 0005-vtest-socket-from-env.patch. Not taken: Termux's recipe,
# its subpackages, its cmake wrapper (only its LLVM lookup needs it; Venus is built without LLVM).
#
# Usage: prepare.sh <termux-packages checkout>
set -euo pipefail

TP=${1:?usage: prepare.sh <termux-packages checkout>}
HERE=$(cd "$(dirname "$0")" && pwd)
MESA="$TP/packages/mesa"
OURS="$TP/packages/mesa-venus"

[[ -f "$MESA/build.sh" ]] || { echo "no packages/mesa in $TP" >&2; exit 1; }
theirs=$(sed -n 's/^TERMUX_PKG_VERSION="\{0,1\}\([^"]*\)"\{0,1\}$/\1/p' "$MESA/build.sh")
ours=$(sed -n 's/^TERMUX_PKG_VERSION="\{0,1\}\([^"]*\)"\{0,1\}$/\1/p' "$HERE/build.sh")
[[ "$theirs" == "$ours" ]] || { echo "termux-packages has Mesa $theirs, droidtop's recipe $ours" >&2; exit 1; }
[[ -f "$MESA/0005-virgl-socket-path.patch" ]] || { echo "termux-packages' Mesa has no 0005-virgl-socket-path.patch to replace" >&2; exit 1; }

mkdir -p "$OURS"
for patch in "$MESA"/*.patch; do
    [[ "$(basename "$patch")" == 0005-virgl-socket-path.patch ]] && continue
    cp "$patch" "$OURS/"
done
cp "$HERE/0005-vtest-socket-from-env.patch" "$HERE/build.sh" "$OURS/"
echo "packages/mesa-venus:"
ls "$OURS"
