#!/usr/bin/env bash
# Packs software graphics for the x86_64 Wine guest: the Khronos Vulkan loader
# and Mesa's lavapipe ICD, and Mesa's xlib OpenGL (llvmpipe), with every library
# they need that the x86_64 guest libraries (build-scripts/x86_64-guest-libs)
# and Android do not already provide.
#
# Why this exists: DXVK needs a Vulkan driver that can present to the app's X
# server (VK_KHR_xlib_surface / VK_KHR_xcb_surface). On an x86_64 Android device
# nothing offers one: Android's own loader has only VK_KHR_android_surface, the
# device's Mesa HAL is an Android-platform build that cannot act as a Khronos
# ICD, and Vortek/Turnip exist only for arm64. Lavapipe renders on the CPU, so it
# is slow, but it runs on every x86_64 device, BlueStacks included.
#
# The binaries are Termux's x86_64 packages (vulkan-loader-generic,
# mesa-vulkan-icd-swrast and their dependencies, from packages.termux.dev), each
# checked against the SHA-256 the repository index publishes. The approach
# (Termux's loader and lavapipe, a dependency closure, an ICD manifest written on
# the device with an absolute library path) follows Bliss-Bass/GameNative-x64
# (scripts/provision-x86_64-vulkan.sh, docs/X86_64_VULKAN_PROVISIONING.md,
# GPL-3.0). usr/share/doc/x86_64-lavapipe/PACKAGES in the archive names every
# package and version it was made from.
#
# OpenGL (Droidtop/tracker#309): WineD3D's default renderer is OpenGL, and the
# x86_64 guest had none. Termux's own mesa package is a DRI GLX behind glvnd,
# which needs a GLX extension the app's X server does not have, so the libGL here
# is Mesa's xlib target (client-side GLX, XPutImage presentation, llvmpipe),
# built from Termux's recipe and patches by mesa-gl/ (MESA_GL_DEB is that
# package); its dependencies join the same closure.
#
# Usage: GUEST_LIBS=<x86_64-guest-libs.tzst> MESA_GL_DEB=<mesa .deb> build.sh <out.tzst>
set -euo pipefail

OUT=${1:?usage: GUEST_LIBS=<x86_64-guest-libs.tzst> MESA_GL_DEB=<mesa .deb> build.sh <out.tzst>}
GUEST_LIBS=${GUEST_LIBS:?set GUEST_LIBS to the x86_64-guest-libs.tzst this driver loads beside}
MESA_GL_DEB=${MESA_GL_DEB:?set MESA_GL_DEB to the xlib OpenGL package mesa-gl/ built}
WORK=${WORK:-$PWD/x86_64-lavapipe-build}
INDEX_URL=https://packages.termux.dev/apt/termux-main/dists/stable/main/binary-x86_64/Packages
POOL_URL=https://packages.termux.dev/apt/termux-main

rm -rf "${WORK:?}"
mkdir -p "$WORK/debs" "$WORK/unpack" "$WORK/guest" "$WORK/stage/usr/lib" "$WORK/stage/usr/share/doc/x86_64-lavapipe"

curl -fsSL --retry 5 --retry-delay 5 --retry-all-errors -o "$WORK/Packages" "$INDEX_URL"
tar -C "$WORK/guest" -I zstd -xf "$GUEST_LIBS"

# The xlib OpenGL package: its libraries go in as they are, and what it
# depends on joins the closure below.
mkdir -p "$WORK/unpack/mesa-gl"
(cd "$WORK/unpack/mesa-gl" && ar x "$(realpath "$MESA_GL_DEB")" && tar -xf data.tar.* && tar -xf control.tar.*)
gl_depends=$(sed -n 's/^Depends: //p' "$WORK/unpack/mesa-gl/control")
gl_version=$(sed -n 's/^Version: //p' "$WORK/unpack/mesa-gl/control")
find "$WORK/unpack/mesa-gl/data/data/com.termux/files/usr/lib" -maxdepth 1 \( -type f -o -type l \) -name 'libGL.so*' \
    -exec cp -a {} "$WORK/stage/usr/lib/" \;
# Termux drops version suffixes (its libGL's SONAME is libGL.so), while Wine
# opens libGL.so.1 by that name.
[[ -e "$WORK/stage/usr/lib/libGL.so.1" ]] || ln -s libGL.so "$WORK/stage/usr/lib/libGL.so.1"
printf 'mesa-xlib-gl %s https://www.mesa3d.org\n' "$gl_version" >> "$WORK/stage/usr/share/doc/x86_64-lavapipe/PACKAGES"

# The package closure, by the index's own Depends; prints "name version filename sha256".
python3 - "$WORK/Packages" "$gl_depends" > "$WORK/closure" <<'PY'
import re, sys
packages = {}
for block in open(sys.argv[1], encoding="utf-8").read().split("\n\n"):
    fields = {}
    for line in block.splitlines():
        if line and not line[0].isspace() and ":" in line:
            key, value = line.split(":", 1)
            fields[key] = value.strip()
    if "Package" in fields:
        packages[fields["Package"]] = fields
