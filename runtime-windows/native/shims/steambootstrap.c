// libsteambootstrap for x86_64 (docs/SPEC.md 10b, "Steam on x86_64 is the
// Linux client in proot"). On arm64 this program brings up Valve's Android
// arm64 libsteamclient.so; no x86_64 Android client exists, so it cannot do
// that job here. It reports the reason through the ready file SteamBootstrap
// polls (argv: appId, libsteamclient path, ready file; statuses in
// SteamBootstrap.kt) until the proot Linux client takes this place.
#include <stdio.h>

int main(int argc, char **argv) {
    static const char reason[] =
        "the bionic Steam client is arm64-only; x86_64 uses the Linux client";
    fprintf(stderr, "steambootstrap: %s\n", reason);
    if (argc > 3) {
        FILE *ready = fopen(argv[3], "w");
        if (ready != NULL) {
            fprintf(ready, "FAILED:%s", reason);
            fclose(ready);
        }
    }
    return 1;
}
