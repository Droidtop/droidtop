package dev.droidtop.pluginhost

import dev.droidtop.runtime.util.Sha256

import java.io.ByteArrayOutputStream
import java.io.File
import dev.droidtop.runtime.util.ChainSignature
import dev.droidtop.runtime.util.DroidtopSigningRoot
import dev.droidtop.testpki.TestPki
import java.security.KeyPair
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.X509Certificate
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

    /** The anchors trusted for the test running now ([trusting]), as "Keys you trust" hands them to the installer. */
    private var trusted = mapOf<String, String>()

    private fun <T> trusting(origin: String, pair: KeyPair, block: () -> T): T {
        val previous = trusted
        trusted = trusted + (origin to Base64.getEncoder().encodeToString(pair.public.encoded))
        try {
            return block()
        } finally {
            trusted = previous
        }
    }

    private fun install(bundle: File, root: File, keys: Map<String, String> = trusted) = PluginBundleInstaller.install(bundle, root, keys)

    private fun verifyInstalled(root: File, record: PluginRecord, keys: Map<String, String> = trusted) = PluginBundleInstaller.verifyInstalled(root, record, keys)

    /** A publisher's self-signed signing certificate for [origin], the simplest chain a trusted key anchors. */
    private fun selfSigned(pair: KeyPair, origin: String): X509Certificate =
        TestPki.cert("publisher $origin", pair.public).eku(TestPki.PLUGIN).uri(ChainSignature.ORIGIN_URN + origin).selfSigned(pair.private).build()

    private fun buildBundle(
        origin: String,
        pair: KeyPair,
        manifestOverride: (JSONObject) -> Unit = {},
        tamperPayloadAfterSigning: Boolean = false,
        includeExtraUndeclaredFile: Boolean = false,
        payloadBytes: ByteArray = classesJarBytes,
        signWith: PrivateKey = pair.private,
        chain: List<X509Certificate> = listOf(selfSigned(pair, origin)),
        legacyOnly: Boolean = false,
    ): File {
        val classesSha = Sha256.hex(payloadBytes)
        val manifestJson = JSONObject().apply {
            put("id", "$origin.statustile")
            put("origin", origin)
            put("label", "Status tile")
            put("kind", "native_bundle")
            put("capabilities", JSONArray(listOf("status_tile")))
            put("contractVersion", PLUGIN_CONTRACT_VERSION)
            put("abis", JSONArray(listOf("arm64-v8a", "x86_64")))
            put("entryClass", "dev.droidtop.testplugin.StatusTilePlugin")
            put("payload", JSONArray(listOf(JSONObject().put("path", "classes.jar").put("sha256", classesSha))))
        }
        manifestOverride(manifestJson)
        val manifestBytes = manifestJson.toString().toByteArray()

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
            if (legacyOnly) {
                putEntry("manifest.sig", legacySignature(pair.private, manifestBytes).toByteArray())
            } else {
                putEntry("manifest.jws", TestPki.detached(ChainSignature.Purpose.PLUGIN.typ, chain, signWith, manifestBytes).toByteArray())
            }
            putEntry("classes.jar", actualPayloadBytes)
            if (includeExtraUndeclaredFile) putEntry("sneaky.jar", "extra".toByteArray())
        }
        val file = tmp.newFile("bundle-${System.nanoTime()}.droidplugin.tar.xz")
        file.writeBytes(out.toByteArray())
        return file
    }

    private fun freshKeyPair() = TestPki.ec()

    /** The classes.jar of the version the installed record runs. */
    private fun installedJar(root: File): File =
        File(PluginBundleInstaller.installedDir(root, PluginBundleInstaller.readRecord(root, "testorigin.statustile")!!), "classes.jar")

    /** The old format's manifest signature: base64 DER ECDSA over the manifest bytes. */
    private fun legacySignature(key: PrivateKey, data: ByteArray): String =
        Base64.getEncoder().encodeToString(Signature.getInstance("SHA256withECDSA").apply { initSign(key); update(data) }.sign())

    @Test
    fun `installs a validly signed bundle and extracts its payload`() {
        val pair = freshKeyPair()
        val pluginsRoot = tmp.newFolder("plugins")
        trusting("testorigin", pair) {
            val bundle = buildBundle("testorigin", pair)
            val result = install(bundle, pluginsRoot)
            assertTrue(result is PluginInstallResult.Installed)
            val record = (result as PluginInstallResult.Installed).record
            assertEquals(PluginTrustState.PENDING, record.trust)
            val extracted = installedJar(pluginsRoot)
            assertTrue(extracted.isFile)
            assertTrue(extracted.readBytes().contentEquals(classesJarBytes))
        }
    }

    @Test
    fun `streams payload through a staging folder that is gone afterwards, refused or installed`() {
        val pair = freshKeyPair()
        val pluginsRoot = tmp.newFolder("plugins-staging")
        trusting("testorigin", pair) {
            val big = ByteArray(3 * 1024 * 1024) { (it * 31).toByte() }
            assertTrue(install(buildBundle("testorigin", pair, payloadBytes = big), pluginsRoot) is PluginInstallResult.Installed)
            assertTrue(installedJar(pluginsRoot).readBytes().contentEquals(big))
            val refused = install(buildBundle("testorigin", pair, tamperPayloadAfterSigning = true), pluginsRoot)
            assertTrue(refused is PluginInstallResult.Refused)
            // A refused update leaves the installed version exactly as it was.
            assertTrue(installedJar(pluginsRoot).readBytes().contentEquals(big))
            assertEquals(emptyList<String>(), pluginsRoot.parentFile.list().orEmpty().filter { it.startsWith(".plugin-install-") })
        }
    }

    @Test
    fun `refuses a bundle whose signature does not verify`() {
        val pair = freshKeyPair()
        val impostor = freshKeyPair()
        val pluginsRoot = tmp.newFolder("plugins")
        trusting("testorigin", pair) {
            val bundle = buildBundle("testorigin", pair, signWith = impostor.private)
            val result = install(bundle, pluginsRoot)
            assertTrue(result is PluginInstallResult.Refused)
            assertTrue((result as PluginInstallResult.Refused).error.reason.contains("signature"))
        }
    }

    @Test
    fun `refuses a bundle from an origin no key anywhere resolves for`() {
        val pair = freshKeyPair()
        val pluginsRoot = tmp.newFolder("plugins")
        // Nothing trusted: "stranger" is unknown, and an
        // unknown origin is refused outright -- never "installed but
        // unverified".
        val result = install(buildBundle("stranger", pair), pluginsRoot)
        assertTrue(result is PluginInstallResult.Refused)
        assertTrue((result as PluginInstallResult.Refused).error.reason.contains("no key you trust"))
    }

    @Test
    fun `installs a user-trusted origin's bundle, then refuses its update once the key is removed`() {
        val pair = freshKeyPair()
        val pluginsRoot = tmp.newFolder("plugins")
        val keyStore = tmp.newFile("plugin-user-keys.json")
        UserOriginKeys.add(keyStore, "acme", Base64.getEncoder().encodeToString(pair.public.encoded), source = "https://github.com/acme/acme-plugins")

        val userKeys = UserOriginKeys.loadBase64(keyStore)
        val installed = install(buildBundle("acme", pair), pluginsRoot, userKeys)
        assertTrue(installed is PluginInstallResult.Installed)

        // An update (different bytes, same origin, same key) verifies while the key is trusted...
        val update = install(
            buildBundle("acme", pair, manifestOverride = { it.put("label", "Status tile v2") }),
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
        val refusedUpdate = install(buildBundle("acme", pair), pluginsRoot, removedKeys)
        assertTrue(refusedUpdate is PluginInstallResult.Refused)
        assertTrue((refusedUpdate as PluginInstallResult.Refused).error.reason.contains("no key you trust"))

        // The already-installed plugin stops running too: re-verification
        // before activation fails on the same missing key.
        val problem = verifyInstalled(pluginsRoot, record, removedKeys)
        assertTrue(problem != null && problem.reason.contains("signature"))
        // Nothing was lost: with the key trusted again, the same files verify.
        UserOriginKeys.add(keyStore, "acme", Base64.getEncoder().encodeToString(pair.public.encoded), source = null)
        assertTrue(verifyInstalled(pluginsRoot, record, UserOriginKeys.loadBase64(keyStore)) == null)
    }

    @Test
    fun `refuses a bundle whose payload hash does not match`() {
        val pair = freshKeyPair()
        val pluginsRoot = tmp.newFolder("plugins")
        trusting("testorigin", pair) {
            val bundle = buildBundle("testorigin", pair, tamperPayloadAfterSigning = true)
            val result = install(bundle, pluginsRoot)
            assertTrue(result is PluginInstallResult.Refused)
            assertTrue((result as PluginInstallResult.Refused).error.reason.contains("hash mismatch"))
        }
    }

    @Test
    fun `refuses a bundle carrying a file the manifest never declared`() {
        val pair = freshKeyPair()
        val pluginsRoot = tmp.newFolder("plugins")
        trusting("testorigin", pair) {
            val bundle = buildBundle("testorigin", pair, includeExtraUndeclaredFile = true)
            val result = install(bundle, pluginsRoot)
            assertTrue(result is PluginInstallResult.Refused)
            assertTrue((result as PluginInstallResult.Refused).error.reason.contains("not declared"))
        }
    }

    @Test
    fun `refuses a plugin id claimed by another origin`() {
        val pair = freshKeyPair()
        val other = freshKeyPair()
        val pluginsRoot = tmp.newFolder("plugins")
        trusting("testorigin", pair) {
            trusting("otherorigin", other) {
                // First install under "testorigin" claims testorigin.statustile.
                install(buildBundle("testorigin", pair), pluginsRoot)
                // A same-id manifest but reporting a different origin should be refused,
                // since fromJson's own namespace check ties id to origin the manifest itself
                // is already rejected for by structuralProblems(); exercise the store-level
                // guard instead with a same-origin-string but different signer forged onto
                // the existing id via a crafted id under "otherorigin"'s own namespace that
                // happens to collide is not constructible here, so this test instead confirms
                // a genuinely different origin cannot silently take over an id namespaced to
                // the first.
                val collidingManifest: (JSONObject) -> Unit = { json -> json.put("id", "testorigin.statustile") }
                val result = install(
                    buildBundle("otherorigin", other, manifestOverride = collidingManifest),
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
        trusting("testorigin", pair) {
            val installed = install(buildBundle("testorigin", pair), pluginsRoot)
            val record = (installed as PluginInstallResult.Installed).record
            assertTrue(verifyInstalled(pluginsRoot, record) == null)

            // PluginBundleInstaller now marks every payload file read-only right
            // after writing it (Android 10+ refuses DexClassLoader on a writable
            // dex file otherwise) -- simulating a real tamperer here needs the
            // same setWritable(true) step an actual attacker with filesystem
            // access could always do, or this write fails with
            // FileNotFoundException (permission denied) before ever reaching
            // the check this test is actually exercising.
            val edited = installedJar(pluginsRoot)
            edited.setWritable(true)
            edited.writeBytes("edited on disk".toByteArray())
            val problem = verifyInstalled(pluginsRoot, record)
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
        trusting("testorigin", pair) {
            val installed = install(buildBundle("testorigin", pair), pluginsRoot)
            val first = (installed as PluginInstallResult.Installed).record
            approve(pluginsRoot, first)
            // A genuinely different digest: new payload bytes, same signer.
            val updated = buildBundle("testorigin", pair, payloadBytes = "payload, version two".toByteArray())
            val result = install(updated, pluginsRoot)
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
        trusting("testorigin", pair) {
            val installed = install(buildBundle("testorigin", pair), pluginsRoot)
            approve(pluginsRoot, (installed as PluginInstallResult.Installed).record)
        }
        // The origin now publishes a different key: a rotation is a new trust decision.
        trusting("testorigin", rotated) {
            val updated = buildBundle("testorigin", rotated, payloadBytes = "payload, version two".toByteArray())
            val result = install(updated, pluginsRoot)
            val record = (result as PluginInstallResult.Installed).record
            assertEquals(PluginTrustState.PENDING, record.trust)
        }
    }

    @Test
    fun `an update signed by the confirmed successor of the approved key carries approval and grants over`() {
        val pair = freshKeyPair()
        val rotated = freshKeyPair()
        val pluginsRoot = tmp.newFolder("plugins")
        val first = trusting("testorigin", pair) {
            (install(buildBundle("testorigin", pair), pluginsRoot) as PluginInstallResult.Installed).record
        }
        approve(pluginsRoot, first)
        PluginGrants.forPluginsRoot(pluginsRoot).setCategory(first.manifest.id, "network", GrantState.DENIED)
        trusting("testorigin", rotated) {
            val updated = buildBundle("testorigin", rotated, payloadBytes = "payload, version two".toByteArray())
            val result = PluginBundleInstaller.install(updated, pluginsRoot, trusted, succeeds = { origin, approved, verified ->
                origin == "testorigin" && approved == first.approvedKeySha256 && verified != approved
            })
            val record = (result as PluginInstallResult.Installed).record
            assertEquals(PluginTrustState.APPROVED, record.trust)
            assertTrue(record.enabled)
            assertEquals(GrantState.DENIED, PluginGrants.forPluginsRoot(pluginsRoot).read(record.manifest.id).states[PluginPermissionPolicy.categoryKey("network")])
        }
    }

    @Test
    fun `an update of a denied plugin does not carry the denial state over, but never approves either`() {
        val pair = freshKeyPair()
        val pluginsRoot = tmp.newFolder("plugins")
        trusting("testorigin", pair) {
            val installed = install(buildBundle("testorigin", pair), pluginsRoot)
            val first = (installed as PluginInstallResult.Installed).record
            PluginBundleInstaller.writeRecord(pluginsRoot, first.copy(trust = PluginTrustState.DENIED, enabled = false))
            val updated = buildBundle("testorigin", pair, payloadBytes = "payload, version two".toByteArray())
            val result = install(updated, pluginsRoot)
            val record = (result as PluginInstallResult.Installed).record
            assertEquals(PluginTrustState.PENDING, record.trust)
        }
    }

    @Test
    fun `root approval carries over to an update that still requests root, and drops when it stops`() {
        val pair = freshKeyPair()
        val pluginsRoot = tmp.newFolder("plugins")
        val requestsRoot: (JSONObject) -> Unit = { json -> json.put("requestsRoot", true) }
        trusting("testorigin", pair) {
            val installed = install(
                buildBundle("testorigin", pair, manifestOverride = requestsRoot),
                pluginsRoot,
            )
            approve(pluginsRoot, (installed as PluginInstallResult.Installed).record, rootApproved = true)

            val stillAsking = buildBundle("testorigin",
                pair,
                manifestOverride = requestsRoot,
                payloadBytes = "payload, version two".toByteArray(),
            )
            val record = (install(stillAsking, pluginsRoot) as PluginInstallResult.Installed).record
            assertEquals(PluginTrustState.APPROVED, record.trust)
            assertTrue(record.rootApproved)

            val stoppedAsking = buildBundle("testorigin", pair, payloadBytes = "payload, version three".toByteArray())
            val after = (install(stoppedAsking, pluginsRoot) as PluginInstallResult.Installed).record
            assertEquals(PluginTrustState.APPROVED, after.trust)
            assertTrue(!after.rootApproved)
        }
    }

    @Test
    fun `a re-install of the exact same bytes keeps its existing state, approved or denied`() {
        val pair = freshKeyPair()
        val pluginsRoot = tmp.newFolder("plugins")
        trusting("testorigin", pair) {
            val installed = install(buildBundle("testorigin", pair), pluginsRoot)
            val first = (installed as PluginInstallResult.Installed).record
            approve(pluginsRoot, first)
            val reinstalled = (install(buildBundle("testorigin", pair), pluginsRoot) as PluginInstallResult.Installed).record
            assertEquals(first.archiveDigest, reinstalled.archiveDigest)
            assertEquals(PluginTrustState.APPROVED, reinstalled.trust)
            assertTrue(reinstalled.enabled)
        }
    }

    // ----- The certificate format against Droidtop Core, and the bounded legacy path -----

    @Test
    fun `an official bundle installs through a leaf the signing root issued, and not without a pinned root`() {
        val coreKey = TestPki.rsa()
        val core = TestPki.core(coreKey)
        val leafKey = freshKeyPair()
        val leaf = TestPki.pluginLeaf(leafKey, "droidtop", core, coreKey.private)
        val pluginsRoot = tmp.newFolder("plugins")
        val bundle = buildBundle("droidtop", leafKey, chain = listOf(leaf))
        DroidtopSigningRoot.withPinned(null) {
            val refused = install(bundle, pluginsRoot)
            assertTrue((refused as PluginInstallResult.Refused).error.reason.contains("signing root"))
        }
        DroidtopSigningRoot.withPinned(core.publicKey) {
            val record = (install(bundle, pluginsRoot) as PluginInstallResult.Installed).record
            assertEquals(Sha256.hex(core.publicKey.encoded), record.approvedKeySha256)
            assertTrue(File(PluginBundleInstaller.installedDir(pluginsRoot, record), "manifest.jws").isFile)
            assertTrue(verifyInstalled(pluginsRoot, record) == null)
            // A user key claiming the official origin changes nothing: the official origin resolves to Core first.
            val forger = freshKeyPair()
            val forged = buildBundle("droidtop", forger, payloadBytes = "forged".toByteArray())
            val keys = mapOf("droidtop" to Base64.getEncoder().encodeToString(forger.public.encoded))
            assertTrue(install(forged, pluginsRoot, keys) is PluginInstallResult.Refused)
        }
    }

    @Test
    fun `a bundle signed only the old way is not installed, and says why`() {
        val pair = freshKeyPair()
        val pluginsRoot = tmp.newFolder("plugins")
        trusting("testorigin", pair) {
            val result = install(buildBundle("testorigin", pair, legacyOnly = true), pluginsRoot)
            assertTrue((result as PluginInstallResult.Refused).error.reason.contains("signed the old way"))
        }
    }

    @Test
    fun `a plugin installed the old way keeps verifying until it updates, and its approval carries over`() {
        val pair = freshKeyPair()
        val pluginsRoot = tmp.newFolder("plugins")
        trusting("testorigin", pair) {
            // What an older droidtop left on disk: manifest.json, manifest.sig and an approved record.
            val manifestBytes = """{"id":"testorigin.statustile","origin":"testorigin","label":"Status tile","kind":"native_bundle","capabilities":["status_tile"],"contractVersion":$PLUGIN_CONTRACT_VERSION,"abis":["arm64-v8a","x86_64"],"entryClass":"dev.droidtop.testplugin.StatusTilePlugin","payload":[{"path":"classes.jar","sha256":"${Sha256.hex(classesJarBytes)}"}]}""".toByteArray()
            val dir = File(pluginsRoot, "testorigin.statustile").also { it.mkdirs() }
            File(dir, "manifest.json").writeBytes(manifestBytes)
            File(dir, "manifest.sig").writeText(legacySignature(pair.private, manifestBytes))
            File(dir, "classes.jar").writeBytes(classesJarBytes)
            val manifest = PluginManifest.fromJson(JSONObject(manifestBytes.toString(Charsets.UTF_8)))!!
            val legacy = PluginRecord(manifest, Sha256.hex(manifestBytes), Sha256.hex(pair.public.encoded), PluginTrustState.APPROVED, enabled = true, rootApproved = false, disabledReason = null)
            PluginBundleInstaller.writeRecord(pluginsRoot, legacy)
            assertTrue(verifyInstalled(pluginsRoot, legacy) == null)
            // A tampered legacy manifest signature stops it, as before.
            File(dir, "manifest.sig").writeText(legacySignature(freshKeyPair().private, manifestBytes))
            assertTrue(verifyInstalled(pluginsRoot, legacy) != null)
            File(dir, "manifest.sig").writeText(legacySignature(pair.private, manifestBytes))
            // The publisher wraps the same key in a certificate: the update installs over it, approval carried over.
            val update = (install(buildBundle("testorigin", pair, payloadBytes = "payload, version two".toByteArray()), pluginsRoot) as PluginInstallResult.Installed).record
            assertEquals(PluginTrustState.APPROVED, update.trust)
            assertTrue(verifyInstalled(pluginsRoot, update) == null)
            // The old version stays while a caller may still hold its record; the next update removes it, data kept.
            assertTrue(verifyInstalled(pluginsRoot, legacy) == null)
            File(dir, "data").mkdirs()
            File(dir, "data/kept.txt").writeText("mine")
            val third = (install(buildBundle("testorigin", pair, payloadBytes = "payload, version three".toByteArray()), pluginsRoot) as PluginInstallResult.Installed).record
            assertTrue(verifyInstalled(pluginsRoot, third) == null)
            assertTrue(!File(dir, "manifest.sig").exists() && !File(dir, "manifest.json").exists() && !File(dir, "classes.jar").exists())
            assertEquals("mine", File(dir, "data/kept.txt").readText())
        }
    }

    @Test
    fun `a check during an update sees one whole version, never the new manifest against the old record`() {
        val pair = freshKeyPair()
        val pluginsRoot = tmp.newFolder("plugins")
        trusting("testorigin", pair) {
            val first = (install(buildBundle("testorigin", pair), pluginsRoot) as PluginInstallResult.Installed).record
            approve(pluginsRoot, first)
            val approved = PluginBundleInstaller.readRecord(pluginsRoot, first.manifest.id)!!
            val second = (install(buildBundle("testorigin", pair, payloadBytes = "version two".toByteArray()), pluginsRoot) as PluginInstallResult.Installed).record
            // A caller that read the record before the update still verifies the version it read, and the new record its own.
            assertTrue(verifyInstalled(pluginsRoot, approved) == null)
            assertTrue(verifyInstalled(pluginsRoot, second) == null)
            assertTrue(PluginBundleInstaller.installedDir(pluginsRoot, approved) != PluginBundleInstaller.installedDir(pluginsRoot, second))
            // The third version drops the first; the second stays for whoever read it last.
            val third = (install(buildBundle("testorigin", pair, payloadBytes = "version three".toByteArray()), pluginsRoot) as PluginInstallResult.Installed).record
            assertTrue(!PluginBundleInstaller.versionDir(pluginsRoot, first.manifest.id, first.archiveDigest).exists())
            assertTrue(verifyInstalled(pluginsRoot, second) == null)
            assertTrue(verifyInstalled(pluginsRoot, third) == null)
        }
    }

    @Test
    fun `an update keeps the plugin's data and contexts`() {
        val pair = freshKeyPair()
        val pluginsRoot = tmp.newFolder("plugins")
        trusting("testorigin", pair) {
            val first = (install(buildBundle("testorigin", pair), pluginsRoot) as PluginInstallResult.Installed).record
            val dir = File(pluginsRoot, first.manifest.id)
            File(dir, "data").mkdirs()
            File(dir, "data/settings.json").writeText("{}")
            File(dir, "contexts").mkdirs()
            File(dir, "contexts/a.json").writeText("[]")
            install(buildBundle("testorigin", pair, payloadBytes = "version two".toByteArray()), pluginsRoot)
            install(buildBundle("testorigin", pair, payloadBytes = "version three".toByteArray()), pluginsRoot)
            assertEquals("{}", File(dir, "data/settings.json").readText())
            assertEquals("[]", File(dir, "contexts/a.json").readText())
        }
    }

    @Test
    fun `a version's files are read-only and a re-install of the same bytes keeps its folder`() {
        val pair = freshKeyPair()
        val pluginsRoot = tmp.newFolder("plugins")
        trusting("testorigin", pair) {
            val first = (install(buildBundle("testorigin", pair), pluginsRoot) as PluginInstallResult.Installed).record
            val dir = PluginBundleInstaller.installedDir(pluginsRoot, first)
            assertTrue(dir.walkTopDown().filter { it.isFile }.none { it.canWrite() })
            val jar = File(dir, "classes.jar")
            val before = jar.lastModified()
            Thread.sleep(20)
            val again = (install(buildBundle("testorigin", pair), pluginsRoot) as PluginInstallResult.Installed).record
            assertEquals(first.archiveDigest, again.archiveDigest)
            assertEquals(before, jar.lastModified())
            assertTrue(verifyInstalled(pluginsRoot, again) == null)
        }
    }

    @Test
    fun `a plugin disabled because its files did not match comes back once they verify`() {
        val pair = freshKeyPair()
        trusting("testorigin", pair) {
            val pluginsRoot = tmp.newFolder("plugins")
            val record = (install(buildBundle("testorigin", pair), pluginsRoot) as PluginInstallResult.Installed).record
                .copy(trust = PluginTrustState.APPROVED, enabled = false, disabledReason = "manifest.json changed on disk since approval")
            assertTrue(record.recoverableOnceVerified())
            assertTrue(record.copy(disabledReason = PluginRecord.FILES_MISSING).recoverableOnceVerified())
            // Switched off by the person, crashed, or never approved: not this.
            assertTrue(!record.copy(disabledReason = null).recoverableOnceVerified())
            assertTrue(!record.copy(disabledReason = "crashed").recoverableOnceVerified())
            assertTrue(!record.copy(trust = PluginTrustState.PENDING).recoverableOnceVerified())
            assertTrue(verifyInstalled(pluginsRoot, record) == null)
        }
    }
}
