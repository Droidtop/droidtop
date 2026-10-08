#!/usr/bin/env bash
# Adds droidtop's Mesa recipe (build.sh beside this script) to a checkout of
# termux-packages as its own package, packages/mesa-xlib-gl, beside Termux's
# packages/mesa, which stays as it is (other packages name its subpackages).
#
# Taken from Termux's packages/mesa: every patch (bionic fixes Termux
# maintains) except 0001-disable-multithreading-for-llvmpipe.patch, so llvmpipe
# keeps upstream's threads (LP_NUM_THREADS still sets them), and
# cmake-wrapper.in, which build.sh uses the way Termux's recipe does. Not
# taken: Termux's recipe itself, its subpackages, and the diffs it applies by
# hand.
#
# Usage: prepare.sh <termux-packages checkout>
set -euo pipefail

TP=${1:?usage: prepare.sh <termux-packages checkout>}
HERE=$(cd "$(dirname "$0")" && pwd)
MESA="$TP/packages/mesa"
OURS="$TP/packages/mesa-xlib-gl"

[[ -f "$MESA/build.sh" ]] || { echo "no packages/mesa in $TP" >&2; exit 1; }
# The recipe pins a Mesa version; Termux's patches are for theirs, so the two must agree.
theirs=$(sed -n 's/^TERMUX_PKG_VERSION="\{0,1\}\([^"]*\)"\{0,1\}$/\1/p' "$MESA/build.sh")
ours=$(sed -n 's/^TERMUX_PKG_VERSION="\{0,1\}\([^"]*\)"\{0,1\}$/\1/p' "$HERE/build.sh")
[[ "$theirs" == "$ours" ]] || { echo "termux-packages has Mesa $theirs, droidtop's recipe $ours" >&2; exit 1; }

mkdir -p "$OURS"
for patch in "$MESA"/*.patch; do
    [[ "$(basename "$patch")" == 0001-disable-multithreading-for-llvmpipe.patch ]] && continue
    cp "$patch" "$OURS/"
done
cp "$MESA/cmake-wrapper.in" "$OURS/"
cp "$HERE/build.sh" "$OURS/build.sh"
echo "packages/mesa-xlib-gl:"
ls "$OURS"
