package dev.droidtop.pluginhost

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.android.apksig.ApkSigner
import dev.droidtop.shared.trust.Sha256
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date
import javax.security.auth.x500.X500Principal

/**
 * The enhancement provider host (docs/SPEC.md "Enhancement providers", Droidtop/tracker#852): the one app that holds
 * elevated access, so droidtop's own code holds none. droidtop carries it as an unsigned template
 * ([TEMPLATE_ASSET], built by :provider-host), signs it on the device with a key it made in Android's Keystore, and
 * installs it; the same key signs every later version, so each installs over the last. The key never leaves the
 * Keystore and the signed APK never leaves the device. droidtop trusts the host it signed as its own
 * ([AppInterfaceTrust.decide], [certSha256]); a host signed by any other key is not this one.
 *
 * Everything here touches the Keystore or the disk: never on the main thread.
 */
object ProviderHost {
    /** The host's package, fixed: the user id the person allows in the root manager and in Shizuku is this app's. */
    const val PACKAGE = "dev.droidtop.providerhost"

    /** The service droidtop binds (the host's manifest and its droidtop-plugin.json). */
    const val SERVICE = "dev.droidtop.providerhost.ProviderHostService"

    /** The unsigned template in droidtop's assets (app/build.gradle.kts, copyProviderHost). */
    const val TEMPLATE_ASSET = "provider-host.apk"

    /** The Keystore alias of the key that signs the host. Made on first use, kept for good; uninstalling droidtop deletes it. */
    private const val KEY_ALIAS = "droidtop-provider-host"
    private const val KEYSTORE = "AndroidKeyStore"

    /** Where the template is copied and signed: droidtop's private cache, removed once installed. */
    private const val WORK_DIR = "provider-host"

    @Volatile
    private var cert: String? = null

