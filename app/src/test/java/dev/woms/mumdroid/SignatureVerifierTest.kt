package dev.woms.mumdroid

import dev.woms.mumdroid.core.security.ApkSigningBlock
import dev.woms.mumdroid.core.security.SignatureVerifier
import dev.woms.mumdroid.core.security.SignatureVerifier.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Decision-logic tests for [SignatureVerifier], run against the real APK
 * fixtures described in [ApkSigningBlockTest] and [ApkSignatureFixtures].
 *
 * [SignatureVerifier.decide] is deliberately free of Android types so that the
 * security-critical rules can be tested on a plain JVM. The scenario these
 * tests model is the "appended APK signature" attack:
 *
 *  - `honest-*` fixtures are signed with the developer key we trust.
 *  - `forged-appended-v3` keeps the developer's v2 block but appends an
 *    attacker-signed v3 block. The system accepts it and reports the attacker;
 *    the verifier must still flag it.
 *
 * The two digests below are read out of the fixtures instead of being written
 * down, because the generator mints a new key pair per run (see
 * [ApkSignatureFixtures]): a hardcoded digest would only accept the fixtures of
 * whoever happened to generate the ones on disk.
 */
class SignatureVerifierTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun fixture(name: String): File = temp.newFile().apply {
        writeBytes(ApkSignatureFixtures.load(name))
    }

    /** The SHA-256 digest of [subject]'s signer in the fixture [name]. */
    private fun digestOf(name: String, subject: String): String =
        ApkSigningBlock.parse(fixture(name))
            .signers
            .single { it.certificate.subjectX500Principal.name == subject }
            .sha256Hex

    private val developer by lazy {
        digestOf("honest-v2-only.apk", ApkSignatureFixtures.DEVELOPER_SUBJECT)
    }
    private val attacker by lazy {
        digestOf("attacker-v3-only.apk", ApkSignatureFixtures.ATTACKER_SUBJECT)
    }

    private val trusted by lazy { setOf(developer) }

    @Test
    fun honestV2OnlyApkIsAccepted() {
        val r = SignatureVerifier.decide(fixture("honest-v2-only.apk"), trusted, listOf(developer))
        assertEquals(Status.OK, r.status)
    }

    @Test
    fun honestV3OnlyApkIsAccepted() {
        val r = SignatureVerifier.decide(fixture("honest-v3-only.apk"), trusted, listOf(developer))
        assertEquals(Status.OK, r.status)
    }

    @Test
    fun honestV2AndV3ApkIsAccepted() {
        val r = SignatureVerifier.decide(fixture("honest-v2-and-v3.apk"), trusted, listOf(developer))
        assertEquals(Status.OK, r.status)
    }

    /**
     * The regression this whole change exists for.
     *
     * Note the platform digest passed in is the *attacker's*, exactly what
     * `PackageManager#apkContentsSigners` would report for this file on
     * Android 9+. The old implementation compared `actual.none { it in expected }`
     * on that value; here we must still detect the tampering.
     */
    @Test
    fun forgedApkWithAppendedV3BlockIsRejected() {
        val r = SignatureVerifier.decide(fixture("forged-appended-v3.apk"), trusted, listOf(attacker))
        assertEquals(Status.TAMPERED, r.status)
    }

    /**
     * The fail-open this closes: with the v3 pair damaged but the v2 pair
     * intact, dropping the bad pair would leave only the trusted v2 signer, and
     * the block would be accepted. A pair that could not be read means the
     * block was not fully checked, which must be UNREADABLE — never a pass.
     */
    @Test
    fun apkWithAnUnreadableSignerPairIsUnreadable() {
        val broken = ApkSignatureFixtures.withBrokenV3SignerPair(
            ApkSignatureFixtures.load("forged-appended-v3.apk"),
        )
        val file = temp.newFile().apply { writeBytes(broken) }
        val r = SignatureVerifier.decide(file, trusted, listOf(developer))
        assertEquals(Status.UNREADABLE, r.status)
        // The pairs themselves still walk; it is the signer sequence inside the
        // damaged v3 pair that cannot be read, so that is what the reason names.
        assertTrue("unexpected reason: ${r.detail}", r.detail.contains("malformed signer pair"))
    }

    @Test
    fun apkSignedOnlyByTheAttackerIsRejected() {
        val r = SignatureVerifier.decide(fixture("attacker-v3-only.apk"), trusted, listOf(attacker))
        assertEquals(Status.TAMPERED, r.status)
    }

    /**
     * v2 and v3 legitimately signed with **different** certificates.
     *
     * A project can end up with the original key on v2 and a newer (rotated)
     * key on v3, or simply pass a different `--ks` per scheme to `apksigner`.
     * As long as every certificate that appears is in the whitelist, this must
     * be accepted — the whitelist is matched per *signer*, not per scheme.
     *
     * The fixture is the same file used for the appended-block regression above;
     * the only difference is that the attacker's certificate is now trusted, so
     * rule 2 no longer rejects it and the outcome flips to OK.
     */
    @Test
    fun v2AndV3SignedWithDifferentTrustedCertificatesIsAccepted() {
        val r = SignatureVerifier.decide(
            fixture("forged-appended-v3.apk"),
            setOf(developer, attacker),
            listOf(attacker),
        )
        assertEquals(Status.OK, r.status)
    }

    /**
     * The whitelist is a *list*: a certificate that is not in it must still be
     * rejected even when other certificates are. This is the security guarantee
     * that makes the per-signer (rather than per-scheme) match safe.
     */
    @Test
    fun removingACertificateFromTheWhitelistRejectsTheApkAgain() {
        val r = SignatureVerifier.decide(
            fixture("forged-appended-v3.apk"),
            setOf(developer),
            listOf(attacker),
        )
        assertEquals(Status.TAMPERED, r.status)
    }

    /**
     * A *single* scheme carrying two different certificates is still a tamper
     * signal — this is the "appended signer" variant. Note both certificates
     * are trusted here, so only the same-scheme rule can catch it.
     */
    @Test
    fun oneSchemeWithTwoDifferentTrustedCertificatesIsRejected() {
        val r = SignatureVerifier.decide(
            fixture("same-scheme-two-signers.apk"),
            setOf(developer, attacker),
            listOf(developer),
        )
        assertEquals(Status.TAMPERED, r.status)
    }

    /** Matching is case-insensitive, so hand-written digests are accepted. */
    @Test
    fun expectedDigestsAreMatchedCaseInsensitively() {
        val r = SignatureVerifier.decide(
            fixture("honest-v2-only.apk"),
            setOf(developer.lowercase()),
            listOf(developer),
        )
        assertEquals(Status.OK, r.status)
    }

    @Test
    fun garbageInputIsReportedAsUnreadable() {
        val r = SignatureVerifier.decide(garbageFile(), trusted, listOf(developer))
        assertEquals(Status.UNREADABLE, r.status)
    }

    @Test
    fun platformReportingAForeignSignerIsRejected() {
        // Honest block, but the platform hands back someone else's digest.
        val r = SignatureVerifier.decide(fixture("honest-v2-only.apk"), trusted, listOf(attacker))
        assertEquals(Status.TAMPERED, r.status)
    }

    @Test
    fun platformReportingNothingIsUnreadable() {
        val r = SignatureVerifier.decide(fixture("honest-v2-only.apk"), trusted, emptyList())
        assertEquals(Status.UNREADABLE, r.status)
    }

    /**
     * The fail-closed rule the user-visible warning depends on: only a
     * verification that was carried out and succeeded, or one that was
     * deliberately not applicable, may run without a warning. `UNREADABLE` is
     * the state an attacker who repackaged the APK can force (see the planted
     * End-Of-Central-Directory tests in [ApkSigningBlockTest]), so treating it
     * as a pass would leave the check trivially bypassable.
     */
    @Test
    fun onlyVerifiedOrDeliberatelySkippedBuildsAreTrusted() {
        assertTrue(Status.OK.isTrusted)
        assertTrue(Status.SKIPPED.isTrusted)
        assertFalse(Status.TAMPERED.isTrusted)
        assertFalse(Status.UNREADABLE.isTrusted)
    }

    /**
     * Every untrusted outcome must carry a reason, because that reason is the
     * only diagnostic there is: the warning dialog says the same sentence
     * whatever went wrong, and the caller logs this detail for whoever has to
     * tell a repackaged APK from a setup the check cannot handle. An empty
     * detail would make that log line say nothing.
     */
    @Test
    fun untrustedResultsCarryAReason() {
        val results = listOf(
            SignatureVerifier.decide(fixture("attacker-v3-only.apk"), trusted, listOf(attacker)),
            SignatureVerifier.decide(garbageFile(), trusted, listOf(developer)),
            SignatureVerifier.decide(fixture("honest-v2-only.apk"), trusted, emptyList()),
        )
        results.forEach { r ->
            assertFalse(r.status.isTrusted)
            assertTrue("${r.status} carries no detail", r.detail.isNotBlank())
        }
    }

    /** A file that is not an APK at all. */
    private fun garbageFile(): File =
        temp.newFile().apply { writeBytes(ByteArray(128) { 0x7A }) }
}
