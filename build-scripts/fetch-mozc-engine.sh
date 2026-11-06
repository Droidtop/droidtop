#!/usr/bin/env bash
# Puts Mozc's engine library (Droidtop/tracker#903 H7b's prerelease) into
# input-japanese/mozc/jniLibs/, where input-japanese/build.gradle.kts reads
# it. The release and the archive's SHA-256 are input-japanese/mozc-engine.pin;
# a mismatch fails the build rather than packaging something else. The
# archive's LICENSE-mozc, credits-mozc.html and MOZC_VERSION land beside the
# libraries (NOTICE.md's Mozc entries are taken from credits-mozc.html).
# The libraries come unstripped; AGP strips them when it packages the app
# (:app:strip*DebugSymbols, with the NDK the android-setup action installs).
# The dictionary, mozc.data, is not here: the keyboard downloads it the first
# time Japanese is used (MozcData in :input-japanese).
set -eu
ROOT=$(cd "$(dirname "$0")/.." && pwd)
PIN="$ROOT/input-japanese/mozc-engine.pin"
DEST="$ROOT/input-japanese/mozc"
tag=$(sed -n 's/^tag=//p' "$PIN")
sha=$(sed -n 's/^sha256=//p' "$PIN")
: "${tag:?no tag in $PIN}" "${sha:?no sha256 in $PIN}"
if [ -f "$DEST/.pin" ] && [ "$(cat "$DEST/.pin")" = "$tag $sha" ]; then
    echo "Mozc engine $tag already in place"
    exit 0
fi
work=$(mktemp -d)
trap 'rm -rf -- "${work:?}"' EXIT
curl -fsSL --retry 5 --retry-delay 10 -o "$work/m.tar.zst" \
    "https://github.com/Droidtop/droidtop/releases/download/$tag/mozc-engine.tar.zst"
echo "$sha  $work/m.tar.zst" | sha256sum -c -
rm -rf -- "${DEST:?}"
mkdir -p "$DEST"
zstd -dc "$work/m.tar.zst" | tar -C "$DEST" -xf -
for abi in arm64-v8a x86_64; do
    [ -f "$DEST/jniLibs/$abi/libmozc.so" ] || { echo "no jniLibs/$abi/libmozc.so in $tag"; exit 1; }
done
echo "$tag $sha" > "$DEST/.pin"
find "$DEST" -maxdepth 3 -type f
