package dev.droidtop.pluginhost

import dev.droidtop.runtime.util.Sha256

import java.io.File
import java.security.MessageDigest
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.json.JSONObject

/** Why an install was refused -- shown verbatim on the approval screen, never swallowed into a generic "couldn't install". */
data class PluginInstallError(val reason: String)

sealed interface PluginInstallResult {
    data class Installed(val record: PluginRecord) : PluginInstallResult
    data class Refused(val error: PluginInstallError) : PluginInstallResult
}

/**
 * Validates and extracts a `*.droidplugin.tar.xz` bundle into a
 * per-plugin private directory. Every step in the trust-boundary
 * checklist (docs/SPEC.md 12a) runs here, in this order, and the first
 * failure refuses the whole install -- nothing partial is ever left
 * approved. Signature and hashes are re-checked by [verifyInstalled]
 * before every activation too (checklist point 1's "not only at
 * install"), not just here. `userKeys` carries the user-trusted origin
 * anchors ("Keys you trust", [UserOriginKeys.loadBase64]): signature
 * verification resolves the official origin to droidtop's signing root first, then
 * those, so a bundle from an origin the user trusted verifies here exactly
 * like an official one and an origin no anchor resolves for is still
 * refused outright.
 *
 * Bundle layout (tar, then xz -- same tar.xz choice as enginehost's
 * bundles and droidtop's own OCI layer handling, so this is the second
 * user of that tool combination, not a new one):
 *
 *   manifest.json     the [PluginManifest], UTF-8
 *   manifest.jws      detached JWS over manifest.json's raw bytes, carrying its certificate chain ([BundleSignature])
 *   manifest.sig, origin.cert
 *                     optional, ignored: the old format's files, which a publisher may keep so older
 *                     droidtop builds still install the bundle ([LegacyBundleSignature])
 *   <payload files>   exactly the paths [PluginManifest.payload] declares, nothing more, nothing fewer
 */
object PluginBundleInstaller {
    private const val MAX_MANIFEST_BYTES = 64 * 1024
    private const val MAX_ENTRY_BYTES = 64L * 1024 * 1024

    /** The bundle's trust files: small, read whole; every other entry is payload and is streamed to disk. */
    private val TRUST_FILES = setOf("manifest.json", BundleSignature.FILE_NAME, LegacyBundleSignature.SIGNATURE_FILE, LegacyBundleSignature.CERTIFICATE_FILE)

    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

    fun install(
        bundleFile: File,
        pluginsRoot: File,
        userKeys: Map<String, String> = emptyMap(),
        /** The permissions droidtop's own manifest requests ([AndroidPermissions.heldBy]); null skips that check (tests). */
        hostPermissions: Set<String>? = null,
    ): PluginInstallResult {
        // Entries are streamed to a staging folder on disk and hashed as they go: a bundle carrying both ABIs of an
        // engine is tens of MiB, and several installs run at once (Update all), so none of it is held in the heap
        // (Droidtop/tracker#545). Only the small trust files are read into memory, each under its cap.
        val staging = File(pluginsRoot.absoluteFile.parentFile ?: pluginsRoot, ".plugin-install-" + System.nanoTime()).also { it.mkdirs() }
        try {
            return installStaged(bundleFile, pluginsRoot, staging, userKeys, hostPermissions)
        } finally {
            staging.deleteRecursively()
        }
    }

    /** One non-trust bundle entry after it was streamed to disk: where it is and its SHA-256. */
    private class Staged(val file: File, val sha256: String)

