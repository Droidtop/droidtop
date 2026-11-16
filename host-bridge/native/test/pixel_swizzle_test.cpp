// pixel_swizzle.h against its own reference, on the build machine: the SSSE3 path with -mssse3, the plain one without
// (Droidtop/tracker#950). The NEON path is the same rule; the Android build compiles it for arm64-v8a.
// Android checks runs: g++ -O2 [-mssse3] -I host-bridge/native/src host-bridge/native/test/pixel_swizzle_test.cpp
#include "pixel_swizzle.h"
#include <cstdio>
#include <vector>
#include <random>
int main() {
    std::mt19937 rng(7);
    for (size_t n : {0u, 1u, 3u, 4u, 5u, 15u, 16u, 17u, 31u, 33u, 1920u}) {
        std::vector<uint8_t> src(n * 4), a(n * 4), b(n * 4);
        for (auto& v : src) v = rng() & 0xFF;
        hostbridge::swizzleRowScalar(src.data(), a.data(), n);
        hostbridge::swizzleRow(src.data(), b.data(), n);
        if (a != b) { std::printf("mismatch at n=%zu\n", n); return 1; }
        for (size_t i = 0; i < n; i++) {
            if (a[i*4] != src[i*4+2] || a[i*4+1] != src[i*4+1] || a[i*4+2] != src[i*4] || a[i*4+3] != 0xFF) { std::printf("scalar wrong at %zu\n", i); return 1; }
        }
    }
    std::puts("ok");
}
