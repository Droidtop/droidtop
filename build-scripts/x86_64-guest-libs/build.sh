#!/usr/bin/env bash
# Builds the x86_64 guest libraries an x86_64 Android device needs to run the
# bionic x86_64 Wine natively (no box64), and packs them as one .tzst.
#
# Why this exists: the base system GameNative publishes (imagefs_bionic.txz) is
# an aarch64 userland. On arm64, box64 runs the x86_64 Wine and maps its X11,
# freetype and fontconfig calls onto those aarch64 libraries. On an x86_64
# device Wine runs directly, so it needs those libraries built for x86_64. They
# go into their own directory beside the image (X86_64GuestLibs.kt), and the
# aarch64 image stays installed for its data (fonts, shared files, extras).
#
# The libraries are built from upstream sources, not taken from Termux: Termux's
# builds hard-code /data/data/com.termux/files/usr (the X socket directory among
# others), which is what the closed aarch64 libredirect papers over and which
# nothing redirects on x86_64. These builds read their paths from the
# environment the launcher sets (DISPLAY as an absolute socket path,
# FONTCONFIG_FILE, XLOCALEDIR).
#
# The library list and configure flags follow Bliss-Bass/GameNative-x64
# (scripts/build-x86_64-bionic-libs.sh, GPL-3.0), which runs the same Wine on
# x86_64 Android tablets. Added here: libandroid-sysvshm (SysV shm for MIT-SHM,
# from Droidtop/proton-wine-tux android/android_sysvshm), exec-redirect
# (exec-redirect.c beside this file) and SDL2, which evshim (the controller
# shim preloaded into Wine) and winebus.sys's SDL backend dlopen as
# libSDL2-2.0.so.0; on arm64 it comes from the aarch64 image. And GnuTLS with
# GMP and Nettle: Wine's bcrypt, crypt32 and secur32 dlopen libgnutls for every
# public-key operation (certificate chains, TLS, signatures). Without it Wine
# logs "failed to load libgnutls, no support for encryption" and a game whose
# start-up checks a certificate gets an error where Windows succeeds.
#
# Usage: NDK_ROOT=<ndk> SYSVSHM_SRC=<dir with android_sysvshm.c> build.sh <out.tzst>
set -euo pipefail

OUT=${1:?usage: build.sh <out.tzst>}
HERE="$(cd "$(dirname "$0")" && pwd)"
NDK_ROOT=${NDK_ROOT:?set NDK_ROOT}
SYSVSHM_SRC=${SYSVSHM_SRC:?set SYSVSHM_SRC}
WORK=${WORK:-$PWD/x86_64-guest-libs-build}
SRC="$WORK/src"
PREFIX="$WORK/root/usr"
STAGE="$WORK/stage"

API=26
HOST=x86_64-linux-android
TOOLCHAIN="$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin"
export CC="$TOOLCHAIN/${HOST}${API}-clang"
export CXX="$TOOLCHAIN/${HOST}${API}-clang++"
export AR="$TOOLCHAIN/llvm-ar"
export RANLIB="$TOOLCHAIN/llvm-ranlib"
export STRIP="$TOOLCHAIN/llvm-strip"
export CFLAGS="-O2 -fPIC"
export CPPFLAGS="-I$PREFIX/include"
# 16 KB pages: the same flag proton-wine-tux links Wine with.
export LDFLAGS="-L$PREFIX/lib -pthread -Wl,-z,max-page-size=16384"
# Only this prefix: the runner's own .pc files describe glibc libraries.
export PKG_CONFIG_LIBDIR="$PREFIX/lib/pkgconfig:$PREFIX/share/pkgconfig"
export PKG_CONFIG_PATH="$PKG_CONFIG_LIBDIR"
export ACLOCAL_PATH="$PREFIX/share/aclocal"

mkdir -p "$SRC" "$PREFIX/lib" "$PREFIX/include"

fetch() {
    local dest="$1"; shift
    [[ -f "$SRC/$dest" ]] && return 0
    local url
    for url in "$@"; do
        echo "fetch $url"
        # freedesktop.org answers bursts from shared CI ranges with HTTP 418;
        # --retry-all-errors retries those too.
        curl -fsSL --retry 5 --retry-delay 5 --retry-all-errors -o "$SRC/$dest" "$url" && return 0
    done
    echo "could not fetch $dest" >&2
    return 1
}

unpack() {
    local archive="$1"
    local dir="${archive%%.tar.*}"
    rm -rf "${SRC:?}/$dir"
    tar -C "$SRC" -xf "$SRC/$archive"
    echo "$SRC/$dir"
}

