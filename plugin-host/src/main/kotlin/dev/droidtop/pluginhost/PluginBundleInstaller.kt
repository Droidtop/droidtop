package dev.droidtop.pluginhost

import java.io.File
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
 * keys ("Keys you trust", [UserOriginKeys.loadBase64]): signature
 * verification resolves the official pinned key first, then those,
 * so a bundle from an origin the user trusted verifies here exactly
 * like an official one and an origin no key resolves for is still
 * refused outright.
 *
 * Bundle layout (tar, then xz -- same tar.xz choice as enginehost's
 * bundles and droidtop's own OCI layer handling, so this is the second
 * user of that tool combination, not a new one):
 *
 *   manifest.json     the [PluginManifest], UTF-8
 *   manifest.sig      base64 ECDSA-P256/SHA-256 signature over manifest.json's raw bytes
 *   <payload files>   exactly the paths [PluginManifest.payload] declares, nothing more, nothing fewer
 */
object PluginBundleInstaller {
    private const val MAX_MANIFEST_BYTES = 64 * 1024
    private const val MAX_ENTRY_BYTES = 64L * 1024 * 1024

    fun install(bundleFile: File, pluginsRoot: File, userKeys: Map<String, String> = emptyMap()): PluginInstallResult {
        val entries = linkedMapOf<String, ByteArray>()
        try {
            TarArchiveInputStream(XZCompressorInputStream(bundleFile.inputStream().buffered())).use { tar ->
                var entry: TarArchiveEntry? = tar.nextTarEntry
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
                        entries[name] = tar.readBytes()
                    }
                    entry = tar.nextTarEntry
                }
            }
        } catch (e: Exception) {
            return PluginInstallResult.Refused(PluginInstallError("couldn't read the bundle: ${e.message}"))
        }

        val manifestBytes = entries["manifest.json"]
            ?: return PluginInstallResult.Refused(PluginInstallError("bundle has no manifest.json"))
        if (manifestBytes.size > MAX_MANIFEST_BYTES) {
            return PluginInstallResult.Refused(PluginInstallError("manifest.json exceeds the ${MAX_MANIFEST_BYTES / 1024} KiB cap"))
        }
        val signatureBase64 = entries["manifest.sig"]?.toString(Charsets.US_ASCII)
            ?: return PluginInstallResult.Refused(PluginInstallError("bundle has no manifest.sig"))

        val manifest = runCatching { PluginManifest.fromJson(JSONObject(manifestBytes.toString(Charsets.UTF_8))) }
            .getOrNull() ?: return PluginInstallResult.Refused(PluginInstallError("manifest.json isn't a complete plugin manifest"))

        manifest.structuralProblems().firstOrNull()?.let {
            return PluginInstallResult.Refused(PluginInstallError(it))
        }

        // Checklist point 3: signature and schema before anything else touches disk as "the plugin".
        if (!BundleSignature.verifyManifest(manifestBytes, signatureBase64, manifest.origin, userKeys)) {
            return PluginInstallResult.Refused(PluginInstallError("signature doesn't verify against a trusted key for origin \"${manifest.origin}\""))
        }

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
        val extraFiles = entries.keys - declaredPaths - setOf("manifest.json", "manifest.sig")
        if (extraFiles.isNotEmpty()) {
            return PluginInstallResult.Refused(PluginInstallError("bundle contains files not declared in the manifest: ${extraFiles.joinToString()}"))
        }
        for (file in manifest.payload) {
            val bytes = entries[file.path]
                ?: return PluginInstallResult.Refused(PluginInstallError("manifest declares ${file.path} but the bundle doesn't contain it"))
            val actual = BundleSignature.sha256(bytes)
            if (!actual.equals(file.sha256, ignoreCase = true)) {
                return PluginInstallResult.Refused(PluginInstallError("hash mismatch for ${file.path}"))
            }
        }

        // Everything verified -- now, and only now, write to disk.
        if (!existingRoot.isDirectory) existingRoot.mkdirs() else existingRoot.listFiles()?.forEach { it.deleteRecursively() }
        File(existingRoot, "manifest.json").writeBytes(manifestBytes)
        File(existingRoot, "manifest.sig").writeText(signatureBase64)
        for (file in manifest.payload) {
            val target = File(existingRoot, file.path)
            target.parentFile?.mkdirs()
            target.writeBytes(entries.getValue(file.path))
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

        val digest = BundleSignature.sha256(manifestBytes)
        // The key this bundle verified against is the origin's pinned key
        // (the signature check above refused the install otherwise), so
        // its fingerprint is exactly what "signed by the same key" means
        // for the carry-over rule below.
        val keyFingerprint = BundleSignature.keyFingerprintFor(manifest.origin).orEmpty()
        // Approval carries over to an update signed by the SAME key the
        // plugin was approved under (docs/SPEC.md 12a, "Trust over
        // updates"): a re-install of the exact same bytes keeps whatever
        // state it had, and a different digest keeps APPROVED -- with its
        // enabled and root-approved bits -- when it verifies against that
        // same pinned key. Everything else starts PENDING: a first
        // install, an update of a plugin that was only ever PENDING, an
        // update signed by a different key (a rotation is a new trust
        // decision), and any update of a DENIED plugin (the user said no;
        // it re-enters at PENDING).
        val sameBytes = existingState?.archiveDigest == digest
        val carriedOver = sameBytes || (
            existingState?.trust == PluginTrustState.APPROVED &&
                existingState.manifest.origin == manifest.origin &&
                existingState.approvedKeySha256.isNotEmpty() &&
                existingState.approvedKeySha256 == keyFingerprint
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
            enabled = carriedOver && existingState!!.enabled,
            rootApproved = carriedOver && existingState!!.rootApproved && manifest.requestsRoot,
            disabledReason = null,
        )
        writeRecord(pluginsRoot, record)
        return PluginInstallResult.Installed(record)
    }

    /**
     * Re-verifies an already-installed plugin's files against its own
     * recorded hashes and signature before every activation (checklist
     * point 1). A file changed on disk after approval -- however that
     * happened -- fails this and [PluginStore] refuses to run it; so
     * does a plugin whose origin's user-trusted key was removed from
     * "Keys you trust", since [userKeys] is the same store
     * [install] verified against.
     */
    fun verifyInstalled(pluginsRoot: File, record: PluginRecord, userKeys: Map<String, String> = emptyMap()): PluginInstallError? {
        val dir = File(pluginsRoot, record.manifest.id)
        val manifestFile = File(dir, "manifest.json")
        val sigFile = File(dir, "manifest.sig")
        if (!manifestFile.isFile || !sigFile.isFile) return PluginInstallError("plugin files are missing")
        val manifestBytes = manifestFile.readBytes()
        if (BundleSignature.sha256(manifestBytes) != record.archiveDigest) {
            return PluginInstallError("manifest.json changed on disk since approval")
        }
        if (!BundleSignature.verifyManifest(manifestBytes, sigFile.readText(), record.manifest.origin, userKeys)) {
            return PluginInstallError("signature no longer verifies")
        }
        for (file in record.manifest.payload) {
            val target = File(dir, file.path)
            if (!target.isFile || BundleSignature.sha256(target.readBytes()) != file.sha256) {
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
    }

    /** Built-in ids no plugin may ever declare (checklist point 2). */
    val PROTECTED_IDS = setOf("droidtop", "enginehost", "app", "settings", "library")
}
