package dev.woms.mumdroid.core.security

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import dev.woms.mumdroid.BuildConfig
import dev.woms.mumdroid.core.security.SignatureVerifier.decide
import dev.woms.mumdroid.core.security.SignatureVerifier.expectedDigests
import dev.woms.mumdroid.core.security.SignatureVerifier.isApkTampered
import dev.woms.mumdroid.core.security.SignatureVerifier.verify
import java.security.MessageDigest

/**
 * Verifies that the running APK was signed with the certificate(s) this build
 * was compiled against, and that the APK's signature material has not been
 * tampered with.
 *
 * At build time the release signing certificate's SHA-256 digest(s) are embedded
 * into [BuildConfig.EXPECTED_SIGNATURE_SHA256]. At runtime we compare against
 * them.
 *
 * ## Why comparing the platform's answer is not enough
 *
 * The obvious implementation — read `PackageManager#apkContentsSigners` and
 * compare it to the expected digest — is **bypassable**. Android validates APK
 * signatures with a *priority* model:
 *
 *  - If a v3 block is present, the v2 block is **not** validated at all.
 *  - The v3 "strip protection" marker (`0x3ba06f8c`) only defends against
 *    *removing* v3, never against *adding* it.
 *
 * So an attacker can keep the original v2 block untouched, modify the APK
 * contents, and append a v3 block signed with their own key. The result:
 *
 *  - the system verifies the APK (v3 wins),
 *  - `apkContentsSigners` reports the **attacker's** certificate.
 *
 * A verifier that compares that reported certificate to the expected one will
 * therefore compare against the attacker, and — with a naive "at least one
 * match" rule — can be made to pass. This blind spot is described in the
 * "appended APK signature" write-up.
 *
 * ## What this verifier does instead
 *
 * It parses the APK Signing Block itself (see [ApkSigningBlock]), enumerates
 * **every** signer of **every** scheme, and requires:
 *
 *  1. at least one signer is present,
 *  2. **every** signer's certificate is in the expected *whitelist* (any
 *     foreign signer — an appended block or an appended signer — is a tamper
 *     signal),
 *  3. no single scheme carries two different certificates (the "appended
 *     signer" variant), and
 *  4. the platform-reported contents signers also match, as a final cross-check.
 *
 * Rule 2 alone defeats the "appended v3 block" and "appended signer" attacks.
 *
 * ## Multiple trusted certificates
 *
 * The expected value is a **list**, not a single digest — see
 * [expectedDigests]. Two situations need this:
 *
 *  - **Signing-key rotation.** An APK signed with the previous key still
 *    installs, so both certificates must be accepted while users migrate.
 *  - **v2 and v3 signed with different certificates.** `apksigner` can be told
 *    to use a different key per scheme, and a project that enabled v3 later
 *    may end up with the original key on v2 and a newer key on v3. The
 *    whitelist is therefore matched per *signer* rather than per *scheme*, so
 *    as long as every certificate that appears is trusted, differing schemes
 *    are accepted.
 *
 * This does not weaken the appended-block defence: an attacker's certificate
 * is not in the whitelist, so rule 2 still rejects it. Adding a certificate to
 * the whitelist requires possessing its private key, i.e. the developer
 * authorised it.
 *
 * ## Release-only safeguard
 *
 * Debug builds use the auto-generated debug keystore and never match the
 * release digest, so [isApkTampered] short-circuits to `false` whenever
 * `BuildConfig.DEBUG` is true. When no release keystore was configured the
 * expected digest is empty and the check is skipped as well, leaving normal
 * development unaffected.
 */
object SignatureVerifier {

    /** Outcome of a verification pass. */
    enum class Status {
        /** The APK matches the expected signing certificate(s). */
        OK,

        /** The build carries no expected digest, or is a debug build. */
        SKIPPED,

        /** The APK is signed with something other than the expected certificate. */
        TAMPERED,

        /** The signature material could not be read or parsed. */
        UNREADABLE,
    }

    /**
     * Whether the running APK appears to be tampered with.
     *
     * Callers that only need a boolean can keep using this; [verify] exposes the
     * fuller [Status] for diagnostics.
     */
    fun isApkTampered(context: Context): Boolean = verify(context).status == Status.TAMPERED

    /** Result of [verify]. */
    data class Report(
        val status: Status,
        /** Human-readable reason, empty when [status] is [Status.OK]. */
        val detail: String = "",
    )

    /**
     * Runs the full verification.
     *
     * @param apkPath override for the APK to inspect; defaults to the path of
     *   the running application. Exposed mainly so tests can point at fixtures.
     */
    fun verify(context: Context, apkPath: String? = null): Report {
        // Debug builds are signed with the auto-generated debug keystore, which
        // never matches the embedded release digest. The tamper check is a
        // release-only safeguard, so skip it entirely for debug builds.
        if (BuildConfig.DEBUG) return Report(Status.SKIPPED, "debug build")

        val expected = expectedDigests()
        if (expected.isEmpty()) return Report(Status.SKIPPED, "no expected signature configured")

        val path = apkPath ?: context.applicationInfo?.sourceDir
        if (path.isNullOrBlank()) return Report(Status.UNREADABLE, "no APK path")

        // The signing block sits at the end of the file, but its offsets are
        // relative to the whole APK, so we read it in full. This runs once per
        // process start and the file is already in the page cache.
        val apkBytes = try {
            java.io.File(path).readBytes()
        } catch (e: Exception) {
            return Report(Status.UNREADABLE, "cannot read APK: ${e.message}")
        }

        // The platform's own view, used as a cross-check below.
        val platform = try {
            platformDigests(context)
        } catch (e: Exception) {
            return Report(Status.UNREADABLE, "cannot read platform signature: ${e.message}")
        }

        return decide(apkBytes, expected, platform)
    }

