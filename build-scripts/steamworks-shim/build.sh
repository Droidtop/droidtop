#!/usr/bin/env bash
# Packs the Steamworks support a Windows Steam game gets inside droidtop's Wine
# prefix (docs/SPEC.md 5b, "Steamworks in the prefix", Droidtop/tracker#310):
# gbe_fork's steamclient build (Detanup01/gbe_fork, LGPL-3.0) and its
# ColdClientLoader, unmodified, from the upstream release named below and
# checked against the SHA-256 recorded here.
#
# Why this one: a game's own steam_api(64).dll only starts when it finds a
# running Steam and loads that Steam's steamclient(64).dll. gbe_fork's
# steamclient answers it inside the prefix, with no Steam client, no sign-in of
# its own and no change to the game's files: the loader puts the registry keys
# steam_api reads in place, sets the app id and starts the game. droidtop
# writes its settings (who is playing, the language, which DLC, where saves go)
# beside it at each launch (SteamworksShim.kt).
#
# What goes in: steamclient.dll, steamclient64.dll, steamclient_loader_x86.exe,
# steamclient_loader_x64.exe, GameOverlayRenderer.dll and
# GameOverlayRenderer64.dll (some games look for them), upstream's readme for
# that build, and the licence. usr/share/doc/steamworks-shim/SOURCE names the
# release and its SHA-256.
#
# Usage: build.sh <out.tzst>
set -euo pipefail

OUT=${1:?usage: build.sh <out.tzst>}
WORK=${WORK:-$PWD/steamworks-shim-build}
REPO=Detanup01/gbe_fork
TAG=release-2026_09_16_2
ASSET=emu-win-release-vs22.7z
ASSET_SHA256=d311deadc2a8a8aed620fe66976646059388123587aa22d408f723c592fc9688
LICENSE_SHA256=e3a994d82e644b03a792a930f574002658412f62407f5fee083f2555c5f23118

rm -rf "${WORK:?}"
mkdir -p "$WORK/stage/steam" "$WORK/stage/usr/share/doc/steamworks-shim"

curl -fsSL --retry 5 --retry-delay 5 --retry-all-errors -o "$WORK/$ASSET" "https://github.com/$REPO/releases/download/$TAG/$ASSET"
echo "$ASSET_SHA256  $WORK/$ASSET" | sha256sum -c --quiet
curl -fsSL --retry 5 --retry-delay 5 --retry-all-errors -o "$WORK/LICENSE" "https://raw.githubusercontent.com/$REPO/$TAG/LICENSE"
echo "$LICENSE_SHA256  $WORK/LICENSE" | sha256sum -c --quiet

7z x -y -o"$WORK/unpack" "$WORK/$ASSET" >/dev/null
from="$WORK/unpack/release/steamclient_experimental"
for f in steamclient.dll steamclient64.dll steamclient_loader_x86.exe steamclient_loader_x64.exe \
    GameOverlayRenderer.dll GameOverlayRenderer64.dll; do
    [[ -f "$from/$f" ]] || { echo "no $f in $ASSET" >&2; exit 1; }
    cp "$from/$f" "$WORK/stage/steam/"
done
cp "$from/README.experimental_steamclient.md" "$WORK/stage/usr/share/doc/steamworks-shim/"
cp "$WORK/LICENSE" "$WORK/stage/usr/share/doc/steamworks-shim/LICENSE"
printf '%s %s %s sha256:%s\n' "$REPO" "$TAG" "$ASSET" "$ASSET_SHA256" > "$WORK/stage/usr/share/doc/steamworks-shim/SOURCE"

mkdir -p "$(dirname "$OUT")"
tar -C "$WORK/stage" -I 'zstd -19 -T0' -cf "$OUT" steam usr
echo "built $OUT"
du -sh "$WORK/stage" "$OUT"