# Header/protocol packages configure with the runner's compiler (they install
# only data); everything else cross-compiles.
autotools() {
    local archive="$1" url="$2"; shift 2
    fetch "$archive" "$url"
    local dir; dir=$(unpack "$archive")
    (
        cd "$dir"
        if [[ " $* " == *" --host="* ]]; then
            ./configure --prefix="$PREFIX" "$@"
        else
            CC=gcc CXX=g++ AR=ar RANLIB=ranlib ./configure --prefix="$PREFIX" "$@"
        fi
        # bionic has pthreads in libc; there is no libpthread to link.
        find . -name Makefile -exec sed -i 's/-lpthread/-pthread/g' {} +
        make -j"$(nproc)"
        make install
    )
}

cross=(--host="$HOST" --disable-static)

# --- compression and image libraries freetype links ---
fetch zlib-1.3.1.tar.gz \
    https://github.com/madler/zlib/releases/download/v1.3.1/zlib-1.3.1.tar.gz \
    https://zlib.net/fossils/zlib-1.3.1.tar.gz
( cd "$(unpack zlib-1.3.1.tar.gz)" && CHOST=$HOST ./configure --prefix="$PREFIX" && make -j"$(nproc)" && make install )

fetch bzip2-1.0.8.tar.gz https://sourceware.org/pub/bzip2/bzip2-1.0.8.tar.gz
(
    cd "$(unpack bzip2-1.0.8.tar.gz)"
    make -f Makefile-libbz2_so CC="$CC" AR="$AR" RANLIB="$RANLIB" CFLAGS="$CFLAGS -D_FILE_OFFSET_BITS=64"
    cp -a libbz2.so.1.0.8 "$PREFIX/lib/"
    ln -sf libbz2.so.1.0.8 "$PREFIX/lib/libbz2.so.1.0"
    ln -sf libbz2.so.1.0.8 "$PREFIX/lib/libbz2.so.1"
    ln -sf libbz2.so.1.0.8 "$PREFIX/lib/libbz2.so"
    cp bzlib.h "$PREFIX/include/"
)

autotools libpng-1.6.43.tar.xz https://download.sourceforge.net/libpng/libpng-1.6.43.tar.xz "${cross[@]}"

fetch brotli-1.1.0.tar.gz https://github.com/google/brotli/archive/refs/tags/v1.1.0.tar.gz
(
    cd "$(unpack brotli-1.1.0.tar.gz)"
    cmake -S . -B build -DCMAKE_TOOLCHAIN_FILE="$NDK_ROOT/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI=x86_64 -DANDROID_PLATFORM=android-$API -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_INSTALL_PREFIX="$PREFIX" -DCMAKE_INSTALL_LIBDIR=lib -DBUILD_SHARED_LIBS=ON -DBROTLI_DISABLE_TESTS=ON \
        -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
        -DCMAKE_SHARED_LINKER_FLAGS="-Wl,-z,max-page-size=16384"
    cmake --build build -j"$(nproc)"
    cmake --install build
)

# --- fonts ---
autotools freetype-2.13.2.tar.xz https://download.savannah.gnu.org/releases/freetype/freetype-2.13.2.tar.xz \
    "${cross[@]}" --with-zlib=yes --with-bzip2=yes --with-png=yes --with-brotli=yes --with-harfbuzz=no \
    BZIP2_CFLAGS="-I$PREFIX/include" BZIP2_LIBS="-L$PREFIX/lib -lbz2"

autotools expat-2.6.2.tar.gz https://github.com/libexpat/libexpat/releases/download/R_2_6_2/expat-2.6.2.tar.gz \
    "${cross[@]}" --without-docbook --without-examples --without-tests

fetch fontconfig-2.15.0.tar.xz \
    https://www.freedesktop.org/software/fontconfig/release/fontconfig-2.15.0.tar.xz \
    http://deb.debian.org/debian/pool/main/f/fontconfig/fontconfig_2.15.0.orig.tar.xz
autotools fontconfig-2.15.0.tar.xz unused "${cross[@]}" --disable-docs --disable-cache-build \
    FREETYPE_CFLAGS="-I$PREFIX/include/freetype2" FREETYPE_LIBS="-L$PREFIX/lib -lfreetype" \
    EXPAT_CFLAGS="-I$PREFIX/include" EXPAT_LIBS="-L$PREFIX/lib -lexpat"

