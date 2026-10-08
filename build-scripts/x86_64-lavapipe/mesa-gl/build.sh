# Termux package recipe for Mesa's xlib OpenGL (llvmpipe), for droidtop's x86_64
# Wine guest. prepare.sh adds it to termux-packages as packages/mesa-xlib-gl,
# beside Termux's own packages/mesa, with that recipe's patches (all but the
# llvmpipe single-thread one), and the Termux package builder builds it against
# Termux's x86_64 packages, the same packages the rest of the software graphics
# archive comes from.
#
# Why xlib GLX: the app's X server has no GLX extension, so a DRI-based libGL
# (Termux's own mesa, glvnd) cannot start there. Mesa's xlib target is a libGL
# that does GLX on the client side and presents with XPutImage, so WineD3D's
# OpenGL renderer gets a context on any X server. Everything else is left out.
TERMUX_PKG_HOMEPAGE=https://www.mesa3d.org
TERMUX_PKG_DESCRIPTION="Mesa's xlib OpenGL (llvmpipe) for droidtop's x86_64 Wine guest"
TERMUX_PKG_LICENSE="MIT"
TERMUX_PKG_LICENSE_FILE="docs/license.rst"
TERMUX_PKG_MAINTAINER="droidtop"
TERMUX_PKG_VERSION="26.2.4"
TERMUX_PKG_SRCURL="https://archive.mesa3d.org/mesa-${TERMUX_PKG_VERSION}.tar.xz"
TERMUX_PKG_SHA256=bce5f7fbebb934373b86c999a064d52fb5065878dc57f287f95346648ec832e9
TERMUX_PKG_DEPENDS="libandroid-shmem, libc++, libllvm (<< $TERMUX_LLVM_NEXT_MAJOR_VERSION), libx11, libxcb, libxext, libxshmfence, ncurses, zlib, zstd"
TERMUX_PKG_BUILD_DEPENDS="llvm, xorgproto"

TERMUX_PKG_EXTRA_CONFIGURE_ARGS="
--cmake-prefix-path $TERMUX_PREFIX
-Dplatforms=x11
-Dglx=xlib
-Dglvnd=disabled
-Dopengl=true
-Degl=disabled
-Dgbm=disabled
-Dgles1=disabled
-Dgles2=disabled
-Dllvm=enabled
-Dshared-llvm=enabled
-Dgallium-drivers=llvmpipe,softpipe
-Dvulkan-drivers=
-Dgallium-rusticl=false
-Dxmlconfig=disabled
-Dvalgrind=disabled
-Dlibunwind=disabled
-Dlmsensors=disabled
-Dbuild-tests=false
"

termux_step_post_get_source() {
	# Do not use meson wrap projects
	rm -rf subprojects
}

termux_step_pre_configure() {
	termux_setup_cmake

	CPPFLAGS+=" -D__USE_GNU"
	LDFLAGS+=" -landroid-shmem"
	# Below API 29 the NDK emulates TLS, which renames the glapi TLS symbols
	# a version script may name; let the link go on without them.
	LDFLAGS+=" -Wl,--undefined-version"

	# Find LLVM through CMake, as termux-packages' own mesa recipe does: the
	# wrapper makes CMake treat the Android toolchain file as Linux.
	_WRAPPER_BIN=$TERMUX_PKG_BUILDDIR/_wrapper/bin
	mkdir -p "$_WRAPPER_BIN"
	sed 's|@CMAKE@|'"$(command -v cmake)"'|g' \
		"$TERMUX_PKG_BUILDER_DIR/cmake-wrapper.in" > "$_WRAPPER_BIN/cmake"
	chmod 0700 "$_WRAPPER_BIN/cmake"
	export LLVM_CONFIG="${TERMUX_PREFIX}/bin/llvm-config"
	export PATH="${_WRAPPER_BIN}:${PATH}"
}

termux_step_post_configure() {
	rm -f "$_WRAPPER_BIN/cmake"
}
