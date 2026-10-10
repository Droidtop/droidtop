package dev.droidtop.library.integrations

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import dev.droidtop.library.integrations.AppCatalogs.PackageFacts
import java.io.File
import java.security.MessageDigest

/**
 * What an APK file or an installed app is (docs/SPEC.md 10b "Installing apps", Droidtop/tracker#261): its package,
 * version code and signing certificates, read from Android's package manager, and the one rule for whether a
 * downloaded APK may be installed ([refusal]). Blocking reads: worker threads only.
 */
object AppPackages {
    /** The facts of the APK at [apk], or null when Android cannot read it as one. Blocking. */
    fun archiveFacts(context: Context, apk: File): PackageFacts? {
        @Suppress("DEPRECATION")
        val flags = PackageManager.GET_SIGNATURES or if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else 0
        val info = context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags) ?: return null
        return factsOf(info)
    }

    /** The facts of the installed [packageName], or null when it is not installed. Blocking. */
    fun installedFacts(context: Context, packageName: String): PackageFacts? {
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info = runCatching { context.packageManager.getPackageInfo(packageName, flags) }.getOrNull() ?: return null
        return factsOf(info)
    }

    /**
     * Every installed app's facts, by package, from ONE package-manager query (the Updates place compares a
     * catalog's offers with these; there is no per-app lookup). Blocking.
     */
    fun allInstalledFacts(context: Context): Map<String, PackageFacts> {
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        return runCatching { context.packageManager.getInstalledPackages(flags) }.getOrDefault(emptyList())
            .associate { it.packageName to factsOf(it) }
    }

    @Suppress("DEPRECATION")
    private fun factsOf(info: PackageInfo): PackageFacts {
        val signatures = if (Build.VERSION.SDK_INT >= 28 && info.signingInfo != null) {
            val signing = info.signingInfo!!
            if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
        } else {
            info.signatures
        }
        val signers = signatures.orEmpty().filterNotNull().map { sha256Hex(it.toByteArray()) }.toSet()
        return PackageFacts(info.packageName, PackageInfoCompat.getLongVersionCode(info), info.versionName, signers)
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * Why the downloaded APK must not be installed, or null when it may (pure): it must be the package and version
     * the catalog offered, signed by a key the catalog named, and, when the app is installed, by a key the installed
     * app has: a changed signing key is refused and said plainly, never left to a cryptic installer error.
     */
    fun refusal(apk: PackageFacts?, expected: AppCatalogs.Expected, installed: PackageFacts?): String? = when {
        apk == null -> "the download is not an Android app"
        apk.packageName != expected.packageName ->
            "the download is ${apk.packageName}, not ${expected.packageName}"
        expected.versionCode > 0 && apk.versionCode != expected.versionCode ->
            "the download is version code ${apk.versionCode}, not the ${expected.versionCode} the catalog offered"
        expected.signers.isNotEmpty() && apk.signers.none { it in expected.signers } ->
            "the download is not signed with the key the catalog names for ${expected.packageName}"
        installed != null && installed.signers.isNotEmpty() && apk.signers.none { it in installed.signers } ->
            "the installed ${expected.packageName} is signed with a different key than this version. Android would refuse it; " +
                "uninstalling the installed app first would lose its data, so droidtop does not offer this version"
        installed != null && installed.versionCode > apk.versionCode ->
            "a newer version (${installed.versionName ?: installed.versionCode}) is already installed"
        else -> null
    }

}