# --- X11 client libraries winex11.drv opens ---
X=https://www.x.org/archive/individual
autotools xorgproto-2024.1.tar.xz $X/proto/xorgproto-2024.1.tar.xz
autotools xcb-proto-1.17.0.tar.xz $X/proto/xcb-proto-1.17.0.tar.xz
autotools xtrans-1.6.0.tar.xz $X/lib/xtrans-1.6.0.tar.xz
autotools libXau-1.0.11.tar.xz $X/lib/libXau-1.0.11.tar.xz "${cross[@]}"
autotools libXdmcp-1.1.5.tar.xz $X/lib/libXdmcp-1.1.5.tar.xz "${cross[@]}"
autotools libxcb-1.17.0.tar.xz $X/lib/libxcb-1.17.0.tar.xz "${cross[@]}" --without-doxygen
autotools libX11-1.8.10.tar.xz $X/lib/libX11-1.8.10.tar.xz "${cross[@]}" \
    --disable-xf86bigfont --disable-specs --enable-xthreads --enable-malloc0returnsnull=no
for lib in libXext-1.3.6 libXfixes-6.0.1 libXrender-0.9.11 libXrandr-1.5.4 libXi-1.8.2 libXcursor-1.2.3 \
           libXinerama-1.1.5 libXcomposite-0.4.6 libXxf86vm-1.1.6; do
    autotools "$lib.tar.xz" "$X/lib/$lib.tar.xz" "${cross[@]}" --enable-malloc0returnsnull=no
done

# --- SDL2, for game controllers ---
# Built as a Linux library on bionic, not as SDL's Android port: the Android
# port reaches its joystick, file and thread code through JNI into SDL's own
# Java activity, which does not exist in a Wine process (evshim calls
# SDL_Init(SDL_INIT_JOYSTICK) there). -U__ANDROID__ keeps SDL's sources on
# their Linux paths; bionic's headers do not depend on it. Only what a
# controller needs is built (events, joystick with the virtual driver evshim
# attaches, game controller, haptic); no video or audio, so it links against
# libc, libm and libdl alone.
fetch SDL2-2.32.10.tar.gz https://github.com/libsdl-org/SDL/releases/download/release-2.32.10/SDL2-2.32.10.tar.gz
(
    cd "$(unpack SDL2-2.32.10.tar.gz)"
    cmake -S . -B build \
        -DCMAKE_SYSTEM_NAME=Linux -DCMAKE_SYSTEM_PROCESSOR=x86_64 \
        -DCMAKE_C_COMPILER="$CC" -DCMAKE_CXX_COMPILER="$CXX" -DCMAKE_AR="$AR" -DCMAKE_RANLIB="$RANLIB" \
        -DCMAKE_C_FLAGS="-U__ANDROID__" -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_INSTALL_PREFIX="$PREFIX" -DCMAKE_INSTALL_LIBDIR=lib \
        -DCMAKE_SHARED_LINKER_FLAGS="-Wl,-z,max-page-size=16384" \
        -DSDL_SHARED=ON -DSDL_STATIC=OFF -DSDL_TEST=OFF \
        -DSDL_AUDIO=OFF -DSDL_VIDEO=OFF -DSDL_RENDER=OFF \
        -DSDL_HIDAPI=ON -DSDL_HIDAPI_JOYSTICK=OFF -DSDL_HIDAPI_LIBUSB=OFF -DSDL_VIRTUAL_JOYSTICK=ON \
        -DSDL_LIBUDEV=OFF -DSDL_DBUS=OFF -DSDL_IBUS=OFF -DSDL_SYSTEM_ICONV=OFF -DSDL_LIBICONV=OFF
    cmake --build build -j"$(nproc)"
    cmake --install build
)

# --- GnuTLS, for Wine's bcrypt, crypt32 and secur32 ---
# Portable C only (no assembly, no CPU-specific acceleration): these builds
# have to work on every x86_64 device, and the public-key work Wine hands to
# GnuTLS is a handful of operations per connection or certificate.
fetch gmp-6.3.0.tar.xz https://ftp.gnu.org/gnu/gmp/gmp-6.3.0.tar.xz https://gmplib.org/download/gmp/gmp-6.3.0.tar.xz
autotools gmp-6.3.0.tar.xz unused "${cross[@]}" --enable-shared --disable-assembly --disable-cxx

fetch nettle-3.10.1.tar.gz https://ftp.gnu.org/gnu/nettle/nettle-3.10.1.tar.gz
autotools nettle-3.10.1.tar.gz unused "${cross[@]}" --libdir="$PREFIX/lib" --disable-assembler \
    --disable-documentation --disable-openssl --disable-fat

