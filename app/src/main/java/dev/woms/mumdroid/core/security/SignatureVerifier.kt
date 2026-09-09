package dev.woms.mumdroid.core.security

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import dev.woms.mumdroid.BuildConfig
import dev.woms.mumdroid.core.security.SignatureVerifier.isApkTampered
import java.security.MessageDigest

/**
 * Verifies that the currently installed APK was signed with a signing certificate
 * that this build was compiled against.
 *
 * At build time the release signing certificate's SHA-256 digest(s) are embedded
 * into [BuildConfig.EXPECTED_SIGNATURE_SHA256]. At runtime we recompute the
 * digest(s) of the installed signature and compare. A repackaged / tampered APK
 * re-signed with an attacker's key will therefore fail the check.
 *
 * ## Key rotation support
 *
 * On API 28+ Android exposes a signing certificate *history* via
 * [android.content.pm.SigningInfo]. When an app's signing key is rotated, the
 * *current* signer (the one that validated the APK contents) lives in
 * `apkContentsSigners`, while the whole rotation history (older certificates
 * that were previously valid) is available through `signingCertificateHistory`.
 *
 * If a previously-valid (older) certificate is still accepted for updates, we
 * must also accept it here. We therefore consider BOTH the current contents
 * signers AND the full signing history when computing the "actual" set of
 * accepted certificates — so an APK signed with a rotated-but-still-trusted key
 * is not falsely flagged as tampered.
 *
 * When the build had no release keystore configured (local debug builds) the
 * expected digest is empty and [isApkTampered] always returns false, i.e. the
 * check is skipped so normal development is unaffected.
 */
object SignatureVerifier {

    /** Whether the running APK signature matches the one used at compile time. */
    fun isApkTampered(context: Context): Boolean {
        val expectedRaw = BuildConfig.EXPECTED_SIGNATURE_SHA256
        if (expectedRaw.isBlank()) return false
        val expected = expectedRaw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (expected.isEmpty()) return false

        val actual = try {
            installedSignatureDigests(context)
        } catch (e: Exception) {
            // If the signature can't be read we treat it as suspicious.
            emptyList()
        }
        // Pass as long as at least one of the actual (current or historical)
        // certificates matches the expected set. A freshly signed/trusted cert
        // is enough; an attacker-repackaged APK will never match any of them.
        return actual.none { it in expected }
    }

    private fun installedSignatureDigests(context: Context): List<String> {
        val packageName = context.packageName
        val pm = context.packageManager
        val certs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            signingInfoCertificates(info?.signingInfo)
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures?.toList().orEmpty()
        }
        val md = MessageDigest.getInstance("SHA-256")
        return certs
            .map { cert -> md.digest(cert.toByteArray()).joinToString(":") { "%02X".format(it) } }
            .distinct()
    }

    /**
     * Gathers every signing certificate we should accept for verification.
     *
     * On API 28+ [android.content.pm.SigningInfo] exposes both the *current*
     * contents signers and the full signing-history. We collect both so that a
     * key-rotation scenario — where an older, previously-valid certificate is
     * still trusted — is not incorrectly reported as tampered.
     */
    private fun signingInfoCertificates(signingInfo: android.content.pm.SigningInfo?): List<Signature> {
        if (signingInfo == null) return emptyList()
        val result = mutableListOf<Signature>()
        // 1) The signer(s) that validated the current APK contents.
        signingInfo.apkContentsSigners?.let { result.addAll(it) }
        // 2) The whole rotation history (includes older / rotated certificates).
        if (signingInfo.hasPastSigningCertificates()) {
            signingInfo.signingCertificateHistory?.let { result.addAll(it) }
        }
        return result
    }
}
