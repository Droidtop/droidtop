#pragma once

#include <cstddef>
#include <cstdint>
#include <cstring>

#if defined(__aarch64__) || defined(__ARM_NEON)
#include <arm_neon.h>
#define HOSTBRIDGE_SWIZZLE_NEON 1
#elif defined(__x86_64__) && defined(__SSSE3__)
#include <tmmintrin.h>
#define HOSTBRIDGE_SWIZZLE_SSSE3 1
#endif

// One row of a captured desktop frame onto Android's window (mytop-project/tracker#950, item 1). wl_shm's XRGB8888 and
// ARGB8888 are native-endian 0xAARRGGBB, so the bytes are B,G,R,X on a little-endian device; Android's
// WINDOW_FORMAT_RGBA_8888 wants R,G,B,A. The fourth byte is written opaque: the desktop output is opaque, and in the
// XRGB8888 frames a pixman compositor sends it is undefined. Kept free of Wayland and Android types so it reads, and
// tests, on its own.

namespace hostbridge {

// The plain rule, one pixel at a time: the reference the vector paths must match.
inline void swizzleRowScalar(const uint8_t* src, uint8_t* dst, size_t pixels) {
    for (size_t x = 0; x < pixels; x++) {
        uint32_t p;
        std::memcpy(&p, src + x * 4, 4); // 0xXXRRGGBB
        uint32_t q = ((p & 0x000000FFu) << 16) | (p & 0x0000FF00u) | ((p >> 16) & 0x000000FFu) | 0xFF000000u;
        std::memcpy(dst + x * 4, &q, 4); // bytes R, G, B, 0xFF
    }
}

// The same with vectors where the CPU has them (NEON on arm64, sixteen pixels a step; SSSE3 on x86_64, which every
// Android x86_64 device has, four a step), then the rest one at a time.
inline void swizzleRow(const uint8_t* src, uint8_t* dst, size_t pixels) {
    size_t x = 0;
#if defined(HOSTBRIDGE_SWIZZLE_NEON)
    const uint8x16_t opaque = vdupq_n_u8(0xFF);
    for (; x + 16 <= pixels; x += 16) {
        uint8x16x4_t bgra = vld4q_u8(src + x * 4); // val[0] B, val[1] G, val[2] R, val[3] X
        uint8x16x4_t rgba;
        rgba.val[0] = bgra.val[2];
        rgba.val[1] = bgra.val[1];
        rgba.val[2] = bgra.val[0];
        rgba.val[3] = opaque;
        vst4q_u8(dst + x * 4, rgba);
    }
#elif defined(HOSTBRIDGE_SWIZZLE_SSSE3)
    const __m128i order = _mm_setr_epi8(2, 1, 0, 3, 6, 5, 4, 7, 10, 9, 8, 11, 14, 13, 12, 15);
    const __m128i opaque = _mm_set1_epi32(static_cast<int>(0xFF000000u));
    for (; x + 4 <= pixels; x += 4) {
        __m128i bgra = _mm_loadu_si128(reinterpret_cast<const __m128i*>(src + x * 4));
        __m128i rgba = _mm_or_si128(_mm_shuffle_epi8(bgra, order), opaque);
        _mm_storeu_si128(reinterpret_cast<__m128i*>(dst + x * 4), rgba);
    }
#endif
    swizzleRowScalar(src + x * 4, dst + x * 4, pixels - x);
}

} // namespace hostbridge
