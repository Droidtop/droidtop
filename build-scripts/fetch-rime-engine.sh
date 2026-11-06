#!/usr/bin/env bash
# Puts RIME's engine library (Droidtop/tracker#903 H8a's prerelease) into
# input-chinese/rime/, where input-chinese/src/main/cpp/CMakeLists.txt links
# it: jniLibs/<abi>/librime.so for arm64-v8a and x86_64, and include/rime_api.h
# for droidtop's own JNI. The release and the archive's SHA-256 are
# input-chinese/rime-engine.pin; a mismatch fails the build rather than
# packaging something else. The archive's LICENSE-* files, NOTICE-ndk-toolchain
# and VERSION land beside them (NOTICE.md's RIME entries are taken from the
# licence files). Modelled on fetch-mozc-engine.sh.
# The libraries come unstripped; AGP strips them when it packages the app
# (:app:strip*DebugSymbols, with the NDK the android-setup action installs).
# The data, rime-data-luna.tar.gz, is not here: the keyboard downloads it the
# first time Chinese is used (RimeData in :input-chinese).
set -eu
ROOT=$(cd "$(dirname "$0")/.." && pwd)
PIN="$ROOT/input-chinese/rime-engine.pin"
DEST="$ROOT/input-chinese/rime"
tag=$(sed -n 's/^tag=//p' "$PIN")
sha=$(sed -n 's/^sha256=//p' "$PIN")
: "${tag:?no tag in $PIN}" "${sha:?no sha256 in $PIN}"
if [ -f "$DEST/.pin" ] && [ "$(cat "$DEST/.pin")" = "$tag $sha" ]; then
    echo "RIME engine $tag already in place"
    exit 0
fi
work=$(mktemp -d)
trap 'rm -rf -- "${work:?}"' EXIT
curl -fsSL --retry 5 --retry-delay 10 -o "$work/r.tar.zst" \
    "https://github.com/Droidtop/droidtop/releases/download/$tag/rime-engine.tar.zst"
echo "$sha  $work/r.tar.zst" | sha256sum -c -
# Only plain relative paths: the archive unpacks into DEST and nowhere else.
if zstd -dc "$work/r.tar.zst" | tar -tf - | grep -E '(^/|(^|/)\.\.(/|$))'; then
    echo "rime-engine.tar.zst has a path outside its directory"; exit 1
fi
rm -rf -- "${DEST:?}"
mkdir -p "$DEST"
zstd -dc "$work/r.tar.zst" | tar -C "$DEST" --no-same-owner -xf -
for abi in arm64-v8a x86_64; do
    [ -f "$DEST/jniLibs/$abi/librime.so" ] || { echo "no jniLibs/$abi/librime.so in $tag"; exit 1; }
done
[ -f "$DEST/include/rime_api.h" ] || { echo "no include/rime_api.h in $tag"; exit 1; }
echo "$tag $sha" > "$DEST/.pin"
find "$DEST" -maxdepth 3 -type f
