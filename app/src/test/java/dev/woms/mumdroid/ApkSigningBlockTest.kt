package dev.woms.mumdroid

import dev.woms.mumdroid.core.security.ApkSigningBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * Regression tests for [ApkSigningBlock], driven by real APKs produced with
 * `apksigner` (see [ApkSignatureFixtures] and `tools/make-signature-fixtures.sh`).
 *
 * The fixtures cover the attack described in the "appended APK signature"
 * write-up: an honest v2-only APK, and the same APK with the original v2 block
 * preserved but an attacker-signed v3 block appended. Both pass
 * `apksigner verify`; only this parser can tell them apart.
 *
 * Signers are identified by certificate subject rather than digest: the
 * generator mints a new key pair per run, so a hardcoded digest would only match
 * the fixtures of whoever generated them (see [ApkSignatureFixtures]).
 *
 * [ApkSigningBlock.parse] takes a [File] so that an APK is never read into
 * memory, so the fixtures are materialised into a temp directory first — which
 * also means nothing is left behind after the run.
 */
class ApkSigningBlockTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun fixture(name: String): File = temp.newFile().apply {
        writeBytes(ApkSignatureFixtures.load(name))
    }

    /** The subjects of [this] result's signers, in file order. */
    private fun ApkSigningBlock.Result.subjects(): List<String> =
        signers.map { it.certificate.subjectX500Principal.name }

    private fun ApkSigningBlock.Result.subjectsPerScheme(): Map<String, Set<String>> =
        signers.groupBy { it.schemeId }
            .mapValues { (_, v) -> v.map { it.certificate.subjectX500Principal.name }.toSet() }

    @Test
    fun honestV2OnlyApkExposesExactlyTheDeveloperSigner() {
        val result = ApkSigningBlock.parse(fixture("honest-v2-only.apk"))
        assertEquals(listOf(ApkSignatureFixtures.DEVELOPER_SUBJECT), result.subjects())
        assertEquals(listOf(ApkSigningBlock.ID_V2_HEX), result.schemes.toList())
    }

    @Test
    fun honestV3OnlyApkExposesExactlyTheDeveloperSigner() {
        val result = ApkSigningBlock.parse(fixture("honest-v3-only.apk"))
        assertEquals(listOf(ApkSignatureFixtures.DEVELOPER_SUBJECT), result.subjects())
        assertEquals(listOf(ApkSigningBlock.ID_V3_0_HEX), result.schemes.toList())
    }

    @Test
    fun honestV2AndV3ApkSignsBothSchemesWithTheSameKey() {
        val perScheme = ApkSigningBlock.parse(fixture("honest-v2-and-v3.apk")).subjectsPerScheme()
        assertEquals(2, perScheme.size)
        // The whole point of an honest multi-scheme build: identical signer sets.
        assertEquals(1, perScheme.values.toSet().size)
        assertTrue(perScheme.values.all { it == setOf(ApkSignatureFixtures.DEVELOPER_SUBJECT) })
    }

    /**
     * The core regression: the forged APK is accepted by the platform, but the
     * block carries a *second*, foreign signer. Enumerating all signers is what
     * makes the forgery detectable.
     */
    @Test
    fun forgedApkWithAppendedV3BlockExposesAForeignSigner() {
        val subjects = ApkSigningBlock.parse(fixture("forged-appended-v3.apk")).subjects()
        assertTrue("expected the developer's v2 signer", subjects.contains(ApkSignatureFixtures.DEVELOPER_SUBJECT))
        assertTrue("expected the attacker's appended v3 signer", subjects.contains(ApkSignatureFixtures.ATTACKER_SUBJECT))
        // And the appended scheme disagrees with the original one.
        val perScheme = ApkSigningBlock.parse(fixture("forged-appended-v3.apk")).subjectsPerScheme()
        assertEquals(2, perScheme.size)
        assertEquals(2, perScheme.values.toSet().size)
    }

    @Test
    fun forgedApkReportsBothTheV2AndTheV3SignerIds() {
        val result = ApkSigningBlock.parse(fixture("forged-appended-v3.apk"))
        assertTrue(result.ids.contains(ApkSigningBlock.ID_V2_HEX))
        assertTrue(result.ids.contains(ApkSigningBlock.ID_V3_0_HEX))
    }

    /**
     * A signer pair that cannot be read fails the whole parse rather than being
     * dropped. The fixture keeps the developer's genuine v2 pair, so a parser
     * that skips only the damaged v3 pair still hands back a non-empty signer
     * list — and the block then looks verified. "We could not read this pair"
     * is not "this pair is fine", so the parse is refused instead.
     */
    @Test
    fun oneUnreadableSignerPairFailsTheWholeBlock() {
        val broken = ApkSignatureFixtures.withBrokenV3SignerPair(
            ApkSignatureFixtures.load("forged-appended-v3.apk"),
        )
        val file = temp.newFile().apply { writeBytes(broken) }
        assertThrows(IOException::class.java) { ApkSigningBlock.parse(file) }
    }

    @Test
    fun signerCarriesTheCertificateSubject() {
        val result = ApkSigningBlock.parse(fixture("honest-v2-only.apk"))
        assertEquals(ApkSignatureFixtures.DEVELOPER_SUBJECT, result.subjects().single())
    }

    @Test
    fun honestApksDoNotContainTheStripProtectionMarker() {
        // apksigner (build-tools 34) does not emit the 0x3ba06f8c marker, which
        // is why relying on it alone would silently miss real tampering.
        val result = ApkSigningBlock.parse(fixture("honest-v2-and-v3.apk"))
        assertFalse(
            result.attributesContain(
                byteArrayOf(0x8c.toByte(), 0x6f.toByte(), 0xa0.toByte(), 0x3b),
            ),
        )
    }

    /**
     * Every pair's value must be exactly `len - 4` bytes: `len` counts the
     * 4-byte id, so a `len`-sized slice would append the next pair's length
     * field (or, for the last pair, the trailer) to the value.
     *
     * The over-read is invisible in the signer lists, because their content is
     * reached through an inner length prefix that ignores trailing bytes — but
     * it does feed 4 junk bytes into `signingAttributes()`, which
     * `attributesContain()` scans.
     */
    @Test
    fun pairValuesDoNotOverrunIntoTheNextPair() {
        val result = ApkSigningBlock.parse(fixture("honest-v2-only.apk"))
        val signerPair = result.pairs.single { it.isV2 }
        // The pair's first u32 is the length of the enclosed signer sequence;
        // value.size must be 4 + that, with nothing left over.
        val declared = (signerPair.value[0].toInt() and 0xFF) or
            ((signerPair.value[1].toInt() and 0xFF) shl 8) or
            ((signerPair.value[2].toInt() and 0xFF) shl 16) or
            ((signerPair.value[3].toInt() and 0xFF) shl 24)
        assertEquals(4 + declared, signerPair.value.size)
    }

    @Test
    fun nonApkInputIsRejected() {
        val garbage = temp.newFile().apply { writeBytes(ByteArray(64) { 0x41 }) }
        val e = assertThrows(IOException::class.java) { ApkSigningBlock.parse(garbage) }
        assertNotNull(e.message)
    }

    /**
     * The ZIP comment is attacker-controlled data, and a plain backwards scan
     * takes the *last* `PK\x05\x06` it finds — so one planted in the comment
     * would win over the real record, and the central-directory offset the
     * parser then follows would be whatever the attacker wrote there. Requiring
     * the record's declared comment length to end at the end of the file is what
     * keeps the real record in charge, and an honest APK carrying such a comment
     * verifiable.
     */
    @Test
    fun fakeEocdInCommentDoesNotDisplaceTheRealRecord() {
        val comment = ByteArray(64) { 0x41 }
        eocdSignature().copyInto(comment, 8)
        // An implausible central-directory offset in the planted record's body.
        byteArrayOf(0x7f, 0x7f, 0x7f, 0x7f).copyInto(comment, 8 + 16)

        val result = ApkSigningBlock.parse(fixtureWithComment("honest-v2-only.apk", comment))

        assertEquals(listOf(ApkSignatureFixtures.DEVELOPER_SUBJECT), result.subjects())
    }

    /**
     * The crafted variant: the planted record's comment length is set so that it
     * *does* end at the end of the file, which makes it self-consistent and
     * therefore the candidate the backwards scan settles on. Nothing in the file
     * tells it apart from a real record, so the parse fails — which
     * [dev.woms.mumdroid.core.security.SignatureVerifier] reports as
     * `Status.UNREADABLE`, and `Status.isTrusted` rejects. This test pins that
     * the search hardening above does not silently pretend to cover this case.
     */
    @Test
    fun selfConsistentFakeEocdInCommentFailsTheParse() {
        val fakeAt = 8
        val comment = ByteArray(64) { 0x41 }
        eocdSignature().copyInto(comment, fakeAt)
        byteArrayOf(0x7f, 0x7f, 0x7f, 0x7f).copyInto(comment, fakeAt + 16)
        // The comment length that makes the planted record end exactly at the
        // end of the file: everything after its 22-byte body.
        val declared = comment.size - fakeAt - 22
        comment[fakeAt + 20] = declared.toByte()
        comment[fakeAt + 21] = (declared shr 8).toByte()

        val file = fixtureWithComment("honest-v2-only.apk", comment)

        assertThrows(IOException::class.java) { ApkSigningBlock.parse(file) }
    }

    private fun fixtureWithComment(name: String, comment: ByteArray): File {
        val apk = ApkSignatureFixtures.load(name)
        val eocd = eocdOffset(apk)
        // The fixtures are signed without a comment, so the record ends the file.
        assertEquals(apk.size, eocd + 22)
        val out = apk.copyOf(apk.size + comment.size)
        out[eocd + 20] = comment.size.toByte()
        out[eocd + 21] = (comment.size shr 8).toByte()
        comment.copyInto(out, apk.size)
        return temp.newFile().apply { writeBytes(out) }
    }

    /** The last `PK\x05\x06` in [apk], the way a plain ZIP reader looks for it. */
    private fun eocdOffset(apk: ByteArray): Int {
        val sig = eocdSignature()
        for (i in apk.size - 22 downTo 0) {
            if (apk[i] == sig[0] && apk[i + 1] == sig[1] &&
                apk[i + 2] == sig[2] && apk[i + 3] == sig[3]
            ) {
                return i
            }
        }
        throw AssertionError("fixture has no End-Of-Central-Directory record")
    }

    private fun eocdSignature() = byteArrayOf(0x50, 0x4b, 0x05, 0x06)
}
