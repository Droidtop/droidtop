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
        payloadBytes: ByteArray = classesJarBytes,
    ): File {
        val classesSha = BundleSignature.sha256(payloadBytes)
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

        val actualPayloadBytes = if (tamperPayloadAfterSigning) "tampered!".toByteArray() else payloadBytes

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
            putEntry("classes.jar", actualPayloadBytes)
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
    fun `refuses a bundle from an origin no key anywhere resolves for`() {
        val pair = freshKeyPair()
        val pluginsRoot = tmp.newFolder("plugins")
        // No withOrigin hook, no user keys: "stranger" is unknown, and an
        // unknown origin is refused outright -- never "installed but
        // unverified".
        val result = PluginBundleInstaller.install(buildBundle("stranger", pair.private), pluginsRoot)
        assertTrue(result is PluginInstallResult.Refused)
        assertTrue((result as PluginInstallResult.Refused).error.reason.contains("trusted key"))
    }

    @Test
    fun `installs a user-trusted origin's bundle, then refuses its update once the key is removed`() {
        val pair = freshKeyPair()
        val pluginsRoot = tmp.newFolder("plugins")
        val keyStore = tmp.newFile("plugin-user-keys.json")
        UserOriginKeys.add(keyStore, "acme", Base64.getEncoder().encodeToString(pair.public.encoded), source = "https://github.com/acme/acme-plugins")

        val userKeys = UserOriginKeys.loadBase64(keyStore)
        val installed = PluginBundleInstaller.install(buildBundle("acme", pair.private), pluginsRoot, userKeys)
        assertTrue(installed is PluginInstallResult.Installed)

        // An update (different bytes, same origin, same key) verifies while the key is trusted...
        val update = PluginBundleInstaller.install(
            buildBundle("acme", pair.private, manifestOverride = { it.put("label", "Sample status tile v2") }),
            pluginsRoot,
            userKeys,
        )
        assertTrue(update is PluginInstallResult.Installed)
        val record = (update as PluginInstallResult.Installed).record

        // ...and is refused the moment the origin's key is removed: no key
        // resolves for "acme" anymore, so the signature check fails the
        // same way an unknown origin's always did.
        assertTrue(UserOriginKeys.remove(keyStore, "acme"))
        val removedKeys = UserOriginKeys.loadBase64(keyStore)
        val refusedUpdate = PluginBundleInstaller.install(buildBundle("acme", pair.private), pluginsRoot, removedKeys)
        assertTrue(refusedUpdate is PluginInstallResult.Refused)
        assertTrue((refusedUpdate as PluginInstallResult.Refused).error.reason.contains("signature"))

        // The already-installed plugin stops running too: re-verification
        // before activation fails on the same missing key.
        val problem = PluginBundleInstaller.verifyInstalled(pluginsRoot, record, removedKeys)
        assertTrue(problem != null && problem.reason.contains("signature"))
        // Nothing was lost: with the key trusted again, the same files verify.
        UserOriginKeys.add(keyStore, "acme", Base64.getEncoder().encodeToString(pair.public.encoded), source = null)
        assertTrue(PluginBundleInstaller.verifyInstalled(pluginsRoot, record, UserOriginKeys.loadBase64(keyStore)) == null)
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

            // PluginBundleInstaller now marks every payload file read-only right
            // after writing it (Android 10+ refuses DexClassLoader on a writable
            // dex file otherwise) -- simulating a real tamperer here needs the
            // same setWritable(true) step an actual attacker with filesystem
            // access could always do, or this write fails with
            // FileNotFoundException (permission denied) before ever reaching
            // the check this test is actually exercising.
            val edited = File(pluginsRoot, "testorigin.sample-statustile/classes.jar")
            edited.setWritable(true)
            edited.writeBytes("edited on disk".toByteArray())
            val problem = PluginBundleInstaller.verifyInstalled(pluginsRoot, record)
            assertTrue(problem != null && problem.reason.contains("classes.jar"))
        }
    }

    /** Simulates the approval screen: the one place allowed to move a record PENDING -> APPROVED. */
    private fun approve(pluginsRoot: File, record: PluginRecord, enabled: Boolean = true, rootApproved: Boolean = false) {
        PluginBundleInstaller.writeRecord(
            pluginsRoot,
            record.copy(trust = PluginTrustState.APPROVED, enabled = enabled, rootApproved = rootApproved),
        )
    }

    @Test
    fun `an update signed by the same key carries approval over to the new digest`() {
        val pair = freshKeyPair()
        val pluginsRoot = tmp.newFolder("plugins")
        PluginOriginKeys.withOrigin("testorigin", Base64.getEncoder().encodeToString(pair.public.encoded)) {
            val installed = PluginBundleInstaller.install(buildBundle("testorigin", pair.private), pluginsRoot)
            val first = (installed as PluginInstallResult.Installed).record
            approve(pluginsRoot, first)
            // A genuinely different digest: new payload bytes, same signer.
            val updated = buildBundle("testorigin", pair.private, payloadBytes = "payload, version two".toByteArray())
            val result = PluginBundleInstaller.install(updated, pluginsRoot)
            val record = (result as PluginInstallResult.Installed).record
            assertTrue(record.archiveDigest != first.archiveDigest)
            assertEquals(PluginTrustState.APPROVED, record.trust)
            assertTrue(record.enabled)
        }
    }

    @Test
    fun `an update signed by a different key does not carry approval over`() {
        val pair = freshKeyPair()
        val rotated = freshKeyPair()
        val pluginsRoot = tmp.newFolder("plugins")
        PluginOriginKeys.withOrigin("testorigin", Base64.getEncoder().encodeToString(pair.public.encoded)) {
            val installed = PluginBundleInstaller.install(buildBundle("testorigin", pair.private), pluginsRoot)
            approve(pluginsRoot, (installed as PluginInstallResult.Installed).record)
        }
        // The origin now publishes a different key: a rotation is a new trust decision.
        PluginOriginKeys.withOrigin("testorigin", Base64.getEncoder().encodeToString(rotated.public.encoded)) {
            val updated = buildBundle("testorigin", rotated.private, payloadBytes = "payload, version two".toByteArray())
            val result = PluginBundleInstaller.install(updated, pluginsRoot)
            val record = (result as PluginInstallResult.Installed).record
            assertEquals(PluginTrustState.PENDING, record.trust)
        }
    }

    @Test
    fun `an update of a denied plugin does not carry the denial state over, but never approves either`() {
        val pair = freshKeyPair()
        val pluginsRoot = tmp.newFolder("plugins")
        PluginOriginKeys.withOrigin("testorigin", Base64.getEncoder().encodeToString(pair.public.encoded)) {
            val installed = PluginBundleInstaller.install(buildBundle("testorigin", pair.private), pluginsRoot)
            val first = (installed as PluginInstallResult.Installed).record
            PluginBundleInstaller.writeRecord(pluginsRoot, first.copy(trust = PluginTrustState.DENIED, enabled = false))
            val updated = buildBundle("testorigin", pair.private, payloadBytes = "payload, version two".toByteArray())
            val result = PluginBundleInstaller.install(updated, pluginsRoot)
            val record = (result as PluginInstallResult.Installed).record
            assertEquals(PluginTrustState.PENDING, record.trust)
        }
    }

    @Test
    fun `root approval carries over to an update that still requests root, and drops when it stops`() {
        val pair = freshKeyPair()
        val pluginsRoot = tmp.newFolder("plugins")
        val requestsRoot: (JSONObject) -> Unit = { json -> json.put("requestsRoot", true) }
        PluginOriginKeys.withOrigin("testorigin", Base64.getEncoder().encodeToString(pair.public.encoded)) {
            val installed = PluginBundleInstaller.install(
                buildBundle("testorigin", pair.private, manifestOverride = requestsRoot),
                pluginsRoot,
            )
            approve(pluginsRoot, (installed as PluginInstallResult.Installed).record, rootApproved = true)

            val stillAsking = buildBundle(
                "testorigin",
                pair.private,
                manifestOverride = requestsRoot,
                payloadBytes = "payload, version two".toByteArray(),
            )
            val record = (PluginBundleInstaller.install(stillAsking, pluginsRoot) as PluginInstallResult.Installed).record
            assertEquals(PluginTrustState.APPROVED, record.trust)
            assertTrue(record.rootApproved)

            val stoppedAsking = buildBundle("testorigin", pair.private, payloadBytes = "payload, version three".toByteArray())
            val after = (PluginBundleInstaller.install(stoppedAsking, pluginsRoot) as PluginInstallResult.Installed).record
            assertEquals(PluginTrustState.APPROVED, after.trust)
            assertTrue(!after.rootApproved)
        }
    }

    @Test
    fun `a re-install of the exact same bytes keeps its existing state, approved or denied`() {
        val pair = freshKeyPair()
        val pluginsRoot = tmp.newFolder("plugins")
        PluginOriginKeys.withOrigin("testorigin", Base64.getEncoder().encodeToString(pair.public.encoded)) {
            val installed = PluginBundleInstaller.install(buildBundle("testorigin", pair.private), pluginsRoot)
            val first = (installed as PluginInstallResult.Installed).record
            approve(pluginsRoot, first)
            val reinstalled = (PluginBundleInstaller.install(buildBundle("testorigin", pair.private), pluginsRoot) as PluginInstallResult.Installed).record
            assertEquals(first.archiveDigest, reinstalled.archiveDigest)
            assertEquals(PluginTrustState.APPROVED, reinstalled.trust)
            assertTrue(reinstalled.enabled)
        }
    }
}
