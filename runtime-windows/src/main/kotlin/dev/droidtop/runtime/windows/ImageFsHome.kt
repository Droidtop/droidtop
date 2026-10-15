package dev.droidtop.runtime.windows

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Where the Windows prefixes really live, so a delete can be fenced to that directory.
 *
 * gamenative's ImageFsInstaller (ensureSharedHomeSymlink) turns `imagefs/home` into a symlink to
 * `imagefs_shared/home`, so one user home is shared by every image variant. A prefix written at
 * `imagefs/home/xuser-1` is therefore really in `imagefs_shared/home/xuser-1`, and a delete fenced to
 * `imagefs` correctly refuses it: its parent resolves outside the fence (Droidtop/tracker#249, console
 * 2026-10-14: "xuser-1 is not inside .../imagefs"). The fence is not widened to fit. It moves to the one
 * directory the installer itself names, and only when `home` points exactly there.
 */
internal object ImageFsHome {
    /** The directory prefixes live in, or why it cannot be trusted as a delete fence. */
    sealed interface Root {
        data class Found(val dir: File) : Root
        data class Refused(val why: String) : Root
    }

    /**
     * [imageFsRoot]'s home as a fence: the image's own `home` when it is a real directory (or not made yet), the shared home
     * [sharedHome] when `home` is a symlink that resolves to exactly that directory, and a refusal for a
     * symlink anywhere else (no delete is ever fenced by a place a link merely points at).
     */
    fun root(imageFsRoot: File, sharedHome: File): Root {
        val home = File(imageFsRoot, "home").toPath()
        if (!Files.isSymbolicLink(home)) {
            // Not there yet (nothing to clear) or a real directory inside the image: the image's own home.
            return if (!Files.exists(home, LinkOption.NOFOLLOW_LINKS) || Files.isDirectory(home, LinkOption.NOFOLLOW_LINKS)) {
                Root.Found(home.toFile())
            } else {
                Root.Refused("$home is not a directory")
            }
        }
        return try {
            val target = home.toRealPath()
            val shared = sharedHome.toPath().toRealPath()
            if (target == shared) Root.Found(shared.toFile()) else Root.Refused("$home points at $target, not the shared home $shared")
        } catch (e: IOException) {
            Root.Refused("$home cannot be resolved (${e.javaClass.simpleName}: ${e.message})")
        }
    }
}
