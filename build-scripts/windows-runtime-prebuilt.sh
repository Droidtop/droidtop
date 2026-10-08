#!/usr/bin/env bash
# Packs the Windows runtime's prebuilt binaries out of a checkout of droidtop's
# GameNative fork (Droidtop/gamenative-tux) into one archive that droidtop
# re-hosts as a release asset (docs/SPEC.md 9, "The runtime's prebuilt
# binaries"), the way build-toolchains re-hosts musl.cc's toolchains. Nothing
# is rebuilt or modified; the files are copied as the fork carries them.
#
#   jniLibs/arm64-v8a/  GameNative's arm64 native libraries (its src/main and
#                       src/modern sets). Some have no source anywhere
#                       (libvortekrenderer, the Adreno hook libraries); the
#                       x86_64 set is built from source by
#                       runtime-windows/native and must hold the same names.
#   assets/             the payloads the runtime reads with getAssets() and
#                       has no download for: box64/FEXCore/WowBox64 builds,
#                       input DLLs, redirect libraries, the arm64 PulseAudio
#                       modules, the JSON lists, and the modern flavour's
#                       libredirect-bionic-wx.so.
#
# Left out, because droidtop's runtime never loads them: the dxwrapper
# payloads (every entry in dxwrapper_download.json is fetched on demand from
# downloads.gamenative.app), the Steam-only steampipe, steaminput and
# steam_regions.json, LSFG's layer and assets, the Quest OpenXR loader, the
# SteamBootstrap program and libpatchelf (nothing calls PatchElf).
#
# usage: windows-runtime-prebuilt.sh <fork checkout> <out.tar.zst>
set -eu
FORK=${1:?fork checkout}
OUT=${2:?output archive}
SRC="$FORK/app/src"
STAGE=$(mktemp -d)
trap 'rm -rf -- "${STAGE:?}"' EXIT

mkdir -p "$STAGE/jniLibs/arm64-v8a" "$STAGE/assets"
for dir in "$SRC/main/jniLibs/arm64-v8a" "$SRC/modern/jniLibs/arm64-v8a"; do
    for lib in "$dir"/*.so; do
        case "$(basename "$lib")" in
            liblsfg-vk-layer.so|libopenxr_loader.so|libsteambootstrap.so|libpatchelf.so) continue ;;
        esac
        cp "$lib" "$STAGE/jniLibs/arm64-v8a/"
    done
done
for entry in "$SRC/main/assets"/* "$SRC/modern/assets"/*; do
    case "$(basename "$entry")" in
        dxwrapper|steampipe|steaminput|steam_regions.json|lsfg_vk) continue ;;
    esac
    cp -r "$entry" "$STAGE/assets/"
done

mkdir -p "$(dirname "$OUT")"
tar -C "$STAGE" -cf - jniLibs assets | zstd -19 -T0 -q -o "$OUT"
ls -l "$OUT"
