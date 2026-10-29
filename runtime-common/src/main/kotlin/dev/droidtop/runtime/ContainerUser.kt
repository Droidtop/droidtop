package dev.droidtop.runtime

import java.io.File

/**
 * The user a container's program or command runs as (Droidtop/tracker#715): `-u`/`--user`, the Engine API's `User`,
 * or the image's own `User`, read as Docker reads them (`name`, `uid`, `name:group`, `uid:gid`) against the image's
 * own /etc/passwd and /etc/group. [parse] and [resolve] are pure; [resolveIn] reads the two files from a tree.
 */
object ContainerUser {
    /**
     * Set by a backend that switches users inside the container (droidspaces, with real root): the user name [EXEC_TAIL]
     * becomes. proot changes the ids it shows instead (`--change-id`), and leaves it unset.
     */
    const val SWITCH_ENV = "DROIDTOP_SWITCH_USER"

    /**
     * The end of each of droidtop's own scripts that run a program: `"$@"` run, as [SWITCH_ENV] when it is set. The
     * scripts make their redirections first, while still root, so the switched program writes to files and request
     * pipes it may not open itself; `su` without `-` keeps the environment, and the program does not see [SWITCH_ENV].
     */
    const val EXEC_TAIL = "if [ -n \"\$$SWITCH_ENV\" ]; then u=\$$SWITCH_ENV; unset $SWITCH_ENV; " +
        "exec su -s /bin/sh \"\$u\" -c 'exec \"\$@\"' sh \"\$@\"; fi; exec \"\$@\""

    private val WORD = Regex("[A-Za-z0-9._][A-Za-z0-9._-]{0,31}")

    data class Spec(val user: String, val group: String?) {
        /** Root, the user every container ran everything as before: nothing to switch to. */
        val isRoot: Boolean get() = user in setOf("root", "0") && (group == null || group in setOf("root", "0"))

        override fun toString(): String = if (group == null) user else "$user:$group"
    }

    /** Who to run as: [name] null for a uid the image's /etc/passwd has no line for. */
    data class Resolved(val uid: Int, val gid: Int, val name: String?)

    /** [spec] as given; null for one that is blank or not names and numbers (nothing a shell or a flag would read). */
    fun parse(spec: String?): Spec? {
        val s = spec?.trim().orEmpty()
        if (s.isEmpty()) return null
        val user = s.substringBefore(':')
        val group = s.substringAfter(':', "").takeIf { ':' in s }
        if (!WORD.matches(user) || (group != null && !WORD.matches(group))) return null
        return Spec(user, group)
    }

    /**
     * [spec] in the image's [passwd] and [group] files, or why not, in Docker's words. A user name must have its line;
     * a uid need not, and then runs with gid 0, as Docker's does. A group is a name in [group] or a gid.
     */
    fun resolve(spec: Spec, passwd: String, group: String): Result<Resolved> {
        val users = passwd.lines().mapNotNull { line ->
            val f = line.split(':')
            val uid = f.getOrNull(2)?.toIntOrNull()
            val gid = f.getOrNull(3)?.toIntOrNull()
            if (f.size < 4 || uid == null || gid == null || line.startsWith("#")) null else Triple(f[0], uid, gid)
        }
        val numeric = spec.user.toIntOrNull()
        val entry = if (numeric != null) users.firstOrNull { it.second == numeric } else users.firstOrNull { it.first == spec.user }
        if (numeric == null && entry == null) {
            return Result.failure(IllegalArgumentException("unable to find user ${spec.user}: no matching entries in passwd file"))
        }
        val uid = numeric ?: entry!!.second
        val gid = when (val g = spec.group) {
            null -> entry?.third ?: 0
            else -> g.toIntOrNull() ?: group.lines().firstNotNullOfOrNull { line ->
                val f = line.split(':')
                if (f.size >= 3 && f[0] == g) f[2].toIntOrNull() else null
            } ?: return Result.failure(IllegalArgumentException("unable to find group $g: no matching entries in group file"))
        }
        return Result.success(Resolved(uid, gid, entry?.first))
    }

    /**
     * [spec] in the tree at [root], reading its etc/passwd and etc/group. A file that resolves outside the tree (an
     * absolute symlink in the image) reads as empty, so the host's own is never read; so does one past 1 MB.
     */
    fun resolveIn(root: File, spec: Spec): Result<Resolved> =
        resolve(spec, readInTree(root, "etc/passwd"), readInTree(root, "etc/group"))

    private fun readInTree(root: File, relative: String): String = runCatching {
        val tree = root.canonicalFile
        val file = File(root, relative).canonicalFile
        if (!file.path.startsWith(tree.path + File.separator) || !file.isFile || file.length() > MAX_FILE_BYTES) "" else file.readText()
    }.getOrDefault("")

    private const val MAX_FILE_BYTES = 1L shl 20
}
