package dev.woms.mumdroid

import dev.woms.mumdroid.core.security.ApkSigningBlock
import org.junit.Assume.assumeTrue
import java.io.InputStream

/**
 * The `apksigner` fixtures the signature tests run against.
 *
 * They live in `app/src/test/resources/apk-signatures/` and are deliberately
 * **not committed** (see `.gitignore`): `tools/make-signature-fixtures.sh` mints
 * a fresh key pair on every run, so the certificates — and the SHA-256 digests
 * derived from them — are per-machine artifacts. A test must therefore read the
 * digests it asserts on out of the fixtures instead of hardcoding them, or it
 * would only pass for whoever generated the files that happen to be on disk.
 *
 * When the fixtures have not been generated, the affected tests are skipped
 * rather than failed — a missing test input is not a regression — and the skip
 * reason names the script that creates them. The `testDebugUnitTest` task also
 * logs a warning about the missing directory, because Gradle swallows test
 * stdout and a reason that has to be dug out of the report is easy to miss.
 */
object ApkSignatureFixtures {

    private const val DIR = "/apk-signatures"

    /**
     * The certificate subjects `make-signature-fixtures.sh` signs with. The key
     * material is regenerated per run, but these names are pinned by the script,
     * so tests can identify a signer by subject.
     */
    const val DEVELOPER_SUBJECT = "CN=Original Dev"
    const val ATTACKER_SUBJECT = "CN=Attacker"

    /**
     * The fixture [name] as bytes.
     *
     * Skips the calling test (JUnit assumption) when it has not been generated.
     */
    fun load(name: String): ByteArray {
        val stream = javaClass.getResourceAsStream("$DIR/$name")
        if (stream == null) {
            assumeTrue(
                "APK signature fixture $name has not been generated; run " +
                    "tools/make-signature-fixtures.sh (its output is not committed)",
                false,
            )
        }
        return (stream as InputStream).use { it.readBytes() }
    }

    /** The v3.0 signer pair id, as stored on disk (little-endian). */
    private val V3_SIGNERS_ID: ByteArray =
        ApkSigningBlock.ID_V3_0_HEX.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /**
     * [apk] with the v3 signer pair's inner grammar wrecked.
     *
     * Only the pair's *framing* is left intact, so a parser can still walk from
     * one pair to the next; the length prefix of the enclosed signer sequence
     * is overwritten with an impossible value, so reading the pair throws. That
     * is the shape a damaged-but-still-walkable block has, and the only way to
     * tell whether a parser drops just the bad pair or refuses the block: the
     * input must carry a second (here v2) signer pair that still reads cleanly.
     */
    fun withBrokenV3SignerPair(apk: ByteArray): ByteArray {
        val out = apk.copyOf()
        // A pair is [u64 len][u32 id][value]; the value's first u32 is the
        // length of the signer sequence that follows it.
        val at = uniqueIndexOf(out, V3_SIGNERS_ID) + 4
        for (i in 0 until 4) out[at + i] = 0xFF.toByte()
        return out
    }

    /** The single index of [needle] in [hay], failing if absent or repeated. */
    private fun uniqueIndexOf(hay: ByteArray, needle: ByteArray): Int {
        var found = -1
        for (i in 0..hay.size - needle.size) {
            if (needle.indices.all { hay[i + it] == needle[it] }) {
                check(found < 0) { "byte pattern is not unique" }
                found = i
            }
        }
        check(found >= 0) { "byte pattern not found" }
        return found
    }
}
