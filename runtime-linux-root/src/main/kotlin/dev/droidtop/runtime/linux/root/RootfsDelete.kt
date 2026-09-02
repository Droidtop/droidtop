package dev.droidtop.runtime.linux.root

import dev.droidtop.runtime.RootProcess
import dev.droidtop.runtime.RootProcessResult
import java.io.File

/**
 * The one way a rootfs tree is removed, for both backends' sakes: as
 * root, with `rm -rf`, and only after proving nothing is mounted
 * underneath it.
 *
 * Why not a Kotlin `deleteRecursively`: it follows symlinked
 * directories, and a Linux rootfs is full of symlinks -- absolute ones
 * included -- which is the same defect class that emptied the test
 * device's internal storage through a Wine prefix on 2026-09-02 (see
 * docs/SPEC.md 5b). It also cannot work here anyway: the tree is
 * extracted by root and is root-owned. `rm` never follows a symlink,
 * so the links are unlinked, not entered.
 *
 * Why the mount check: `rm -rf` DOES descend into a live mount, and a
 * droidspaces rootfs is exactly where those live -- the runtime
 * bind-mounts the sockets dir and the app-storage dir into every
 * container, and a leaked instance keeps them mounted (confirmed live;
 * see DroidSpacesRuntime.createContainer). Deleting through a
 * still-mounted app-storage bind would empty user storage, so this
 * reads /proc/mounts and refuses instead of trusting a prior `stop`.
 */
object RootfsDelete {

    suspend fun delete(rootfsPath: String): RootProcessResult {
        val canonical = File(rootfsPath).canonicalPath
        // A rootfs lives deep inside app-private storage; anything this
        // shallow is not one, whatever the caller thinks.
        if (canonical.count { it == '/' } < 3) {
            return RootProcessResult(1, "", "refusing to delete '$canonical': too shallow to be a rootfs")
        }
        // Root's view of the mount table, since root is what deletes.
        val mounts = RootProcess.run("cat", "/proc/mounts")
        if (!mounts.succeeded) {
            return RootProcessResult(
                mounts.exitCode,
                "",
                "refusing to delete '$canonical': couldn't read /proc/mounts (${mounts.stderr.trim()})",
            )
        }
        val mountedUnder = mounts.stdout.lineSequence()
            .mapNotNull { it.split(' ').getOrNull(1) }
            .firstOrNull { it == canonical || it.startsWith("$canonical/") }
        if (mountedUnder != null) {
            return RootProcessResult(
                1,
                "",
                "refusing to delete '$canonical': '$mountedUnder' is still mounted under it -- stop the container first",
            )
        }
        return RootProcess.run("rm", "-rf", canonical)
    }
}
