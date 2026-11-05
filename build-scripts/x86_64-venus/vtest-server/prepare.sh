#!/usr/bin/env bash
# Adds droidtop's vtest server recipe to a checkout of termux-packages as packages/virgl-vtest-droidtop: Termux's own
# packages/virglrenderer-android recipe and patches (virglrenderer 1.3.0 with Venus, built with the NDK in its host
# step, patched to replay Venus on Android's own Vulkan, 0011-use-Android-Vulkan), changed in three ways only:
#
# - no angle-android dependency: without its --angle-* flags the server's libepoxy opens Android's own libEGL.so and
#   libGLESv2.so (0003), so droidtop ships no ANGLE;
# - virglrenderer and libepoxy are static (-Ddefault_library=static), so the server is one executable. droidtop
#   already ships a libvirglrenderer.so (GameNative's older GL server, VirGLRendererComponent) and a second one with
#   the same name could not sit beside it;
# - the executable is kept as libvirgl_vtest.so, the name it ships under in the APK (an executable runs from
#   nativeLibraryDir only, and only lib*.so files are extracted there).
#
# Its socket comes from --socket-path on the command line. Termux's default under its own prefix (0007) is never used.
#
# Usage: prepare.sh <termux-packages checkout>
set -euo pipefail

TP=${1:?usage: prepare.sh <termux-packages checkout>}
THEIRS="$TP/packages/virglrenderer-android"
OURS="$TP/packages/virgl-vtest-droidtop"

[[ -f "$THEIRS/build.sh" ]] || { echo "no packages/virglrenderer-android in $TP" >&2; exit 1; }
[[ -f "$THEIRS/0011-use-Android-Vulkan.patch.beforehostbuild" ]] || { echo "virglrenderer-android has no Android Vulkan patch (0011)" >&2; exit 1; }
grep -q -- '-Dvenus=true' "$THEIRS/build.sh" || { echo "virglrenderer-android is not built with Venus" >&2; exit 1; }

mkdir -p "$OURS"
cp "$THEIRS"/*.beforehostbuild "$THEIRS"/COPYING-* "$OURS/"
sed -e 's/^TERMUX_PKG_DEPENDS="angle-android"$/TERMUX_PKG_DEPENDS=""/' \
    -e 's|--libdir lib \\|--libdir lib -Ddefault_library=static \\|' \
    -e 's|\$TERMUX_PREFIX/bin/virgl_test_server_android|$TERMUX_PREFIX/lib/libvirgl_vtest.so|' \
    -e 's|^TERMUX_PKG_DESCRIPTION=.*|TERMUX_PKG_DESCRIPTION="virglrenderer vtest server with Venus on Android Vulkan, for droidtop (Droidtop/tracker#608)"|' \
    "$THEIRS/build.sh" > "$OURS/build.sh"
# Every change must have landed: a changed upstream recipe fails here, not in a half-built package.
grep -q '^TERMUX_PKG_DEPENDS=""$' "$OURS/build.sh"
[[ $(grep -c -- '-Ddefault_library=static' "$OURS/build.sh") -eq 2 ]]
grep -q 'lib/libvirgl_vtest.so' "$OURS/build.sh"
echo "packages/virgl-vtest-droidtop:"
ls "$OURS"