    private fun installStaged(
        bundleFile: File,
        pluginsRoot: File,
        staging: File,
        userKeys: Map<String, String>,
        hostPermissions: Set<String>?,
    ): PluginInstallResult {
        val small = linkedMapOf<String, ByteArray>()
        val staged = linkedMapOf<String, Staged>()
        try {
            TarArchiveInputStream(XZCompressorInputStream(bundleFile.inputStream().buffered())).use { tar ->
                var entry: TarArchiveEntry? = tar.nextTarEntry
                var count = 0
                val buffer = ByteArray(64 * 1024)
                while (entry != null) {
                    if (!entry.isDirectory) {
                        if (entry.size > MAX_ENTRY_BYTES) {
                            return PluginInstallResult.Refused(PluginInstallError("${entry.name} is larger than the ${MAX_ENTRY_BYTES / (1024 * 1024)} MiB per-file cap"))
                        }
                        // normalize() + the leading-".." check refuse a
                        // path that would extract outside the plugin's
                        // own directory (zip-slip shape), independent of
                        // the manifest's own payload list matching later.
                        val name = File(entry.name).path
                        if (File(name).normalize().path.startsWith("..")) {
                            return PluginInstallResult.Refused(PluginInstallError("bundle entry escapes its own directory: ${entry.name}"))
                        }
                        if (name in TRUST_FILES) {
                            if (entry.size > MAX_MANIFEST_BYTES) {
                                return PluginInstallResult.Refused(PluginInstallError("$name exceeds the ${MAX_MANIFEST_BYTES / 1024} KiB cap"))
                            }
                            small[name] = tar.readBytes()
                        } else {
                            // Staged under a counter, never under the bundle's own name: the name is only a key.
                            val target = File(staging, "f" + count++)
                            val digest = MessageDigest.getInstance("SHA-256")
                            target.outputStream().buffered().use { out ->
                                var left = MAX_ENTRY_BYTES
                                while (true) {
                                    val read = tar.read(buffer)
                                    if (read < 0) break
                                    left -= read
                                    if (left < 0) return PluginInstallResult.Refused(PluginInstallError("$name is larger than the ${MAX_ENTRY_BYTES / (1024 * 1024)} MiB per-file cap"))
                                    digest.update(buffer, 0, read)
                                    out.write(buffer, 0, read)
                                }
                            }
                            staged.remove(name)?.file?.delete()
                            staged[name] = Staged(target, digest.digest().toHexString())
                        }
                    }
                    entry = tar.nextTarEntry
                }
            }
        } catch (e: OutOfMemoryError) {
            // The xz dictionary a bundle asks for is the one thing left that sizes memory: a refusal on its row, never a crash.
            return PluginInstallResult.Refused(PluginInstallError("couldn't read the bundle: it needs more memory than droidtop can spare"))
        } catch (e: Exception) {
            return PluginInstallResult.Refused(PluginInstallError("couldn't read the bundle: ${e.message}"))
        }

        val manifestBytes = small["manifest.json"]
            ?: return PluginInstallResult.Refused(PluginInstallError("bundle has no manifest.json"))
        if (manifestBytes.size > MAX_MANIFEST_BYTES) {
            return PluginInstallResult.Refused(PluginInstallError("manifest.json exceeds the ${MAX_MANIFEST_BYTES / 1024} KiB cap"))
        }
        // Only the certificate format installs; the old one verifies only what is already installed (SPEC 12a "Migration from raw keys").
        val jws = small[BundleSignature.FILE_NAME]?.toString(Charsets.US_ASCII)
            ?: return PluginInstallResult.Refused(
                PluginInstallError(
                    if (LegacyBundleSignature.SIGNATURE_FILE in small) {
                        "this bundle is signed the old way, which droidtop no longer installs: its publisher has to sign it again with a certificate (${BundleSignature.FILE_NAME})"
                    } else {
                        "bundle has no ${BundleSignature.FILE_NAME}"
                    },
                ),
            )

        val manifest = runCatching { PluginManifest.fromJson(JSONObject(manifestBytes.toString(Charsets.UTF_8))) }
            .getOrNull() ?: return PluginInstallResult.Refused(PluginInstallError("manifest.json isn't a complete plugin manifest"))

        manifest.structuralProblems().firstOrNull()?.let {
            return PluginInstallResult.Refused(PluginInstallError(it))
        }
        // docs/plugin-api.md 4.1: an Android permission droidtop does not have can never reach a plugin through droidtop.
        if (hostPermissions != null) {
            AndroidPermissions.installProblems(manifest, hostPermissions).firstOrNull()?.let {
                return PluginInstallResult.Refused(PluginInstallError(it))
            }
        }

        // Checklist point 3: signature and schema before anything else touches disk as "the plugin".
        val verdict = BundleSignature.verifyBundle(
            manifestBytes, jws, manifest.origin, manifest.id,
            userKeys, PluginRevocations.load(pluginsRoot),
        )
        // The fingerprint approval binds to: the anchor the chain ended at,
        // the signing root's or the user-trusted key's ([BundleVerdict.Verified.anchorSha256]).
        val verified = when (verdict) {
            is BundleVerdict.Refused -> return PluginInstallResult.Refused(PluginInstallError(verdict.reason))
            is BundleVerdict.Verified -> verdict
        }
        val keyFingerprint = verified.anchorSha256

        // Checklist point 2: never shadow a built-in id or one another origin already installed under.
        val existingRoot = File(pluginsRoot, manifest.id)
        val existingState = readRecord(pluginsRoot, manifest.id)
        if (existingState != null && existingState.manifest.origin != manifest.origin) {
            return PluginInstallResult.Refused(PluginInstallError("\"${manifest.id}\" is already installed from origin \"${existingState.manifest.origin}\""))
        }
        if (manifest.id in PROTECTED_IDS) {
            return PluginInstallResult.Refused(PluginInstallError("\"${manifest.id}\" is a protected id"))
        }

        // Checklist point 1: every declared payload file present with a matching hash, and no extra files smuggled in unaccounted for.
        val declaredPaths = manifest.payload.map { it.path }.toSet()
        val extraFiles = staged.keys - declaredPaths
        if (extraFiles.isNotEmpty()) {
            return PluginInstallResult.Refused(PluginInstallError("bundle contains files not declared in the manifest: ${extraFiles.joinToString()}"))
        }
        for (file in manifest.payload) {
            val entry = staged[file.path]
                ?: return PluginInstallResult.Refused(PluginInstallError("manifest declares ${file.path} but the bundle doesn't contain it"))
            if (!entry.sha256.equals(file.sha256, ignoreCase = true)) {
                return PluginInstallResult.Refused(PluginInstallError("hash mismatch for ${file.path}"))
            }
        }

        // Everything verified -- now, and only now, write to disk.
        if (!existingRoot.isDirectory) existingRoot.mkdirs() else existingRoot.listFiles()?.forEach { it.deleteRecursively() }
        File(existingRoot, "manifest.json").writeBytes(manifestBytes)
        // Kept beside the manifest so every re-verification checks the same chain the install did.
        File(existingRoot, BundleSignature.FILE_NAME).writeText(jws)
        for (file in manifest.payload) {
            val target = File(existingRoot, file.path)
            target.parentFile?.mkdirs()
            val from = staged.getValue(file.path).file
            // A rename within one volume; the copy is the fallback if the staging folder were elsewhere.
            if (!from.renameTo(target)) {
                from.copyTo(target, overwrite = true)
                from.delete()
            }
            // Android 10+ refuses to DexClassLoader a writable file
            // ("Writable dex file ... is not allowed") -- confirmed live
            // on emulator-5560 (Android 14): a native_bundle plugin
            // approved and running fine moments earlier came back
            // disabledReason="load failed: Writable dex file ... is not
            // allowed" on its very next real invoke(), since nothing
            // here had ever marked the extracted payload read-only. The
            // BlueStacks rig (Android 9) never enforces this, which is
            // why dq-plugins-01 read as a full pass there. PluginContext's
            // own doc comment already called this payload "read-only" --
            // this is what actually makes that true.
            target.setReadOnly()
        }

        val digest = Sha256.hex(manifestBytes)
        // Other bytes than the installed ones: any process still running the old code lets go of it (Droidtop/tracker#539).
        if (existingState != null && existingState.archiveDigest != digest) runCatching { PluginProcesses.retire(manifest.id) }
        // Approval carries over to an update verified under the SAME trust
        // anchor the plugin was approved under (docs/SPEC.md 12a, "Trust
        // over updates"): a re-install of the exact same bytes keeps
        // whatever state it had, and a different digest keeps APPROVED --
        // with its enabled and root-approved bits -- when it verifies under
        // that same anchor: the same user-trusted key, or for the official
        // origin droidtop's own anchors (its signing root, or the legacy key or
        // old plugin master it was approved under, [PluginOriginKeys.sameTrustAnchor]). Everything else
        // starts PENDING: a first install, an update of a plugin that was
        // only ever PENDING, an update signed by a different user key (a
        // rotation is a new trust decision), and any update of a DENIED
        // plugin (the user said no; it re-enters at PENDING).
        val sameBytes = existingState?.archiveDigest == digest
        val carriedOver = sameBytes || (
            existingState?.trust == PluginTrustState.APPROVED &&
                existingState.manifest.origin == manifest.origin &&
                PluginOriginKeys.sameTrustAnchor(existingState.approvedKeySha256, keyFingerprint)
            )
        val record = PluginRecord(
            manifest = manifest,
            archiveDigest = digest,
            approvedKeySha256 = keyFingerprint,
            trust = when {
                sameBytes -> existingState!!.trust
                carriedOver -> PluginTrustState.APPROVED
                else -> PluginTrustState.PENDING
            },
            // A new version of a plugin that was stopped by a failure starts switched on: the update is the fix being
            // offered, and installing it must not leave the plugin "Disabled" with nothing to press.
            enabled = carriedOver && (existingState!!.enabled || (!sameBytes && existingState.disabledReason != null)),
            rootApproved = carriedOver && existingState!!.rootApproved && manifest.requestsRoot,
            disabledReason = null,
            // An approved version replaced by one from a different maker: kept until the person decides (D3). A pending
            // version replaced again keeps what it replaced.
            replacedKeySha256 = when {
                sameBytes || carriedOver -> existingState?.replacedKeySha256
                existingState?.trust == PluginTrustState.APPROVED -> existingState.approvedKeySha256
                else -> existingState?.replacedKeySha256
            },
            replacedOrigin = when {
                sameBytes || carriedOver -> existingState?.replacedOrigin
                existingState?.trust == PluginTrustState.APPROVED -> existingState.manifest.origin
                else -> existingState?.replacedOrigin
            },
        )
        writeRecord(pluginsRoot, record)
        // A same-key update keeps the grants it effectively had; what it adds that is dangerous waits at `ask` (docs/plugin-api.md 1.5).
        if (carriedOver && !sameBytes && existingState != null) {
            PluginGrants.forPluginsRoot(pluginsRoot).applyUpdate(existingState, record)
        }
        return PluginInstallResult.Installed(record)
    }