# The trust store GnuTLS falls back to is Android's own CA directory; Wine
# keeps its own root store for Windows programs either way.
fetch gnutls-3.8.9.tar.xz https://www.gnupg.org/ftp/gcrypt/gnutls/v3.8/gnutls-3.8.9.tar.xz \
    https://mirrors.dotsrc.org/gcrypt/gnutls/v3.8/gnutls-3.8.9.tar.xz
autotools gnutls-3.8.9.tar.xz unused "${cross[@]}" \
    --with-included-libtasn1 --with-included-unistring --without-p11-kit --without-idn \
    --without-tpm --without-tpm2 --without-brotli --without-zstd --without-zlib \
    --disable-doc --disable-manpages --disable-tests --disable-tools --disable-cxx --disable-nls \
    --disable-guile --disable-libdane --disable-ktls --disable-hardware-acceleration \
    --with-default-trust-store-dir=/system/etc/security/cacerts \
    GMP_CFLAGS="-I$PREFIX/include" GMP_LIBS="-L$PREFIX/lib -lgmp"

# --- droidtop's own two ---
"$CC" -Wall -std=gnu99 -O2 -shared -fPIC -Wl,-z,max-page-size=16384 -I"$SYSVSHM_SRC" \
    -o "$PREFIX/lib/libandroid-sysvshm.so" "$SYSVSHM_SRC/android_sysvshm.c"
"$CC" -Wall -Wextra -O2 -U_FORTIFY_SOURCE -shared -fPIC -Wl,-z,max-page-size=16384 \
    -o "$PREFIX/lib/libexec-redirect.so" "$HERE/exec-redirect.c" -ldl

# --- stage: shared libraries and the X11 locale data, nothing else ---
rm -rf "${STAGE:?}"
mkdir -p "$STAGE/usr/lib" "$STAGE/usr/share"
cp -a "$PREFIX"/lib/*.so* "$STAGE/usr/lib/"
cp -a "$PREFIX/share/X11" "$STAGE/usr/share/"
find "$STAGE/usr/lib" -type f -name '*.so*' -exec "$STRIP" --strip-unneeded {} +
# libtool builds unversioned sonames for Android (libX11.so), the names
# Termux-built Wine opens. A Wine configured against a glibc-style tree opens
# the versioned names instead, so both resolve.
for alias in libX11.so.6 libX11-xcb.so.1 libxcb.so.1 libXext.so.6 libXrender.so.1 libXfixes.so.3 \
             libXrandr.so.2 libXi.so.6 libXcursor.so.1 libXinerama.so.1 libXcomposite.so.1 \
             libXxf86vm.so.1 libfreetype.so.6 libfontconfig.so.1 libgnutls.so.30; do
    base="${alias%.so.*}.so"
    # A library that already carries the versioned name keeps it: linking it
    # to the unversioned name, itself a link to it, would make a loop.
    if [[ -e "$STAGE/usr/lib/$base" && ! -e "$STAGE/usr/lib/$alias" ]]; then
        ln -s "$base" "$STAGE/usr/lib/$alias"
    fi
done

# Every library must load: each NEEDED name has to be here or in Android.
system=" libc.so libm.so libdl.so liblog.so libandroid.so "
here=" $(cd "$STAGE/usr/lib" && ls | tr '\n' ' ') "
missing=0
while IFS= read -r f; do
    while IFS= read -r needed; do
        if [[ "$here" != *" $needed "* && "$system" != *" $needed "* ]]; then
            echo "$(basename "$f") needs $needed, which nothing provides" >&2
            missing=1
        fi
    done < <(readelf -d "$f" | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p')
done < <(find "$STAGE/usr/lib" -type f -name '*.so*')
[[ $missing -eq 0 ]] || exit 1

# Every ELF must be x86-64: an aarch64 or host library here is the exact
# failure this asset exists to end (Droidtop/tracker#242).
bad=0
while IFS= read -r f; do
    desc=$(file -b "$f")
    case "$desc" in
        *"ELF 64-bit LSB shared object, x86-64"*) ;;
        *) echo "not an x86-64 shared object: $f: $desc" >&2; bad=1 ;;
    esac
done < <(find "$STAGE" -type f -name '*.so*')
[[ $bad -eq 0 ]] || exit 1

mkdir -p "$(dirname "$OUT")"
tar -C "$STAGE" -I 'zstd -19 -T0' -cf "$OUT" usr
echo "built $OUT"
tar -I zstd -tvf "$OUT" | grep '\.so' | sort -k6
