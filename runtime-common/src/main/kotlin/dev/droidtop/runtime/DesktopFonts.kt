package dev.droidtop.runtime

/**
 * Fonts for all languages in the desktop (Droidtop/tracker#390, docs/SPEC.md
 * 3d "Fonts for all languages"): the distro's Noto CJK and emoji packages,
 * about 100 MB, installed by a job in Downloads and installs rather than by
 * the boot plan, so the download is seen, waits for an unmetered network
 * and resumes where it stopped.
 */
object DesktopFonts {
    /** Written in the primary once the fonts are installed; a test for it is the whole "already done" check. */
    const val MARKER = "/var/lib/droidtop-fonts-installed"

    const val ALPINE_PACKAGES = "font-noto-cjk font-noto-emoji"
    const val DEBIAN_PACKAGES = "fonts-noto-cjk fonts-noto-color-emoji"

    /**
     * The install, run with `sh -c` in the primary; safe to run again after
     * any interruption. Alpine's apk cannot resume a package download, so
     * each package is fetched with busybox `wget -c` into a cache directory
     * (its URL is the one `apk fetch --simulate --url` names, or, with an apk
     * that cannot say, `apk search -x`'s name-version tried in each
     * repository with the expected misses kept quiet) and installed from the
     * files, falling back to a plain `apk add` when that cannot be worked out. Debian's apt resumes partial
     * downloads in `archives/partial` itself, so it downloads first and then
     * installs. [MARKER] is written last.
     */
    fun installScript(): String =
        """
            set -e
            m=/var/lib/droidtop-fonts-installed
            if [ -f "${'$'}m" ]; then echo 'droidtop: fonts already installed'; exit 0; fi
            if command -v apk >/dev/null 2>&1; then
              c=/var/cache/droidtop-fonts
              mkdir -p "${'$'}c"
              apk update -q
              arch="${'$'}(apk --print-arch)"
              for p in font-noto-cjk font-noto-emoji; do
                # The exact file apk itself would fetch, from its own resolver; with an apk that cannot say,
                # the package name and version tried in each repository in turn, a miss being expected.
                u="${'$'}(apk fetch --simulate --url "${'$'}p" 2>/dev/null | grep -m 1 -E '^(https?|ftp)://' || true)"
                if [ -n "${'$'}u" ]; then
                  urls="${'$'}u"
                else
                  v="${'$'}(apk search -x "${'$'}p" 2>/dev/null | head -n 1)"
                  [ -n "${'$'}v" ] || continue
                  urls=""
                  for r in ${'$'}(grep -v '^#' /etc/apk/repositories); do urls="${'$'}urls ${'$'}r/${'$'}arch/${'$'}v.apk"; done
                fi
                for url in ${'$'}urls; do
                  f="${'$'}c/${'$'}{url##*/}"
                  [ -f "${'$'}f.done" ] && break
                  echo "droidtop: downloading ${'$'}url"
                  if wget -c -q -O "${'$'}f" "${'$'}url" 2>/dev/null; then touch "${'$'}f.done"; break; fi
                  [ -s "${'$'}f" ] || rm -f "${'$'}f"
                done
              done
              if ls "${'$'}c"/*.apk >/dev/null 2>&1 && apk add --no-cache "${'$'}c"/*.apk; then :; else apk add --no-cache font-noto-cjk font-noto-emoji; fi
              rm -rf "${'$'}c"
            elif command -v apt-get >/dev/null 2>&1; then
              export DEBIAN_FRONTEND=noninteractive
              apt-get update
              apt-get install -y --no-install-recommends --download-only fonts-noto-cjk fonts-noto-color-emoji
              apt-get install -y --no-install-recommends fonts-noto-cjk fonts-noto-color-emoji
              apt-get clean
            else
              echo 'droidtop: this container has neither apk nor apt-get' >&2
              exit 3
            fi
            mkdir -p /var/lib
            touch "${'$'}m"
            echo 'droidtop: fonts installed'
        """.trimIndent() + "\n"
}