    /**
     * Re-verifies an already-installed plugin's files against its own
     * recorded hashes and signature before every activation (checklist
     * point 1). A file changed on disk after approval -- however that
     * happened -- fails this and [PluginStore] refuses to run it; so
     * does a plugin whose origin's user-trusted key was removed from
     * "Keys you trust", since [userKeys] is the same store
     * [install] verified against, and a plugin whose certificate or key a
     * revocation list has since withdrawn. A certificate that merely expired
     * does not stop an installed plugin. A plugin installed before the
     * certificate format (`manifest.sig`, no `manifest.jws`) is checked on
     * the bounded legacy path ([LegacyBundleSignature]) until it updates.
     */
    fun verifyInstalled(pluginsRoot: File, record: PluginRecord, userKeys: Map<String, String> = emptyMap()): PluginInstallError? {
        val dir = File(pluginsRoot, record.manifest.id)
        val manifestFile = File(dir, "manifest.json")
        val jwsFile = File(dir, BundleSignature.FILE_NAME)
        val legacySigFile = File(dir, LegacyBundleSignature.SIGNATURE_FILE)
        if (!manifestFile.isFile || (!jwsFile.isFile && !legacySigFile.isFile)) return PluginInstallError("plugin files are missing")
        val manifestBytes = manifestFile.readBytes()
        if (Sha256.hex(manifestBytes) != record.archiveDigest) {
            return PluginInstallError("manifest.json changed on disk since approval")
        }
        val revocations = PluginRevocations.load(pluginsRoot)
        val verdict = if (jwsFile.isFile) {
            BundleSignature.verifyBundle(manifestBytes, jwsFile.readText(), record.manifest.origin, record.manifest.id, userKeys, revocations)
        } else {
            val certificateFile = File(dir, LegacyBundleSignature.CERTIFICATE_FILE)
            LegacyBundleSignature.verifyInstalled(
                manifestBytes, legacySigFile.readText(), record.manifest.origin, record.manifest.id,
                if (certificateFile.isFile) certificateFile.readText() else null, userKeys, pluginsRoot, revocations,
            )
        }
        if (verdict is BundleVerdict.Refused) {
            return PluginInstallError("signature no longer verifies: ${verdict.reason}")
        }
        for (file in record.manifest.payload) {
            val target = File(dir, file.path)
            if (!target.isFile || !Sha256.hex(target).equals(file.sha256, ignoreCase = true)) {
                return PluginInstallError("${file.path} changed on disk since approval")
            }
        }
        return null
    }

    fun readRecord(pluginsRoot: File, pluginId: String): PluginRecord? {
        val file = File(pluginsRoot, "$pluginId/record.json")
        if (!file.isFile) return null
        return runCatching { PluginRecord.fromJson(JSONObject(file.readText())) }.getOrNull()
    }

    fun writeRecord(pluginsRoot: File, record: PluginRecord) {
        val dir = File(pluginsRoot, record.manifest.id)
        dir.mkdirs()
        File(dir, "record.json").writeText(record.toJson().toString())
        PluginEpoch.bump()
    }

    /** Built-in ids no plugin may ever declare (checklist point 2). */
    val PROTECTED_IDS = setOf("droidtop", "enginehost", "app", "settings", "library")
}
