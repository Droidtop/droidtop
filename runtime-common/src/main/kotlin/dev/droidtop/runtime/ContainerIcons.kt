package dev.droidtop.runtime

import java.io.File

/**
 * The container's application icons, for droidtop's own chrome (docs/SPEC.md 2b "Linux app icons",
 * Droidtop/tracker#689): the Start menu's Linux apps, their taskbar pins, search and the bar's window buttons.
 *
 * An entry's `Icon` names a file in the container's icon themes. One exec resolves the icons droidtop has not
 * seen yet: for each, the first PNG or SVG in hicolor, then Adwaita, then every other installed theme, in any of
 * a theme's contexts (apps, places, devices, ...: Desktop Preferences' `user-desktop` is a place), by size (128 px
 * first, then the sizes around it, then scalable), then in a context-first layout (breeze's `apps/48`), then
 * `/usr/share/pixmaps`, and last a theme's `-symbolic` SVG (current Adwaita has some app icons only so), rig
 * 2026-10-29: only Foot's icon showed. It copies the file whole into [IN_CONTAINER_DIR], which is the app's own
 * [hostDir], so droidtop reads it as a plain file; droidtop's image loader draws SVG as well as PNG. A name with
 * nothing found is remembered with an empty [NONE] file, so it is not looked for again. Each cached file is named by
 * [ContainerLauncher.token] of the `Icon` value. XPM is left out, as nothing in droidtop decodes it.
 */
object ContainerIcons {
    /** The cache inside every container, under the launcher channel the app's files already share. */
    const val IN_CONTAINER_DIR = "${ContainerLauncher.IN_CONTAINER_DIR}/icons"

    /** An icon theme name, or an absolute path to a PNG or SVG; anything else is not looked for. */
    private val NAME = Regex("[A-Za-z0-9._+-]{1,128}")
    private val PATH = Regex("/[A-Za-z0-9._+/-]{1,512}\\.(png|svg)")

    fun hostDir(filesDir: File): File = File(ContainerLauncher.hostDir(filesDir), "icons")

    /** Whether [icon] is an `Icon` value droidtop will look for. */
    fun lookable(icon: String): Boolean =
        NAME.matches(icon) && icon != "." && icon != ".." ||
            PATH.matches(icon) && icon.split('/').none { it == ".." || it == "." }

    /** [apps] with [ContainerApp.iconFile] set from the cache, resolving the icons it does not hold yet first. */
    suspend fun attach(runtime: ContainerRuntime, container: Container, filesDir: File, apps: List<ContainerApp>): List<ContainerApp> {
        val dir = hostDir(filesDir)
        val wanted = apps.mapNotNull { it.icon }.filter(::lookable).distinct()
        val unseen = wanted.filter { cached(dir, it) == null && !File(dir, ContainerLauncher.token(it) + NONE).isFile }
        if (unseen.isNotEmpty()) {
            dir.mkdirs()
            val result = runCatching { runtime.exec(container, command(unseen)) }
            result.exceptionOrNull()?.let { if (it is kotlinx.coroutines.CancellationException) throw it }
        }
        return apps.map { app -> app.icon?.takeIf(::lookable)?.let { cached(dir, it) }?.let { app.copy(iconFile = it.absolutePath) } ?: app }
    }

    /** The cached file for [icon], PNG before SVG; null when there is none yet. */
    fun cached(dir: File, icon: String): File? {
        val token = ContainerLauncher.token(icon)
        return listOf("png", "svg").map { File(dir, "$token.$it") }.firstOrNull { it.isFile && it.length() > 0 }
    }

    /** The exec that resolves and copies [icons]: the names go in as arguments, never into the script's text. */
    fun command(icons: List<String>): List<String> =
        listOf("/bin/sh", "-c", SCRIPT, "droidtop-icons", IN_CONTAINER_DIR) + icons.flatMap { listOf(ContainerLauncher.token(it), it) }

    internal val SCRIPT = """
        dir=${'$'}1; shift
        r=${'$'}{DROIDTOP_ICON_ROOT:-/usr/share}
        mkdir -p "${'$'}dir" || exit 0
        themes="hicolor Adwaita"
        for d in "${'$'}r"/icons/*/index.theme; do
          [ -f "${'$'}d" ] || continue
          th=${'$'}{d%/index.theme}; th=${'$'}{th##*/}
          case "${'$'}th" in hicolor|Adwaita|*' '*) ;; *) themes="${'$'}themes ${'$'}th" ;; esac
        done
        while [ ${'$'}# -ge 2 ]; do
          t=${'$'}1; n=${'$'}2; shift 2; found=
          case "${'$'}n" in
            /*) [ -f "${'$'}n" ] && found=${'$'}n ;;
            *)
              for th in ${'$'}themes; do
                for s in 128x128 96x96 256x256 64x64 512x512 48x48 scalable; do
                  for f in "${'$'}r"/icons/${'$'}th/${'$'}s/*/"${'$'}n".png "${'$'}r"/icons/${'$'}th/${'$'}s/*/"${'$'}n".svg; do
                    if [ -f "${'$'}f" ]; then found=${'$'}f; break 3; fi
                  done
                done
                for f in "${'$'}r"/icons/${'$'}th/*/*/"${'$'}n".svg "${'$'}r"/icons/${'$'}th/*/*/"${'$'}n".png; do
                  if [ -f "${'$'}f" ]; then found=${'$'}f; break 2; fi
                done
              done
              if [ -z "${'$'}found" ]; then
                for f in "${'$'}r"/pixmaps/"${'$'}n".png "${'$'}r"/pixmaps/"${'$'}n".svg; do
                  if [ -f "${'$'}f" ]; then found=${'$'}f; break; fi
                done
              fi
              if [ -z "${'$'}found" ]; then
                for th in ${'$'}themes; do
                  for f in "${'$'}r"/icons/${'$'}th/*/*/"${'$'}n"-symbolic.svg; do
                    if [ -f "${'$'}f" ]; then found=${'$'}f; break 2; fi
                  done
                done
              fi ;;
          esac
          case "${'$'}found" in
            *.png) e=png ;;
            *.svg) e=svg ;;
            *) : > "${'$'}dir/${'$'}t$NONE"; continue ;;
          esac
          cp -L "${'$'}found" "${'$'}dir/.${'$'}t.${'$'}e" && mv -f "${'$'}dir/.${'$'}t.${'$'}e" "${'$'}dir/${'$'}t.${'$'}e"
        done
        true
    """.trimIndent()

    /**
     * The mark of a name looked for and not found. Its name changes with the search: the first search (`.none`) read
     * only hicolor's and Adwaita's `apps`, and what it gave up on is looked for again.
     */
    internal const val NONE = ".none-2"
}
