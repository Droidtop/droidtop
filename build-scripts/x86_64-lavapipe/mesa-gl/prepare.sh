#!/usr/bin/env bash
# Puts droidtop's Mesa recipe (build.sh beside this script) in place of
# termux-packages' own packages/mesa recipe in a checkout of termux-packages.
#
# Kept from termux-packages: every patch (bionic fixes Termux maintains) except
# 0001-disable-multithreading-for-llvmpipe.patch, so llvmpipe keeps upstream's
# threads (LP_NUM_THREADS still sets them); and cmake-wrapper.in, which
# build.sh uses the way Termux's recipe does. Dropped: Termux's recipe itself,
# its subpackages (headers, Vulkan and OpenCL ICDs this build does not make),
# and the host-build diff it applies by hand.
#
# Usage: prepare.sh <termux-packages checkout>
set -euo pipefail

TP=${1:?usage: prepare.sh <termux-packages checkout>}
HERE=$(cd "$(dirname "$0")" && pwd)
PKG="$TP/packages/mesa"

[[ -f "$PKG/build.sh" ]] || { echo "no packages/mesa in $TP" >&2; exit 1; }
# The recipe pins a Mesa version; Termux's patches are for theirs, so the two must agree.
theirs=$(sed -n 's/^TERMUX_PKG_VERSION="\{0,1\}\([^"]*\)"\{0,1\}$/\1/p' "$PKG/build.sh")
ours=$(sed -n 's/^TERMUX_PKG_VERSION="\{0,1\}\([^"]*\)"\{0,1\}$/\1/p' "$HERE/build.sh")
[[ "$theirs" == "$ours" ]] || { echo "termux-packages has Mesa $theirs, droidtop's recipe $ours" >&2; exit 1; }

rm -f -- "${PKG:?}/0001-disable-multithreading-for-llvmpipe.patch"
rm -f -- "${PKG:?}"/*.subpackage.sh
rm -f -- "${PKG:?}"/*.beforehostbuild "${PKG:?}"/*.diff
cp "$HERE/build.sh" "$PKG/build.sh"
echo "packages/mesa now builds droidtop's xlib OpenGL:"
ls "$PKG"
