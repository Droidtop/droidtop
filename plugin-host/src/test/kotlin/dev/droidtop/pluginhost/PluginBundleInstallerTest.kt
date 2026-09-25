package dev.droidtop.pluginhost

import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** End-to-end: build a real signed tar.xz bundle in memory, exactly as PluginBundleInstaller expects, and run it through install(). */
class PluginBundleInstallerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val classesJarBytes = "not a real dex, just payload bytes for the hash/extract test".toByteArray()

    private fun buildBundle(
        origin: String,
        privateKey: java.security.PrivateKey,
        manifestOverride: (JSONObject) -> Unit = {},
        tamperPayloadAfterSigning: Boolean = false,
        includeExtraUndeclaredFile: Boolean = false,
    ): File {
        val classesSha = BundleSignature.sha256(classesJarBytes)
        val manifestJson = JSONObject().apply {
            put("id", "$origin.sample-statustile")
            put("origin", origin)
            put("label", "Sample status tile")
            put("kind", "native_bundle")
            put("capabilities", JSONArray(listOf("status_tile")))
            put("contractVersion", PLUGIN_CONTRACT_VERSION)
            put("abis", JSONArray(listOf("arm64-v8a", "x86_64")))
            put("entryClass", "dev.droidtop.samples.statustile.StatusTilePlugin")
            put("payload", JSONArray(listOf(JSONObject().put("path", "classes.jar").put("sha256", classesSha))))
        }
        manifestOverride(manifestJson)
        val manifestBytes = manifestJson.toString().toByteArray()
        val signature = Signature.getInstance("SHA256withECDSA").apply {
            initSign(privateKey)
            update(manifestBytes)
        }.sign()
        val signatureBase64 = Base64.getEncoder().encodeToString(signature)

        val payloadBytes = if (tamperPayloadAfterSigning) "tampered!".toByteArray() else classesJarBytes

        val out = ByteArrayOutputStream()
        TarArchiveOutputStream(XZCompressorOutputStream(out)).use { tar ->
            fun putEntry(name: String, bytes: ByteArray) {
                val entry = TarArchiveEntry(name)
                entry.size = bytes.size.toLong()
                tar.putArchiveEntry(entry)
                tar.write(bytes)
                tar.closeArchiveEntry()
            }
            putEntry("manifest.json", manifestBytes)
            putEntry("manifest.sig", signatureBase64.toByteArray())
            putEntry("classes.jar", payloadBytes)
            if (includeExtraUndeclaredFile) putEntry("sneaky.jar", "extra".toByteArray())
        }
        val file = tmp.newFile("bundle-${System.nanoTime()}.droidplugin.tar.xz")
        file.writeBytes(out.toByteArray())
        return file
    }

    private fun freshKeyPair() = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    @Test
    fun `installs a validly signed bundle and extracts its payload`() {
        val pair = freshKeyPair()
        val pluginsRoot = tmp.newFolder("plugins")
        PluginOriginKeys.withOrigin("testorigin", Base64.getEncoder().encodeToString(pair.public.encoded)) {
            val bundle = buildBundle("testorigin", pair.private)
            val result = PluginBundleInstaller.install(bundle, pluginsRoot)
            assertTrue(result is PluginInstallResult.Installed)
            val record = (result as PluginInstallResult.Installed).record
            assertEquals(PluginTrustState.PENDING, record.trust)
            val extracted = File(pluginsRoot, "testorigin.sample-statustile/classes.jar")
            assertTrue(extracted.isFile)
            assertTrue(extracted.readBytes().contentEquals(classesJarBytes))
        }
    }

    @Test
    fun `refuses a bundle whose signature does not verify`() {
        val pair = freshKeyPair()
        val impostor = freshKeyPair()
        val pluginsRoot = tmp.newFolder("plugins")
        PluginOriginKeys.withOrigin("testorigin", Base64.getEncoder().encodeToString(pair.public.encoded)) {
            val bundle = buildBundle("testorigin", impostor.private)
            val result = PluginBundleInstaller.install(bundle, pluginsRoot)
            assertTrue(result is PluginInstallResult.Refused)
            assertTrue((result as PluginInstallResult.Refused).error.reason.contains("signature"))
        }
    }

    @Test
    fun `refuses a bundle whose payload hash does not match`() {
        val pair = freshKeyPair()
        val pluginsRoot = tmp.newFolder("plugins")
        PluginOriginKeys.withOrigin("testorigin", Base64.getEncoder().encodeToString(pair.public.encoded)) {
            val bundle = buildBundle("testorigin", pair.private, tamperPayloadAfterSigning = true)
            val result = PluginBundleInstaller.install(bundle, pluginsRoot)
            assertTrue(result is PluginInstallResult.Refused)
            assertTrue((result as PluginInstallResult.Refused).error.reason.contains("hash mismatch"))
        }
    }

    @Test
    fun `refuses a bundle carrying a file the manifest never declared`() {
        val pair = freshKeyPair()
        val pluginsRoot = tmp.newFolder("plugins")
        PluginOriginKeys.withOrigin("testorigin", Base64.getEncoder().encodeToString(pair.public.encoded)) {
            val bundle = buildBundle("testorigin", pair.private, includeExtraUndeclaredFile = true)
            val result = PluginBundleInstaller.install(bundle, pluginsRoot)
            assertTrue(result is PluginInstallResult.Refused)
            assertTrue((result as PluginInstallResult.Refused).error.reason.contains("not declared"))
        }
    }

    @Test
    fun `refuses a plugin id claimed by another origin`() {
        val pair = freshKeyPair()
        val other = freshKeyPair()
        val pluginsRoot = tmp.newFolder("plugins")
        PluginOriginKeys.withOrigin("testorigin", Base64.getEncoder().encodeToString(pair.public.encoded)) {
            PluginOriginKeys.withOrigin("otherorigin", Base64.getEncoder().encodeToString(other.public.encoded)) {
                // First install under "testorigin" claims testorigin.sample-statustile.
                PluginBundleInstaller.install(buildBundle("testorigin", pair.private), pluginsRoot)
                // A same-id manifest but reporting a different origin should be refused,
                // since fromJson's own namespace check ties id to origin the manifest itself
                // is already rejected for by structuralProblems(); exercise the store-level
                // guard instead with a same-origin-string but different signer forged onto
                // the existing id via a crafted id under "otherorigin"'s own namespace that
                // happens to collide is not constructible here, so this test instead confirms
                // a genuinely different origin cannot silently take over an id namespaced to
                // the first.
                val collidingManifest: (JSONObject) -> Unit = { json -> json.put("id", "testorigin.sample-statustile") }
                val result = PluginBundleInstaller.install(
                    buildBundle("otherorigin", other.private, manifestOverride = collidingManifest),
                    pluginsRoot,
                )
                assertTrue(result is PluginInstallResult.Refused)
            }
        }
    }

    @Test
    fun `verifyInstalled catches a payload file edited after approval`() {
        val pair = freshKeyPair()
        val pluginsRoot = tmp.newFolder("plugins")
        PluginOriginKeys.withOrigin("testorigin", Base64.getEncoder().encodeToString(pair.public.encoded)) {
            val installed = PluginBundleInstaller.install(buildBundle("testorigin", pair.private), pluginsRoot)
            val record = (installed as PluginInstallResult.Installed).record
            assertTrue(PluginBundleInstaller.verifyInstalled(pluginsRoot, record) == null)

            File(pluginsRoot, "testorigin.sample-statustile/classes.jar").writeBytes("edited on disk".toByteArray())
            val problem = PluginBundleInstaller.verifyInstalled(pluginsRoot, record)
            assertTrue(problem != null && problem.reason.contains("classes.jar"))
        }
    }
}