def names(depends):
    out = []
    for group in (depends or "").split(","):
        options = [re.sub(r"\s*\(.*\)", "", o).strip() for o in group.split("|")]
        options = [o for o in options if o]
        if not options:
            continue
        chosen = next((o for o in options if o in packages), None)
        if chosen is None:
            sys.exit("no package satisfies: " + group.strip())
        out.append(chosen)
    return out
seen, queue = [], ["vulkan-loader-generic", "mesa-vulkan-icd-swrast"] + names(sys.argv[2])
while queue:
    name = queue.pop(0)
    if name in seen:
        continue
    if name not in packages:
        sys.exit("not in the index: " + name)
    seen.append(name)
    queue.extend(names(packages[name].get("Depends")))
for name in seen:
    p = packages[name]
    print(name, p["Version"], p["Filename"], p["SHA256"], p.get("Homepage", "-"))
PY

while read -r name version filename sha homepage; do
    deb="$WORK/debs/$(basename "$filename")"
    curl -fsSL --retry 5 --retry-delay 5 --retry-all-errors -o "$deb" "$POOL_URL/$filename"
    echo "$sha  $deb" | sha256sum -c --quiet
    mkdir -p "$WORK/unpack/$name"
    (cd "$WORK/unpack/$name" && ar x "$deb" && tar -xf data.tar.*)
    lib="$WORK/unpack/$name/data/data/com.termux/files/usr/lib"
    if [[ -d "$lib" ]]; then
        find "$lib" -maxdepth 1 \( -type f -o -type l \) -name '*.so*' -exec cp -a {} "$WORK/stage/usr/lib/" \;
    fi
    # The ICD manifest names Termux's own path; the app rewrites
    # library_path to where it unpacked this archive (X86_64Graphics).
    icd="$WORK/unpack/$name/data/data/com.termux/files/usr/share/vulkan/icd.d"
    if [[ -d "$icd" ]]; then
        mkdir -p "$WORK/stage/usr/share/vulkan/icd.d"
        cp -a "$icd"/*.json "$WORK/stage/usr/share/vulkan/icd.d/"
    fi
    printf '%s %s %s\n' "$name" "$version" "$homepage" >> "$WORK/stage/usr/share/doc/x86_64-lavapipe/PACKAGES"
done < "$WORK/closure"

# What the device already has: the guest libraries (on the search path after
# this set) and Android's own libraries. A Termux copy of either would shadow
# the one the rest of the guest uses, so it is dropped.
system=" libc.so libm.so libdl.so liblog.so libandroid.so libnativewindow.so libsync.so "
provided=" $system $(cd "$WORK/guest/usr/lib" && ls | tr '\n' ' ') "
for f in "$WORK"/stage/usr/lib/*; do
    n=$(basename "$f")
    if [[ "$provided" == *" $n "* ]]; then rm -f "$f"; fi
done
# Static archives, linker scripts and anything not an ELF or a symlink to one.
find "$WORK/stage/usr/lib" -type f ! -name '*.so*' -delete
find "$WORK/stage/usr/lib" -xtype l -delete

# Every library must load: each NEEDED name must be here, in the guest set or
# in Android. A gap here is a dlopen failure on the device, so it fails the build.
missing=0
here=" $(cd "$WORK/stage/usr/lib" && ls | tr '\n' ' ') "
while IFS= read -r f; do
    desc=$(file -b "$f")
    case "$desc" in
        *"ELF 64-bit LSB shared object, x86-64"*) ;;
        *) echo "not an x86-64 shared object: $f: $desc" >&2; missing=1; continue ;;
    esac
    while IFS= read -r needed; do
        if [[ "$here" != *" $needed "* && "$provided" != *" $needed "* ]]; then
            echo "$(basename "$f") needs $needed, which nothing provides" >&2
            missing=1
        fi
    done < <(readelf -d "$f" | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p')
done < <(find "$WORK/stage/usr/lib" -type f -name '*.so*')
[[ $missing -eq 0 ]] || exit 1
[[ -f "$WORK/stage/usr/lib/libvulkan_lvp.so" ]] || { echo "no libvulkan_lvp.so in mesa-vulkan-icd-swrast" >&2; exit 1; }
[[ -e "$WORK/stage/usr/lib/libvulkan.so.1" ]] || { echo "no libvulkan.so.1 in vulkan-loader-generic" >&2; exit 1; }
[[ -f "$WORK/stage/usr/share/vulkan/icd.d/lvp_icd.x86_64.json" ]] || { echo "no lvp_icd.x86_64.json in mesa-vulkan-icd-swrast" >&2; exit 1; }
[[ -e "$WORK/stage/usr/lib/libGL.so.1" ]] || { echo "no libGL.so.1 in the xlib OpenGL package" >&2; exit 1; }

mkdir -p "$(dirname "$OUT")"
tar -C "$WORK/stage" -I 'zstd -19 -T0' -cf "$OUT" usr
echo "built $OUT"
cat "$WORK/stage/usr/share/doc/x86_64-lavapipe/PACKAGES"
du -sh "$WORK/stage" "$OUT"