    /**
     * The full decision, kept free of Android types so it can be unit-tested
     * against real APK fixtures.
     *
     * @param apkBytes the APK to inspect.
     * @param expected the accepted certificate digests (colon-separated hex).
     * @param platformDigests what `PackageManager` reported, if available.
     */
    internal fun decide(
        apkBytes: ByteArray,
        expected: Collection<String>,
        platformDigests: List<String>,
    ): Report {
        // 1) Parse the signing block ourselves and enumerate every signer.
        val block = try {
            ApkSigningBlock.parse(apkBytes)
        } catch (e: Exception) {
            return Report(Status.UNREADABLE, "cannot parse signing block: ${e.message}")
        }
        if (block.signers.isEmpty()) {
            return Report(Status.UNREADABLE, "no signers found in the signing block")
        }

        // 2) Every signer must be expected. A foreign signer is the appended
        //    block / appended signer attack, and also the minSdk/maxSdk variant.
        val trusted = expected.mapTo(HashSet()) { it.uppercase() }
        val foreign = block.signers.filterNot { it.sha256Hex.uppercase() in trusted }
        if (foreign.isNotEmpty()) {
            return Report(
                Status.TAMPERED,
                "unexpected signer(s): " + foreign.joinToString(", ") {
                    "${it.schemeId}/${it.certificate.subjectX500Principal.name}"
                },
            )
        }

        // 3) Per-scheme signer sets are allowed to DIFFER from each other.
        //    Different schemes may legitimately be signed by different
        //    certificates — e.g. a build that signs v2 with the original key
        //    and v3 with a rotated key, or an `apksigner` invocation that
        //    passes a different `--ks` per scheme. Rule 2 already guarantees
        //    every signer is in the trusted set, so a mismatch is no longer a
        //    tamper signal on its own.
        //
        //    What we DO require is that each scheme is internally consistent:
        //    within one scheme, all signers (for the same algorithm) must use
        //    the same certificate. A single scheme carrying two unrelated
        //    certs is the "appended signer" variant described in the write-up.
        block.signers.groupBy { it.schemeId }.forEach { (scheme, signers) ->
            val distinct = signers.mapTo(LinkedHashSet()) { it.sha256Hex }
            if (distinct.size > 1) {
                return Report(
                    Status.TAMPERED,
                    "scheme $scheme carries multiple signer certificates: " +
                        signers.joinToString(", ") { it.certificate.subjectX500Principal.name },
                )
            }
        }

        // 4) Cross-check against what the platform reports. This catches
        //    anything the block parser might have mis-handled, and keeps the
        //    check meaningful even if the block layout changes in future.
        if (platformDigests.isEmpty()) {
            return Report(Status.UNREADABLE, "platform reported no signatures")
        }
        if (platformDigests.none { it.uppercase() in trusted }) {
            return Report(Status.TAMPERED, "platform signature does not match the expected set")
        }

        return Report(Status.OK)
    }

    /**
     * The trusted signing-certificate digests, as a **list / whitelist**.
     *
     * The value embedded at build time is a separator-delimited list, so a
     * multi-certificate setup is supported out of the box. This covers:
     *
     *  - **Signing-key rotation** — the old and the new certificate are both
     *    listed while in-flight APKs signed with either one still install.
     *  - **v2 and v3 signed with different certificates** — see [decide]; the
     *    whitelist is compared per *signer*, not per scheme, so each scheme may
     *    legitimately use a different (but trusted) certificate.
     *
     * Separators: `,` and `;`; whitespace and newlines are ignored, so the
     * value can also be produced by hand or by a multi-line Gradle property.
     */
    fun expectedDigests(): List<String> {
        val raw = BuildConfig.EXPECTED_SIGNATURE_SHA256
        if (raw.isBlank()) return emptyList()
        return raw.split(',', ';', '\n', '\r')
            .map { it.trim().uppercase() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(bytes).joinToString(":") { "%02X".format(it) }
    }

    /**
     * Gathers the platform's view of the signing certificates.
     *
     * On API 28+ [android.content.pm.SigningInfo] exposes both the *current*
     * contents signers and the full signing history, so a key-rotation scenario
     * — where an older, previously-valid certificate is still trusted — is not
     * incorrectly reported as tampered.
     */
    private fun platformDigests(context: Context): List<String> {
        val packageName = context.packageName
        val pm = context.packageManager
        val certs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            signingInfoCertificates(info?.signingInfo)
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures?.toList().orEmpty()
        }
        return certs.map { sha256Hex(it.toByteArray()) }.distinct()
    }

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