    /**
     * The SHA-256 of the host key's certificate (lowercase hex), or null while droidtop has made none. The certificate a
     * host must be signed by for droidtop to call it its own. Reads the Keystore once.
     */
    fun certSha256(): String? = cert ?: runCatching {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getCertificate(KEY_ALIAS) as? X509Certificate)?.let { Sha256.hex(it.encoded) }
    }.getOrNull()?.also { cert = it }

    /** The key and its certificate, made now when there is none: an EC P-256 key that can only sign. */
    private fun key(): Pair<PrivateKey, X509Certificate> {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        if (!store.containsAlias(KEY_ALIAS)) {
            val now = System.currentTimeMillis()
            val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setCertificateSubject(X500Principal("CN=droidtop provider host"))
                .setCertificateSerialNumber(BigInteger.valueOf(now))
                .setCertificateNotBefore(Date(now - DAY_MS))
                // An APK's signing certificate is not checked for expiry by Android; a long one keeps every tool content.
                .setCertificateNotAfter(Date(now + CERT_VALID_MS))
                .build()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE).apply { initialize(spec) }.generateKeyPair()
        }
        val entry = store.getEntry(KEY_ALIAS, null) as KeyStore.PrivateKeyEntry
        val certificate = entry.certificate as X509Certificate
        cert = Sha256.hex(certificate.encoded)
        return entry.privateKey to certificate
    }

    /** The template's version, read by Android from the file itself; null when droidtop carries none. */
    fun templateVersion(context: Context): Long? = runCatching {
        val file = copyTemplate(context)
        @Suppress("DEPRECATION")
        context.packageManager.getPackageArchiveInfo(file.path, 0)?.let { versionOf(it) }
    }.getOrNull()

    /** The installed host's version, or null when there is none. */
    fun installedVersion(context: Context): Long? = runCatching {
        @Suppress("DEPRECATION")
        versionOf(context.packageManager.getPackageInfo(PACKAGE, 0))
    }.getOrNull()

    /** Whether the installed host was installed by droidtop, so Android lets droidtop update it without asking (12+). */
    fun installedByDroidtop(context: Context): Boolean = runCatching {
        val pm = context.packageManager
        val installer = if (Build.VERSION.SDK_INT >= 30) {
            pm.getInstallSourceInfo(PACKAGE).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            pm.getInstallerPackageName(PACKAGE)
        }
        installer == context.packageName
    }.getOrDefault(false)

    /**
     * What droidtop should do about the host now ([Plan.decide]): the template's version against the installed one,
     * and whether the installed one is the host droidtop signed.
     */
    fun plan(context: Context): Plan = Plan.decide(
        template = templateVersion(context),
        installed = installedVersion(context),
        ours = installedSignedByUs(context),
    )

    /** Whether the installed host is signed by droidtop's host key, as Android reports its signer. */
    private fun installedSignedByUs(context: Context): Boolean {
        val mine = certSha256() ?: return false
        return runCatching {
            @Suppress("DEPRECATION")
            val signers = if (Build.VERSION.SDK_INT >= 28) {
                context.packageManager.getPackageInfo(PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners
            } else {
                context.packageManager.getPackageInfo(PACKAGE, PackageManager.GET_SIGNATURES).signatures
            }
            signers.orEmpty().filterNotNull().any { Sha256.hex(it.toByteArray()).equals(mine, ignoreCase = true) }
        }.getOrDefault(false)
    }

    /** What to do about the host. Pure, so it is unit-tested. */
    enum class Plan {
        /** droidtop carries no template (a build without it): nothing to offer. */
        NONE,

        /** No host yet: installing it asks the person once. */
        INSTALL,

        /** droidtop's host, older than the template: updated over itself, without asking where Android allows it. */
        UPDATE,

        /** The installed host is the template's version or newer. */
        CURRENT,

        /**
         * A host is installed that droidtop's key did not sign (droidtop was reinstalled, which deletes its key): Android
         * refuses an update signed by another key, so the person removes the old host first.
         */
        FOREIGN,
        ;

        companion object {
            fun decide(template: Long?, installed: Long?, ours: Boolean): Plan = when {
                template == null -> NONE
                installed == null -> INSTALL
                !ours -> FOREIGN
                installed < template -> UPDATE
                else -> CURRENT
            }
        }
    }

    /**
     * The template signed with droidtop's host key, ready to install: APK Signature Scheme v2 and v3 (the checks
     * droidtop's own trust needs, docs/plugin-api.md 1.3), no v1, made in droidtop's private cache. The key is made now
     * when there is none.
     */
    fun signed(context: Context): File {
        val template = copyTemplate(context)
        val (privateKey, certificate) = key()
        val out = File(template.parentFile, "provider-host-signed.apk")
        out.delete()
        val signer = ApkSigner.SignerConfig.Builder("droidtop", privateKey, listOf(certificate)).build()
        ApkSigner.Builder(listOf(signer))
            .setInputApk(template)
            .setOutputApk(out)
            .setMinSdkVersion(MIN_SDK)
            .setV1SigningEnabled(false)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .build()
            .sign()
        return out
    }

    /** Removes the template copies once the host is installed (or the install was given up). */
    fun cleanUp(context: Context) {
        File(context.cacheDir, WORK_DIR).deleteRecursively()
    }

    /** The template copied out of droidtop's assets, once per droidtop version (its size and droidtop's update time). */
    private fun copyTemplate(context: Context): File {
        val dir = File(context.cacheDir, WORK_DIR).apply { mkdirs() }
        val file = File(dir, "provider-host-template.apk")
        @Suppress("DEPRECATION")
        val stamp = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        val stampFile = File(dir, "template.stamp")
        if (file.isFile && stampFile.isFile && stampFile.readText() == stamp) return file
        context.assets.open(TEMPLATE_ASSET).use { input -> dev.droidtop.net.AtomicWrite.stream(file) { out -> input.copyTo(out) } }
        stampFile.writeText(stamp)
        return file
    }

    @Suppress("DEPRECATION")
    private fun versionOf(info: android.content.pm.PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    private const val MIN_SDK = 26
    private const val DAY_MS = 86_400_000L
    private const val CERT_VALID_MS = 50L * 365 * DAY_MS
}
