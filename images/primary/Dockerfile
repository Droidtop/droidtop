# droidtop's own PRIMARY-role image: a real Wayland compositor
# (sway, headless backend) with a working /sbin/init, published so
# DesktopSessionService/CraneRootfsPuller have something real to pull
# instead of the placeholder repository docs/SPEC.md §3a's bundled seed
# catalog previously pointed at (see runtime-common's
# known-image-repositories.json "debian-sway" entry).
#
# Nothing droidtop-specific beyond sway/seatd/xwayland + this boot script
# is baked in here -- the in-container launcher/helper process (SPEC §2a,
# not built yet) are meant to be injected at runtime, not part of this
# image, matching "OCI images stay stock" (§2a).
#
# UNVERIFIED end to end: this has never been booted against a real
# droidspaces container (no rooted test device in this environment) --
# see droidtop-boot.sh's own comments for the specific assumptions this
# rests on (env_file injection timing, seatd sufficing without logind).
FROM debian:bookworm-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
        sway \
        xwayland \
        seatd \
        dumb-init \
    && rm -rf /var/lib/apt/lists/*

COPY droidtop-boot.sh /usr/local/bin/droidtop-boot.sh
RUN chmod +x /usr/local/bin/droidtop-boot.sh

# droidspaces requires the rootfs to contain a real /sbin/init (its own
# Documentation/Linux-CLI.md: "--rootfs=PATH ... Must contain /sbin/init")
# -- dumb-init as PID 1 for correct signal forwarding/zombie reaping, with
# our boot script as its fixed child (dumb-init can't itself be the
# container's init entry point, since droidspaces execs /sbin/init with no
# arguments).
RUN printf '#!/bin/sh\nexec dumb-init /usr/local/bin/droidtop-boot.sh\n' > /sbin/init \
    && chmod +x /sbin/init
